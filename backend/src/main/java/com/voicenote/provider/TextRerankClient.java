package com.voicenote.provider;

import java.util.List;

public interface TextRerankClient {
    RerankResult rerank(String query, List<Candidate> candidates);
    record Candidate(String id, String text, double retrievalScore) { }
    record Ranked(String id, double score) { }
    record RerankResult(List<Ranked> ranked, boolean fallback, String limitation) { }
}
