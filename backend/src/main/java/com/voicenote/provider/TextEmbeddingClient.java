package com.voicenote.provider;

import java.util.List;

public interface TextEmbeddingClient {
    List<List<Double>> embedDocuments(List<String> texts);
    List<Double> embedQuery(String text);
}
