package com.voicenote.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AppPropertiesTest {
    @Test
    void derivesTheNativeApiEndpointFromAnOpenAiCompatibleEndpoint() {
        AppProperties.Dashscope dashscope = new AppProperties().getDashscope();
        dashscope.setBaseUrl("https://example.invalid/compatible-mode/v1/");

        assertThat(dashscope.getApiBaseUrl()).isEqualTo("https://example.invalid/api/v1");
        assertThat(dashscope.getCompatibleBaseUrl()).isEqualTo("https://example.invalid/compatible-mode/v1");
    }

    @Test
    void memoryIsOptInAndUsesBoundedDefaults() {
        AppProperties.Memory memory = new AppProperties().getMemory();

        assertThat(memory.isEnabled()).isFalse();
        assertThat(memory.getCollection()).isEqualTo("voicenote_user_memories");
        assertThat(memory.getRecentTurns()).isEqualTo(6);
        assertThat(memory.getContextMaxCharacters()).isEqualTo(16_000);
        assertThat(memory.getSummaryMaxCharacters()).isEqualTo(4_000);
        assertThat(memory.getMaxCandidatesPerTurn()).isEqualTo(5);
        assertThat(memory.getCandidateConfidenceThreshold()).isEqualTo(0.75);
        assertThat(memory.getSearchLimit()).isEqualTo(8);
    }
}
