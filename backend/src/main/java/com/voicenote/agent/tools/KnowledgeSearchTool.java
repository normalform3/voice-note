package com.voicenote.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.voicenote.agent.*;
import com.voicenote.config.AppProperties;
import com.voicenote.domain.QaRetrievalMode;
import com.voicenote.provider.AgentModelClient;
import com.voicenote.service.KnowledgeSearchService;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class KnowledgeSearchTool implements AgentTool {
    private final ObjectMapper mapper;
    private final KnowledgeSearchService search;
    private final AppProperties properties;
    public KnowledgeSearchTool(ObjectMapper mapper, KnowledgeSearchService search, AppProperties properties) {
        this.mapper = mapper; this.search = search; this.properties = properties;
    }

    @Override public boolean available(AgentExecutionContext context) {
        return context.documents().stream().anyMatch(value -> value.retrievalMode() == QaRetrievalMode.HYBRID_INDEX
                && value.knowledgeDocumentId() != null && value.indexVersionId() != null);
    }

    @Override public AgentModelClient.AgentToolDefinition definition() {
        ObjectNode schema = mapper.createObjectNode(); schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("query").put("type", "string").put("minLength", 1).put("maxLength", 2000);
        properties.putObject("documentIds").put("type", "array").put("maxItems", 12).putObject("items").put("type", "string");
        properties.putObject("perDocumentLimit").put("type", "integer").put("minimum", 1).put("maximum", 4);
        schema.putArray("required").add("query"); schema.put("additionalProperties", false);
        return new AgentModelClient.AgentToolDefinition("knowledge_search", "Hybrid Dense and BM25 search with document coverage and optional reranking. Target no more than 12 authorized indexed documents per call.", schema);
    }

    @Override public ToolResult execute(AgentExecutionContext context, JsonNode arguments) {
        String query = arguments.path("query").asText("").trim();
        if (query.isEmpty() || query.length() > 2000) throw new IllegalArgumentException("query must contain 1 to 2000 characters");
        List<String> ids = new ArrayList<>();
        if (arguments.path("documentIds").isArray()) arguments.path("documentIds").forEach(value -> ids.add(context.requireDocument(value.asText()).taskId()));
        if (ids.isEmpty()) ids.addAll(context.documents().stream().map(AgentExecutionContext.ScopeDocument::taskId).toList());
        if (ids.size() > 12) throw new IllegalArgumentException("knowledge_search accepts at most 12 documents; read overviews and choose a smaller subset");
        List<KnowledgeSearchService.ScopedDocument> indexed = new ArrayList<>();
        for (String id : ids) {
            AgentExecutionContext.ScopeDocument document = context.requireDocument(id);
            if (document.knowledgeDocumentId() != null && document.indexVersionId() != null) {
                indexed.add(new KnowledgeSearchService.ScopedDocument(id, document.knowledgeDocumentId(), document.indexVersionId()));
            }
        }
        var result = search.searchScoped(context.ownerId(), indexed, query, arguments.path("perDocumentLimit").asInt(2));
        if (result.limitation() != null) context.addLimitation(result.limitation());
        ArrayNode chunks = mapper.createArrayNode();
        boolean byteTruncated = false;
        for (KnowledgeSearchService.ReadableChunk chunk : result.chunks()) {
            ObjectNode item = mapper.createObjectNode(); item.put("documentId", chunk.transcriptionTaskId()); item.put("documentTitle", chunk.documentTitle());
            item.put("topic", Objects.toString(chunk.topicTitle(), "整理片段")); item.put("profile", chunk.profile().name());
            item.put("chunkKind", chunk.chunkKind().name()); item.put("tokenCount", Objects.requireNonNullElse(chunk.tokenCount(), 0));
            item.put("oversized", chunk.oversized()); item.put("content", chunk.content());
            item.put("startMs", chunk.startMs()); item.put("endMs", chunk.endMs()); ArrayNode sources = item.putArray("sources");
            if (!chunk.sourceFragments().isEmpty()) {
                for (KnowledgeSearchService.SourceFragment fragment : chunk.sourceFragments()) {
                    ObjectNode source = sources.addObject();
                    source.put("sourceRef", context.evidence().registerTranscript(chunk.documentId(), chunk.transcriptionTaskId(), chunk.chunkId(), fragment.segmentId(),
                            chunk.topicTitle(), fragment.speakerId(), fragment.startMs(), fragment.endMs(), fragment.text()));
                    source.put("segmentId", fragment.segmentId()); source.put("speakerId", Objects.toString(fragment.speakerId(), ""));
                    source.put("startMs", fragment.startMs()); source.put("endMs", fragment.endMs()); source.put("text", fragment.text());
                    source.put("context", chunk.contextSegmentIds().contains(fragment.segmentId()));
                }
            } else {
                for (String segmentId : chunk.segmentIds()) {
                    ObjectNode source = sources.addObject(); source.put("segmentId", segmentId);
                    source.put("sourceRef", context.evidence().registerTranscript(chunk.documentId(), chunk.transcriptionTaskId(), chunk.chunkId(), segmentId,
                            chunk.topicTitle(), null, chunk.startMs(), chunk.endMs(), chunk.content()));
                    source.put("context", chunk.contextSegmentIds().contains(segmentId));
                }
            }
            chunks.add(item);
            if (serializedBytes(previewOutput(chunks, ids, result, true, "toolOutputByteLimit")) > properties.getAgent().getMaxToolOutputBytes()) {
                chunks.remove(chunks.size() - 1); byteTruncated = true;
            }
        }
        List<String> covered = new ArrayList<>();
        chunks.forEach(value -> { String id = value.path("documentId").asText(); if (!covered.contains(id)) covered.add(id); });
        context.markSearched(covered);
        String truncationReason = byteTruncated ? "toolOutputByteLimit" : result.truncationReason();
        ObjectNode output = previewOutput(chunks, ids, result, byteTruncated || result.truncationReason() != null, truncationReason);
        return ToolResult.value(output, "检索覆盖 " + covered.size() + "/" + ids.size() + " 份文档，返回 " + chunks.size() + " 个 Chunk");
    }

    private ObjectNode previewOutput(ArrayNode chunks, List<String> requestedIds, KnowledgeSearchService.ScopedSearchResult result,
                                     boolean truncated, String truncationReason) {
        LinkedHashSet<String> covered = new LinkedHashSet<>();
        chunks.forEach(value -> covered.add(value.path("documentId").asText()));
        LinkedHashSet<String> uncovered = new LinkedHashSet<>(requestedIds); uncovered.removeAll(covered);
        ObjectNode output = mapper.createObjectNode(); output.set("chunks", chunks); output.set("coveredDocumentIds", mapper.valueToTree(covered));
        output.set("uncoveredDocumentIds", mapper.valueToTree(uncovered)); output.put("rerankFallback", result.rerankFallback());
        output.put("truncated", truncated); if (truncationReason != null) output.put("truncationReason", truncationReason);
        return output;
    }

    private int serializedBytes(JsonNode value) {
        try { return mapper.writeValueAsBytes(value).length; }
        catch (Exception exception) { throw new IllegalStateException("Cannot measure knowledge_search output", exception); }
    }
}
