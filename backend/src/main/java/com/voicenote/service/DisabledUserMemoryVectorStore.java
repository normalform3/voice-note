package com.voicenote.service;

import com.voicenote.domain.UserMemoryCategory;
import com.voicenote.provider.ProviderException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import java.util.List;

@Component
@ConditionalOnProperty(name = "app.memory.enabled", havingValue = "false", matchIfMissing = true)
public class DisabledUserMemoryVectorStore implements UserMemoryVectorStore {
    private ProviderException disabled() { return new ProviderException(ProviderException.Kind.FINAL_REJECTION, "MEMORY_DISABLED", "Long-term memory is disabled"); }
    @Override public void ensureCollection() { throw disabled(); }
    @Override public void upsert(String ownerId, String memoryId, String versionId, UserMemoryCategory category, String content, List<Double> denseVector) { throw disabled(); }
    @Override public void deleteMemory(String ownerId, String memoryId) { }
    @Override public List<MemoryHit> search(String ownerId, String query, List<Double> denseVector, List<UserMemoryCategory> categories, int limit) { throw disabled(); }
}
