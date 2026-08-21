package com.voicenote.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voicenote.agent.AgentMetrics;
import com.voicenote.config.AppProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class VoiceTtsServiceTest {
    @Test
    void requiresEveryTtsCapabilitySetting() {
        AppProperties properties = new AppProperties();
        VoiceTtsService service = new VoiceTtsService(properties, new ObjectMapper(), mock(AgentMetrics.class));
        assertThat(service.isEnabled()).isFalse();

        properties.getTts().setEnabled(true);
        properties.getDashscope().setEnabled(true);
        assertThat(service.isEnabled()).isFalse();

        properties.getDashscope().setApiKey("test-key");
        assertThat(service.isEnabled()).isTrue();

        properties.getTts().setWsUrl("https://example.invalid/tts");
        assertThat(service.isEnabled()).isFalse();
        properties.getTts().setWsUrl("wss://example.invalid/tts");
        properties.getTts().setVoice(" ");
        assertThat(service.isEnabled()).isFalse();

        properties.getTts().setVoice("Cherry");
        assertThatThrownBy(() -> service.stream("utterance", "x".repeat(501), new java.io.ByteArrayOutputStream()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("1 to 500");
    }
}
