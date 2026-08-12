package com.voicenote.service;

import com.voicenote.domain.UserMemoryCategory;
import java.util.List;

public interface UserMemoryVectorStore {
    void ensureCollection();
    void upsert(String ownerId, String memoryId, String versionId, UserMemoryCategory category, String content, List<Double> denseVector);
    void deleteMemory(String ownerId, String memoryId);
    List<MemoryHit> search(String ownerId, String query, List<Double> denseVector, List<UserMemoryCategory> categories, int limit);
    record MemoryHit(String memoryId, String versionId, double score) { }
}
