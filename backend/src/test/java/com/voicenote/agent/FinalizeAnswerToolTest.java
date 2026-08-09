package com.voicenote.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voicenote.agent.tools.FinalizeAnswerTool;
import com.voicenote.domain.AgentScopeType;
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

    private AgentExecutionContext.ScopeDocument document(String taskId) {
        return new AgentExecutionContext.ScopeDocument(taskId, "doc-" + taskId, "index-" + taskId, taskId, Instant.now(),
                "MEETING", taskId, List.of(), 1, mapper.createObjectNode());
    }
}
