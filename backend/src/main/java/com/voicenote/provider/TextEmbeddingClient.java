package com.voicenote.provider;

import java.util.List;

public interface TextEmbeddingClient {
    List<List<Double>> embedDocuments(List<String> texts);
    List<Double> embedQuery(String text);
    default EmbeddedDocument embedDocumentWithUsage(String text) { return new EmbeddedDocument(embedDocuments(List.of(text)).get(0), null); }
    record EmbeddedDocument(List<Double> vector, Integer promptTokens) { }
}
