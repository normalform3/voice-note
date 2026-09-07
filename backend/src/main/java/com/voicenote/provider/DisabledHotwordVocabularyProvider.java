package com.voicenote.provider;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import java.util.List;

@Component
@ConditionalOnProperty(name = "app.dashscope.enabled", havingValue = "false", matchIfMissing = true)
public class DisabledHotwordVocabularyProvider implements HotwordVocabularyProvider {
    private ProviderException disabled() {
        return new ProviderException(ProviderException.Kind.FINAL_REJECTION, "HOTWORD_PROVIDER_DISABLED", "DashScope 未启用，无法同步热词库");
    }
    @Override public String create(String prefix, String targetModel, List<Entry> entries) { throw disabled(); }
    @Override public Vocabulary query(String vocabularyId) { throw disabled(); }
    @Override public String findIdByPrefix(String prefix) { throw disabled(); }
    @Override public void update(String vocabularyId, List<Entry> entries) { throw disabled(); }
    @Override public void delete(String vocabularyId) { throw disabled(); }
}
