package com.voicenote.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.voicenote.agent.*;
import com.voicenote.provider.AgentModelClient;
import org.springframework.stereotype.Component;
import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.*;

@Component
public class DocumentTool implements AgentTool {
    private final ObjectMapper mapper;
    public DocumentTool(ObjectMapper mapper) { this.mapper = mapper; }

    @Override public AgentModelClient.AgentToolDefinition definition() {
        ObjectNode schema = mapper.createObjectNode(); schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("documentIds").put("type", "array").putObject("items").put("type", "string");
        properties.putObject("relativePeriod").put("type", "string").putArray("enum").add("THIS_WEEK").add("LAST_WEEK");
        properties.putObject("from").put("type", "string"); properties.putObject("to").put("type", "string");
        properties.putObject("sceneType").put("type", "string").putArray("enum").add("INTERVIEW").add("MEETING").add("OTHER");
        properties.putObject("tags").put("type", "array").putObject("items").put("type", "string");
        properties.putObject("offset").put("type", "integer").put("minimum", 0);
        properties.putObject("limit").put("type", "integer").put("minimum", 1).put("maximum", 20);
        schema.put("additionalProperties", false);
        return new AgentModelClient.AgentToolDefinition("document_list", "List and filter only the documents already authorized for this run. Relative periods are resolved by the server.", schema);
    }

    @Override public ToolResult execute(AgentExecutionContext context, JsonNode arguments) {
        Set<String> requested = ids(arguments.path("documentIds"));
        for (String id : requested) context.requireDocument(id);
        Instant from = instant(arguments.path("from")); Instant to = instant(arguments.path("to"));
        String relative = arguments.path("relativePeriod").asText(null);
        if (relative != null) {
            LocalDate today = LocalDate.now(context.timeZone());
            LocalDate monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            if ("LAST_WEEK".equals(relative)) monday = monday.minusWeeks(1);
            from = monday.atStartOfDay(context.timeZone()).toInstant();
            to = monday.plusWeeks(1).atStartOfDay(context.timeZone()).toInstant();
        }
        String scene = arguments.path("sceneType").asText(null); Set<String> tags = ids(arguments.path("tags"));
        List<AgentExecutionContext.ScopeDocument> filtered = new ArrayList<>();
        for (AgentExecutionContext.ScopeDocument document : context.documents()) {
            if (!requested.isEmpty() && !requested.contains(document.taskId())) continue;
            if (from != null && document.occurredAt().isBefore(from)) continue;
            if (to != null && !document.occurredAt().isBefore(to)) continue;
            if (scene != null && !scene.equals(document.sceneType())) continue;
            if (!document.tags().containsAll(tags)) continue;
            filtered.add(document);
        }
        int offset = Math.max(0, arguments.path("offset").asInt(0)); int limit = Math.max(1, Math.min(20, arguments.path("limit").asInt(20)));
        int end = Math.min(filtered.size(), offset + limit); List<AgentExecutionContext.ScopeDocument> page = offset >= filtered.size() ? List.of() : filtered.subList(offset, end);
        ArrayNode documents = mapper.createArrayNode();
        for (AgentExecutionContext.ScopeDocument document : page) {
            String ref = context.evidence().registerMetadata(document.knowledgeDocumentId(), document.taskId(), "documentMetadata",
                    document.title() + " | " + document.occurredAt() + " | " + document.sceneType() + " | " + Objects.toString(document.subject(), ""));
            ObjectNode item = documents.addObject(); item.put("documentId", document.taskId()); item.put("title", document.title());
            item.put("occurredAt", document.occurredAt().toString()); item.put("sceneType", document.sceneType());
            if (document.subject() != null) item.put("subject", document.subject());
            item.set("tags", mapper.valueToTree(document.tags())); item.put("indexed", document.indexVersionId() != null); item.put("sourceRef", ref);
        }
        ObjectNode output = mapper.createObjectNode(); output.set("documents", documents); output.put("total", filtered.size());
        output.put("offset", offset); output.put("hasMore", end < filtered.size()); if (end < filtered.size()) output.put("nextOffset", end);
        return ToolResult.value(output, "列出 " + page.size() + "/" + filtered.size() + " 份授权文档");
    }

    private static Set<String> ids(JsonNode node) {
        if (!node.isArray()) return Set.of(); LinkedHashSet<String> values = new LinkedHashSet<>();
        node.forEach(value -> { if (value.isTextual() && !value.asText().isBlank()) values.add(value.asText()); }); return values;
    }
    private static Instant instant(JsonNode node) {
        if (!node.isTextual() || node.asText().isBlank()) return null;
        try { return Instant.parse(node.asText()); } catch (DateTimeException exception) { throw new IllegalArgumentException("Date filters must use ISO-8601 instants"); }
    }
}
