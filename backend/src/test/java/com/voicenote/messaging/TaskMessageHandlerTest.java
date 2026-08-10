package com.voicenote.messaging;

import com.voicenote.domain.EventType;
import com.voicenote.domain.OutboxEvent;
import com.voicenote.repository.OutboxEventRepository;
import com.voicenote.service.AnalysisService;
import com.voicenote.service.DocumentOrganizationService;
import com.voicenote.service.KnowledgeAgentService;
import com.voicenote.service.KnowledgeDocumentService;
import com.voicenote.service.KnowledgeIndexWorker;
import com.voicenote.service.OrganizedDocumentWorker;
import com.voicenote.service.TranscriptionTaskService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TaskMessageHandlerTest {
    @Test
    void createsTheFirstAsrAttemptWhenTheBrokerDeliversATranscriptionRequest() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        OutboxEventRepository outbox = mock(OutboxEventRepository.class);
        TranscriptionTaskService transcriptionTasks = mock(TranscriptionTaskService.class);
        OutboxEvent event = new OutboxEvent("transcription_task", "task-id", EventType.TRANSCRIPTION_REQUESTED, "{}", null);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        when(outbox.findById(event.getId())).thenReturn(Optional.of(event));
        TaskMessageHandler handler = new TaskMessageHandler(jdbc, outbox, transcriptionTasks, mock(AnalysisService.class),
                mock(KnowledgeDocumentService.class), mock(DocumentOrganizationService.class), mock(OrganizedDocumentWorker.class),
                mock(KnowledgeIndexWorker.class), mock(KnowledgeAgentService.class), mock(ProgressMessageHandler.class),
                mock(com.voicenote.service.SpeakerCorrectionService.class), mock(com.voicenote.service.SpeakerCorrectionWorker.class));

        handler.consume("rocketmq-transcription", event.getId());

        verify(transcriptionTasks).ensureFirstAttempt("task-id");
    }
}
