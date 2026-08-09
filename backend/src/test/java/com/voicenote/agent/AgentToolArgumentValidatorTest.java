package com.voicenote.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;

class AgentToolArgumentValidatorTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void rejectsUnknownFieldsAndOversizedDocumentLists() throws Exception {
        var schema = mapper.readTree("""
                {"type":"object","additionalProperties":false,"properties":{"documentIds":{"type":"array","maxItems":2,"items":{"type":"string"}}}}
                """);

        assertThatThrownBy(() -> AgentToolArgumentValidator.validate(schema, mapper.readTree("{\"ownerId\":\"other-user\"}")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("ownerId");
        assertThatThrownBy(() -> AgentToolArgumentValidator.validate(schema, mapper.readTree("{\"documentIds\":[\"a\",\"b\",\"c\"]}")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("number of items");
    }

    @Test
    void truncatesToolOutputByUtf8BytesWithoutSplittingACharacter() {
        String truncated = AgentOutputLimits.truncateUtf8("中文内容中文内容", 10);
        assertThat(AgentOutputLimits.utf8Bytes(truncated)).isLessThanOrEqualTo(10);
        assertThat(truncated).isEqualTo("中文内");
    }
}
