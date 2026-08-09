package com.voicenote.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voicenote.agent.tools.TranscriptContextTool;
import com.voicenote.domain.AgentScopeType;
import com.voicenote.domain.TranscriptSegment;
import com.voicenote.repository.TranscriptSegmentRepository;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
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
}
