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
}
