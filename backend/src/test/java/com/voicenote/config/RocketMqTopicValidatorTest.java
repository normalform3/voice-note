package com.voicenote.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RocketMqTopicValidatorTest {
    @Test
    void acceptsRocketMqTopicCharacters() {
        assertThatCode(() -> RocketMqTopicValidator.validate("topic", "voicenote-transcription_01%blue"))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsTopicCharactersRocketMqCannotPublish() {
        assertThatThrownBy(() -> RocketMqTopicValidator.validate("app.rocketmq.transcription-topic", "voicenote.transcription"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("voicenote-transcription");
    }
}
