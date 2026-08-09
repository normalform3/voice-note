package com.voicenote.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.voicenote.agent.*;
import com.voicenote.domain.OrganizedBlockType;
import com.voicenote.domain.OrganizedDocumentStatus;
import com.voicenote.provider.AgentModelClient;
import com.voicenote.repository.*;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class DocumentOverviewTool implements AgentTool {
    private final ObjectMapper mapper;
    private final KnowledgeIndexVersionRepository versions;
    private final OrganizedDocumentRepository organizedDocuments;
    private final OrganizedDocumentBlockRepository organizedBlocks;

    public DocumentOverviewTool(ObjectMapper mapper, KnowledgeIndexVersionRepository versions, OrganizedDocumentRepository organizedDocuments,
                                OrganizedDocumentBlockRepository organizedBlocks) {
        this.mapper = mapper; this.versions = versions; this.organizedDocuments = organizedDocuments; this.organizedBlocks = organizedBlocks;
    }

    @Override public AgentModelClient.AgentToolDefinition definition() {
        ObjectNode schema = mapper.createObjectNode(); schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("documentIds").put("type", "array").put("maxItems", 20).putObject("items").put("type", "string");
        properties.putObject("offset").put("type", "integer").put("minimum", 0);
        properties.putObject("limit").put("type", "integer").put("minimum", 1).put("maximum", 20);
        schema.put("additionalProperties", false);
        return new AgentModelClient.AgentToolDefinition("document_overview", "Read compact, versioned overviews for up to 20 authorized documents before broad comparison or summary.", schema);
    }

    @Override public ToolResult execute(AgentExecutionContext context, JsonNode arguments) {
        List<String> requested = new ArrayList<>();
        if (arguments.path("documentIds").isArray()) arguments.path("documentIds").forEach(value -> requested.add(context.requireDocument(value.asText()).taskId()));
        if (requested.isEmpty()) requested.addAll(context.documents().stream().map(AgentExecutionContext.ScopeDocument::taskId).toList());
        if (requested.size() > 50) throw new IllegalArgumentException("Overview scope must not exceed 50 documents");
        int offset = Math.max(0, arguments.path("offset").asInt(0)); int limit = Math.max(1, Math.min(20, arguments.path("limit").asInt(20)));
        int end = Math.min(requested.size(), offset + limit); List<String> page = offset >= requested.size() ? List.of() : requested.subList(offset, end);
        ArrayNode overviews = mapper.createArrayNode(); ArrayNode missing = mapper.createArrayNode(); ArrayNode unavailable = mapper.createArrayNode();
        for (String taskId : page) {
            AgentExecutionContext.ScopeDocument scope = context.requireDocument(taskId);
            if (scope.indexVersionId() == null) unavailable.add(taskId);
            JsonNode overview = overview(context, scope);
            if (overview == null) { missing.add(taskId); continue; }
            overviews.add(overview);
        }
        context.markOverviewed(page.stream().filter(id -> !contains(missing, id)).toList());
        List<String> covered = page.stream().filter(id -> !contains(missing, id)).toList();
        ObjectNode output = mapper.createObjectNode(); output.set("overviews", overviews); output.set("coveredDocumentIds", mapper.valueToTree(covered));
        output.set("unavailableDocumentIds", unavailable); output.set("missingDocumentIds", missing);
        output.put("totalRequested", requested.size()); output.put("hasMore", end < requested.size()); if (end < requested.size()) output.put("nextOffset", end);
        return ToolResult.value(output, "读取 " + overviews.size() + "/" + page.size() + " 份文档概览");
    }

    private JsonNode overview(AgentExecutionContext context, AgentExecutionContext.ScopeDocument scope) {
        com.voicenote.domain.KnowledgeIndexVersion indexVersion = null;
        if (scope.indexVersionId() != null) {
            indexVersion = versions.findById(scope.indexVersionId()).orElse(null);
            JsonNode stored = indexVersion == null ? null : parse(indexVersion.getOverviewDocument());
            if (stored != null && stored.isObject()) return normalize(context, scope, stored);
        }
        var organized = indexVersion == null
                ? organizedDocuments.findTopByOwnerIdAndTranscriptionTaskIdOrderByUpdatedAtDesc(context.ownerId(), scope.taskId()).orElse(null)
                : organizedDocuments.findById(indexVersion.getOrganizedDocumentId()).orElse(null);
        if (organized != null && (!organized.getOwnerId().equals(context.ownerId()) || organized.getStatus() != OrganizedDocumentStatus.READY)) organized = null;
        if (organized == null) return null;
        ObjectNode raw = mapper.createObjectNode(); raw.put("title", organized.getTitle()); raw.put("summary", Objects.toString(organized.getSummaryText(), ""));
        ArrayNode topics = raw.putArray("topics");
        organizedBlocks.findByOrganizedDocumentIdOrderByBlockIndex(organized.getId()).stream().filter(value -> value.getBlockType() == OrganizedBlockType.TOPIC).forEach(block -> {
            ObjectNode topic = topics.addObject(); topic.put("title", Objects.toString(block.getTopicTitle(), "整理片段"));
            topic.put("content", Objects.toString(block.getSummaryText(), block.getTextContent())); topic.put("startMs", block.getStartMs()); topic.put("endMs", block.getEndMs());
            topic.set("sourceFragments", parse(block.getSourceFragments()));
        });
        return normalize(context, scope, raw);
    }

    private JsonNode normalize(AgentExecutionContext context, AgentExecutionContext.ScopeDocument scope, JsonNode raw) {
        ObjectNode output = mapper.createObjectNode(); output.put("documentId", scope.taskId()); output.put("title", raw.path("title").asText(scope.title()));
        output.put("summary", shorten(raw.path("summary").asText(""), 800)); ArrayNode topics = output.putArray("topics");
        int topicCount = 0;
        for (JsonNode value : raw.path("topics")) {
            if (topicCount++ >= 20) break;
            ObjectNode topic = topics.addObject(); String title = value.path("title").asText("整理片段");
            topic.put("title", title); topic.put("content", shorten(value.path("content").asText(""), 500));
            topic.put("startMs", value.path("startMs").asLong()); topic.put("endMs", value.path("endMs").asLong());
            ArrayNode refs = topic.putArray("sourceRefs"); int count = 0;
            for (JsonNode fragment : value.path("sourceFragments")) {
                if (count++ >= 3) break;
                String segmentId = fragment.path("segmentId").asText(null); if (segmentId == null) continue;
                refs.add(context.evidence().registerTranscript(scope.knowledgeDocumentId(), scope.taskId(), null, segmentId, title,
                        fragment.path("speakerId").asText(null), fragment.path("startMs").asLong(), fragment.path("endMs").asLong(), fragment.path("text").asText("")));
            }
        }
        output.put("topicCount", raw.path("topics").size()); return output;
    }

    private JsonNode parse(String value) {
        if (value == null || value.isBlank()) return mapper.createArrayNode();
        try { return mapper.readTree(value); } catch (Exception exception) { return mapper.createArrayNode(); }
    }
    private static boolean contains(ArrayNode values, String target) { for (JsonNode value : values) if (target.equals(value.asText())) return true; return false; }
    private static String shorten(String value, int max) { return value.length() <= max ? value : value.substring(0, max) + "…"; }
}
