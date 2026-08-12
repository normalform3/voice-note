package com.voicenote.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.voicenote.agent.*;
import com.voicenote.domain.EvidenceSourceKind;
import com.voicenote.domain.SkillBlockType;
import com.voicenote.provider.AgentModelClient;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class FinalizeAnswerTool implements AgentTool {
    private static final List<String> ITEM_FIELDS = List.of("title", "content", "status", "owner", "dueAt", "question", "answer",
            "dimension", "assessment", "followUp", "label", "values");
    private final ObjectMapper mapper;
    public FinalizeAnswerTool(ObjectMapper mapper) { this.mapper = mapper; }

    /** Legacy schema for frozen v1 Skill snapshots. */
    @Override public AgentModelClient.AgentToolDefinition definition() {
        ObjectNode evidence = evidenceSchema();
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

    @Override public AgentModelClient.AgentToolDefinition definition(AgentExecutionContext context) {
        if (context.skill().outputBlocks().isEmpty()) return definition();
        ObjectNode item = mapper.createObjectNode().put("type", "object").put("additionalProperties", false);
        ObjectNode itemProperties = item.putObject("properties");
        for (String name : ITEM_FIELDS) {
            if ("values".equals(name)) itemProperties.putObject(name).put("type", "array").putObject("items").put("type", "string");
            else if ("owner".equals(name) || "dueAt".equals(name)) itemProperties.putObject(name);
            else itemProperties.putObject(name).put("type", "string");
        }
        itemProperties.set("evidenceRefs", evidenceSchema());

        ObjectNode block = mapper.createObjectNode().put("type", "object").put("additionalProperties", false);
        ObjectNode blockProperties = block.putObject("properties");
        ArrayNode allowed = blockProperties.putObject("type").put("type", "string").putArray("enum");
        context.skill().outputBlocks().forEach(value -> allowed.add(value.name()));
        blockProperties.putObject("title").put("type", "string"); blockProperties.putObject("content").put("type", "string");
        blockProperties.putObject("status").put("type", "string"); blockProperties.set("evidenceRefs", evidenceSchema());
        blockProperties.putObject("items").put("type", "array").put("maxItems", 50).set("items", item);
        blockProperties.putObject("columns").put("type", "array").put("maxItems", 12).putObject("items").put("type", "string");
        blockProperties.putObject("rows").put("type", "array").put("maxItems", 50).set("items", item.deepCopy());
        block.putArray("required").add("type");

        ObjectNode schema = mapper.createObjectNode().put("type", "object").put("additionalProperties", false);
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("resultSchemaVersion").put("type", "integer").putArray("enum").add(2);
        properties.putObject("blocks").put("type", "array").put("minItems", 1).put("maxItems", 16).set("items", block);
        properties.putObject("limitations").put("type", "array").putObject("items").put("type", "string");
        schema.putArray("required").add("resultSchemaVersion").add("blocks");
        return new AgentModelClient.AgentToolDefinition("finalize_answer",
                "Submit resultSchemaVersion 2 using only these block types: " + context.skill().outputBlocks() + ". SUMMARY uses content/evidenceRefs; COMPARISON_TABLE uses columns/rows; other blocks use items. Every factual item must cite sourceRefs from this run. Use null, UNKNOWN or NOT_OBSERVED instead of inventing missing values.", schema);
    }

    @Override public boolean dynamicParameters() { return true; }

    @Override public ToolResult execute(AgentExecutionContext context, JsonNode arguments) {
        return context.skill().outputBlocks().isEmpty() ? executeLegacy(context, arguments) : executeV2(context, arguments);
    }

    private ToolResult executeLegacy(AgentExecutionContext context, JsonNode arguments) {
        if (!arguments.isObject() || !arguments.path("answer").isTextual() || !arguments.path("findings").isArray()) {
            throw new IllegalArgumentException("Final answer requires answer and findings");
        }
        requireOverview(context);
        ObjectNode normalized = mapper.createObjectNode(); normalized.put("answer", arguments.path("answer").asText()); ArrayNode findings = normalized.putArray("findings");
        LinkedHashMap<String, AgentEvidenceLedger.EvidenceSource> cited = new LinkedHashMap<>();
        for (JsonNode rawFinding : arguments.path("findings")) {
            if (!rawFinding.path("title").isTextual() || !rawFinding.path("content").isTextual() || !rawFinding.path("evidenceRefs").isArray()) {
                throw new IllegalArgumentException("Every finding requires title, content, and evidenceRefs");
            }
            ObjectNode finding = findings.addObject(); finding.put("title", rawFinding.path("title").asText()); finding.put("content", rawFinding.path("content").asText());
            attachEvidence(context, rawFinding.path("evidenceRefs"), finding, cited, true);
        }
        addLimitations(context, arguments); finishCoverage(context, normalized, cited);
        return ToolResult.terminal(normalized, "提交包含 " + findings.size() + " 条发现和 " + cited.size() + " 个引用的最终答案");
    }

    private ToolResult executeV2(AgentExecutionContext context, JsonNode arguments) {
        if (!arguments.isObject() || arguments.path("resultSchemaVersion").asInt() != 2 || !arguments.path("blocks").isArray() || arguments.path("blocks").isEmpty()) {
            throw new IllegalArgumentException("Typed final answer requires resultSchemaVersion 2 and non-empty blocks");
        }
        requireOverview(context);
        Set<SkillBlockType> allowed = new LinkedHashSet<>(context.skill().outputBlocks());
        LinkedHashMap<String, AgentEvidenceLedger.EvidenceSource> cited = new LinkedHashMap<>();
        ObjectNode normalized = mapper.createObjectNode().put("resultSchemaVersion", 2); ArrayNode blocks = normalized.putArray("blocks");
        for (JsonNode raw : arguments.path("blocks")) {
            SkillBlockType type;
            try { type = SkillBlockType.valueOf(raw.path("type").asText()); }
            catch (IllegalArgumentException exception) { throw new IllegalArgumentException("Unknown result block type"); }
            if (!allowed.contains(type)) throw new IllegalArgumentException("Result block is outside the selected Skill contract: " + type);
            ObjectNode block = blocks.addObject().put("type", type.name()); copyText(raw, block, "title", "content", "status");
            if (type == SkillBlockType.SUMMARY) {
                if (!raw.path("content").isTextual()) throw new IllegalArgumentException("SUMMARY requires content");
                boolean insufficientEvidence = "INSUFFICIENT_EVIDENCE".equals(raw.path("status").asText());
                attachEvidence(context, raw.path("evidenceRefs"), block, cited, !insufficientEvidence);
            } else if (type == SkillBlockType.COMPARISON_TABLE) {
                if (!raw.path("columns").isArray() || raw.path("columns").size() < 2 || !raw.path("rows").isArray()) {
                    throw new IllegalArgumentException("COMPARISON_TABLE requires at least two columns and rows");
                }
                block.set("columns", raw.path("columns").deepCopy()); ArrayNode rows = block.putArray("rows");
                int expectedValues = raw.path("columns").size() - 1;
                for (JsonNode value : raw.path("rows")) {
                    if (!value.path("values").isArray() || value.path("values").size() != expectedValues) {
                        throw new IllegalArgumentException("Every comparison row must provide one value for each column after its label");
                    }
                    rows.add(normalizeItem(context, value, cited, type));
                }
            } else {
                if (!raw.path("items").isArray()) throw new IllegalArgumentException(type + " requires items");
                ArrayNode items = block.putArray("items"); for (JsonNode value : raw.path("items")) items.add(normalizeItem(context, value, cited, type));
            }
        }
        if (blocks.findValuesAsText("type").stream().noneMatch(SkillBlockType.SUMMARY.name()::equals)) {
            throw new IllegalArgumentException("Typed result must include a SUMMARY block");
        }
        addLimitations(context, arguments); finishCoverage(context, normalized, cited);
        return ToolResult.terminal(normalized, "提交包含 " + blocks.size() + " 个类型化区块和 " + cited.size() + " 个引用的最终答案");
    }

    private ObjectNode normalizeItem(AgentExecutionContext context, JsonNode raw, Map<String, AgentEvidenceLedger.EvidenceSource> cited, SkillBlockType blockType) {
        if (!raw.isObject()) throw new IllegalArgumentException("Result block items must be objects");
        if (blockType == SkillBlockType.DECISIONS && !Set.of("CONFIRMED", "PROPOSED", "UNCLEAR").contains(raw.path("status").asText())) {
            throw new IllegalArgumentException("DECISIONS status must be CONFIRMED, PROPOSED or UNCLEAR");
        }
        if (blockType == SkillBlockType.ASSESSMENT_MATRIX) {
            String assessment = raw.has("assessment") ? raw.path("assessment").asText() : raw.path("status").asText();
            if (!Set.of("STRONG", "MIXED", "WEAK", "UNKNOWN", "NOT_OBSERVED").contains(assessment)) {
                throw new IllegalArgumentException("ASSESSMENT_MATRIX items require a supported assessment status");
            }
        }
        ObjectNode item = mapper.createObjectNode();
        for (String name : ITEM_FIELDS) {
            if (!raw.has(name)) continue;
            if (raw.get(name).isNull()) item.putNull(name); else item.set(name, raw.get(name).deepCopy());
        }
        boolean notObserved = "NOT_OBSERVED".equals(raw.path("status").asText()) || "NOT_OBSERVED".equals(raw.path("assessment").asText());
        attachEvidence(context, raw.path("evidenceRefs"), item, cited, !notObserved);
        return item;
    }

    private void attachEvidence(AgentExecutionContext context, JsonNode refs, ObjectNode target,
                                Map<String, AgentEvidenceLedger.EvidenceSource> cited, boolean factualSourceRequired) {
        if (!refs.isArray() || (factualSourceRequired && refs.isEmpty())) throw new IllegalArgumentException("Every factual result item requires evidenceRefs");
        ArrayNode evidence = target.putArray("evidence"); boolean hasFactualSource = false;
        for (JsonNode refNode : refs) {
            AgentEvidenceLedger.EvidenceSource source = context.evidence().require(refNode.asText()); cited.put(source.ref(), source);
            if (source.kind() == EvidenceSourceKind.TRANSCRIPT_SEGMENT || source.kind() == EvidenceSourceKind.USER_MEMORY) hasFactualSource = true;
            evidence.addObject().put("sourceRef", source.ref()).put("kind", source.kind().name());
        }
        if (factualSourceRequired && !hasFactualSource) throw new IllegalArgumentException("Every factual result item must cite at least one transcript or confirmed-memory sourceRef");
    }
    private void copyText(JsonNode source, ObjectNode target, String... names) { for (String name : names) if (source.path(name).isTextual()) target.put(name, source.path(name).asText()); }
    private void requireOverview(AgentExecutionContext context) { if (context.documents().size() > 1 && context.skill().requireOverviewForMultipleDocuments() && !context.hasOverviewedAllDocuments()) throw new IllegalArgumentException("This multi-document Skill must read every available document overview before finalizing"); }
    private void addLimitations(AgentExecutionContext context, JsonNode arguments) { if (arguments.path("limitations").isArray()) arguments.path("limitations").forEach(value -> context.addLimitation(value.asText())); }
    private void finishCoverage(AgentExecutionContext context, ObjectNode normalized, Map<String, AgentEvidenceLedger.EvidenceSource> cited) {
        if (context.documents().size() > 1 && !context.coverage(cited.values()).omittedDocumentIds().isEmpty()) context.addLimitation("notAllScopeDocumentsReviewed");
        normalized.set("coverage", mapper.valueToTree(context.coverage(cited.values())));
    }
    private ObjectNode evidenceSchema() { ObjectNode evidence = mapper.createObjectNode().put("type", "array").put("maxItems", 20); evidence.putObject("items").put("type", "string"); return evidence; }
}
