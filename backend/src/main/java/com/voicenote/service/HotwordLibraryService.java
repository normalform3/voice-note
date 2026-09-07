package com.voicenote.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voicenote.config.AppProperties;
import com.voicenote.domain.*;
import com.voicenote.provider.HotwordVocabularyProvider;
import com.voicenote.provider.ProviderException;
import com.voicenote.repository.*;
import com.voicenote.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

@Service
public class HotwordLibraryService {
    public static final int LIBRARY_LIMIT = 10;
    public static final int ENTRY_LIMIT = 500;
    public static final int FIXED_WEIGHT = 4;
    public static final Duration UPDATE_COOLDOWN = Duration.ofMinutes(5);
    private static final Set<String> LANGUAGES = Set.of("zh", "en");
    private static final Set<RealtimeRecordingStatus> ACTIVE_RECORDINGS = Set.of(
            RealtimeRecordingStatus.RECORDING, RealtimeRecordingStatus.FINALIZING,
            RealtimeRecordingStatus.PROCESSING, RealtimeRecordingStatus.FAILED);
    private final HotwordLibraryRepository libraries;
    private final HotwordCapacityRepository capacity;
    private final TranscriptionTaskRepository tasks;
    private final RealtimeRecordingSessionRepository recordings;
    private final HotwordVocabularyProvider provider;
    private final IdempotencyService idempotency;
    private final ObjectMapper mapper;
    private final AppProperties properties;

    public HotwordLibraryService(HotwordLibraryRepository libraries, HotwordCapacityRepository capacity,
                                 TranscriptionTaskRepository tasks, RealtimeRecordingSessionRepository recordings,
                                 HotwordVocabularyProvider provider, IdempotencyService idempotency,
                                 ObjectMapper mapper, AppProperties properties) {
        this.libraries = libraries; this.capacity = capacity; this.tasks = tasks; this.recordings = recordings;
        this.provider = provider; this.idempotency = idempotency; this.mapper = mapper; this.properties = properties;
    }

    @Transactional(readOnly = true)
    public Catalog list(String ownerId) {
        List<LibraryView> items = libraries.findByOwnerIdAndStatusNotOrderByUpdatedAtDesc(ownerId, HotwordLibraryStatus.DELETED)
                .stream().map(this::view).toList();
        return new Catalog(items, new Capacity(libraries.countByStatusNot(HotwordLibraryStatus.DELETED), LIBRARY_LIMIT));
    }

    @Transactional
    public LibraryView create(String ownerId, String key, SaveCommand command) {
        Normalized input = normalize(command);
        IdempotencyRecord record = idempotency.reserve(ownerId, "CREATE_HOTWORD_LIBRARY", key, Hashing.canonicalJsonHash(input));
        if (record.getResourceId() != null) return view(owned(ownerId, record.getResourceId()));
        capacity.lockCapacity().orElseThrow(() -> new IllegalStateException("Hotword capacity row is missing"));
        if (libraries.countByStatusNot(HotwordLibraryStatus.DELETED) >= LIBRARY_LIMIT) {
            throw conflict("HOTWORD_LIBRARY_LIMIT_REACHED", "全系统最多可创建 10 个热词库，请先删除不再使用的词库");
        }
        ensureUniqueName(ownerId, input.name(), null);
        HotwordLibrary library = libraries.save(new HotwordLibrary(ownerId, input.name(), json(input.entries()), input.contentHash()));
        sync(library);
        complete(record, library);
        return view(library);
    }

