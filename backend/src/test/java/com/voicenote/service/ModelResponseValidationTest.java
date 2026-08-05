package com.voicenote.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voicenote.config.AppProperties;
import com.voicenote.provider.ProviderException;
import com.voicenote.repository.*;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class ModelResponseValidationTest {
    @Test
    void rejectsANonJsonOrganizationResponseBeforeItCanReachTheJsonColumn() {
        OrganizationInvocationRepository invocations = mock(OrganizationInvocationRepository.class);
        DocumentOrganizationService service = new DocumentOrganizationService(mock(OrganizedDocumentRepository.class), mock(OrganizedDocumentBlockRepository.class),
                mock(TranscriptSegmentRepository.class), mock(TranscriptionTaskRepository.class), invocations, mock(TranscriptSpeakerService.class),
                mock(OutboxService.class), new ObjectMapper());

        assertThatThrownBy(() -> service.completeSemantic("document", "Invalid value."))
                .isInstanceOf(ProviderException.class)
                .hasMessage("Document organization must return a JSON object");

        verifyNoInteractions(invocations);
    }

    @Test
    void rejectsANonJsonAnalysisResponseBeforeItCanReachTheJsonColumn() {
        AnalysisInvocationRepository invocations = mock(AnalysisInvocationRepository.class);
        AnalysisService service = new AnalysisService(mock(AnalysisRunRepository.class), mock(TranscriptionTaskRepository.class),
                mock(TranscriptSegmentRepository.class), mock(OrganizedDocumentRepository.class), mock(OrganizedDocumentBlockRepository.class),
                invocations, mock(AnalysisEvidenceRepository.class), mock(IdempotencyService.class), mock(OutboxService.class), new ObjectMapper(),
                new AppProperties(), mock(ProgressEventPublisher.class));

        assertThatThrownBy(() -> service.completeStage("run", "MAP", 0, "Invalid value."))
                .isInstanceOf(ProviderException.class)
                .hasMessage("Analysis must return a JSON object");

        verifyNoInteractions(invocations);
    }
}
