package com.voicenote.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.voicenote.agent.*;
import com.voicenote.domain.EvidenceSourceKind;
import com.voicenote.provider.AgentModelClient;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class FinalizeAnswerTool implements AgentTool {
    private final ObjectMapper mapper;
    public FinalizeAnswerTool(ObjectMapper mapper) { this.mapper = mapper; }

    @Override public AgentModelClient.AgentToolDefinition definition() {
        ObjectNode evidence = mapper.createObjectNode().put("type", "array").put("minItems", 1);
        evidence.putObject("items").put("type", "string");
        ObjectNode finding = mapper.createObjectNode().put("type", "object").put("additionalProperties", false);
        ObjectNode findingProperties = finding.putObject("properties");
        findingProperties.putObject("title").put("type", "string"); findingProperties.putObject("content").put("type", "string"); findingProperties.set("evidenceRefs", evidence);
        finding.putArray("required").add("title").add("content").add("evidenceRefs");
        ObjectNode schema = mapper.createObjectNode().put("type", "object").put("additionalProperties", false);
        ObjectNode properties = schema.putObject("properties"); properties.putObject("answer").put("type", "string");
        properties.putObject("findings").put("type", "array").set("items", finding);
        properties.putObject("limitations").put("type", "array").putObject("items").put("type", "string");
        schema.putArray("required").add("answer").add("findings");
        return new AgentModelClient.AgentToolDefinition("finalize_answer", "Submit the final evidence-backed answer. Every factual finding must cite sourceRefs returned by tools in this run.", schema);
    }

    @Override public ToolResult execute(AgentExecutionContext context, JsonNode arguments) {
        if (!arguments.isObject() || !arguments.path("answer").isTextual() || !arguments.path("findings").isArray()) {
            throw new IllegalArgumentException("Final answer requires answer and findings");
        }
        if (context.documents().size() > 1 && context.skill().requireOverviewForMultipleDocuments() && !context.hasOverviewedAllDocuments()) {
            throw new IllegalArgumentException("This multi-document Skill must read every available document overview before finalizing");
        }
        ObjectNode normalized = mapper.createObjectNode(); normalized.put("answer", arguments.path("answer").asText()); ArrayNode findings = normalized.putArray("findings");
        LinkedHashMap<String, AgentEvidenceLedger.EvidenceSource> cited = new LinkedHashMap<>();
        for (JsonNode rawFinding : arguments.path("findings")) {
            if (!rawFinding.path("title").isTextual() || !rawFinding.path("content").isTextual() || !rawFinding.path("evidenceRefs").isArray()) {
                throw new IllegalArgumentException("Every finding requires title, content, and evidenceRefs");
            }
            ObjectNode finding = findings.addObject(); finding.put("title", rawFinding.path("title").asText()); finding.put("content", rawFinding.path("content").asText());
            ArrayNode evidence = finding.putArray("evidence"); boolean hasTranscript = false;
            for (JsonNode refNode : rawFinding.path("evidenceRefs")) {
                AgentEvidenceLedger.EvidenceSource source = context.evidence().require(refNode.asText()); cited.put(source.ref(), source);
                if (source.kind() == EvidenceSourceKind.TRANSCRIPT_SEGMENT) hasTranscript = true;
                evidence.addObject().put("sourceRef", source.ref()).put("kind", source.kind().name());
            }
            if (!hasTranscript) throw new IllegalArgumentException("Every factual finding must cite at least one transcript sourceRef");
        }
        if (arguments.path("limitations").isArray()) arguments.path("limitations").forEach(value -> context.addLimitation(value.asText()));
        if (context.documents().size() > 1 && context.coverage(cited.values()).omittedDocumentIds().size() > 0) context.addLimitation("notAllScopeDocumentsReviewed");
        normalized.set("coverage", mapper.valueToTree(context.coverage(cited.values())));
        return ToolResult.terminal(normalized, "提交包含 " + findings.size() + " 条发现和 " + cited.size() + " 个引用的最终答案");
    }
}
