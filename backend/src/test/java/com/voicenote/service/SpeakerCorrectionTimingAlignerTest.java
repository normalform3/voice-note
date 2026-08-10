package com.voicenote.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voicenote.domain.RawTranscriptDocument;
import com.voicenote.domain.SegmentTimingSource;
import com.voicenote.domain.TranscriptSegment;
import com.voicenote.repository.RawTranscriptDocumentRepository;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SpeakerCorrectionTimingAlignerTest {
    private final RawTranscriptDocumentRepository documents = mock(RawTranscriptDocumentRepository.class);
    private final ObjectStorage storage = mock(ObjectStorage.class);
    private final SpeakerCorrectionTimingAligner aligner = new SpeakerCorrectionTimingAligner(documents, storage, new ObjectMapper());

    @Test
    void alignsSplitPartsToProviderWordTimes() {
        TranscriptSegment source = new TranscriptSegment("task", 1, 0, "SPEAKER_0", 0, 2_000, "你好回答");
        RawTranscriptDocument raw = new RawTranscriptDocument("owner", "task", 1, "provider", "raw.json", "a".repeat(64), "", 1);
        when(documents.findByOwnerIdAndTranscriptionTaskIdAndTranscriptVersion("owner", "task", 1)).thenReturn(Optional.of(raw));
        String json = "{\"transcripts\":[{\"sentences\":[{\"text\":\"你好回答\",\"words\":[" +
                "{\"text\":\"你好\",\"begin_time\":100,\"end_time\":500},{\"text\":\"回答\",\"begin_time\":700,\"end_time\":1500}]}]}]}";
        when(storage.get("raw.json")).thenReturn(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));

        var parts = aligner.align("owner", "task", 1, source, List.of(
                new SpeakerCorrectionTimingAligner.TextPart("SPEAKER_0", "你好"),
                new SpeakerCorrectionTimingAligner.TextPart("SPEAKER_1", "回答")));

        assertThat(parts).extracting(SpeakerCorrectionTimingAligner.TimedPart::timingSource)
                .containsOnly(SegmentTimingSource.WORD_ALIGNED);
        assertThat(parts.get(0).startMs()).isEqualTo(100);
        assertThat(parts.get(1).startMs()).isEqualTo(700);
    }

    @Test
    void fallsBackToProportionalTimesWhenWordsAreUnavailable() {
        TranscriptSegment source = new TranscriptSegment("task", 1, 0, "SPEAKER_0", 1_000, 3_000, "甲乙丙丁");
        when(documents.findByOwnerIdAndTranscriptionTaskIdAndTranscriptVersion("owner", "task", 1)).thenReturn(Optional.empty());

        var parts = aligner.align("owner", "task", 1, source, List.of(
                new SpeakerCorrectionTimingAligner.TextPart("SPEAKER_0", "甲乙"),
                new SpeakerCorrectionTimingAligner.TextPart("SPEAKER_1", "丙丁")));

        assertThat(parts).extracting(SpeakerCorrectionTimingAligner.TimedPart::timingSource)
                .containsOnly(SegmentTimingSource.PROPORTIONAL);
        assertThat(parts.get(0).endMs()).isEqualTo(2_000);
        assertThat(parts.get(1).startMs()).isEqualTo(2_000);
    }
}
