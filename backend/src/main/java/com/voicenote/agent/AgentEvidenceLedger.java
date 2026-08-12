package com.voicenote.agent;

import com.voicenote.domain.EvidenceSourceKind;
import java.util.*;

public class AgentEvidenceLedger {
    private final Map<String, EvidenceSource> byRef = new LinkedHashMap<>();
    private final Map<String, String> refsByIdentity = new HashMap<>();

    public String registerTranscript(String documentId, String taskId, String chunkId, String segmentId, String topic,
                                     String speakerId, Long startMs, Long endMs, String text) {
        String identity = "segment:" + taskId + ":" + segmentId;
        return refsByIdentity.computeIfAbsent(identity, ignored -> register(new EvidenceSource(nextRef(), EvidenceSourceKind.TRANSCRIPT_SEGMENT,
                documentId, taskId, chunkId, segmentId, null, null, topic, speakerId, startMs, endMs, text, null, null)));
    }

    public String registerMetadata(String documentId, String taskId, String label, String text) {
        String identity = "metadata:" + taskId + ":" + label;
        return refsByIdentity.computeIfAbsent(identity, ignored -> register(new EvidenceSource(nextRef(), EvidenceSourceKind.DOCUMENT_METADATA,
                documentId, taskId, null, null, null, null, null, null, null, null, text, label, null)));
    }

    public String registerExternal(String label, String url, String text) {
        String identity = "external:" + label + ":" + Objects.toString(url, "");
        return refsByIdentity.computeIfAbsent(identity, ignored -> register(new EvidenceSource(nextRef(), EvidenceSourceKind.EXTERNAL,
                null, null, null, null, null, null, null, null, null, null, text, label, url)));
    }

    public String registerMemory(String memoryId, String versionId, String category, String text) {
        String identity = "memory:" + memoryId + ":" + versionId;
        return refsByIdentity.computeIfAbsent(identity, ignored -> register(new EvidenceSource(nextRef(), EvidenceSourceKind.USER_MEMORY,
                null, null, null, null, memoryId, versionId, category, null, null, null, text, "已确认的用户记忆", null)));
    }

    public EvidenceSource require(String ref) {
        EvidenceSource source = byRef.get(ref);
        if (source == null) throw new IllegalArgumentException("Unknown sourceRef: " + ref);
        return source;
    }
    public void restore(EvidenceSource source) {
        byRef.putIfAbsent(source.ref(), source);
        refsByIdentity.putIfAbsent(identity(source), source.ref());
    }
    public boolean containsTranscriptExcerpt(String serializedArguments) {
        if (serializedArguments == null || serializedArguments.isBlank()) return false;
        for (EvidenceSource source : byRef.values()) {
            if (source.kind() != EvidenceSourceKind.TRANSCRIPT_SEGMENT || source.text() == null) continue;
            String text = source.text().trim();
            if (text.length() >= 24 && serializedArguments.contains(text.substring(0, Math.min(80, text.length())))) return true;
        }
        return false;
    }
    public Collection<EvidenceSource> all() { return List.copyOf(byRef.values()); }

    private String register(EvidenceSource source) { byRef.put(source.ref(), source); return source.ref(); }
    private static String nextRef() { return "src_" + UUID.randomUUID().toString().replace("-", ""); }
    private static String identity(EvidenceSource source) {
        return switch (source.kind()) {
            case TRANSCRIPT_SEGMENT -> "segment:" + source.taskId() + ":" + source.segmentId();
            case DOCUMENT_METADATA -> "metadata:" + source.taskId() + ":" + source.label();
            case EXTERNAL -> "external:" + source.label() + ":" + Objects.toString(source.url(), "");
            case USER_MEMORY -> "memory:" + source.memoryId() + ":" + source.memoryVersionId();
        };
    }

    public record EvidenceSource(String ref, EvidenceSourceKind kind, String documentId, String taskId, String chunkId, String segmentId,
                                 String memoryId, String memoryVersionId,
                                 String topic, String speakerId, Long startMs, Long endMs, String text, String label, String url) { }
}
