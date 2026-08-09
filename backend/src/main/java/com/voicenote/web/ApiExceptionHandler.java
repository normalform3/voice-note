package com.voicenote.web;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.util.DisconnectedClientHelper;
import java.io.IOException;
import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final DisconnectedClientHelper DISCONNECTED_CLIENTS =
            new DisconnectedClientHelper(ApiExceptionHandler.class.getName());

    @ExceptionHandler(ApiException.class)
    ResponseEntity<Map<String, String>> handle(ApiException exception) {
        return ResponseEntity.status(exception.getStatus()).body(Map.of("code", exception.getCode(), "message", exception.getMessage()));
    }
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String, String>> validation(MethodArgumentNotValidException exception) {
        String message = exception.getBindingResult().getFieldErrors().isEmpty() ? "Invalid request" : exception.getBindingResult().getFieldErrors().get(0).getDefaultMessage();
        return ResponseEntity.badRequest().body(Map.of("code", "VALIDATION_ERROR", "message", message));
    }

    @ExceptionHandler(IOException.class)
    void disconnectedClient(IOException exception) throws IOException {
        if (!DISCONNECTED_CLIENTS.checkAndLogClientDisconnectedException(exception)) throw exception;
    }
}
