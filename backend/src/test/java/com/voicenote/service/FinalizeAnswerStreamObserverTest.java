package com.voicenote.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voicenote.agent.AgentExecutionContext;
import com.voicenote.agent.AgentSkill;
import com.voicenote.agent.tools.FinalizeAnswerTool;
import com.voicenote.domain.AgentScopeType;
import com.voicenote.domain.SceneType;
import com.voicenote.domain.SkillBlockType;
import com.voicenote.domain.SkillInvocationPolicy;
import com.voicenote.domain.SkillSource;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FinalizeAnswerStreamObserverTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void publishesOnlySkillAllowedEvidenceBackedAndBoundedBlocks() {
        AgentSkill skill = new AgentSkill("summary", "v3", "总结", "", List.of(), "", List.of("finalize_answer"), false,
                "version", SkillSource.BUILTIN, SkillInvocationPolicy.AUTO, List.of(SceneType.MEETING), List.of(AgentScopeType.CURRENT_DOCUMENT),
                List.of(SkillBlockType.SUMMARY), List.of(), null, List.of());
        AgentExecutionContext context = new AgentExecutionContext("run", "owner", AgentScopeType.CURRENT_DOCUMENT,
                ZoneId.of("Asia/Shanghai"), skill, List.of(document()), Instant.now().plusSeconds(10));
        String sourceRef = context.evidence().registerTranscript("doc", "task", "chunk", "segment", "主题", "speaker", 0L, 1000L, "原文");
        List<FinalizeAnswerStreamObserver.ValidatedBlock> published = new ArrayList<>();
        String arguments = """
                {"resultSchemaVersion":3,"blocks":[
                  {"type":"SUMMARY","statements":[{"text":"可信摘要","evidenceRefs":["%s"]}]},
                  {"type":"QA_REVIEW","items":[]},
                  {"type":"SUMMARY","statements":[{"text":"伪造引用","evidenceRefs":["src_unknown"]}]}
                ]}
                """.formatted(sourceRef);

        try (FinalizeAnswerStreamObserver observer = new FinalizeAnswerStreamObserver(mapper, new FinalizeAnswerTool(mapper), context, 32_768, published::add)) {
            observer.onToolCallDelta(0, "call", "finalize_", arguments.substring(0, 17));
            observer.onToolCallDelta(0, "", "answer", arguments.substring(17));
        }

        assertThat(published).hasSize(1);
        assertThat(published.get(0).block().path("type").asText()).isEqualTo("SUMMARY");
        assertThat(published.get(0).spokenText()).contains("可信摘要");

        List<FinalizeAnswerStreamObserver.ValidatedBlock> oversized = new ArrayList<>();
        try (FinalizeAnswerStreamObserver observer = new FinalizeAnswerStreamObserver(mapper, new FinalizeAnswerTool(mapper), context, 8, oversized::add)) {
            observer.onToolCallDelta(0, "call", "finalize_answer", arguments);
        }
        assertThat(oversized).isEmpty();
    }

    private AgentExecutionContext.ScopeDocument document() {
        return new AgentExecutionContext.ScopeDocument("task", "doc", "index", "organized", Instant.now(),
                "MEETING", "会议", List.of(), 1, mapper.createObjectNode());
    }
}
