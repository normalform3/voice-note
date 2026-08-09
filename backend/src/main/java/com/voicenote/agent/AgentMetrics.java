package com.voicenote.agent;

import com.voicenote.domain.KnowledgeRunStatus;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class AgentMetrics {
    private final MeterRegistry registry;
    public AgentMetrics(MeterRegistry registry) { this.registry = registry; }

    public void modelCall(Duration duration, boolean success) {
        registry.counter("voicenote.agent.model.calls", "outcome", success ? "success" : "error").increment();
        registry.timer("voicenote.agent.model.latency", "outcome", success ? "success" : "error").record(duration);
    }
    public void toolCall(String toolName, Duration duration, boolean success) {
        registry.counter("voicenote.agent.tool.calls", "tool", safeToolName(toolName), "outcome", success ? "success" : "error").increment();
        registry.timer("voicenote.agent.tool.latency", "tool", safeToolName(toolName), "outcome", success ? "success" : "error").record(duration);
        if (toolName != null && toolName.startsWith("mcp.") && !success) registry.counter("voicenote.agent.mcp.errors", "tool", safeToolName(toolName)).increment();
    }
    public void settled(KnowledgeRunStatus status) { registry.counter("voicenote.agent.runs", "status", status.name()).increment(); }
    public void evidenceRejected(String code) { registry.counter("voicenote.agent.evidence.rejected", "code", code).increment(); }
    public void coverage(AgentExecutionContext.Coverage coverage) {
        registry.summary("voicenote.agent.coverage.scope_documents").record(coverage.scopeDocumentCount());
        registry.summary("voicenote.agent.coverage.overviewed_documents").record(coverage.overviewedDocumentIds().size());
        registry.summary("voicenote.agent.coverage.searched_documents").record(coverage.searchedDocumentIds().size());
        registry.summary("voicenote.agent.coverage.cited_documents").record(coverage.citedDocumentIds().size());
    }
    private static String safeToolName(String value) { return value == null || value.isBlank() ? "unknown" : value.substring(0, Math.min(128, value.length())); }
}