    @Transactional
    public LibraryView update(String ownerId, String key, String id, SaveCommand command) {
        Normalized input = normalize(command);
        IdempotencyRecord record = idempotency.reserve(ownerId, "UPDATE_HOTWORD_LIBRARY", key, Hashing.canonicalJsonHash(Map.of("id", id, "input", input)));
        if (record.getResourceId() != null) return view(owned(ownerId, record.getResourceId()));
        HotwordLibrary library = locked(ownerId, id);
        if (library.getStatus() == HotwordLibraryStatus.DELETED || library.getStatus() == HotwordLibraryStatus.DELETING) throw notFound();
        ensureUniqueName(ownerId, input.name(), id);
        if (library.getContentHash().equals(input.contentHash())) {
            library.rename(input.name()); libraries.save(library); complete(record, library); return view(library);
        }
        assertMutable(library);
        Instant nextUpdateAt = nextUpdateAt(library);
        if (nextUpdateAt != null && nextUpdateAt.isAfter(Instant.now())) {
            long seconds = Math.max(1, Duration.between(Instant.now(), nextUpdateAt).toSeconds());
            throw conflict("HOTWORD_UPDATE_COOLDOWN", "DashScope 建议同一词表至少间隔 5 分钟更新，请在 " + seconds + " 秒后重试");
        }
        library.replace(input.name(), json(input.entries()), input.contentHash()); libraries.save(library);
        sync(library); complete(record, library); return view(library);
    }

    @Transactional
    public LibraryView delete(String ownerId, String key, String id) {
        IdempotencyRecord record = idempotency.reserve(ownerId, "DELETE_HOTWORD_LIBRARY", key, Hashing.sha256(id));
        if (record.getResourceId() != null) return view(ownedIncludingDeleted(ownerId, record.getResourceId()));
        HotwordLibrary library = locked(ownerId, id);
        if (library.getStatus() == HotwordLibraryStatus.DELETED) { complete(record, library); return view(library); }
        assertMutable(library); library.beginDelete(); libraries.save(library); deleteFromProvider(library); complete(record, library); return view(library);
    }

    @Transactional
    public LibraryView retrySync(String ownerId, String key, String id) {
        IdempotencyRecord record = idempotency.reserve(ownerId, "RETRY_HOTWORD_LIBRARY_SYNC", key, Hashing.sha256(id));
        if (record.getResourceId() != null) return view(ownedIncludingDeleted(ownerId, record.getResourceId()));
        HotwordLibrary library = locked(ownerId, id);
        if (library.getStatus() == HotwordLibraryStatus.SYNC_FAILED) { library.beginSync(); libraries.save(library); sync(library); }
        else if (library.getStatus() == HotwordLibraryStatus.DELETE_FAILED) { library.beginDelete(); libraries.save(library); deleteFromProvider(library); }
        else throw conflict("HOTWORD_SYNC_NOT_RETRYABLE", "当前热词库没有可重试的同步操作");
        complete(record, library); return view(library);
    }

    @Transactional
    public Selection resolveForUse(String ownerId, String id) {
        if (id == null || id.isBlank()) return null;
        HotwordLibrary library = libraries.findOwnedForUse(id, ownerId).orElseThrow(this::notFound);
        if (library.getStatus() != HotwordLibraryStatus.READY || library.getProviderVocabularyId() == null) {
            throw conflict("HOTWORD_LIBRARY_NOT_READY", "所选热词库尚未同步完成，请在个人中心检查后重试");
        }
        return new Selection(library.getId(), library.getRevision(), library.getProviderVocabularyId());
    }

    @Scheduled(fixedDelayString = "${app.workers.recovery-interval-ms:30000}")
    @Transactional
    public void recoverStaleOperations() {
        if (!properties.getWorkers().isEnabled()) return;
        Instant cutoff = Instant.now().minusSeconds(60);
        for (HotwordLibrary library : libraries.findTop10ByStatusInAndUpdatedAtBeforeOrderByUpdatedAtAsc(
                Set.of(HotwordLibraryStatus.SYNCING, HotwordLibraryStatus.DELETING), cutoff)) {
            if (library.getStatus() == HotwordLibraryStatus.DELETING) deleteFromProvider(library); else sync(library);
        }
    }

