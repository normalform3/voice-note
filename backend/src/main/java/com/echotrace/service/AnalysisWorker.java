package com.echotrace.service;

import com.echotrace.config.AppProperties;
import com.echotrace.provider.AnalysisModelClient;
import com.echotrace.provider.ProviderException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.List;

@Component
public class AnalysisWorker {
    private final AppProperties properties; private final AnalysisService analyses; private final AnalysisModelClient model;
    public AnalysisWorker(AppProperties properties, AnalysisService analyses, AnalysisModelClient model) { this.properties = properties; this.analyses = analyses; this.model = model; }
    @Scheduled(fixedDelayString = "${app.workers.poll-interval-ms:5000}")
    public void work() { if (properties.getWorkers().isEnabled()) analyses.queuedRunIds().forEach(this::run); }
    private void run(String runId) {
        AnalysisService.RunWork run = analyses.claim(runId); if (run == null) return;
        try {
            List<String> maps = new ArrayList<>();
            for (int i = 0; i < run.chunks().size(); i++) maps.add(call(run.runId(), "MAP", i, "Extract facts relevant to this goal: " + run.goal() + ". Return JSON with claims and evidence segmentIds only.\n\n" + run.chunks().get(i)));
            String draft = call(run.runId(), "ANALYSIS", 0, "Create a structured JSON answer for goal: " + run.goal() + ". Every finding must cite valid segmentIds. Source findings:\n" + String.join("\n", maps));
            String critic = call(run.runId(), "CRITIC", 0, "Review this JSON for unsupported claims, missing goal coverage, and contradictions. Return {\"approved\":true|false,\"issues\":[]}.\n" + draft);
            String result = critic.contains("\"approved\":false") ? call(run.runId(), "REPAIR", 0, "Repair only the identified issues and return the complete evidence-backed JSON document. Draft:\n" + draft + "\nCritique:\n" + critic) : draft;
            analyses.completeRun(run.runId(), result);
        } catch (ProviderException exception) { analyses.failRun(runId, exception); }
    }
    private String call(String runId, String stage, int index, String prompt) {
        AnalysisService.StageAction action = analyses.prepareStage(runId, stage, index, prompt);
        if (action.cached()) return action.value();
        String response = model.complete(action.value()); analyses.completeStage(runId, stage, index, response); return response;
    }
}
