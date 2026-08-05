package com.voicenote.service;

import com.voicenote.web.ApiException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TranscriptionTaskServiceTest {
    @Test
    void preservesAnExplicitChoiceToDisableSpeakerDiarization() {
        TranscriptionTaskService.AsrConfig config = new TranscriptionTaskService.AsrConfig(List.of("zh"), false, null);

        assertThat(config.normalized().diarizationEnabled()).isFalse();
    }

    @Test
    void keepsSpeakerDiarizationEnabledForLegacyRequestsWithoutTheField() {
        TranscriptionTaskService.AsrConfig config = new TranscriptionTaskService.AsrConfig(List.of("zh"), null, null);

        assertThat(config.normalized().diarizationEnabled()).isTrue();
    }

    @Test
    void rejectsSpeakerCountsOutsideTheProviderRange() {
        TranscriptionTaskService.AsrConfig config = new TranscriptionTaskService.AsrConfig(List.of("zh"), true, 1);

        assertThatThrownBy(config::normalized)
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("speakerCount must be between 2 and 100");
    }
}