    private void sync(HotwordLibrary library) {
        try {
            List<EntryInput> values = entries(library);
            List<HotwordVocabularyProvider.Entry> providerEntries = values.stream()
                    .map(value -> new HotwordVocabularyProvider.Entry(value.text(), FIXED_WEIGHT, value.language())).toList();
            String vocabularyId = library.getProviderVocabularyId();
            if (vocabularyId == null) vocabularyId = provider.findIdByPrefix(library.getProviderPrefix());
            if (vocabularyId == null) vocabularyId = provider.create(library.getProviderPrefix(), properties.getDashscope().getAsrModel(), providerEntries);
            else {
                HotwordVocabularyProvider.Vocabulary current = provider.query(vocabularyId);
                if (!matches(current, providerEntries)) provider.update(vocabularyId, providerEntries);
            }
            library.ready(vocabularyId); libraries.save(library);
        } catch (ProviderException exception) {
            library.syncFailed(exception.getCode(), safe(exception.getMessage())); libraries.save(library);
        }
    }

    private void deleteFromProvider(HotwordLibrary library) {
        try {
            if (library.getProviderVocabularyId() != null) provider.delete(library.getProviderVocabularyId());
            library.deleted(); libraries.save(library);
        } catch (ProviderException exception) {
            library.deleteFailed(exception.getCode(), safe(exception.getMessage())); libraries.save(library);
        }
    }

