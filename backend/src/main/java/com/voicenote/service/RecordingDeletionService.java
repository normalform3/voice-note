package com.voicenote.service;

import com.voicenote.domain.*;
import com.voicenote.repository.*;
import com.voicenote.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Deletes a recording and all of its derived data without touching other tasks that share the upload. */
@Service
public class RecordingDeletionService {
    private final TranscriptionTaskRepository tasks;
    private final AudioBlobRepository blobs;
    private final TaskAttemptRepository attempts;
    private final ProviderInvocationRepository providerInvocations;
    private final TaskStageAttemptRepository stages;
    private final TranscriptSegmentRepository segments;
    private final TranscriptSpeakerRepository transcriptSpeakers;
    private final RawTranscriptDocumentRepository rawTranscriptDocuments;
    private final OrganizedDocumentRepository organizedDocuments;
    private final OrganizedDocumentBlockRepository organizedBlocks;
    private final KnowledgeDocumentRepository knowledgeDocuments;
    private final KnowledgeChunkRepository knowledgeChunks;
    private final KnowledgeIndexVersionRepository knowledgeIndexVersions;
    private final KnowledgeIndexStageAttemptRepository knowledgeIndexStages;
    private final KnowledgeTopicRepository knowledgeTopics;
    private final KnowledgeChunkTopicRepository knowledgeChunkTopics;
    private final KnowledgeRunEvidenceRepository knowledgeEvidence;
    private final KnowledgeRunDocumentRepository knowledgeRunDocuments;
    private final KnowledgeRunStepRepository knowledgeRunSteps;
    private final KnowledgeRunSourceRepository knowledgeRunSources;
    private final AgentCheckpointRepository agentCheckpoints;
    private final KnowledgeRunRepository knowledgeRuns;
    private final AnalysisRunRepository analysisRuns;
    private final AnalysisEvidenceRepository analysisEvidence;
    private final AnalysisInvocationRepository analysisInvocations;
    private final OrganizationInvocationRepository organizationInvocations;
    private final SpeakerCorrectionRunRepository speakerCorrectionRuns;
    private final SpeakerCorrectionSuggestionRepository speakerCorrectionSuggestions;
    private final SpeakerCorrectionInvocationRepository speakerCorrectionInvocations;
    private final OutboxEventRepository outbox;
    private final IdempotencyRecordRepository idempotencyRecords;
    private final IdempotencyService idempotency;
    private final KnowledgeVectorStore vectors;
    private final ObjectStorage storage;
    private final TransactionTemplate transactions;

