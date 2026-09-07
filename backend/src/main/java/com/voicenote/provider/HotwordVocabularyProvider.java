package com.voicenote.provider;

import java.util.List;

public interface HotwordVocabularyProvider {
    String create(String prefix, String targetModel, List<Entry> entries);
    Vocabulary query(String vocabularyId);
    String findIdByPrefix(String prefix);
    void update(String vocabularyId, List<Entry> entries);
    void delete(String vocabularyId);

    record Entry(String text, int weight, String language) { }
    record Vocabulary(String id, String targetModel, List<Entry> entries, String status) { }
}
