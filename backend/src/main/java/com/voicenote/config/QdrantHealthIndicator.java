package com.voicenote.config;

import com.voicenote.service.KnowledgeVectorStore;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Reports dependency readiness without blocking the unrelated audio pipeline at startup. */
@Component("qdrant")
@ConditionalOnProperty(name = "app.knowledge.enabled", havingValue = "true")
public class QdrantHealthIndicator implements HealthIndicator {
    private final KnowledgeVectorStore vectors;
    public QdrantHealthIndicator(KnowledgeVectorStore vectors) { this.vectors = vectors; }
    @Override public Health health() {
        try { vectors.ensureAvailable(); return Health.up().build(); }
        catch (RuntimeException exception) { return Health.down().withDetail("reason", "Qdrant is unavailable").build(); }
    }
}
