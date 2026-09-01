package com.voicenote.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voicenote.agent.tools.TranscriptContextTool;
import com.voicenote.config.AppProperties;
import com.voicenote.domain.AgentScopeType;
import com.voicenote.domain.SpeakerRole;
import com.voicenote.domain.TranscriptSegment;
import com.voicenote.domain.TranscriptSpeaker;
import com.voicenote.repository.TranscriptSegmentRepository;
import com.voicenote.repository.TranscriptSpeakerRepository;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class TranscriptContextToolTest {
    @Test
    void searchesTheCurrentTranscriptWithoutAKnowledgeIndex() throws Exception {
        ObjectMapper mapper = new ObjectMapper(); TranscriptSegmentRepository segments = mock(TranscriptSegmentRepository.class);
        TranscriptSegment irrelevant = new TranscriptSegment("task", 2, 0, "S1", 0, 800, "今天先讨论项目排期");
        TranscriptSegment relevant = new TranscriptSegment("task", 2, 1, "S2", 900, 1_800, "事务隔离级别包括读已提交和可重复读");
        when(segments.findByTranscriptionTaskIdAndTranscriptVersionOrderBySegmentIndex("task", 2)).thenReturn(List.of(irrelevant, relevant));
        AgentSkill skill = new AgentSkill("knowledge-qa", "v1", "问答", "", List.of(), "", List.of("transcript_context"), false);
        AgentExecutionContext context = new AgentExecutionContext("run", "owner", AgentScopeType.CURRENT_DOCUMENT, ZoneId.of("Asia/Shanghai"), skill,
                List.of(new AgentExecutionContext.ScopeDocument("task", null, null, "转写", Instant.now(), "OTHER", null, List.of(), 2, mapper.createObjectNode())), Instant.now().plusSeconds(10));

        var result = new TranscriptContextTool(mapper, segments).execute(context,
                mapper.readTree("{\"operation\":\"SEARCH\",\"query\":\"事务隔离级别\",\"documentIds\":[\"task\"]}"));

        assertThat(result.payload().path("segments").size()).isGreaterThan(0);
        assertThat(result.payload().path("coveredDocumentIds").path(0).asText()).isEqualTo("task");
        assertThat(context.evidence().all()).isNotEmpty();
    }

    @Test
    void returnsResolvedSpeakerNamesAndRolesWithStableFallbacks() throws Exception {
        ObjectMapper mapper = new ObjectMapper(); TranscriptSegmentRepository segments = mock(TranscriptSegmentRepository.class);
        TranscriptSpeakerRepository speakers = mock(TranscriptSpeakerRepository.class);
        TranscriptSegment unnamed = new TranscriptSegment("task", 2, 0, "S1", 0, 800, "项目背景");
        TranscriptSegment named = new TranscriptSegment("task", 2, 1, "S2", 900, 1_800, "候选人说明了事务隔离级别");
        TranscriptSegment suggested = new TranscriptSegment("task", 2, 2, "S3", 1_900, 2_600, "请继续说明实现方案");
        TranscriptSpeaker candidate = new TranscriptSpeaker("task", 2, "S2");
        candidate.confirm(SpeakerRole.CANDIDATE, "李雷");
        TranscriptSpeaker interviewer = new TranscriptSpeaker("task", 2, "S3");
        interviewer.suggest(SpeakerRole.INTERVIEWER, 0.88);
        when(segments.findByTranscriptionTaskIdAndTranscriptVersionOrderBySegmentIndex("task", 2)).thenReturn(List.of(unnamed, named, suggested));
        when(speakers.findByTranscriptionTaskIdAndTranscriptVersionOrderByAsrSpeakerId("task", 2)).thenReturn(List.of(candidate, interviewer));

        var result = new TranscriptContextTool(mapper, segments, speakers, new AppProperties()).execute(context(mapper),
                mapper.readTree("{\"operation\":\"READ_FULL\",\"documentIds\":[\"task\"]}"));

        var returned = result.payload().path("segments");
        assertThat(returned.path(0).path("speakerName").asText()).isEqualTo("S1");
        assertThat(returned.path(0).path("speakerRole").asText()).isEqualTo("UNKNOWN");
        assertThat(returned.path(1).path("speakerName").asText()).isEqualTo("李雷");
        assertThat(returned.path(1).path("speakerRole").asText()).isEqualTo("CANDIDATE");
        assertThat(returned.path(2).path("speakerName").asText()).isEqualTo("S3");
        assertThat(returned.path(2).path("speakerRole").asText()).isEqualTo("INTERVIEWER");
        verify(speakers, times(1)).findByTranscriptionTaskIdAndTranscriptVersionOrderByAsrSpeakerId("task", 2);
    }

    @Test
    void rejectsOutOfScopeDocumentsBeforeReadingSpeakerMetadata() throws Exception {
        ObjectMapper mapper = new ObjectMapper(); TranscriptSegmentRepository segments = mock(TranscriptSegmentRepository.class);
        TranscriptSpeakerRepository speakers = mock(TranscriptSpeakerRepository.class);

        assertThatThrownBy(() -> new TranscriptContextTool(mapper, segments, speakers, new AppProperties())
                .execute(context(mapper), mapper.readTree("{\"operation\":\"SEARCH\",\"query\":\"内容\",\"documentIds\":[\"outside\"]}")))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(speakers);
    }

    @Test
    void readsTheWholeTranscriptOnlyWhenItFitsTheSharedContextBudget() throws Exception {
        ObjectMapper mapper = new ObjectMapper(); TranscriptSegmentRepository segments = mock(TranscriptSegmentRepository.class);
        TranscriptSegment first = new TranscriptSegment("task", 2, 0, "S1", 0, 800, "第一段");
        TranscriptSegment second = new TranscriptSegment("task", 2, 1, "S2", 900, 1_800, "第二段");
        when(segments.findByTranscriptionTaskIdAndTranscriptVersionOrderBySegmentIndex("task", 2)).thenReturn(List.of(first, second));
        AgentExecutionContext context = context(mapper);

        var result = new TranscriptContextTool(mapper, segments).execute(context,
                mapper.readTree("{\"operation\":\"READ_FULL\",\"documentIds\":[\"task\"]}"));

        assertThat(result.payload().path("fullDocumentRead").asBoolean()).isTrue();
        assertThat(result.payload().path("segments").size()).isEqualTo(2);
        assertThat(result.payload().path("requiresFormalDocument").asBoolean()).isFalse();
    }

    @Test
    void refusesAnUnboundedWholeTranscriptAndRecordsTheLimitation() throws Exception {
        ObjectMapper mapper = new ObjectMapper(); TranscriptSegmentRepository segments = mock(TranscriptSegmentRepository.class);
        TranscriptSegment longSegment = new TranscriptSegment("task", 2, 0, "S1", 0, 800, "超长原文内容".repeat(20));
        when(segments.findByTranscriptionTaskIdAndTranscriptVersionOrderBySegmentIndex("task", 2)).thenReturn(List.of(longSegment));
        AppProperties properties = new AppProperties(); properties.getKnowledge().setRetrievalContextMaxTokens(5);
        AgentExecutionContext context = context(mapper);

        var result = new TranscriptContextTool(mapper, segments, properties).execute(context,
                mapper.readTree("{\"operation\":\"READ_FULL\",\"documentIds\":[\"task\"]}"));

        assertThat(result.payload().path("segments")).isEmpty();
        assertThat(result.payload().path("truncationReason").asText()).isEqualTo("contextTokenLimit");
        assertThat(result.payload().path("requiresFormalDocument").asBoolean()).isTrue();
        assertThat(context.checkpointCoverage().limitations()).isNotEmpty();
    }

    private static AgentExecutionContext context(ObjectMapper mapper) {
        AgentSkill skill = new AgentSkill("knowledge-qa", "v1", "问答", "", List.of(), "", List.of("transcript_context"), false);
        return new AgentExecutionContext("run", "owner", AgentScopeType.CURRENT_DOCUMENT, ZoneId.of("Asia/Shanghai"), skill,
                List.of(new AgentExecutionContext.ScopeDocument("task", null, null, "转写", Instant.now(), "OTHER", null, List.of(), 2, mapper.createObjectNode())), Instant.now().plusSeconds(10));
    }
}
