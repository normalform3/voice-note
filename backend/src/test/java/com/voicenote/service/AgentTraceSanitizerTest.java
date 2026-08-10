package com.voicenote.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentTraceSanitizerTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void removesCredentialsAndPrivateAddressesFromTraceJson() throws Exception {
        String sanitized = AgentTraceSanitizer.sanitizeJson(mapper, """
                {"authorization":"Bearer private-token-value","nested":{"apiKey":"secret-value"},
                 "message":"Authorization: Bearer another-private-token at http://127.0.0.1:8080/path",
                 "publicUrl":"https://example.com/source"}
                """);
        JsonNode value = mapper.readTree(sanitized);

        assertThat(value.path("authorization").asText()).isEqualTo("[REDACTED]");
        assertThat(value.path("nested").path("apiKey").asText()).isEqualTo("[REDACTED]");
        assertThat(value.path("message").asText()).doesNotContain("private-token", "127.0.0.1");
        assertThat(value.path("publicUrl").asText()).isEqualTo("https://example.com/source");
    }
}
