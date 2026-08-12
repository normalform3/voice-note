package com.voicenote.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voicenote.config.AppProperties;
import com.voicenote.domain.*;
import com.voicenote.provider.ProviderException;
import com.voicenote.repository.*;
import com.voicenote.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import java.util.regex.Pattern;
import java.nio.charset.StandardCharsets;

@Service
public class UserMemoryService {
    public static final String EXTRACTION_VERSION = "user-memory-extraction-v1";
    private static final Pattern SECRET = Pattern.compile("(?i)(password|passwd|api[ _-]?key|access[ _-]?token|secret|private[ _-]?key|密码|口令|密钥|私钥|令牌|-----BEGIN)");
    private static final Pattern SENSITIVE = Pattern.compile("(?i)(diagnos|medical|health|religion|politic|salary|bank account|疾病|诊断|病史|药物|医疗|健康|宗教|政治|党派|收入|工资|银行卡|银行账户|精确地址|家庭住址)");
    private static final Pattern FINANCIAL = Pattern.compile("(?i)(net worth|credit card|portfolio|investment|mortgage|debt|income|贷款|负债|资产|投资|理财|信用卡)");
    private static final Pattern THIRD_PARTY_PRIVATE = Pattern.compile("(?i)(colleague|coworker|friend|customer|client|wife|husband|child|同事|朋友|客户|家人|配偶|孩子).{0,24}(full name|name|phone|email|address|birthday|姓名|名字|电话|邮箱|住址|生日|身份证)");
    private static final Pattern FIRST_PERSON = Pattern.compile("(?i)(我|我的|本人|I(?:'m| am| have| prefer| need| want)\\b|\\bmy\\b)");
    private final UserMemoryCandidateRepository candidates;
    private final UserMemoryRepository memories;
    private final UserMemoryVersionRepository versions;
    private final UserMemoryDeletionRepository deletions;
    private final AgentConversationTurnRepository turns;
    private final UserAccountRepository users;
    private final OutboxService outbox;
    private final ProgressEventPublisher progress;
    private final AppProperties properties;
    private final ObjectMapper mapper;
    private final String extractionTemplate;

    public UserMemoryService(UserMemoryCandidateRepository candidates, UserMemoryRepository memories,
                             UserMemoryVersionRepository versions, UserMemoryDeletionRepository deletions,
                             AgentConversationTurnRepository turns, UserAccountRepository users,
                             OutboxService outbox, ProgressEventPublisher progress,
                             AppProperties properties, ObjectMapper mapper) {
        this.candidates = candidates; this.memories = memories; this.versions = versions; this.deletions = deletions;
        this.turns = turns; this.users = users; this.outbox = outbox; this.progress = progress; this.properties = properties; this.mapper = mapper;
        try { this.extractionTemplate = new ClassPathResource("prompts/" + EXTRACTION_VERSION + ".md").getContentAsString(StandardCharsets.UTF_8); }
        catch (Exception exception) { throw new IllegalStateException("Cannot load versioned user-memory extraction prompt", exception); }
    }

    public String extractionPrompt(String userMessage) {
        return extractionTemplate.replace("{{MAX_CANDIDATES}}", Integer.toString(properties.getMemory().getMaxCandidatesPerTurn()))
                .replace("{{USER_MESSAGE}}", userMessage);
    }

    @Transactional
    public ExtractionWork claimExtraction(String turnId) {
        if (!properties.getMemory().isEnabled()) return null;
        AgentConversationTurn turn = turns.findByIdForUpdate(turnId).orElse(null);
        if (turn == null || !turn.beginExtraction()) return null;
        turns.save(turn); return new ExtractionWork(turn.getId(), turn.getOwnerId(), turn.getUserMessage(), turn.getExtractionAttempts());
    }

