package com.voicenote.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.voicenote.agent.*;
import com.voicenote.domain.TranscriptSegment;
import com.voicenote.provider.AgentModelClient;
import com.voicenote.repository.TranscriptSegmentRepository;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class TranscriptContextTool implements AgentTool {
    private static final Pattern TOKEN = Pattern.compile("[\\p{IsHan}]|[\\p{L}\\p{N}_]+", Pattern.UNICODE_CHARACTER_CLASS);
    private final ObjectMapper mapper;
    private final TranscriptSegmentRepository segments;
    public TranscriptContextTool(ObjectMapper mapper, TranscriptSegmentRepository segments) { this.mapper = mapper; this.segments = segments; }

    @Override public AgentModelClient.AgentToolDefinition definition() {
        ObjectNode schema = mapper.createObjectNode(); schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("operation").put("type", "string").putArray("enum").add("SEARCH").add("READ");
        properties.putObject("query").put("type", "string").put("maxLength", 2000);
        properties.putObject("documentIds").put("type", "array").put("maxItems", 3).putObject("items").put("type", "string");
        properties.putObject("sourceRefs").put("type", "array").put("maxItems", 6).putObject("items").put("type", "string");
        schema.putArray("required").add("operation"); schema.put("additionalProperties", false);
        return new AgentModelClient.AgentToolDefinition("transcript_context", "Search transcript segments with local BM25 or read adjacent original segments around prior sourceRefs.", schema);
    }

    @Override public ToolResult execute(AgentExecutionContext context, JsonNode arguments) {
        String operation = arguments.path("operation").asText("");
        LinkedHashMap<String, TranscriptSegment> selected = new LinkedHashMap<>();
        if ("SEARCH".equals(operation)) search(context, arguments, selected);
        else if ("READ".equals(operation)) read(context, arguments, selected);
        else throw new IllegalArgumentException("operation must be SEARCH or READ");
        ArrayNode outputSegments = mapper.createArrayNode(); Set<String> covered = new LinkedHashSet<>();
        for (TranscriptSegment segment : selected.values()) {
            AgentExecutionContext.ScopeDocument document = context.requireDocument(segment.getTranscriptionTaskId()); covered.add(document.taskId());
            ObjectNode item = outputSegments.addObject(); item.put("documentId", document.taskId()); item.put("title", document.title());
            item.put("segmentId", segment.getId()); item.put("speakerId", Objects.toString(segment.getEffectiveSpeakerId(), ""));
            item.put("startMs", segment.getStartMs()); item.put("endMs", segment.getEndMs()); item.put("text", segment.getTextContent());
            item.put("sourceRef", context.evidence().registerTranscript(document.knowledgeDocumentId(), document.taskId(), null, segment.getId(), null,
                    segment.getEffectiveSpeakerId(), segment.getStartMs(), segment.getEndMs(), segment.getTextContent()));
        }
        context.markSearched(covered);
        ObjectNode output = mapper.createObjectNode(); output.set("segments", outputSegments); output.set("coveredDocumentIds", mapper.valueToTree(covered));
        return ToolResult.value(output, ("SEARCH".equals(operation) ? "原文 BM25 检索" : "读取相邻原文") + "返回 " + outputSegments.size() + " 个 Segment");
    }

    private void search(AgentExecutionContext context, JsonNode arguments, Map<String, TranscriptSegment> output) {
        String query = arguments.path("query").asText("").trim(); if (query.isEmpty()) throw new IllegalArgumentException("SEARCH requires query");
        List<String> taskIds = new ArrayList<>();
        if (arguments.path("documentIds").isArray()) arguments.path("documentIds").forEach(value -> taskIds.add(context.requireDocument(value.asText()).taskId()));
        if (taskIds.isEmpty()) taskIds.addAll(context.documents().stream().map(AgentExecutionContext.ScopeDocument::taskId).limit(3).toList());
        if (taskIds.size() > 3) throw new IllegalArgumentException("Transcript search accepts at most 3 documents");
        List<TranscriptSegment> corpus = new ArrayList<>(); Map<String, List<TranscriptSegment>> byTask = new HashMap<>();
        for (String taskId : taskIds) {
            AgentExecutionContext.ScopeDocument document = context.requireDocument(taskId);
            List<TranscriptSegment> values = segments.findByTranscriptionTaskIdAndTranscriptVersionOrderBySegmentIndex(taskId, document.transcriptVersion());
            corpus.addAll(values); byTask.put(taskId, values);
        }
        List<String> queryTerms = tokens(query); Map<String, Integer> documentFrequency = new HashMap<>();
        List<List<String>> tokenized = corpus.stream().map(value -> tokens(value.getTextContent())).toList();
        for (List<String> terms : tokenized) new HashSet<>(terms).forEach(term -> documentFrequency.merge(term, 1, Integer::sum));
        double averageLength = tokenized.stream().mapToInt(List::size).average().orElse(1d); List<Scored> scored = new ArrayList<>();
        for (int index = 0; index < corpus.size(); index++) {
            List<String> terms = tokenized.get(index); Map<String, Long> frequencies = new HashMap<>(); terms.forEach(term -> frequencies.merge(term, 1L, Long::sum));
            double score = 0;
            for (String term : queryTerms) {
                long frequency = frequencies.getOrDefault(term, 0L); if (frequency == 0) continue;
                double idf = Math.log(1 + (corpus.size() - documentFrequency.getOrDefault(term, 0) + 0.5) / (documentFrequency.getOrDefault(term, 0) + 0.5));
                score += idf * frequency * 2.2 / (frequency + 1.2 * (0.25 + 0.75 * terms.size() / Math.max(1d, averageLength)));
            }
            if (score > 0) scored.add(new Scored(corpus.get(index), score));
        }
        scored.stream().sorted(Comparator.comparingDouble(Scored::score).reversed()).limit(6).forEach(hit -> addWindow(byTask.get(hit.segment().getTranscriptionTaskId()), hit.segment(), output));
    }

    private void read(AgentExecutionContext context, JsonNode arguments, Map<String, TranscriptSegment> output) {
        JsonNode refs = arguments.path("sourceRefs"); if (!refs.isArray() || refs.isEmpty()) throw new IllegalArgumentException("READ requires sourceRefs");
        for (JsonNode value : refs) {
            AgentEvidenceLedger.EvidenceSource source = context.evidence().require(value.asText());
            if (source.taskId() == null || source.segmentId() == null) throw new IllegalArgumentException("sourceRef does not point to transcript audio");
            AgentExecutionContext.ScopeDocument document = context.requireDocument(source.taskId());
            List<TranscriptSegment> values = segments.findByTranscriptionTaskIdAndTranscriptVersionOrderBySegmentIndex(document.taskId(), document.transcriptVersion());
            values.stream().filter(segment -> segment.getId().equals(source.segmentId())).findFirst().ifPresent(segment -> addWindow(values, segment, output));
        }
    }

    private static void addWindow(List<TranscriptSegment> values, TranscriptSegment anchor, Map<String, TranscriptSegment> output) {
        int index = values.indexOf(anchor); if (index < 0) return;
        for (int cursor = Math.max(0, index - 1); cursor <= Math.min(values.size() - 1, index + 1); cursor++) output.put(values.get(cursor).getId(), values.get(cursor));
    }
    private static List<String> tokens(String value) {
        List<String> output = new ArrayList<>(); Matcher matcher = TOKEN.matcher(value.toLowerCase(Locale.ROOT));
        while (matcher.find()) output.add(matcher.group()); return output;
    }
    private record Scored(TranscriptSegment segment, double score) { }
}
