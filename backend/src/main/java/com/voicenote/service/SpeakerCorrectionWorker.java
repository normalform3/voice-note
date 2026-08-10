package com.voicenote.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voicenote.config.AppProperties;
import com.voicenote.domain.*;
import com.voicenote.provider.AnalysisModelClient;
import com.voicenote.provider.ProviderException;
import org.springframework.core.io.ClassPathResource;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

@Component
public class SpeakerCorrectionWorker {
    private static final int MAX_CHUNK_CHARACTERS = 12_000;
    private static final int OVERLAP_SEGMENTS = 5;
    private final SpeakerCorrectionService service; private final SpeakerCorrectionTimingAligner timing;
    private final AnalysisModelClient model; private final ObjectMapper mapper; private final AppProperties properties; private final String template;

    public SpeakerCorrectionWorker(SpeakerCorrectionService service, SpeakerCorrectionTimingAligner timing, AnalysisModelClient model,
                                   ObjectMapper mapper, AppProperties properties) {
        this.service = service; this.timing = timing; this.model = model; this.mapper = mapper; this.properties = properties;
        try { this.template = new ClassPathResource("prompts/speaker-correction-v1.md").getContentAsString(StandardCharsets.UTF_8); }
        catch (Exception exception) { throw new IllegalStateException("Cannot load speaker correction prompt", exception); }
    }

    @Scheduled(fixedDelayString = "${app.workers.poll-interval-ms:5000}")
    public void work() {
        if (!properties.getWorkers().isEnabled()) return;
        service.queuedRunIds().forEach(this::process);
    }

    public void process(String runId) {
        SpeakerCorrectionService.RunWork work;
        try { work = service.claim(runId); }
        catch (ObjectOptimisticLockingFailureException concurrentClaim) { return; }
        if (work == null) return;
        try {
            Map<String, TranscriptSegment> source = work.segments().stream().collect(Collectors.toMap(TranscriptSegment::getId, value -> value));
            Set<String> knownSpeakers = work.speakers().stream().map(TranscriptSpeaker::getAsrSpeakerId).collect(Collectors.toSet());
            Map<String, List<Candidate>> candidates = new LinkedHashMap<>(); int rejected = 0; int chunkIndex = 0;
            for (List<TranscriptSegment> chunk : chunks(work.segments())) {
                String prompt = prompt(work, chunk); ParseResult parsed;
                try {
                    String response = model.complete(prompt); service.recordInvocation(runId, chunkIndex, 1, prompt, response, false);
                    parsed = parse(work, response, source, knownSpeakers);
                } catch (InvalidModelResponse invalid) {
                    String repair = prompt + "\n\n上一次输出不符合 JSON 契约。请重新检查并只返回符合上述结构的 JSON。";
                    String response = model.complete(repair); service.recordInvocation(runId, chunkIndex, 2, repair, response, false);
                    parsed = parse(work, response, source, knownSpeakers);
                }
                rejected += parsed.rejected();
                for (Candidate candidate : parsed.candidates()) candidates.computeIfAbsent(candidate.sourceSegmentId(), ignored -> new ArrayList<>()).add(candidate);
                chunkIndex++;
            }
            List<SpeakerCorrectionService.SuggestionDraft> drafts = new ArrayList<>();
            for (List<Candidate> values : candidates.values()) {
                Map<String, Candidate> distinct = values.stream().collect(Collectors.toMap(Candidate::signature, value -> value,
                        (first, second) -> first.confidence() >= second.confidence() ? first : second, LinkedHashMap::new));
                if (distinct.size() != 1) { rejected += values.size(); continue; }
                Candidate candidate = distinct.values().iterator().next();
                drafts.add(new SpeakerCorrectionService.SuggestionDraft(candidate.sourceSegmentId(), candidate.type(), candidate.targetSpeakerId(),
                        candidate.proposalDocument(), candidate.confidence(), candidate.reason(), candidate.timingSource()));
            }
            service.complete(runId, drafts, rejected);
        } catch (ProviderException exception) {
            if (exception.getKind() == ProviderException.Kind.AMBIGUOUS_SUBMISSION) service.recordInvocation(runId, -1, 1, "provider-call", null, true);
            service.fail(runId, exception.getCode(), exception.getMessage());
        } catch (InvalidModelResponse exception) {
            service.fail(runId, "SPEAKER_CORRECTION_RESPONSE_INVALID", exception.getMessage());
        } catch (RuntimeException exception) {
            service.fail(runId, "SPEAKER_CORRECTION_FAILED", exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage());
        }
    }

    private String prompt(SpeakerCorrectionService.RunWork work, List<TranscriptSegment> chunk) {
        try {
            List<Map<String, Object>> roster = work.speakers().stream().map(value -> {
                Map<String, Object> item = new LinkedHashMap<>(); item.put("speakerId", value.getAsrSpeakerId());
                item.put("displayName", value.getDisplayName()); item.put("role", value.getResolvedRole().name()); return item;
            }).toList();
            List<Map<String, Object>> lines = chunk.stream().map(value -> {
                Map<String, Object> item = new LinkedHashMap<>(); item.put("segmentId", value.getId()); item.put("speakerId", value.getEffectiveSpeakerId());
                item.put("startMs", value.getStartMs()); item.put("endMs", value.getEndMs()); item.put("humanLocked", value.isHumanCorrected());
                item.put("text", value.getTextContent()); return item;
            }).toList();
            String scene = work.sceneType().name() + (work.subject() == null || work.subject().isBlank() ? "" : " / " + work.subject());
            return template.replace("{{SPEAKERS}}", mapper.writeValueAsString(roster)).replace("{{SCENE}}", scene)
                    .replace("{{SEGMENTS}}", mapper.writeValueAsString(lines));
        } catch (Exception exception) { throw new IllegalStateException("Cannot build speaker correction prompt", exception); }
    }