    private boolean matches(HotwordVocabularyProvider.Vocabulary current, List<HotwordVocabularyProvider.Entry> desired) {
        if (current == null || !"OK".equals(current.status()) || !properties.getDashscope().getAsrModel().equals(current.targetModel())) return false;
        return new HashSet<>(current.entries()).equals(new HashSet<>(desired));
    }
    private void assertMutable(HotwordLibrary library) {
        if (tasks.existsByHotwordLibraryIdAndTranscriptReadyFalse(library.getId())
                || recordings.existsByHotwordLibraryIdAndStatusIn(library.getId(), ACTIVE_RECORDINGS)) {
            throw conflict("HOTWORD_LIBRARY_IN_USE", "该词库正被录音或未完成的转写使用，请等待完成或删除相关失败任务后再操作");
        }
    }
    private void ensureUniqueName(String ownerId, String name, String excludedId) {
        boolean duplicate = libraries.findByOwnerIdAndStatusNotOrderByUpdatedAtDesc(ownerId, HotwordLibraryStatus.DELETED).stream()
                .anyMatch(value -> !value.getId().equals(excludedId) && value.getDisplayName().equalsIgnoreCase(name));
        if (duplicate) throw conflict("HOTWORD_LIBRARY_NAME_EXISTS", "已存在同名热词库");
    }
    private Normalized normalize(SaveCommand command) {
        if (command == null) throw bad("HOTWORD_LIBRARY_REQUIRED", "热词库内容不能为空");
        String name = command.name() == null ? "" : command.name().trim();
        if (name.isEmpty() || name.length() > 120) throw bad("INVALID_HOTWORD_LIBRARY_NAME", "词库名称长度必须为 1 到 120 个字符");
        if (command.entries() == null || command.entries().isEmpty() || command.entries().size() > ENTRY_LIMIT) {
            throw bad("INVALID_HOTWORD_ENTRY_COUNT", "每个词库必须包含 1 到 500 个热词");
        }
        List<EntryInput> output = new ArrayList<>(); Set<String> seen = new HashSet<>();
        for (EntryInput entry : command.entries()) {
            String text = entry == null || entry.text() == null ? "" : entry.text().trim().replaceAll("\\s+", " ");
            String language = entry == null || entry.language() == null ? "" : entry.language().trim().toLowerCase(Locale.ROOT);
            if (text.isEmpty()) throw bad("HOTWORD_TEXT_REQUIRED", "热词不能为空");
            if (!LANGUAGES.contains(language)) throw bad("HOTWORD_LANGUAGE_UNSUPPORTED", "热词语言仅支持 zh 或 en");
            boolean ascii = text.chars().allMatch(value -> value <= 127);
            if ((!ascii && text.codePointCount(0, text.length()) > 15) || (ascii && text.split(" ").length > 7)) {
                throw bad("HOTWORD_TEXT_TOO_LONG", "含中文的热词最多 15 个字符，纯英文热词最多 7 个空格分段");
            }
            String identity = language + "\u0000" + text.toLowerCase(Locale.ROOT);
            if (!seen.add(identity)) throw bad("HOTWORD_DUPLICATED", "同一语言中不能添加重复热词：" + text);
            output.add(new EntryInput(text, language));
        }
        output.sort(Comparator.comparing(EntryInput::language).thenComparing(EntryInput::text));
        List<EntryInput> result = List.copyOf(output);
        return new Normalized(name, result, Hashing.canonicalJsonHash(result));
    }
    private Instant nextUpdateAt(HotwordLibrary value) { return value.getProviderSyncedAt() == null ? null : value.getProviderSyncedAt().plus(UPDATE_COOLDOWN); }
    private HotwordLibrary locked(String ownerId, String id) { return libraries.findOwnedForUpdate(id, ownerId).orElseThrow(this::notFound); }
    private HotwordLibrary owned(String ownerId, String id) { return libraries.findById(id).filter(value -> value.getOwnerId().equals(ownerId) && value.getStatus() != HotwordLibraryStatus.DELETED).orElseThrow(this::notFound); }
    private HotwordLibrary ownedIncludingDeleted(String ownerId, String id) { return libraries.findById(id).filter(value -> value.getOwnerId().equals(ownerId)).orElseThrow(this::notFound); }
    private ApiException notFound() { return new ApiException(HttpStatus.NOT_FOUND, "HOTWORD_LIBRARY_NOT_FOUND", "热词库不存在"); }
    private static ApiException bad(String code, String message) { return new ApiException(HttpStatus.BAD_REQUEST, code, message); }
    private static ApiException conflict(String code, String message) { return new ApiException(HttpStatus.CONFLICT, code, message); }
    private String json(Object value) { try { return mapper.writeValueAsString(value); } catch (Exception exception) { throw new IllegalStateException("Cannot serialize hotword library", exception); } }
    private List<EntryInput> entries(HotwordLibrary value) { try { return mapper.readValue(value.getEntries(), new TypeReference<>() { }); } catch (Exception exception) { throw new IllegalStateException("Stored hotword entries are invalid", exception); } }
    private void complete(IdempotencyRecord record, HotwordLibrary library) { idempotency.complete(record, library.getId(), json(view(library))); }
    private LibraryView view(HotwordLibrary value) {
        Instant next = nextUpdateAt(value);
        return new LibraryView(value.getId(), value.getDisplayName(), entries(value), value.getRevision(), value.getStatus(),
                value.getErrorCode(), value.getErrorMessage(), next, value.getCreatedAt(), value.getUpdatedAt());
    }
    private static String safe(String message) { if (message == null || message.isBlank()) return "热词同步失败"; String clean = message.replaceAll("[\\r\\n]+", " ").trim(); return clean.substring(0, Math.min(300, clean.length())); }

    public record EntryInput(String text, String language) { }
    public record SaveCommand(String name, List<EntryInput> entries) { }
    private record Normalized(String name, List<EntryInput> entries, String contentHash) { }
    public record Selection(String libraryId, int revision, String vocabularyId) { }
    public record Capacity(long used, int limit) { }
    public record Catalog(List<LibraryView> items, Capacity capacity) { }
    public record LibraryView(String id, String name, List<EntryInput> entries, int revision, HotwordLibraryStatus status,
                              String errorCode, String errorMessage, Instant nextUpdateAt, Instant createdAt, Instant updatedAt) { }
}
