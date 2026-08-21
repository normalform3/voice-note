package com.voicenote.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voicenote.agent.AgentExecutionContext;
import com.voicenote.agent.AgentOutputLimits;
import com.voicenote.agent.tools.FinalizeAnswerTool;
import com.voicenote.provider.AgentModelClient;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

final class FinalizeAnswerStreamObserver implements AgentModelClient.StreamObserver, AutoCloseable {
    private final ObjectMapper mapper;
    private final FinalizeAnswerTool finalizeAnswer;
    private final AgentExecutionContext context;
    private final int maxBytes;
    private final Consumer<ValidatedBlock> consumer;
    private final Map<Integer, ToolCallState> calls = new HashMap<>();

    FinalizeAnswerStreamObserver(ObjectMapper mapper, FinalizeAnswerTool finalizeAnswer, AgentExecutionContext context,
                                 int maxBytes, Consumer<ValidatedBlock> consumer) {
        this.mapper = mapper; this.finalizeAnswer = finalizeAnswer; this.context = context;
        this.maxBytes = maxBytes; this.consumer = consumer;
    }

    @Override public void onToolCallDelta(int index, String idDelta, String nameDelta, String argumentsDelta) {
        ToolCallState state = calls.computeIfAbsent(index, ignored -> new ToolCallState());
        state.name.append(nameDelta == null ? "" : nameDelta);
        boolean becameFinalize = state.extractor == null && "finalize_answer".contentEquals(state.name);
        if (becameFinalize) {
            state.extractor = new StreamingJsonBlockExtractor(mapper, this::validate);
            state.extractor.feed(state.arguments.toString());
        }
        if (argumentsDelta != null && !argumentsDelta.isEmpty()) {
            state.arguments.append(argumentsDelta);
            if (state.extractor != null) state.extractor.feed(argumentsDelta);
        }
    }

    private void validate(JsonNode raw) {
        try {
            JsonNode block = finalizeAnswer.validateStreamingBlock(context, raw);
            if (AgentOutputLimits.utf8Bytes(mapper.writeValueAsString(block)) > maxBytes) return;
            consumer.accept(new ValidatedBlock(block, AgentSpokenTextFormatter.format(block)));
        } catch (Exception ignored) {
            // Partial feedback is best effort. Full finalize_answer remains authoritative.
        }
    }

    @Override public void close() { calls.values().forEach(value -> { if (value.extractor != null) value.extractor.close(); }); }

    record ValidatedBlock(JsonNode block, String spokenText) { }
    private static final class ToolCallState {
        private final StringBuilder name = new StringBuilder();
        private final StringBuilder arguments = new StringBuilder();
        private StreamingJsonBlockExtractor extractor;
    }
}
