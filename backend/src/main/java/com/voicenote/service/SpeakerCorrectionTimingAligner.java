package com.voicenote.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voicenote.domain.RawTranscriptDocument;
import com.voicenote.domain.SegmentTimingSource;
import com.voicenote.domain.TranscriptSegment;
import com.voicenote.repository.RawTranscriptDocumentRepository;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

@Component
public class SpeakerCorrectionTimingAligner {
    private final RawTranscriptDocumentRepository rawDocuments;
    private final ObjectStorage storage;
    private final ObjectMapper mapper;

    public SpeakerCorrectionTimingAligner(RawTranscriptDocumentRepository rawDocuments, ObjectStorage storage, ObjectMapper mapper) {
        this.rawDocuments = rawDocuments; this.storage = storage; this.mapper = mapper;
    }

    public List<TimedPart> align(String ownerId, String taskId, int transcriptVersion, TranscriptSegment source, List<TextPart> parts) {
        List<WordTime> words = loadWords(ownerId, taskId, transcriptVersion, source.getSegmentIndex());
        List<TimedPart> aligned = alignToWords(source, parts, words);
        return aligned == null ? proportional(source, parts) : aligned;
    }

    private List<WordTime> loadWords(String ownerId, String taskId, int version, int segmentIndex) {
        RawTranscriptDocument document = rawDocuments.findByOwnerIdAndTranscriptionTaskIdAndTranscriptVersion(ownerId, taskId, version).orElse(null);
        if (document == null) return List.of();
        try (InputStream input = storage.get(document.getResultObjectKey())) {
            JsonNode root = mapper.readTree(input); List<JsonNode> sentences = new ArrayList<>();
            for (JsonNode transcript : root.path("transcripts")) for (JsonNode sentence : transcript.path("sentences")) sentences.add(sentence);
            if (segmentIndex < 0 || segmentIndex >= sentences.size()) return List.of();
            JsonNode sentence = sentences.get(segmentIndex); JsonNode wordNodes = sentence.path("words");
            if (!wordNodes.isArray() || wordNodes.isEmpty()) return List.of();
            String sentenceText = sentence.path("text").asText(""); int cursor = 0; List<WordTime> output = new ArrayList<>();
            for (JsonNode word : wordNodes) {
                String text = word.path("text").asText("");
                if (text.isEmpty()) text = word.path("word").asText("");
                if (text.isEmpty()) continue;
                int start = sentenceText.indexOf(text, cursor); if (start < 0) return List.of();
                int end = start + text.length(); long beginMs = word.path("begin_time").asLong(-1); long endMs = word.path("end_time").asLong(-1);
                if (beginMs < 0 || endMs < beginMs) return List.of();
                output.add(new WordTime(start, end, beginMs, endMs)); cursor = end;
            }
            return output;
        } catch (Exception ignored) { return List.of(); }
    }

    private static List<TimedPart> alignToWords(TranscriptSegment source, List<TextPart> parts, List<WordTime> words) {
        if (words.isEmpty()) return null;
        List<TimedPart> output = new ArrayList<>(); int cursor = 0; long previousEnd = source.getStartMs();
        for (TextPart part : parts) {
            int localStart = cursor; int localEnd = cursor + part.text().length();
            int rootStart = source.getSourceStartOffset() + localStart; int rootEnd = source.getSourceStartOffset() + localEnd;
            WordTime first = words.stream().filter(word -> word.endOffset() > rootStart).findFirst().orElse(null);
            WordTime last = null;
            for (WordTime word : words) if (word.startOffset() < rootEnd) last = word;
            if (first == null || last == null || first.beginMs() > last.endMs()) return null;
            long startMs = Math.max(source.getStartMs(), first.beginMs()); long endMs = Math.min(source.getEndMs(), last.endMs());
            if (startMs < previousEnd || endMs < startMs) return null;
            output.add(new TimedPart(localStart, localEnd, part.speakerId(), part.text(), startMs, endMs, SegmentTimingSource.WORD_ALIGNED));
            cursor = localEnd; previousEnd = endMs;
        }
        return cursor == source.getTextContent().length() ? output : null;
    }

    private static List<TimedPart> proportional(TranscriptSegment source, List<TextPart> parts) {
        long duration = Math.max(0, source.getEndMs() - source.getStartMs()); int length = source.getTextContent().length();
        List<TimedPart> output = new ArrayList<>(); int cursor = 0; long previousEnd = source.getStartMs();
        for (int index = 0; index < parts.size(); index++) {
            TextPart part = parts.get(index); int startOffset = cursor; int endOffset = cursor + part.text().length();
            long startMs = index == 0 ? source.getStartMs() : previousEnd;
            long endMs = index == parts.size() - 1 ? source.getEndMs() : source.getStartMs() + Math.round(duration * (endOffset / (double) Math.max(1, length)));
            endMs = Math.max(startMs, Math.min(source.getEndMs(), endMs));
            output.add(new TimedPart(startOffset, endOffset, part.speakerId(), part.text(), startMs, endMs, SegmentTimingSource.PROPORTIONAL));
            cursor = endOffset; previousEnd = endMs;
        }
        return output;
    }

    public record TextPart(String speakerId, String text) { }
    public record TimedPart(int startOffset, int endOffset, String speakerId, String text, long startMs, long endMs, SegmentTimingSource timingSource) { }
    private record WordTime(int startOffset, int endOffset, long beginMs, long endMs) { }
}