    private ParseResult parse(SpeakerCorrectionService.RunWork work, String response, Map<String, TranscriptSegment> source,
                              Set<String> knownSpeakers) {
        JsonNode root;
        try { root = mapper.readTree(response); }
        catch (Exception exception) { throw new InvalidModelResponse("AI speaker correction must return valid JSON"); }
        JsonNode values = root == null ? null : root.path("suggestions");
        if (root == null || !root.isObject() || values == null || !values.isArray() || values.size() > 1_000) {
            throw new InvalidModelResponse("AI speaker correction JSON does not match the required schema");
        }
        List<Candidate> output = new ArrayList<>(); int rejected = 0;
        for (JsonNode value : values) {
            try {
                String segmentId = requiredText(value, "segmentId"); TranscriptSegment segment = source.get(segmentId);
                if (segment == null || segment.isHumanCorrected()) { rejected++; continue; }
                SpeakerCorrectionSuggestionType type = SpeakerCorrectionSuggestionType.valueOf(requiredText(value, "type"));
                double confidence = value.path("confidence").asDouble(Double.NaN);
                String reason = requiredText(value, "reason").trim();
                if (!Double.isFinite(confidence) || confidence < 0 || confidence > 1 || reason.isEmpty() || reason.length() > 512) { rejected++; continue; }
                if (type == SpeakerCorrectionSuggestionType.RELABEL) {
                    String speakerId = requiredText(value, "speakerId");
                    if (!knownSpeakers.contains(speakerId) || speakerId.equals(segment.getEffectiveSpeakerId())) { rejected++; continue; }
                    output.add(new Candidate(segmentId, type, speakerId, "[]", confidence, reason, SegmentTimingSource.ASR,
                            "RELABEL:" + speakerId));
                    continue;
                }
                JsonNode partNodes = value.path("parts");
                if (!partNodes.isArray() || partNodes.size() < 2 || partNodes.size() > 20) { rejected++; continue; }
                List<SpeakerCorrectionTimingAligner.TextPart> parts = new ArrayList<>(); StringBuilder combined = new StringBuilder();
                Set<String> partSpeakers = new LinkedHashSet<>();
                for (JsonNode part : partNodes) {
                    String speakerId = requiredText(part, "speakerId"); String text = part.path("text").asText(null);
                    if (!knownSpeakers.contains(speakerId) || text == null || text.isEmpty()) throw new InvalidSuggestion();
                    parts.add(new SpeakerCorrectionTimingAligner.TextPart(speakerId, text)); combined.append(text); partSpeakers.add(speakerId);
                }
                if (!combined.toString().equals(segment.getTextContent()) || partSpeakers.size() < 2) { rejected++; continue; }
                List<SpeakerCorrectionTimingAligner.TimedPart> timed = timing.align(work.ownerId(), work.taskId(), work.transcriptVersion(), segment, parts);
                List<SpeakerCorrectionService.ProposalPart> proposal = timed.stream().map(part -> new SpeakerCorrectionService.ProposalPart(part.startOffset(), part.endOffset(),
                        part.speakerId(), part.text(), part.startMs(), part.endMs(), part.timingSource())).toList();
                SegmentTimingSource timingSource = timed.stream().anyMatch(part -> part.timingSource() == SegmentTimingSource.PROPORTIONAL)
                        ? SegmentTimingSource.PROPORTIONAL : SegmentTimingSource.WORD_ALIGNED;
                String proposalDocument = mapper.writeValueAsString(proposal);
                String signature = "SPLIT:" + parts.stream().map(part -> part.speakerId() + "\u0000" + part.text()).collect(Collectors.joining("\u0001"));
                output.add(new Candidate(segmentId, type, null, proposalDocument, confidence, reason, timingSource, signature));
            } catch (InvalidSuggestion | IllegalArgumentException exception) { rejected++; }
            catch (Exception exception) { throw new InvalidModelResponse("AI split proposal could not be validated"); }
        }
        return new ParseResult(output, rejected);
    }

    static List<List<TranscriptSegment>> chunks(List<TranscriptSegment> source) {
        if (source.isEmpty()) return List.of(); List<List<TranscriptSegment>> output = new ArrayList<>(); int start = 0;
        while (start < source.size()) {
            int end = start; int characters = 0;
            while (end < source.size() && (end == start || characters + source.get(end).getTextContent().length() <= MAX_CHUNK_CHARACTERS)) {
                characters += source.get(end).getTextContent().length(); end++;
            }
            output.add(List.copyOf(source.subList(start, end))); if (end == source.size()) break;
            start = Math.max(start + 1, end - OVERLAP_SEGMENTS);
        }
        return output;
    }

    private static String requiredText(JsonNode value, String field) {
        String output = value.path(field).asText(null); if (output == null || output.isBlank()) throw new InvalidSuggestion(); return output;
    }
    private record ParseResult(List<Candidate> candidates, int rejected) { }
    private record Candidate(String sourceSegmentId, SpeakerCorrectionSuggestionType type, String targetSpeakerId, String proposalDocument,
                             double confidence, String reason, SegmentTimingSource timingSource, String signature) { }
    private static final class InvalidSuggestion extends RuntimeException { }
    private static final class InvalidModelResponse extends RuntimeException { InvalidModelResponse(String message) { super(message); } }
}
