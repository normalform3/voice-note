package com.voicenote.service;

import com.voicenote.repository.KnowledgeRunRepository;
import com.voicenote.web.ProgressSseHub;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import java.util.Map;

@Component
public class ProgressEventListener {
    private final ProgressSseHub hub;
    private final PipelineProgressService pipeline;
    private final AnalysisService analyses;
    private final KnowledgeRunRepository knowledgeRuns;

    public ProgressEventListener(ProgressSseHub hub, PipelineProgressService pipeline, AnalysisService analyses, KnowledgeRunRepository knowledgeRuns) {
        this.hub = hub; this.pipeline = pipeline; this.analyses = analyses; this.knowledgeRuns = knowledgeRuns;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void send(ProgressEventPublisher.ProgressNotification notification) {
        Object payload = switch (notification.type()) {
            case "task-stage-settled" -> Map.of("task", pipeline.viewForNotification(notification.resourceId()));
            case "analysis-run-settled" -> Map.of("run", AnalysisService.AnalysisView.from(analyses.ownedRun(notification.ownerId(), notification.resourceId())));
            case "knowledge-run-settled" -> {
                var run = knowledgeRuns.findById(notification.resourceId()).orElse(null);
                yield run == null ? Map.<String, Object>of("runId", notification.resourceId())
                        : Map.<String, Object>of("run", KnowledgeAgentService.KnowledgeRunView.from(run));
            }
            default -> Map.of("resourceId", notification.resourceId());
        };
        hub.send(notification.ownerId(), notification.type(), payload);
    }
}
