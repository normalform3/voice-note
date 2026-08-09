package com.voicenote.service;

import com.voicenote.domain.*;
import com.voicenote.repository.*;
import com.voicenote.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
public class ProfileService {
    private final UserAccountRepository users;
    private final TranscriptionTaskRepository tasks;
    private final KnowledgeDocumentRepository documents;
    private final KnowledgeRunRepository runs;
    private final SkillDefinitionRepository skills;

    public ProfileService(UserAccountRepository users, TranscriptionTaskRepository tasks, KnowledgeDocumentRepository documents,
                          KnowledgeRunRepository runs, SkillDefinitionRepository skills) {
        this.users = users; this.tasks = tasks; this.documents = documents; this.runs = runs; this.skills = skills;
    }

    @Transactional(readOnly = true)
    public ProfileView get(String ownerId) {
        UserAccount user = users.findById(ownerId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PROFILE_NOT_FOUND", "Profile was not found"));
        return new ProfileView(user.getAccount(), user.getCreatedAt(), new Statistics(
                tasks.countByOwnerId(ownerId), documents.countByOwnerIdAndStatus(ownerId, KnowledgeDocumentStatus.READY),
                runs.countByOwnerIdAndSkillVersionNot(ownerId, "legacy-v1"),
                skills.countByOwnerIdAndSourceAndStatusNot(ownerId, SkillSource.USER, SkillStatus.ARCHIVED)));
    }

    public record ProfileView(String account, Instant createdAt, Statistics statistics) { }
    public record Statistics(long recordingCount, long indexedDocumentCount, long agentRunCount, long customSkillCount) { }
}
