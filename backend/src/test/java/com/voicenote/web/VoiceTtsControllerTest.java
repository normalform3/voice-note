package com.voicenote.web;

import com.voicenote.service.VoiceTtsService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class VoiceTtsControllerTest {
    @Test
    void disabledTtsDoesNotExposeAPlaybackStream() {
        VoiceTtsService tts = mock(VoiceTtsService.class);
        when(tts.isEnabled()).thenReturn(false);
        VoiceTtsController controller = new VoiceTtsController(tts);

        assertThatThrownBy(() -> controller.speak(new VoiceTtsController.TtsRequest(
                "7797a7f7-8d13-48f5-959d-b1e3031ab208", "测试")))
                .isInstanceOfSatisfying(ApiException.class, error -> {
                    org.assertj.core.api.Assertions.assertThat(error.getStatus().value()).isEqualTo(404);
                    org.assertj.core.api.Assertions.assertThat(error.getCode()).isEqualTo("TTS_DISABLED");
                });
    }
}
