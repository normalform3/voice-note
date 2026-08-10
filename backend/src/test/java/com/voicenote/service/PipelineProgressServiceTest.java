package com.voicenote.service;

import com.voicenote.config.AppProperties;
import com.voicenote.domain.PipelineStage;
import com.voicenote.domain.SceneType;
import com.voicenote.domain.StageAttemptStatus;
import com.voicenote.domain.TaskStageAttempt;
import com.voicenote.domain.TaskStatus;
import com.voicenote.domain.TranscriptionTask;
import com.voicenote.domain.EventType;
import com.voicenote.domain.OutboxEvent;
import com.voicenote.domain.OrganizedDocument;
import com.voicenote.domain.QaRetrievalMode;
import com.voicenote.repository.KnowledgeDocumentRepository;
import com.voicenote.repository.OrganizedDocumentRepository;
import com.voicenote.repository.TaskStageAttemptRepository;
import com.voicenote.repository.TranscriptionTaskRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PipelineProgressServiceTest {
    @Test
    void exposesMetadataTagsAsAnArrayInsteadOfRawJson() {
        TranscriptionTaskRepository tasks = mock(TranscriptionTaskRepository.class);
        TaskStageAttemptRepository stages = mock(TaskStageAttemptRepository.class);
        KnowledgeDocumentRepository documents = mock(KnowledgeDocumentRepository.class);
        OrganizedDocumentRepository organizedDocuments = mock(OrganizedDocumentRepository.class);
        TranscriptionTask task = new TranscriptionTask("owner", "audio", "a".repeat(64), "pipeline");
        task.updateMetadata(Instant.now(), SceneType.INTERVIEW, "candidate", "[\"Java\",\"backend\"]");
        when(tasks.findById(task.getId())).thenReturn(Optional.of(task));
        when(stages.findByTranscriptionTaskIdOrderByQueuedAtAsc(task.getId())).thenReturn(List.of());
        when(tasks.findDurationMs(task.getId(), task.getTranscriptVersion())).thenReturn(125_000L);
        when(documents.findByOwnerIdAndTranscriptionTaskIdAndTranscriptVersion("owner", task.getId(), task.getTranscriptVersion())).thenReturn(Optional.empty());
        when(organizedDocuments.findByOwnerIdAndTranscriptionTaskIdAndTranscriptVersion("owner", task.getId(), task.getTranscriptVersion())).thenReturn(Optional.empty());
        PipelineProgressService service = new PipelineProgressService(tasks, stages, documents, organizedDocuments,
                mock(ProgressEventPublisher.class), mock(OutboxService.class), new AppProperties());

        PipelineProgressService.TaskProgressView view = service.ownedView("owner", task.getId());

        assertThat(view.tags()).containsExactly("Java", "backend");
        assertThat(view.createdAt()).isEqualTo(task.getCreatedAt());
        assertThat(view.durationMs()).isEqualTo(125_000L);
        assertThat(view.qaCapabilities().currentDocumentAvailable()).isFalse();
    }

    @Test
    void exposesFormalDocumentQuestionAnsweringWithoutCrossDocumentSearch() {
        TranscriptionTaskRepository tasks = mock(TranscriptionTaskRepository.class); TaskStageAttemptRepository stages = mock(TaskStageAttemptRepository.class);
        KnowledgeDocumentRepository documents = mock(KnowledgeDocumentRepository.class); OrganizedDocumentRepository organizedDocuments = mock(OrganizedDocumentRepository.class);
        TranscriptionTask task = new TranscriptionTask("owner", "audio", "a".repeat(64), "pipeline"); task.transcriptPersisted();
        OrganizedDocument organized = new OrganizedDocument("owner", task.getId(), task.getTranscriptVersion(), "正式文档"); organized.ready("{}", "正文");
        when(tasks.findById(task.getId())).thenReturn(Optional.of(task));
        when(stages.findByTranscriptionTaskIdOrderByQueuedAtAsc(task.getId())).thenReturn(List.of());
        when(documents.findByOwnerIdAndTranscriptionTaskIdAndTranscriptVersion("owner", task.getId(), task.getTranscriptVersion())).thenReturn(Optional.empty());
        when(organizedDocuments.findByOwnerIdAndTranscriptionTaskIdAndTranscriptVersion("owner", task.getId(), task.getTranscriptVersion())).thenReturn(Optional.of(organized));
        PipelineProgressService service = new PipelineProgressService(tasks, stages, documents, organizedDocuments,
                mock(ProgressEventPublisher.class), mock(OutboxService.class), new AppProperties());

        PipelineProgressService.TaskProgressView view = service.ownedView("owner", task.getId());

        assertThat(view.qaCapabilities().currentDocumentAvailable()).isTrue();
        assertThat(view.qaCapabilities().currentMode()).isEqualTo(QaRetrievalMode.FORMAL_OVERVIEW);
        assertThat(view.qaCapabilities().crossDocumentEligible()).isFalse();
    }

    @Test
    void marksTranscriptionAsFailedAndPreventsFurtherWorkWhenDeliveryFails() {
        TranscriptionTaskRepository tasks = mock(TranscriptionTaskRepository.class);
        TaskStageAttemptRepository stages = mock(TaskStageAttemptRepository.class);
        ProgressEventPublisher progress = mock(ProgressEventPublisher.class);
        TranscriptionTask task = new TranscriptionTask("owner", "audio", "a".repeat(64), "pipeline");
        TaskStageAttempt submission = new TaskStageAttempt(task.getId(), PipelineStage.ASR_SUBMIT, 1);
        when(tasks.findById(task.getId())).thenReturn(Optional.of(task));
        when(stages.findTopByTranscriptionTaskIdAndStageOrderByAttemptNumberDesc(task.getId(), PipelineStage.ASR_SUBMIT))
                .thenReturn(Optional.of(submission));
        PipelineProgressService service = new PipelineProgressService(tasks, stages, mock(KnowledgeDocumentRepository.class),
                mock(OrganizedDocumentRepository.class), progress, mock(OutboxService.class), new AppProperties());

        service.failDelivery(new OutboxEvent("transcription_task", task.getId(), EventType.TRANSCRIPTION_REQUESTED, "{}", null),
                new IllegalStateException("send timeout"));

        assertThat(task.getStatus()).isEqualTo(TaskStatus.FAILED);
        assertThat(task.getFailedStage()).isEqualTo(PipelineStage.ASR_SUBMIT);
        assertThat(submission.getStatus()).isEqualTo(StageAttemptStatus.FAILED);
        assertThat(service.begin(task.getId(), PipelineStage.ASR_SUBMIT)).isFalse();
        verify(progress).publish(any(ProgressEventPublisher.ProgressNotification.class));
    }

    @Test
    void createsANewAsrStageWhenACancelledTaskIsSubmittedAgain() {
        TranscriptionTaskRepository tasks = mock(TranscriptionTaskRepository.class);
        TaskStageAttemptRepository stages = mock(TaskStageAttemptRepository.class);
        TranscriptionTask task = new TranscriptionTask("owner", "audio", "a".repeat(64), "pipeline");
        task.cancel();
        TaskStageAttempt cancelledSubmission = new TaskStageAttempt(task.getId(), PipelineStage.ASR_SUBMIT, 1);
        cancelledSubmission.cancel();
        when(tasks.findById(task.getId())).thenReturn(Optional.of(task));
        when(stages.findTopByTranscriptionTaskIdAndStageOrderByAttemptNumberDesc(task.getId(), PipelineStage.ASR_SUBMIT))
                .thenReturn(Optional.of(cancelledSubmission));
        when(stages.save(any(TaskStageAttempt.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(tasks.save(any(TranscriptionTask.class))).thenAnswer(invocation -> invocation.getArgument(0));
        PipelineProgressService service = new PipelineProgressService(tasks, stages, mock(KnowledgeDocumentRepository.class),
                mock(OrganizedDocumentRepository.class), mock(ProgressEventPublisher.class), mock(OutboxService.class), new AppProperties());

        service.restartCancelledTask("owner", task.getId());

        ArgumentCaptor<TaskStageAttempt> created = ArgumentCaptor.forClass(TaskStageAttempt.class);
        verify(stages).save(created.capture());
        assertThat(created.getValue().getStage()).isEqualTo(PipelineStage.ASR_SUBMIT);
        assertThat(created.getValue().getAttemptNumber()).isEqualTo(2);
        assertThat(created.getValue().getStatus()).isEqualTo(StageAttemptStatus.QUEUED);
        assertThat(task.getStatus()).isEqualTo(TaskStatus.QUEUED);
        assertThat(task.isCancelled()).isFalse();
    }

    @Test
    void publishesProgressWhenTheAsrSubmissionStarts() {
        TranscriptionTaskRepository tasks = mock(TranscriptionTaskRepository.class);
        TaskStageAttemptRepository stages = mock(TaskStageAttemptRepository.class);
        ProgressEventPublisher progress = mock(ProgressEventPublisher.class);
        TranscriptionTask task = new TranscriptionTask("owner", "audio", "a".repeat(64), "pipeline");
        TaskStageAttempt submission = new TaskStageAttempt(task.getId(), PipelineStage.ASR_SUBMIT, 1);
        when(tasks.findById(task.getId())).thenReturn(Optional.of(task));
        when(stages.findTopByTranscriptionTaskIdAndStageOrderByAttemptNumberDesc(task.getId(), PipelineStage.ASR_SUBMIT))
                .thenReturn(Optional.of(submission));
        PipelineProgressService service = new PipelineProgressService(tasks, stages, mock(KnowledgeDocumentRepository.class),
                mock(OrganizedDocumentRepository.class), progress, mock(OutboxService.class), new AppProperties());

        assertThat(service.begin(task.getId(), PipelineStage.ASR_SUBMIT)).isTrue();

        assertThat(submission.getStatus()).isEqualTo(StageAttemptStatus.RUNNING);
        verify(progress).publish(new ProgressEventPublisher.ProgressNotification("owner", "task-stage-settled", task.getId()));
    }

    @Test
    void recordsTheModelOnlyWhenAWorkerIsAboutToCallIt() {
        TranscriptionTaskRepository tasks = mock(TranscriptionTaskRepository.class);
        TaskStageAttemptRepository stages = mock(TaskStageAttemptRepository.class);
        ProgressEventPublisher progress = mock(ProgressEventPublisher.class);
        TranscriptionTask task = new TranscriptionTask("owner", "audio", "a".repeat(64), "pipeline");
        TaskStageAttempt organization = new TaskStageAttempt(task.getId(), PipelineStage.DOCUMENT_ORGANIZATION, 1);
        when(tasks.findById(task.getId())).thenReturn(Optional.of(task));
        when(stages.findTopByTranscriptionTaskIdAndStageOrderByAttemptNumberDesc(task.getId(), PipelineStage.DOCUMENT_ORGANIZATION))
                .thenReturn(Optional.of(organization));
        PipelineProgressService service = new PipelineProgressService(tasks, stages, mock(KnowledgeDocumentRepository.class),
                mock(OrganizedDocumentRepository.class), progress, mock(OutboxService.class), new AppProperties());

        service.recordModelInvocation(task.getId(), PipelineStage.DOCUMENT_ORGANIZATION, "qwen-plus");

        assertThat(organization.getModelId()).isEqualTo("qwen-plus");
        verify(stages).save(organization);
        verify(progress).publish(new ProgressEventPublisher.ProgressNotification("owner", "task-stage-settled", task.getId()));
    }

    @Test
    void recordsTheFailureEvenWhenTheWorkerCouldNotCreateItsStage() {
        TranscriptionTaskRepository tasks = mock(TranscriptionTaskRepository.class);
        TaskStageAttemptRepository stages = mock(TaskStageAttemptRepository.class);
        TranscriptionTask task = new TranscriptionTask("owner", "audio", "a".repeat(64), "pipeline");
        when(tasks.findById(task.getId())).thenReturn(Optional.of(task));
        when(stages.findTopByTranscriptionTaskIdAndStageOrderByAttemptNumberDesc(task.getId(), PipelineStage.DOCUMENT_ORGANIZATION))
                .thenReturn(Optional.empty());
        when(stages.save(any(TaskStageAttempt.class))).thenAnswer(invocation -> invocation.getArgument(0));
        PipelineProgressService service = new PipelineProgressService(tasks, stages, mock(KnowledgeDocumentRepository.class),
                mock(OrganizedDocumentRepository.class), mock(ProgressEventPublisher.class), mock(OutboxService.class), new AppProperties());

        service.failed(task.getId(), PipelineStage.DOCUMENT_ORGANIZATION, "FAILED", "worker crashed", false);

        assertThat(task.getStatus()).isEqualTo(TaskStatus.FAILED);
        assertThat(task.getFailedStage()).isEqualTo(PipelineStage.DOCUMENT_ORGANIZATION);
        ArgumentCaptor<TaskStageAttempt> saved = ArgumentCaptor.forClass(TaskStageAttempt.class);
        verify(stages, org.mockito.Mockito.atLeastOnce()).save(saved.capture());
        assertThat(saved.getAllValues().get(0).getStatus()).isEqualTo(StageAttemptStatus.FAILED);
    }

    @Test
    void startsFormalDocumentQueueTimingWhenTheUserRequestsGeneration() {
        TranscriptionTaskRepository tasks = mock(TranscriptionTaskRepository.class);
        TaskStageAttemptRepository stages = mock(TaskStageAttemptRepository.class);
        TranscriptionTask task = new TranscriptionTask("owner", "audio", "a".repeat(64), "pipeline");
        when(tasks.findById(task.getId())).thenReturn(Optional.of(task));
        when(stages.findTopByTranscriptionTaskIdAndStageOrderByAttemptNumberDesc(task.getId(), PipelineStage.DOCUMENT_ORGANIZATION))
                .thenReturn(Optional.empty());
        when(stages.save(any(TaskStageAttempt.class))).thenAnswer(invocation -> invocation.getArgument(0));
        PipelineProgressService service = new PipelineProgressService(tasks, stages, mock(KnowledgeDocumentRepository.class),
                mock(OrganizedDocumentRepository.class), mock(ProgressEventPublisher.class), mock(OutboxService.class), new AppProperties());

        assertThat(service.queue(task.getId(), PipelineStage.DOCUMENT_ORGANIZATION)).isTrue();

        assertThat(task.getStatus()).isEqualTo(TaskStatus.RUNNING);
        assertThat(task.getCurrentStage()).isEqualTo(PipelineStage.DOCUMENT_ORGANIZATION);
        ArgumentCaptor<TaskStageAttempt> saved = ArgumentCaptor.forClass(TaskStageAttempt.class);
        verify(stages).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(StageAttemptStatus.QUEUED);
    }

    @Test
    void createsANewFormalDocumentAttemptAfterSpeakerCorrection() {
        TranscriptionTaskRepository tasks = mock(TranscriptionTaskRepository.class);
        TaskStageAttemptRepository stages = mock(TaskStageAttemptRepository.class);
        TranscriptionTask task = new TranscriptionTask("owner", "audio", "a".repeat(64), "pipeline");
        task.awaitFormalDocument();
        TaskStageAttempt completed = new TaskStageAttempt(task.getId(), PipelineStage.DOCUMENT_ORGANIZATION, 1);
        completed.start(); completed.succeed("{}");
        when(tasks.findById(task.getId())).thenReturn(Optional.of(task));
        when(stages.findTopByTranscriptionTaskIdAndStageOrderByAttemptNumberDesc(task.getId(), PipelineStage.DOCUMENT_ORGANIZATION))
                .thenReturn(Optional.of(completed));
        when(stages.save(any(TaskStageAttempt.class))).thenAnswer(invocation -> invocation.getArgument(0));
        PipelineProgressService service = new PipelineProgressService(tasks, stages, mock(KnowledgeDocumentRepository.class),
                mock(OrganizedDocumentRepository.class), mock(ProgressEventPublisher.class), mock(OutboxService.class), new AppProperties());

        assertThat(service.queue(task.getId(), PipelineStage.DOCUMENT_ORGANIZATION)).isTrue();

        ArgumentCaptor<TaskStageAttempt> created = ArgumentCaptor.forClass(TaskStageAttempt.class);
        verify(stages).save(created.capture());
        assertThat(created.getValue().getAttemptNumber()).isEqualTo(2);
        assertThat(created.getValue().getStatus()).isEqualTo(StageAttemptStatus.QUEUED);
    }

    @Test
    void opensANewKnowledgeStageAttemptWhenAFailedKnowledgeBuildIsRetried() {
        TranscriptionTaskRepository tasks = mock(TranscriptionTaskRepository.class);
        TaskStageAttemptRepository stages = mock(TaskStageAttemptRepository.class);
        TranscriptionTask task = new TranscriptionTask("owner", "audio", "a".repeat(64), "pipeline");
        task.advance(PipelineStage.KNOWLEDGE_INDEX, 90);
        task.fail(TaskStatus.FAILED, "KNOWLEDGE_CHUNKS_EMPTY", "Formal document produced no semantic chunks");
        TaskStageAttempt failed = new TaskStageAttempt(task.getId(), PipelineStage.KNOWLEDGE_INDEX, 1);
        failed.start();
        failed.fail("KNOWLEDGE_CHUNKS_EMPTY", "Formal document produced no semantic chunks", false);
        when(tasks.findById(task.getId())).thenReturn(Optional.of(task));
        when(stages.findTopByTranscriptionTaskIdAndStageOrderByAttemptNumberDesc(task.getId(), PipelineStage.KNOWLEDGE_INDEX))
                .thenReturn(Optional.of(failed));
        when(stages.save(any(TaskStageAttempt.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(tasks.save(any(TranscriptionTask.class))).thenAnswer(invocation -> invocation.getArgument(0));
        PipelineProgressService service = new PipelineProgressService(tasks, stages, mock(KnowledgeDocumentRepository.class),
                mock(OrganizedDocumentRepository.class), mock(ProgressEventPublisher.class), mock(OutboxService.class), new AppProperties());

        service.retryStage("owner", task.getId(), PipelineStage.KNOWLEDGE_INDEX);

        ArgumentCaptor<TaskStageAttempt> created = ArgumentCaptor.forClass(TaskStageAttempt.class);
        verify(stages).save(created.capture());
        assertThat(created.getValue().getStage()).isEqualTo(PipelineStage.KNOWLEDGE_INDEX);
        assertThat(created.getValue().getAttemptNumber()).isEqualTo(2);
        assertThat(created.getValue().getStatus()).isEqualTo(StageAttemptStatus.QUEUED);
        assertThat(task.getStatus()).isEqualTo(TaskStatus.RUNNING);
        assertThat(task.getCurrentStage()).isEqualTo(PipelineStage.KNOWLEDGE_INDEX);
        assertThat(task.getFailureMessage()).isNull();
    }
}