    @Transactional
    public void completeExtraction(String turnId, String rawResponse) { completeExtraction(turnId, rawResponse, null, null); }
    @Transactional
    public void completeExtraction(String turnId, String rawResponse, String modelId, Long durationMs) {
        AgentConversationTurn turn = turns.findById(turnId).orElseThrow();
        try {
            users.findByIdForUpdate(turn.getOwnerId()).orElseThrow();
            JsonNode root = mapper.readTree(stripFence(rawResponse));
            JsonNode values = root.path("candidates");
            if (!values.isArray()) throw new IllegalArgumentException("candidates must be an array");
            int remaining = Math.max(0, properties.getMemory().getMaxPendingCandidates()
                    - Math.toIntExact(candidates.countByOwnerIdAndStatus(turn.getOwnerId(), UserMemoryCandidateStatus.PENDING)));
            int accepted = 0;
            for (JsonNode value : values) {
                if (accepted >= properties.getMemory().getMaxCandidatesPerTurn() || remaining <= 0) break;
                CandidateDraft draft = validateDraft(turn.getUserMessage(), value);
                if (draft == null || candidates.findByOwnerIdAndSourceTurnIdAndSemanticKey(turn.getOwnerId(), turnId, draft.semanticKey()).isPresent()) continue;
                UserMemoryCandidate pending = candidates.findFirstByOwnerIdAndSemanticKeyAndStatusOrderByCreatedAtDesc(
                        turn.getOwnerId(), draft.semanticKey(), UserMemoryCandidateStatus.PENDING).orElse(null);
                if (pending != null && normalized(pending.getContent()).equals(normalized(draft.content()))) continue;
                UserMemory existing = memories.findByOwnerIdAndSemanticKey(turn.getOwnerId(), draft.semanticKey()).orElse(null);
                UserMemoryCandidateStatus status = UserMemoryCandidateStatus.PENDING;
                UserMemoryChangeType change = existing == null ? UserMemoryChangeType.CREATE : UserMemoryChangeType.UPDATE;
                if (existing != null) {
                    UserMemoryVersion current = versions.findById(existing.getCurrentVersionId()).orElse(null);
                    if (current != null && normalized(current.getContent()).equals(normalized(draft.content()))) status = UserMemoryCandidateStatus.DUPLICATE;
                }
                candidates.save(new UserMemoryCandidate(turn.getOwnerId(), turnId, draft.category(), draft.semanticKey(), draft.content(),
                        draft.excerpt(), draft.confidence(), change, existing == null ? null : existing.getId(), status, EXTRACTION_VERSION));
                accepted++; if (status == UserMemoryCandidateStatus.PENDING) remaining--;
            }
            turn.completeExtraction(modelId, durationMs); turns.save(turn); notifyChanged(turn.getOwnerId(), turnId);
        } catch (Exception exception) {
            throw new ProviderException(ProviderException.Kind.FINAL_REJECTION, "MEMORY_EXTRACTION_INVALID", "Memory extraction returned invalid data");
        }
    }

    @Transactional
    public void failExtraction(String turnId, String message) {
        failExtraction(turnId, "MEMORY_EXTRACTION_FAILED", message, null, null);
    }
    @Transactional
    public void failExtraction(String turnId, String code, String message, String modelId, Long durationMs) {
        AgentConversationTurn turn = turns.findById(turnId).orElse(null); if (turn == null) return;
        boolean retry = turn.getExtractionAttempts() < properties.getMemory().getMaxAttempts();
        turn.failExtraction(code, shorten(message), modelId, durationMs, retry); turns.save(turn);
    }

    @Transactional(readOnly = true)
    public List<CandidateView> candidateViews(String ownerId, UserMemoryCandidateStatus status) {
        return candidates.findByOwnerIdAndStatusOrderByCreatedAtDesc(ownerId, status).stream().map(this::candidateView).toList();
    }

