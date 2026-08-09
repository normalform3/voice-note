package com.voicenote.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voicenote.agent.tools.FinalizeAnswerTool;
import com.voicenote.domain.AgentScopeType;
import com.voicenote.domain.SkillBlockType;
import com.voicenote.domain.SkillInvocationPolicy;
import com.voicenote.domain.SkillSource;
import com.voicenote.domain.SceneType;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FinalizeAnswerToolTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final FinalizeAnswerTool tool = new FinalizeAnswerTool(mapper);

    @Test
    void requiresOverviewCoverageAndRejectsForgedSourceRefs() throws Exception {
        AgentSkill skill = new AgentSkill("meeting-summary", "v1", "会议总结", "", List.of(), "", List.of("finalize_answer"), true);
        AgentExecutionContext context = new AgentExecutionContext("run", "owner", AgentScopeType.SELECTED_DOCUMENTS,
                ZoneId.of("Asia/Shanghai"), skill, List.of(document("task-a"), document("task-b")), Instant.now().plusSeconds(10));
        String sourceRef = context.evidence().registerTranscript("doc-a", "task-a", "chunk-a", "segment-a", "主题", "speaker", 0L, 1_000L, "原文");
        var arguments = mapper.readTree("""
                {"answer":"已确认一项决策。","findings":[{"title":"决策","content":"采用方案 A","evidenceRefs":["%s"]}]}
                """.formatted(sourceRef));

        assertThatThrownBy(() -> tool.execute(context, arguments)).hasMessageContaining("every available document overview");
        context.markOverviewed(List.of("task-a", "task-b"));
        var result = tool.execute(context, arguments);
        assertThat(result.terminal()).isTrue();
        assertThat(result.payload().path("coverage").path("scopeDocumentCount").asInt()).isEqualTo(2);

        var forged = mapper.readTree("""
                {"answer":"x","findings":[{"title":"x","content":"x","evidenceRefs":["src_forged"]}]}
                """);
        assertThatThrownBy(() -> tool.execute(context, forged)).hasMessageContaining("Unknown sourceRef");
    }

    @Test
    void metadataAloneCannotSupportAContentFinding() throws Exception {
        AgentSkill skill = new AgentSkill("knowledge-qa", "v1", "问答", "", List.of(), "", List.of("finalize_answer"), false);
        AgentExecutionContext context = new AgentExecutionContext("run", "owner", AgentScopeType.CURRENT_DOCUMENT,
                ZoneId.of("Asia/Shanghai"), skill, List.of(document("task-a")), Instant.now().plusSeconds(10));
        String sourceRef = context.evidence().registerMetadata("doc-a", "task-a", "metadata", "会议");
        var arguments = mapper.readTree("""
                {"answer":"x","findings":[{"title":"x","content":"x","evidenceRefs":["%s"]}]}
                """.formatted(sourceRef));

        assertThatThrownBy(() -> tool.execute(context, arguments)).hasMessageContaining("transcript sourceRef");
    }

    @Test
    void validatesAndNormalizesTypedSkillBlocks() throws Exception {
        AgentSkill skill = new AgentSkill("meeting-summary", "v2", "会议总结", "", List.of(), "", List.of("finalize_answer"), false,
                "version-id", SkillSource.BUILTIN, SkillInvocationPolicy.AUTO, List.of(SceneType.MEETING), List.of(AgentScopeType.CURRENT_DOCUMENT),
                List.of(SkillBlockType.SUMMARY, SkillBlockType.ACTION_ITEMS), List.of(), null, List.of());
        AgentExecutionContext context = new AgentExecutionContext("run", "owner", AgentScopeType.CURRENT_DOCUMENT,
                ZoneId.of("Asia/Shanghai"), skill, List.of(document("task-a")), Instant.now().plusSeconds(10));
        String sourceRef = context.evidence().registerTranscript("doc-a", "task-a", "chunk-a", "segment-a", "行动", "speaker", 0L, 1_000L, "周五完成");
        var arguments = mapper.readTree("""
                {"resultSchemaVersion":2,"blocks":[
                  {"type":"SUMMARY","content":"会议确认了一项行动。","evidenceRefs":["%s"]},
                  {"type":"ACTION_ITEMS","items":[{"title":"完成方案","content":"周五完成","owner":null,"dueAt":"周五","status":"CONFIRMED","evidenceRefs":["%s"]}]}
                ]}
                """.formatted(sourceRef, sourceRef));

        var result = tool.execute(context, arguments);

        assertThat(result.payload().path("resultSchemaVersion").asInt()).isEqualTo(2);
        assertThat(result.payload().path("blocks").get(1).path("items").get(0).path("owner").isNull()).isTrue();
        assertThat(result.payload().path("blocks").get(1).path("items").get(0).path("evidence").get(0).path("sourceRef").asText()).isEqualTo(sourceRef);
        assertThat(tool.definition(context).parameters().path("properties").path("blocks").path("items").path("properties")
                .path("type").path("enum").toString()).contains("SUMMARY", "ACTION_ITEMS").doesNotContain("QA_REVIEW");
    }

    @Test
    void rejectsTypedBlocksOutsideTheSkillContract() throws Exception {
        AgentSkill skill = new AgentSkill("meeting-summary", "v2", "会议总结", "", List.of(), "", List.of("finalize_answer"), false,
                "version-id", SkillSource.BUILTIN, SkillInvocationPolicy.AUTO, List.of(SceneType.MEETING), List.of(AgentScopeType.CURRENT_DOCUMENT),
                List.of(SkillBlockType.SUMMARY), List.of(), null, List.of());
        AgentExecutionContext context = new AgentExecutionContext("run", "owner", AgentScopeType.CURRENT_DOCUMENT,
                ZoneId.of("Asia/Shanghai"), skill, List.of(document("task-a")), Instant.now().plusSeconds(10));
        var arguments = mapper.readTree("{\"resultSchemaVersion\":2,\"blocks\":[{\"type\":\"QA_REVIEW\",\"items\":[]}]}");

        assertThatThrownBy(() -> tool.execute(context, arguments)).hasMessageContaining("outside the selected Skill contract");
    }

    private AgentExecutionContext.ScopeDocument document(String taskId) {
        return new AgentExecutionContext.ScopeDocument(taskId, "doc-" + taskId, "index-" + taskId, taskId, Instant.now(),
                "MEETING", taskId, List.of(), 1, mapper.createObjectNode());
    }
}