    public RecordingDeletionService(TranscriptionTaskRepository tasks, AudioBlobRepository blobs, TaskAttemptRepository attempts,
                                    ProviderInvocationRepository providerInvocations, TaskStageAttemptRepository stages,
                                    TranscriptSegmentRepository segments, TranscriptSpeakerRepository transcriptSpeakers, RawTranscriptDocumentRepository rawTranscriptDocuments, OrganizedDocumentRepository organizedDocuments,
                                    OrganizedDocumentBlockRepository organizedBlocks, KnowledgeDocumentRepository knowledgeDocuments,
                                    KnowledgeChunkRepository knowledgeChunks, KnowledgeIndexVersionRepository knowledgeIndexVersions,
                                    KnowledgeIndexStageAttemptRepository knowledgeIndexStages, KnowledgeTopicRepository knowledgeTopics, KnowledgeChunkTopicRepository knowledgeChunkTopics,
                                    KnowledgeRunEvidenceRepository knowledgeEvidence, KnowledgeRunDocumentRepository knowledgeRunDocuments,
                                    KnowledgeRunStepRepository knowledgeRunSteps, KnowledgeRunSourceRepository knowledgeRunSources,
                                    AgentCheckpointRepository agentCheckpoints,
                                    KnowledgeRunRepository knowledgeRuns, AnalysisRunRepository analysisRuns,
                                    AnalysisEvidenceRepository analysisEvidence, AnalysisInvocationRepository analysisInvocations, OrganizationInvocationRepository organizationInvocations,
                                    SpeakerCorrectionRunRepository speakerCorrectionRuns, SpeakerCorrectionSuggestionRepository speakerCorrectionSuggestions,
                                    SpeakerCorrectionInvocationRepository speakerCorrectionInvocations,
                                    OutboxEventRepository outbox, IdempotencyRecordRepository idempotencyRecords,
                                    IdempotencyService idempotency, KnowledgeVectorStore vectors, ObjectStorage storage,
                                    PlatformTransactionManager transactionManager) {
        this.tasks = tasks; this.blobs = blobs; this.attempts = attempts; this.providerInvocations = providerInvocations; this.stages = stages;
        this.segments = segments; this.transcriptSpeakers = transcriptSpeakers; this.rawTranscriptDocuments = rawTranscriptDocuments; this.organizedDocuments = organizedDocuments; this.organizedBlocks = organizedBlocks;
        this.knowledgeDocuments = knowledgeDocuments; this.knowledgeChunks = knowledgeChunks; this.knowledgeIndexVersions = knowledgeIndexVersions; this.knowledgeIndexStages = knowledgeIndexStages;
        this.knowledgeTopics = knowledgeTopics; this.knowledgeChunkTopics = knowledgeChunkTopics; this.knowledgeEvidence = knowledgeEvidence;
        this.knowledgeRunDocuments = knowledgeRunDocuments; this.knowledgeRunSteps = knowledgeRunSteps; this.knowledgeRunSources = knowledgeRunSources;
        this.agentCheckpoints = agentCheckpoints;
        this.knowledgeRuns = knowledgeRuns; this.analysisRuns = analysisRuns; this.analysisEvidence = analysisEvidence;
        this.analysisInvocations = analysisInvocations; this.organizationInvocations = organizationInvocations; this.outbox = outbox; this.idempotencyRecords = idempotencyRecords;
        this.speakerCorrectionRuns = speakerCorrectionRuns; this.speakerCorrectionSuggestions = speakerCorrectionSuggestions; this.speakerCorrectionInvocations = speakerCorrectionInvocations;
        this.idempotency = idempotency; this.vectors = vectors; this.storage = storage;
        this.transactions = new TransactionTemplate(transactionManager);
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void delete(String ownerId, String key, String taskId) {
        IdempotencyRecord record = idempotency.reserve(ownerId, "DELETE_TRANSCRIPTION_TASK", key, Hashing.sha256(taskId));
        if (record.getResourceId() != null) return;

        DeletionPlan plan = transactions.execute(status -> cancelAndPlan(ownerId, taskId));
        if (plan == null) throw new IllegalStateException("Cannot prepare recording deletion");
        for (String documentId : plan.knowledgeDocumentIds()) vectors.deleteDocument(ownerId, documentId);
        for (String rawResultKey : plan.rawResultObjectKeys()) storage.removeQuietly(rawResultKey);
        if (plan.deleteAudioBlob()) storage.remove(plan.objectKey());
        transactions.executeWithoutResult(status -> deleteMetadata(ownerId, taskId, plan.audioBlobId(), plan.deleteAudioBlob()));
        idempotency.complete(record, taskId, "{\"deleted\":true}");
    }

    /** Commit cancellation before external deletion so in-flight workers discard their result. */
    public DeletionPlan cancelAndPlan(String ownerId, String taskId) {
        TranscriptionTask task = ownedTask(ownerId, taskId);
        if (task.cancel()) {
            stages.findByTranscriptionTaskIdOrderByQueuedAtAsc(taskId).forEach(TaskStageAttempt::cancel);
            tasks.save(task);
        }
        AudioBlob blob = blobs.findById(task.getAudioBlobId()).orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "AUDIO_NOT_FOUND", "The recording source is no longer available"));
        List<String> documentIds = knowledgeDocuments.findByTranscriptionTaskId(taskId).stream().map(KnowledgeDocument::getId).toList();
        List<String> rawResultKeys = rawTranscriptDocuments.findByTranscriptionTaskId(taskId).stream().map(RawTranscriptDocument::getResultObjectKey).toList();
        return new DeletionPlan(blob.getId(), blob.getObjectKey(), tasks.countByAudioBlobId(blob.getId()) == 1, documentIds, rawResultKeys);
    }

    public void deleteMetadata(String ownerId, String taskId, String audioBlobId, boolean deleteAudioBlob) {
        ownedTask(ownerId, taskId);

        List<KnowledgeDocument> documents = knowledgeDocuments.findByTranscriptionTaskId(taskId);
        Set<String> relatedKnowledgeRuns = new LinkedHashSet<>();
        knowledgeRunDocuments.findByTranscriptionTaskId(taskId).forEach(document -> relatedKnowledgeRuns.add(document.getKnowledgeRunId()));
        for (KnowledgeDocument document : documents) {
            knowledgeEvidence.findByKnowledgeDocumentId(document.getId()).forEach(evidence -> relatedKnowledgeRuns.add(evidence.getKnowledgeRunId()));
        }
        for (String runId : relatedKnowledgeRuns) {
            knowledgeEvidence.deleteByKnowledgeRunId(runId);
            knowledgeRunSources.deleteByKnowledgeRunId(runId);
            agentCheckpoints.deleteByKnowledgeRunId(runId);
            knowledgeRunSteps.deleteByKnowledgeRunId(runId);
            knowledgeRunDocuments.deleteByKnowledgeRunId(runId);
            idempotencyRecords.deleteByOwnerIdAndResourceId(ownerId, runId);
            knowledgeRuns.deleteById(runId);
            outbox.deleteByAggregateTypeAndAggregateId("knowledge_run", runId);
        }
        for (KnowledgeDocument document : documents) {
            List<KnowledgeIndexVersion> versions = knowledgeIndexVersions.findByKnowledgeDocumentIdOrderByGenerationDesc(document.getId());
            List<String> chunkIds = knowledgeChunks.findByKnowledgeDocumentIdOrderByChunkIndex(document.getId()).stream().map(KnowledgeChunk::getId).toList();
            if (!chunkIds.isEmpty()) knowledgeChunkTopics.deleteByKnowledgeChunkIdIn(chunkIds);
            knowledgeChunks.deleteByKnowledgeDocumentId(document.getId());
            document.clearActiveIndexVersion(); knowledgeDocuments.save(document);
            for (KnowledgeIndexVersion version : versions) {
                knowledgeIndexStages.deleteByKnowledgeIndexVersionId(version.getId());
                knowledgeTopics.deleteByKnowledgeIndexVersionId(version.getId());
                outbox.deleteByAggregateTypeAndAggregateId("knowledge_index_version", version.getId());
            }
            knowledgeIndexVersions.deleteByKnowledgeDocumentId(document.getId());
            outbox.deleteByAggregateTypeAndAggregateId("knowledge_document", document.getId());
        }
        knowledgeDocuments.deleteByTranscriptionTaskId(taskId);

        for (AnalysisRun run : analysisRuns.findByTranscriptionTaskId(taskId)) {
            analysisEvidence.deleteByAnalysisRunId(run.getId());
            analysisInvocations.deleteByAnalysisRunId(run.getId());
            outbox.deleteByAggregateTypeAndAggregateId("analysis_run", run.getId());
        }
        analysisRuns.deleteByTranscriptionTaskId(taskId);

        for (OrganizedDocument document : organizedDocuments.findByTranscriptionTaskId(taskId)) {
            organizedBlocks.deleteByOrganizedDocumentId(document.getId());
            organizationInvocations.deleteByOrganizedDocumentId(document.getId());
            outbox.deleteByAggregateTypeAndAggregateId("organized_document", document.getId());
        }
        organizedDocuments.deleteByTranscriptionTaskId(taskId);

        for (SpeakerCorrectionRun run : speakerCorrectionRuns.findByTranscriptionTaskId(taskId)) {
            speakerCorrectionSuggestions.deleteByRunId(run.getId());
            speakerCorrectionInvocations.deleteByRunId(run.getId());
            outbox.deleteByAggregateTypeAndAggregateId("speaker_correction_run", run.getId());
            idempotencyRecords.deleteByOwnerIdAndResourceId(ownerId, run.getId());
        }
        speakerCorrectionRuns.deleteByTranscriptionTaskId(taskId);

        for (TaskAttempt attempt : attempts.findByTranscriptionTaskId(taskId)) providerInvocations.deleteByTaskAttemptId(attempt.getId());
        attempts.deleteByTranscriptionTaskId(taskId);
        stages.deleteByTranscriptionTaskId(taskId);
        segments.deleteByTranscriptionTaskId(taskId);
        transcriptSpeakers.deleteByTranscriptionTaskId(taskId);
        rawTranscriptDocuments.deleteByTranscriptionTaskId(taskId);
        outbox.deleteByAggregateTypeAndAggregateId("transcription_task", taskId);
        idempotencyRecords.deleteByOwnerIdAndResourceId(ownerId, taskId);

        tasks.deleteById(taskId);
        tasks.flush();
        if (deleteAudioBlob && tasks.countByAudioBlobId(audioBlobId) == 0) blobs.deleteById(audioBlobId);
    }

    private TranscriptionTask ownedTask(String ownerId, String taskId) {
        return tasks.findById(taskId).filter(task -> task.getOwnerId().equals(ownerId))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "TASK_NOT_FOUND", "Transcription task was not found"));
    }

    record DeletionPlan(String audioBlobId, String objectKey, boolean deleteAudioBlob, List<String> knowledgeDocumentIds, List<String> rawResultObjectKeys) { }
}
