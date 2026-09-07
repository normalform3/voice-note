package com.voicenote.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voicenote.config.AppProperties;
import com.voicenote.domain.*;
import com.voicenote.provider.HotwordVocabularyProvider;
import com.voicenote.repository.*;
import com.voicenote.web.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class HotwordLibraryServiceTest {
    private final HotwordLibraryRepository libraries = mock(HotwordLibraryRepository.class);
    private final HotwordCapacityRepository capacity = mock(HotwordCapacityRepository.class);
    private final TranscriptionTaskRepository tasks = mock(TranscriptionTaskRepository.class);
    private final RealtimeRecordingSessionRepository recordings = mock(RealtimeRecordingSessionRepository.class);
    private final HotwordVocabularyProvider provider = mock(HotwordVocabularyProvider.class);
    private final IdempotencyService idempotency = mock(IdempotencyService.class);
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private HotwordLibraryService service;

    @BeforeEach
    void setUp() {
        AppProperties properties = new AppProperties();
        properties.getDashscope().setAsrModel("paraformer-v2");
        service = new HotwordLibraryService(libraries, capacity, tasks, recordings, provider, idempotency, mapper, properties);
        when(capacity.lockCapacity()).thenReturn(Optional.of(mock(HotwordCapacity.class)));
        when(idempotency.reserve(anyString(), anyString(), anyString(), anyString())).thenReturn(new IdempotencyRecord("owner", "op", "key", "hash"));
        when(libraries.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(provider.findIdByPrefix(anyString())).thenReturn(null);
        when(provider.create(anyString(), anyString(), anyList())).thenReturn("vocab-123");
    }

    @Test
    void createsAReadyPrivateLibraryWithFixedProviderWeights() {
        var view = service.create("owner", "key", new HotwordLibraryService.SaveCommand("产品术语", List.of(
                new HotwordLibraryService.EntryInput("语音实验室", "zh"),
                new HotwordLibraryService.EntryInput("VoiceNote", "en"))));

        ArgumentCaptor<List<HotwordVocabularyProvider.Entry>> entries = ArgumentCaptor.forClass(List.class);
        verify(provider).create(anyString(), eq("paraformer-v2"), entries.capture());
        assertThat(entries.getValue()).allMatch(value -> value.weight() == 4);
        assertThat(entries.getValue()).extracting(HotwordVocabularyProvider.Entry::language).containsExactly("en", "zh");
        assertThat(view.status()).isEqualTo(HotwordLibraryStatus.READY);
        assertThat(view.revision()).isEqualTo(1);
    }

    @Test
    void rejectsDuplicateAndUnsupportedEntriesBeforeCallingTheProvider() {
        assertThatThrownBy(() -> service.create("owner", "key", new HotwordLibraryService.SaveCommand("重复", List.of(
                new HotwordLibraryService.EntryInput("VoiceNote", "en"),
                new HotwordLibraryService.EntryInput("voicenote", "en")))))
                .isInstanceOf(ApiException.class).hasMessageContaining("重复热词");
        assertThatThrownBy(() -> service.create("owner", "key", new HotwordLibraryService.SaveCommand("日语", List.of(
                new HotwordLibraryService.EntryInput("こんにちは", "ja")))))
                .isInstanceOf(ApiException.class).hasMessageContaining("zh 或 en");
        verifyNoInteractions(provider);
    }

    @Test
    void enforcesTheSharedTenLibraryCapacity() {
        when(libraries.countByStatusNot(HotwordLibraryStatus.DELETED)).thenReturn(10L);
        assertThatThrownBy(() -> service.create("owner", "key", new HotwordLibraryService.SaveCommand("第十一个", List.of(
                new HotwordLibraryService.EntryInput("术语", "zh")))))
                .isInstanceOf(ApiException.class).hasMessageContaining("最多可创建 10 个");
        verifyNoInteractions(provider);
    }

    @Test
    void resolvesOnlyOwnedReadyLibrariesForAsr() {
        HotwordLibrary library = new HotwordLibrary("owner", "产品", "[{\"text\":\"VoiceNote\",\"language\":\"en\"}]", "a".repeat(64));
        library.ready("vocab-123");
        when(libraries.findOwnedForUse(library.getId(), "owner")).thenReturn(Optional.of(library));

        assertThat(service.resolveForUse("owner", library.getId()))
                .isEqualTo(new HotwordLibraryService.Selection(library.getId(), 1, "vocab-123"));
        assertThatThrownBy(() -> service.resolveForUse("other", library.getId())).isInstanceOf(ApiException.class);
    }

    @Test
    void rejectsContentUpdatesDuringTheProviderCooldown() {
        HotwordLibrary library = readyLibrary();
        when(libraries.findOwnedForUpdate(library.getId(), "owner")).thenReturn(Optional.of(library));
        when(libraries.findByOwnerIdAndStatusNotOrderByUpdatedAtDesc("owner", HotwordLibraryStatus.DELETED)).thenReturn(List.of(library));

        assertThatThrownBy(() -> service.update("owner", "key", library.getId(), new HotwordLibraryService.SaveCommand(
                "产品", List.of(new HotwordLibraryService.EntryInput("新术语", "zh")))))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("至少间隔 5 分钟");
        verify(provider, never()).update(anyString(), anyList());
    }

    @Test
    void rejectsEntryChangesAndDeletionWhileTranscriptionIsStillActive() {
        HotwordLibrary library = readyLibrary();
        when(libraries.findOwnedForUpdate(library.getId(), "owner")).thenReturn(Optional.of(library));
        when(libraries.findByOwnerIdAndStatusNotOrderByUpdatedAtDesc("owner", HotwordLibraryStatus.DELETED)).thenReturn(List.of(library));
        when(tasks.existsByHotwordLibraryIdAndTranscriptReadyFalse(library.getId())).thenReturn(true);

        assertThatThrownBy(() -> service.update("owner", "key", library.getId(), new HotwordLibraryService.SaveCommand(
                "产品", List.of(new HotwordLibraryService.EntryInput("新术语", "zh")))))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("正被录音或未完成的转写使用");
        assertThatThrownBy(() -> service.delete("owner", "delete-key", library.getId()))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("正被录音或未完成的转写使用");
        verify(provider, never()).delete(anyString());
    }

    private HotwordLibrary readyLibrary() {
        HotwordLibrary library = new HotwordLibrary("owner", "产品", "[{\"text\":\"VoiceNote\",\"language\":\"en\"}]", "a".repeat(64));
        library.ready("vocab-123");
        return library;
    }
}
