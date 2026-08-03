package com.voicenote.provider;

import com.voicenote.domain.AudioBlob;
import java.util.List;

public interface AsrProvider {
    AsrSubmission submit(AudioBlob audio);
    AsrPollResult poll(String providerTaskId);

    record AsrSubmission(String providerTaskId, String providerInputUrl) { }
    record AsrPollResult(Status status, String errorCode, String errorMessage, List<AsrSegment> segments) {
        public enum Status { RUNNING, SUCCEEDED, FAILED }
    }
    record AsrSegment(String speakerLabel, long startMs, long endMs, String text) { }
}
