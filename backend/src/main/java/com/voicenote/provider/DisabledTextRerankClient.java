package com.voicenote.provider;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import java.util.Comparator;
import java.util.List;

@Component
@ConditionalOnProperty(name = "app.knowledge.rerank-enabled", havingValue = "false", matchIfMissing = true)
public class DisabledTextRerankClient implements TextRerankClient {
    @Override public RerankResult rerank(String query, List<Candidate> candidates) {
        return new RerankResult(candidates.stream().sorted(Comparator.comparingDouble(Candidate::retrievalScore).reversed())
                .map(value -> new Ranked(value.id(), value.retrievalScore())).toList(), true, "rerankDisabled");
    }
}
