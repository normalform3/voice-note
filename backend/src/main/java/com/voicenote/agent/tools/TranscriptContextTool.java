package com.voicenote.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.voicenote.agent.*;
import com.voicenote.config.AppProperties;
import com.voicenote.domain.QaRetrievalMode;
import com.voicenote.domain.TranscriptSegment;
import com.voicenote.provider.AgentModelClient;
import com.voicenote.repository.TranscriptSegmentRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class TranscriptContextTool implements AgentTool {
    private static final Pattern TOKEN = Pattern.compile("[\\p{IsHan}]|[\\p{L}\\p{N}_]+", Pattern.UNICODE_CHARACTER_CLASS);
    private final ObjectMapper mapper;
    private final TranscriptSegmentRepository segments;
    private final int contextTokenLimit;
    private final int outputByteLimit;

    @Autowired
    public TranscriptContextTool(ObjectMapper mapper, TranscriptSegmentRepository segments, AppProperties properties) {
        this.mapper = mapper; this.segments = segments;
        this.contextTokenLimit = properties.getKnowledge().getRetrievalContextMaxTokens();
        this.outputByteLimit = Math.max(4_096, properties.getAgent().getMaxToolOutputBytes() - 2_048);
    }

    /** Compatibility constructor for focused unit tests. */
    public TranscriptContextTool(ObjectMapper mapper, TranscriptSegmentRepository segments) {
        this(mapper, segments, new AppProperties());
    }

    @Override public AgentModelClient.AgentToolDefinition definition() {
        ObjectNode schema = mapper.createObjectNode(); schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("operation").put("type", "string").putArray("enum").add("SEARCH").add("READ").add("READ_FULL");
        properties.putObject("query").put("type", "string").put("maxLength", 2000);
        properties.putObject("documentIds").put("type", "array").put("maxItems", 3).putObject("items").put("type", "string");
        properties.putObject("sourceRefs").put("type", "array").put("maxItems", 6).putObject("items").put("type", "string");
        schema.putArray("required").add("operation"); schema.put("additionalProperties", false);
        return new AgentModelClient.AgentToolDefinition("transcript_context", "Search transcript segments with local BM25, read adjacent original segments, or read one complete current transcript when it fits the bounded context budget.", schema);
    }

    @Override public ToolResult execute(AgentExecutionContext context, JsonNode arguments) {
        String operation = arguments.path("operation").asText("");
        Selection selection;
        if ("SEARCH".equals(operation)) selection = search(context, arguments);
        else if ("READ".equals(operation)) selection = read(context, arguments);
        else if ("READ_FULL".equals(operation)) selection = readFull(context, arguments);
        else throw new IllegalArgumentException("operation must be SEARCH, READ, or READ_FULL");
        ArrayNode outputSegments = mapper.createArrayNode(); Set<String> covered = new LinkedHashSet<>();
        for (TranscriptSegment segment : selection.segments().values()) {
            AgentExecutionContext.ScopeDocument document = context.requireDocument(segment.getTranscriptionTaskId()); covered.add(document.taskId());
            ObjectNode item = outputSegments.addObject(); item.put("documentId", document.taskId()); item.put("title", document.title());
            item.put("segmentId", segment.getId()); item.put("speakerId", Objects.toString(segment.getEffectiveSpeakerId(), ""));
            item.put("startMs", segment.getStartMs()); item.put("endMs", segment.getEndMs()); item.put("text", segment.getTextContent());
            item.put("sourceRef", context.evidence().registerTranscript(document.knowledgeDocumentId(), document.taskId(), null, segment.getId(), null,
                    segment.getEffectiveSpeakerId(), segment.getStartMs(), segment.getEndMs(), segment.getTextContent()));
        }
        context.markSearched(covered);
        ObjectNode output = mapper.createObjectNode(); output.put("operation", operation); output.set("segments", outputSegments);
        output.set("coveredDocumentIds", mapper.valueToTree(covered)); output.put("contextTokenLimit", contextTokenLimit);
        if (selection.estimatedSourceTokens() != null) output.put("estimatedSourceTokens", selection.estimatedSourceTokens());
        output.put("truncated", selection.truncated()); output.put("fullDocumentRead", "READ_FULL".equals(operation) && !selection.truncated());
        if (selection.truncationReason() != null) output.put("truncationReason", selection.truncationReason());
        output.put("requiresFormalDocument", selection.requiresFormalDocument());
        String label = switch (operation) { case "SEARCH" -> "原文 BM25 检索"; case "READ_FULL" -> "读取完整原文"; default -> "读取相邻原文"; };
        return ToolResult.value(output, label + "返回 " + outputSegments.size() + " 个 Segment");
    }

    private Selection search(AgentExecutionContext context, JsonNode arguments) {
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
        int sourceTokens = corpus.stream().mapToInt(value -> estimateTokens(value.getTextContent())).sum();
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
        LinkedHashMap<String, TranscriptSegment> selected = new LinkedHashMap<>();
        scored.stream().sorted(Comparator.comparingDouble(Scored::score).reversed()).limit(6)
                .forEach(hit -> addWindow(byTask.get(hit.segment().getTranscriptionTaskId()), hit.segment(), selected));
        Selection bounded = bound(context, selected, sourceTokens);
        boolean longRawSource = sourceTokens > contextTokenLimit && taskIds.stream()
                .map(context::requireDocument).anyMatch(value -> value.retrievalMode() == QaRetrievalMode.TRANSCRIPT_LOCAL);
        if (longRawSource) context.addLimitation("当前回答只检查了超长原始文档中的 BM25 命中片段，未覆盖全文；全局总结前请先生成正式文档。");
        return bounded;
    }

    private Selection read(AgentExecutionContext context, JsonNode arguments) {
        JsonNode refs = arguments.path("sourceRefs"); if (!refs.isArray() || refs.isEmpty()) throw new IllegalArgumentException("READ requires sourceRefs");
        LinkedHashMap<String, TranscriptSegment> output = new LinkedHashMap<>();
        for (JsonNode value : refs) {
            AgentEvidenceLedger.EvidenceSource source = context.evidence().require(value.asText());
            if (source.taskId() == null || source.segmentId() == null) throw new IllegalArgumentException("sourceRef does not point to transcript audio");
            AgentExecutionContext.ScopeDocument document = context.requireDocument(source.taskId());
            List<TranscriptSegment> values = segments.findByTranscriptionTaskIdAndTranscriptVersionOrderBySegmentIndex(document.taskId(), document.transcriptVersion());
            values.stream().filter(segment -> segment.getId().equals(source.segmentId())).findFirst().ifPresent(segment -> addWindow(values, segment, output));
        }
        return bound(context, output, null);
    }

    private Selection readFull(AgentExecutionContext context, JsonNode arguments) {
        if (context.scopeType() != com.voicenote.domain.AgentScopeType.CURRENT_DOCUMENT || context.documents().size() != 1) {
            throw new IllegalArgumentException("READ_FULL is only available for a single CURRENT_DOCUMENT scope");
        }
        if (arguments.path("documentIds").isArray() && arguments.path("documentIds").size() > 1) {
            throw new IllegalArgumentException("READ_FULL accepts exactly one document");
        }
        String requested = arguments.path("documentIds").isArray() && !arguments.path("documentIds").isEmpty()
                ? arguments.path("documentIds").path(0).asText() : context.documents().get(0).taskId();
        AgentExecutionContext.ScopeDocument document = context.requireDocument(requested);
        List<TranscriptSegment> values = segments.findByTranscriptionTaskIdAndTranscriptVersionOrderBySegmentIndex(document.taskId(), document.transcriptVersion());
        int tokens = values.stream().mapToInt(value -> estimateTokens(value.getTextContent())).sum();
        int bytes = values.stream().mapToInt(value -> estimatedOutputBytes(value, document.title())).sum();
        if (tokens > contextTokenLimit || bytes > outputByteLimit) {
            String reason = tokens > contextTokenLimit ? "contextTokenLimit" : "toolOutputByteLimit";
            boolean rawOnly = document.retrievalMode() == QaRetrievalMode.TRANSCRIPT_LOCAL;
            context.addLimitation(rawOnly
                    ? "原始文档超过当前上下文预算，未读取全文；请使用定向检索，或先生成正式文档后再进行全局总结。"
                    : "原始文档超过当前上下文预算，未读取全文；请改用正式文档概览或知识索引定位后再回读原文。");
            return new Selection(new LinkedHashMap<>(), tokens, true, reason, rawOnly);
        }
        LinkedHashMap<String, TranscriptSegment> selected = new LinkedHashMap<>();
        values.forEach(value -> selected.put(value.getId(), value));
        return new Selection(selected, tokens, false, null, false);
    }

    private Selection bound(AgentExecutionContext context, LinkedHashMap<String, TranscriptSegment> requested, Integer sourceTokens) {
        LinkedHashMap<String, TranscriptSegment> selected = new LinkedHashMap<>();
        int tokens = 0; int bytes = 0; boolean truncated = false; String reason = null;
        for (TranscriptSegment segment : requested.values()) {
            int nextTokens = estimateTokens(segment.getTextContent());
            int nextBytes = estimatedOutputBytes(segment, context.requireDocument(segment.getTranscriptionTaskId()).title());
            if (tokens + nextTokens > contextTokenLimit || bytes + nextBytes > outputByteLimit) {
                truncated = true; reason = tokens + nextTokens > contextTokenLimit ? "contextTokenLimit" : "toolOutputByteLimit"; break;
            }
            selected.put(segment.getId(), segment); tokens += nextTokens; bytes += nextBytes;
        }
        return new Selection(selected, sourceTokens, truncated, reason, false);
    }

    private static void addWindow(List<TranscriptSegment> values, TranscriptSegment anchor, Map<String, TranscriptSegment> output) {
        int index = values.indexOf(anchor); if (index < 0) return;
        for (int cursor = Math.max(0, index - 1); cursor <= Math.min(values.size() - 1, index + 1); cursor++) output.put(values.get(cursor).getId(), values.get(cursor));
    }
    private static List<String> tokens(String value) {
        List<String> output = new ArrayList<>(); Matcher matcher = TOKEN.matcher(value.toLowerCase(Locale.ROOT));
        while (matcher.find()) output.add(matcher.group()); return output;
    }
    private static int estimateTokens(String value) {
        if (value == null || value.isBlank()) return 0;
        int han = 0; int other = 0;
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset); offset += Character.charCount(codePoint);
            if (Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN) han++;
            else if (!Character.isWhitespace(codePoint)) other++;
        }
        return han + (other + 3) / 4;
    }
    private static int estimatedOutputBytes(TranscriptSegment segment, String title) {
        return Objects.toString(segment.getTextContent(), "").getBytes(StandardCharsets.UTF_8).length
                + Objects.toString(title, "").getBytes(StandardCharsets.UTF_8).length + 384;
    }
    private record Scored(TranscriptSegment segment, double score) { }
    private record Selection(LinkedHashMap<String, TranscriptSegment> segments, Integer estimatedSourceTokens,
                             boolean truncated, String truncationReason, boolean requiresFormalDocument) { }
}
