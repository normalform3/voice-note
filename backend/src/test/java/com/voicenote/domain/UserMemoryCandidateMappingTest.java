package com.voicenote.domain;

import jakarta.persistence.Column;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class UserMemoryCandidateMappingTest {
    @Test
    void confidenceMatchesTheFlywayDecimalColumn() throws Exception {
        Field field = UserMemoryCandidate.class.getDeclaredField("confidence");
        Column column = field.getAnnotation(Column.class);

        assertThat(field.getType()).isEqualTo(BigDecimal.class);
        assertThat(column.precision()).isEqualTo(5);
        assertThat(column.scale()).isEqualTo(4);
    }
}
