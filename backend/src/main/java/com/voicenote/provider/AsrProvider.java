package com.voicenote.provider;

import com.voicenote.domain.AudioBlob;
import java.util.List;

public interface AsrProvider {
    AsrSubmission submit(AudioBlob audio, AsrOptions options);
    AsrPollResult poll(String providerTaskId);

    record AsrSubmission(String providerTaskId, String providerInputUrl) { }
    record AsrOptions(List<String> languageHints, Integer speakerCount) { }
    record AsrAudioMetadata(Integer channelCount, Long durationMs) { }
    record AsrPollResult(Status status, String errorCode, String errorMessage, List<AsrSegment> segments, AsrAudioMetadata audioMetadata) {
        public AsrPollResult(Status status, String errorCode, String errorMessage, List<AsrSegment> segments) {
            this(status, errorCode, errorMessage, segments, null);
        }
        public enum Status { RUNNING, SUCCEEDED, FAILED }
    }
    record AsrSegment(String speakerId, long startMs, long endMs, String text) { }
}