    @Transactional
    public MemoryView confirm(String ownerId, String candidateId, String editedContent) {
        requireEnabled();
        UserMemoryCandidate candidate = ownedCandidate(ownerId, candidateId);
        users.findByIdForUpdate(ownerId).orElseThrow();
        if (candidate.getStatus() != UserMemoryCandidateStatus.PENDING) throw new ApiException(HttpStatus.CONFLICT, "MEMORY_CANDIDATE_NOT_PENDING", "Memory candidate is no longer pending");
        String content = editedContent == null || editedContent.isBlank() ? candidate.getContent() : editedContent.trim();
        validateContent(content);
        UserMemory memory = candidate.getTargetMemoryId() == null
                ? memories.findByOwnerIdAndSemanticKeyForUpdate(ownerId, candidate.getSemanticKey()).orElse(null)
                : memories.findByIdForUpdate(candidate.getTargetMemoryId()).filter(value -> value.getOwnerId().equals(ownerId)).orElse(null);
        if (memory == null) {
            if (memories.countByOwnerIdAndStatus(ownerId, UserMemoryStatus.ACTIVE) >= properties.getMemory().getMaxActiveMemories()) {
                throw new ApiException(HttpStatus.CONFLICT, "MEMORY_LIMIT_REACHED", "The active long-term memory limit has been reached");
            }
            memory = memories.save(new UserMemory(ownerId, candidate.getCategory(), candidate.getSemanticKey()));
        }
        UserMemoryVersion current = memory.getCurrentVersionId() == null ? null : versions.findById(memory.getCurrentVersionId()).orElse(null);
        if (current != null && normalized(current.getContent()).equals(normalized(content))) {
            candidate.duplicate(); candidates.save(candidate); return memoryView(memory);
        }
        int next = versions.findTopByMemoryIdOrderByVersionNumberDesc(memory.getId()).map(value -> value.getVersionNumber() + 1).orElse(1);
        UserMemoryVersion version = versions.save(new UserMemoryVersion(memory.getId(), next, content, candidate.getId()));
        memory.changeCategory(candidate.getCategory()); memory.useVersion(version.getId()); memories.save(memory);
        candidate.confirm(content); candidates.save(candidate);
        outbox.enqueue("user_memory_version", version.getId(), EventType.USER_MEMORY_INDEX_REQUESTED,
                "{\"versionId\":\"" + version.getId() + "\"}", "user-memory-index:" + version.getId());
        notifyChanged(ownerId, memory.getId()); return memoryView(memory);
    }

    @Transactional
    public void reject(String ownerId, String candidateId) {
        UserMemoryCandidate candidate = ownedCandidate(ownerId, candidateId);
        if (candidate.getStatus() != UserMemoryCandidateStatus.PENDING) throw new ApiException(HttpStatus.CONFLICT, "MEMORY_CANDIDATE_NOT_PENDING", "Memory candidate is no longer pending");
        candidate.reject(); candidates.save(candidate); notifyChanged(ownerId, candidateId);
    }

    @Transactional
    public CandidateView retry(String ownerId, String candidateId) {
        UserMemoryCandidate candidate = ownedCandidate(ownerId, candidateId);
        candidate.retry(); candidates.save(candidate); notifyChanged(ownerId, candidateId); return candidateView(candidate);
    }

    @Transactional(readOnly = true)
    public List<MemoryView> memoryViews(String ownerId) {
        return memories.findByOwnerIdAndStatusOrderByUpdatedAtDesc(ownerId, UserMemoryStatus.ACTIVE).stream().map(this::memoryView).toList();
    }

    @Transactional
    public MemoryView update(String ownerId, String memoryId, String content, UserMemoryCategory category) {
        requireEnabled();
        UserMemory memory = ownedMemory(ownerId, memoryId); String normalizedContent = content == null ? "" : content.trim(); validateContent(normalizedContent);
        UserMemoryVersion current = versions.findById(memory.getCurrentVersionId()).orElseThrow();
        if (normalized(current.getContent()).equals(normalized(normalizedContent)) && (category == null || category == memory.getCategory())) return memoryView(memory);
        int next = versions.findTopByMemoryIdOrderByVersionNumberDesc(memoryId).map(value -> value.getVersionNumber() + 1).orElse(1);
        UserMemoryVersion version = versions.save(new UserMemoryVersion(memoryId, next, normalizedContent, null));
        if (category != null) memory.changeCategory(category); memory.useVersion(version.getId()); memories.save(memory);
        outbox.enqueue("user_memory_version", version.getId(), EventType.USER_MEMORY_INDEX_REQUESTED,
                "{\"versionId\":\"" + version.getId() + "\"}", "user-memory-index:" + version.getId());
        notifyChanged(ownerId, memoryId); return memoryView(memory);
    }

    @Transactional
    public void delete(String ownerId, String memoryId) {
        UserMemory memory = ownedMemory(ownerId, memoryId);
        List<UserMemoryVersion> memoryVersions = versions.findByMemoryIdOrderByVersionNumberDesc(memoryId);
        candidates.deleteByTargetMemoryId(memoryId);
        candidates.deleteAllById(memoryVersions.stream().map(UserMemoryVersion::getSourceCandidateId).filter(Objects::nonNull).toList());
        memory.clearVersion(); memories.saveAndFlush(memory);
        versions.deleteByMemoryId(memoryId); memories.delete(memory);
        UserMemoryDeletion deletion = deletions.save(new UserMemoryDeletion(ownerId, memoryId));
        outbox.enqueue("user_memory_deletion", deletion.getId(), EventType.USER_MEMORY_DELETE_REQUESTED,
                "{\"deletionId\":\"" + deletion.getId() + "\"}", "user-memory-delete:" + deletion.getId());
        notifyChanged(ownerId, memoryId);
    }

