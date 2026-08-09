package com.voicenote.web;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ApiExceptionHandlerTest {
    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Test
    void ignoresClientDisconnects() {
        assertDoesNotThrow(() -> handler.disconnectedClient(new IOException("Broken pipe")));
    }

    @Test
    void doesNotHideOtherIoFailures() {
        IOException failure = new IOException("Failed to read response data");

        IOException thrown = assertThrows(IOException.class, () -> handler.disconnectedClient(failure));

        assertSame(failure, thrown);
    }
}
