package com.voicenote.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.voicenote.domain.AgentScopeType;
import java.time.Instant;
import java.time.ZoneId;
import java.util.*;

public class AgentExecutionContext {
    private final String runId;
    private final String ownerId;
    private final AgentScopeType scopeType;
    private final ZoneId timeZone;
    private final AgentSkill skill;
    private final List<ScopeDocument> documents;
    private final AgentEvidenceLedger evidence = new AgentEvidenceLedger();
    private final Set<String> overviewed = new LinkedHashSet<>();
    private final Set<String> searched = new LinkedHashSet<>();
    private final List<String> limitations = new ArrayList<>();
    private final Instant deadline;

    public AgentExecutionContext(String runId, String ownerId, AgentScopeType scopeType, ZoneId timeZone, AgentSkill skill,
                                 List<ScopeDocument> documents, Instant deadline) {
        this.runId = runId; this.ownerId = ownerId; this.scopeType = scopeType; this.timeZone = timeZone; this.skill = skill;
        this.documents = List.copyOf(documents); this.deadline = deadline;
    }
    public String runId() { return runId; }
    public String ownerId() { return ownerId; }
    public AgentScopeType scopeType() { return scopeType; }
    public ZoneId timeZone() { return timeZone; }
    public AgentSkill skill() { return skill; }
    public List<ScopeDocument> documents() { return documents; }
    public AgentEvidenceLedger evidence() { return evidence; }
    public Instant deadline() { return deadline; }
    public void markOverviewed(Collection<String> taskIds) { overviewed.addAll(taskIds); }
    public void markSearched(Collection<String> taskIds) { searched.addAll(taskIds); }
    public boolean hasOverviewedAllDocuments() { return documents.stream().allMatch(value -> overviewed.contains(value.taskId())); }
    public void addLimitation(String value) { if (value != null && !value.isBlank() && !limitations.contains(value)) limitations.add(value); }
    public Coverage coverage(Collection<AgentEvidenceLedger.EvidenceSource> cited) {
        List<String> citedIds = cited.stream().map(AgentEvidenceLedger.EvidenceSource::taskId).filter(Objects::nonNull).distinct().toList();
        List<String> omitted = documents.stream().map(ScopeDocument::taskId).filter(id -> !overviewed.contains(id) && !searched.contains(id)).toList();
        return new Coverage(documents.size(), List.copyOf(overviewed), List.copyOf(searched), citedIds, omitted, List.copyOf(limitations));
    }
    public ScopeDocument requireDocument(String taskId) {
        return documents.stream().filter(value -> value.taskId().equals(taskId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Document is outside the Agent Run scope: " + taskId));
    }

    public record ScopeDocument(String taskId, String knowledgeDocumentId, String indexVersionId, String title, Instant occurredAt,
                                String sceneType, String subject, List<String> tags, int transcriptVersion, JsonNode metadataSnapshot) { }
    public record Coverage(int scopeDocumentCount, List<String> overviewedDocumentIds, List<String> searchedDocumentIds,
                           List<String> citedDocumentIds, List<String> omittedDocumentIds, List<String> limitations) { }
}