    @Transactional(readOnly = true)
    public List<SearchResult> validateHits(String ownerId, List<UserMemoryVectorStore.MemoryHit> hits) {
        Map<String, UserMemory> byId = new HashMap<>();
        memories.findByIdInAndOwnerIdAndStatus(hits.stream().map(UserMemoryVectorStore.MemoryHit::memoryId).toList(), ownerId, UserMemoryStatus.ACTIVE)
                .forEach(value -> byId.put(value.getId(), value));
        List<SearchResult> output = new ArrayList<>();
        for (UserMemoryVectorStore.MemoryHit hit : hits) {
            UserMemory memory = byId.get(hit.memoryId());
            if (memory == null || !Objects.equals(memory.getCurrentVersionId(), hit.versionId())) continue;
            versions.findById(hit.versionId()).filter(value -> value.getMemoryId().equals(memory.getId()))
                    .ifPresent(value -> output.add(new SearchResult(memory.getId(), value.getId(), memory.getCategory(), value.getContent(), hit.score())));
        }
        return List.copyOf(output);
    }

    @Transactional public IndexWork claimIndex(String versionId) {
        UserMemoryVersion version = versions.findByIdForUpdate(versionId).orElse(null); if (version == null || !version.beginIndexing()) return null;
        UserMemory memory = memories.findById(version.getMemoryId()).orElse(null);
        if (memory == null || !Objects.equals(memory.getCurrentVersionId(), versionId)) { version.indexed(); versions.save(version); return null; }
        versions.save(version); return new IndexWork(version.getId(), memory.getId(), memory.getOwnerId(), memory.getCategory(), version.getContent(), version.getIndexAttempts());
    }
    @Transactional public void completeIndex(String versionId) { versions.findById(versionId).ifPresent(value -> { value.indexed(); versions.save(value); }); }
    @Transactional public void failIndex(String versionId, String message) { versions.findById(versionId).ifPresent(value -> { value.failIndex(shorten(message), value.getIndexAttempts() < properties.getMemory().getMaxAttempts()); versions.save(value); }); }
    @Transactional public DeletionWork claimDeletion(String deletionId) { UserMemoryDeletion value = deletions.findByIdForUpdate(deletionId).orElse(null); return value != null && value.begin() ? new DeletionWork(value.getId(), value.getOwnerId(), value.getMemoryId(), value.getAttempts()) : null; }
    @Transactional public void completeDeletion(String deletionId) { deletions.findById(deletionId).ifPresent(value -> { value.complete(); deletions.save(value); }); }
    @Transactional public void failDeletion(String deletionId, String message) { deletions.findById(deletionId).ifPresent(value -> { value.fail(shorten(message), value.getAttempts() < properties.getMemory().getMaxAttempts()); deletions.save(value); }); }

