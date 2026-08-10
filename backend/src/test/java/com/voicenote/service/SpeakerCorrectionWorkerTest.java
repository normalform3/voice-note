package com.voicenote.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voicenote.config.AppProperties;
import com.voicenote.domain.SceneType;
import com.voicenote.domain.SegmentTimingSource;
import com.voicenote.domain.TranscriptSegment;
import com.voicenote.domain.TranscriptSpeaker;
import com.voicenote.provider.AnalysisModelClient;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SpeakerCorrectionWorkerTest {
    private final SpeakerCorrectionService service = mock(SpeakerCorrectionService.class);
    private final SpeakerCorrectionTimingAligner timing = mock(SpeakerCorrectionTimingAligner.class);
    private final AnalysisModelClient model = mock(AnalysisModelClient.class);
    private final SpeakerCorrectionWorker worker = new SpeakerCorrectionWorker(service, timing, model, new ObjectMapper(), new AppProperties());

    @Test
    void validatesAndPersistsRelabelAndExactSplitSuggestions() {
        TranscriptSegment first = new TranscriptSegment("task", 1, 0, "SPEAKER_0", 0, 1_000, "这是回答");
        TranscriptSegment second = new TranscriptSegment("task", 1, 1, "SPEAKER_0", 1_100, 3_000, "你好我来回答");
        when(service.claim("run")).thenReturn(work(first, second));
        when(model.complete(anyString())).thenReturn("{\"suggestions\":[" +
                "{\"type\":\"RELABEL\",\"segmentId\":\"" + first.getId() + "\",\"speakerId\":\"SPEAKER_1\",\"confidence\":0.9,\"reason\":\"回答语义\"}," +
                "{\"type\":\"SPLIT\",\"segmentId\":\"" + second.getId() + "\",\"parts\":[{\"speakerId\":\"SPEAKER_0\",\"text\":\"你好\"},{\"speakerId\":\"SPEAKER_1\",\"text\":\"我来回答\"}],\"confidence\":0.85,\"reason\":\"问答切换\"}]}" );
        when(timing.align(eq("owner"), eq("task"), eq(1), eq(second), anyList())).thenReturn(List.of(
                new SpeakerCorrectionTimingAligner.TimedPart(0, 2, "SPEAKER_0", "你好", 1_100, 1_600, SegmentTimingSource.WORD_ALIGNED),
                new SpeakerCorrectionTimingAligner.TimedPart(2, 6, "SPEAKER_1", "我来回答", 1_700, 3_000, SegmentTimingSource.WORD_ALIGNED)));

        worker.process("run");

        ArgumentCaptor<List<SpeakerCorrectionService.SuggestionDraft>> drafts = ArgumentCaptor.forClass(List.class);
        verify(service).complete(eq("run"), drafts.capture(), eq(0));
        assertThat(drafts.getValue()).hasSize(2);
        assertThat(drafts.getValue().get(0).targetSpeakerId()).isEqualTo("SPEAKER_1");
        assertThat(drafts.getValue().get(1).proposalDocument()).contains("我来回答");
    }

    @Test
    void rejectsASplitThatChangesTheOriginalText() {
        TranscriptSegment segment = new TranscriptSegment("task", 1, 0, "SPEAKER_0", 0, 1_000, "原始文字");
        when(service.claim("run")).thenReturn(work(segment));
        when(model.complete(anyString())).thenReturn("{\"suggestions\":[{\"type\":\"SPLIT\",\"segmentId\":\"" + segment.getId() +
                "\",\"parts\":[{\"speakerId\":\"SPEAKER_0\",\"text\":\"原始\"},{\"speakerId\":\"SPEAKER_1\",\"text\":\"改写\"}],\"confidence\":0.9,\"reason\":\"切换\"}]}" );

        worker.process("run");

        verify(service).complete(eq("run"), eq(List.of()), eq(1));
        verifyNoInteractions(timing);
    }

    @Test
    void repairsAMalformedEnvelopeOnce() {
        TranscriptSegment segment = new TranscriptSegment("task", 1, 0, "SPEAKER_0", 0, 1_000, "回答");
        when(service.claim("run")).thenReturn(work(segment));
        when(model.complete(anyString())).thenReturn("not-json", "{\"suggestions\":[]}");

        worker.process("run");

        verify(model, times(2)).complete(anyString());
        verify(service).recordInvocation(eq("run"), eq(0), eq(2), anyString(), anyString(), eq(false));
        verify(service).complete("run", List.of(), 0);
    }

    @Test
    void keepsChunksWithinTheConfiguredTextLimitAndOverlapsBoundaries() {
        List<TranscriptSegment> segments = java.util.stream.IntStream.range(0, 14)
                .mapToObj(index -> new TranscriptSegment("task", 1, index, "SPEAKER_0", index, index + 1, "字".repeat(1_000))).toList();

        var chunks = SpeakerCorrectionWorker.chunks(segments);

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0)).hasSize(12);
        assertThat(chunks.get(1).get(0).getId()).isEqualTo(segments.get(7).getId());
    }

    private static SpeakerCorrectionService.RunWork work(TranscriptSegment... segments) {
        return new SpeakerCorrectionService.RunWork("run", "owner", "task", 1, SceneType.MEETING, "topic", List.of(segments), List.of(
                new TranscriptSpeaker("task", 1, "SPEAKER_0"), new TranscriptSpeaker("task", 1, "SPEAKER_1")));
    }
}
