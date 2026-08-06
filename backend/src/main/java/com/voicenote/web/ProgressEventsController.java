package com.voicenote.web;

import com.voicenote.security.UserPrincipal;
import com.voicenote.service.AnalysisService;
import com.voicenote.service.KnowledgeAgentService;
import com.voicenote.service.KnowledgeDocumentService;
import com.voicenote.service.PipelineProgressService;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.util.List;

@RestController
@RequestMapping("/api/progress-events")
public class ProgressEventsController {
    private final ProgressSseHub hub;
    private final PipelineProgressService pipeline;
    private final KnowledgeDocumentService documents;
    private final AnalysisService analyses;
    private final KnowledgeAgentService knowledge;

    public ProgressEventsController(ProgressSseHub hub, PipelineProgressService pipeline, KnowledgeDocumentService documents,
                                    AnalysisService analyses, KnowledgeAgentService knowledge) {
        this.hub = hub; this.pipeline = pipeline; this.documents = documents; this.analyses = analyses; this.knowledge = knowledge;
    }

    @GetMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter subscribe(Authentication authentication) {
        UserPrincipal user = CurrentUser.require(authentication);
        WorkspaceSnapshot snapshot = new WorkspaceSnapshot(
                pipeline.ownedViews(user.id()),
                documents.ownedDocuments(user.id()).stream().map(document -> KnowledgeDocumentController.DocumentView.from(document, documents.currentBuild(document.getId()))).toList(),
                analyses.ownedRuns(user.id()).stream().map(AnalysisService.AnalysisView::from).toList(),
                knowledge.ownedRuns(user.id()).stream().map(KnowledgeAgentService.KnowledgeRunView::from).toList());
        return hub.subscribe(user.id(), snapshot);
    }

    public record WorkspaceSnapshot(List<PipelineProgressService.TaskProgressView> tasks, List<KnowledgeDocumentController.DocumentView> documents,
                                    List<AnalysisService.AnalysisView> analyses, List<KnowledgeAgentService.KnowledgeRunView> knowledgeRuns) { }
}