    private CandidateDraft validateDraft(String message, JsonNode value) {
        double confidence = value.path("confidence").asDouble(0);
        if (confidence < properties.getMemory().getCandidateConfidenceThreshold()) return null;
        UserMemoryCategory category; try { category = UserMemoryCategory.valueOf(value.path("category").asText("")); } catch (IllegalArgumentException exception) { return null; }
        String key = semanticKey(value.path("semanticKey").asText(""));
        String content = value.path("content").asText("").trim(); String excerpt = value.path("sourceExcerpt").asText("").trim();
        if (key.isBlank() || content.isBlank() || content.length() > 2000 || excerpt.isBlank() || excerpt.length() > 2000
                || !message.contains(excerpt) || !FIRST_PERSON.matcher(excerpt).find() || prohibited(content + " " + excerpt)) return null;
        return new CandidateDraft(category, key, content, excerpt, Math.min(1, confidence));
    }
    private CandidateView candidateView(UserMemoryCandidate candidate) {
        String current = candidate.getTargetMemoryId() == null ? null : memories.findById(candidate.getTargetMemoryId())
                .flatMap(value -> versions.findById(value.getCurrentVersionId())).map(UserMemoryVersion::getContent).orElse(null);
        return new CandidateView(candidate.getId(), candidate.getCategory(), candidate.getSemanticKey(), candidate.getContent(), candidate.getSourceExcerpt(),
                candidate.getConfidence(), candidate.getChangeType(), candidate.getTargetMemoryId(), current, candidate.getStatus(), candidate.getCreatedAt());
    }
    private MemoryView memoryView(UserMemory memory) {
        UserMemoryVersion version = versions.findById(memory.getCurrentVersionId()).orElseThrow();
        return new MemoryView(memory.getId(), memory.getCategory(), memory.getSemanticKey(), version.getId(), version.getVersionNumber(),
                version.getContent(), version.getIndexStatus(), version.getConfirmedAt(), memory.getUpdatedAt(), sourceConversationDeleted(memory.getId()));
    }
    private boolean sourceConversationDeleted(String memoryId) {
        return versions.findByMemoryIdOrderByVersionNumberDesc(memoryId).stream()
                .map(UserMemoryVersion::getSourceCandidateId).filter(Objects::nonNull)
                .map(candidates::findById).flatMap(Optional::stream).findFirst()
                .map(value -> value.getSourceTurnId() == null).orElse(false);
    }
    private UserMemoryCandidate ownedCandidate(String ownerId, String id) { return candidates.findByIdForUpdate(id).filter(value -> value.getOwnerId().equals(ownerId)).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "MEMORY_CANDIDATE_NOT_FOUND", "Memory candidate was not found")); }
    private UserMemory ownedMemory(String ownerId, String id) { return memories.findByIdForUpdate(id).filter(value -> value.getOwnerId().equals(ownerId)).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "USER_MEMORY_NOT_FOUND", "User memory was not found")); }
    private void validateContent(String content) { if (content.isBlank() || content.length() > 2000 || prohibited(content)) throw new ApiException(HttpStatus.BAD_REQUEST, "MEMORY_CONTENT_INVALID", "Memory content is empty, too long, or contains disallowed sensitive data"); }
    private static boolean prohibited(String value) { return SECRET.matcher(value).find() || SENSITIVE.matcher(value).find()
            || FINANCIAL.matcher(value).find() || THIRD_PARTY_PRIVATE.matcher(value).find(); }
    private static String semanticKey(String value) { String output = value.trim().toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}._-]+", "-").replaceAll("^-+|-+$", ""); return output.substring(0, Math.min(160, output.length())); }
    private static String normalized(String value) { return value.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT); }
    private void requireEnabled() { if (!properties.getMemory().isEnabled()) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "MEMORY_DISABLED", "Long-term memory is disabled"); }
    private void notifyChanged(String ownerId, String resourceId) { progress.publish(new ProgressEventPublisher.ProgressNotification(ownerId, "user-memory-changed", resourceId)); }
    private static String stripFence(String value) { String trimmed = Objects.toString(value, "").trim(); if (!trimmed.startsWith("```")) return trimmed; int start = trimmed.indexOf('\n'), end = trimmed.lastIndexOf("```"); return start >= 0 && end > start ? trimmed.substring(start + 1, end).trim() : trimmed; }
    private static String shorten(String value) { String normalized = Objects.toString(value, "Memory processing failed").replaceAll("[\\r\\n]+", " "); return normalized.substring(0, Math.min(1000, normalized.length())); }

    private record CandidateDraft(UserMemoryCategory category, String semanticKey, String content, String excerpt, double confidence) { }
    public record ExtractionWork(String turnId, String ownerId, String userMessage, int attempt) { }
    public record IndexWork(String versionId, String memoryId, String ownerId, UserMemoryCategory category, String content, int attempt) { }
    public record DeletionWork(String deletionId, String ownerId, String memoryId, int attempt) { }
    public record SearchResult(String memoryId, String versionId, UserMemoryCategory category, String content, double score) { }
    public record CandidateView(String id, UserMemoryCategory category, String semanticKey, String content, String sourceExcerpt,
                                double confidence, UserMemoryChangeType changeType, String targetMemoryId, String currentContent,
                                UserMemoryCandidateStatus status, java.time.Instant createdAt) { }
    public record MemoryView(String id, UserMemoryCategory category, String semanticKey, String versionId, int versionNumber,
                             String content, MemoryIndexStatus indexStatus, java.time.Instant confirmedAt, java.time.Instant updatedAt,
                             boolean sourceConversationDeleted) { }
}
