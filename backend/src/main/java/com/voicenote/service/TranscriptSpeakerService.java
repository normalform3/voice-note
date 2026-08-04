package com.voicenote.service;

import com.voicenote.domain.SpeakerRole;
import com.voicenote.domain.TranscriptSpeaker;
import com.voicenote.repository.TranscriptSpeakerRepository;
import com.voicenote.repository.TranscriptionTaskRepository;
import com.voicenote.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class TranscriptSpeakerService {
    private final TranscriptSpeakerRepository speakers;
    private final TranscriptionTaskRepository tasks;

    public TranscriptSpeakerService(TranscriptSpeakerRepository speakers, TranscriptionTaskRepository tasks) {
        this.speakers = speakers; this.tasks = tasks;
    }

    @Transactional(readOnly = true)
    public List<TranscriptSpeaker> list(String ownerId, String taskId) {
        var task = ownedTask(ownerId, taskId);
        return speakers.findByTranscriptionTaskIdAndTranscriptVersionOrderByAsrSpeakerId(taskId, task.getTranscriptVersion());
    }

    @Transactional(readOnly = true)
    public Map<String, TranscriptSpeaker> index(String ownerId, String taskId) {
        return list(ownerId, taskId).stream().collect(Collectors.toMap(TranscriptSpeaker::getAsrSpeakerId, Function.identity()));
    }

    @Transactional
    public TranscriptSpeaker confirm(String ownerId, String taskId, String asrSpeakerId, SpeakerRole role, String displayName) {
        var task = ownedTask(ownerId, taskId);
        TranscriptSpeaker speaker = speakers.findByTranscriptionTaskIdAndTranscriptVersionAndAsrSpeakerId(taskId, task.getTranscriptVersion(), asrSpeakerId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "SPEAKER_NOT_FOUND", "ASR speaker was not found"));
        speaker.confirm(role, displayName); return speakers.save(speaker);
    }

    @Transactional
    public void suggest(String taskId, int transcriptVersion, String asrSpeakerId, SpeakerRole role, Double confidence) {
        speakers.findByTranscriptionTaskIdAndTranscriptVersionAndAsrSpeakerId(taskId, transcriptVersion, asrSpeakerId)
                .ifPresent(speaker -> { speaker.suggest(role, confidence); speakers.save(speaker); });
    }

    private com.voicenote.domain.TranscriptionTask ownedTask(String ownerId, String taskId) {
        return tasks.findById(taskId).filter(task -> task.getOwnerId().equals(ownerId))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "TASK_NOT_FOUND", "Transcription task was not found"));
    }
}
