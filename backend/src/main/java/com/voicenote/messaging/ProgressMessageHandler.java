package com.voicenote.messaging;

import com.voicenote.domain.EventType;
import com.voicenote.repository.OutboxEventRepository;
import com.voicenote.repository.AnalysisRunRepository;
import com.voicenote.repository.KnowledgeRunRepository;
import com.voicenote.repository.TranscriptionTaskRepository;
import com.voicenote.repository.SpeakerCorrectionRunRepository;
import com.voicenote.service.ProgressEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProgressMessageHandler {
    private final OutboxEventRepository outbox;
    private final TranscriptionTaskRepository tasks;
    private final AnalysisRunRepository analyses;
    private final KnowledgeRunRepository knowledgeRuns;
    private final ProgressEventPublisher events;
    private final SpeakerCorrectionRunRepository speakerCorrections;

    public ProgressMessageHandler(OutboxEventRepository outbox, TranscriptionTaskRepository tasks, AnalysisRunRepository analyses, KnowledgeRunRepository knowledgeRuns,
                                  SpeakerCorrectionRunRepository speakerCorrections, ProgressEventPublisher events) {
        this.outbox = outbox; this.tasks = tasks; this.analyses = analyses; this.knowledgeRuns = knowledgeRuns; this.speakerCorrections = speakerCorrections; this.events = events;
    }

    @Transactional
    public void consume(String consumerName, String eventId) {
        var event = outbox.findById(eventId).orElse(null);
        if (event == null || event.getEventType() != EventType.PROGRESS_CHANGED) return;
        if ("transcription_task".equals(event.getAggregateType())) {
            tasks.findById(event.getAggregateId()).ifPresent(task -> events.publish(new ProgressEventPublisher.ProgressNotification(task.getOwnerId(), "task-stage-settled", task.getId())));
        } else if ("analysis_run".equals(event.getAggregateType())) {
            analyses.findById(event.getAggregateId()).ifPresent(run -> events.publish(new ProgressEventPublisher.ProgressNotification(run.getOwnerId(), "analysis-run-settled", run.getId())));
        } else if ("knowledge_run".equals(event.getAggregateType())) {
            knowledgeRuns.findById(event.getAggregateId()).ifPresent(run -> events.publish(new ProgressEventPublisher.ProgressNotification(run.getOwnerId(), "knowledge-run-settled", run.getId())));
        } else if ("speaker_correction_run".equals(event.getAggregateType())) {
            speakerCorrections.findById(event.getAggregateId()).ifPresent(run -> events.publish(new ProgressEventPublisher.ProgressNotification(run.getOwnerId(), "speaker-correction-run-settled", run.getId())));
        }
    }
}
