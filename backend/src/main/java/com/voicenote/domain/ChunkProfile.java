package com.voicenote.domain;

/** Scene-aware defaults for retrieval granularity and context expansion. */
public enum ChunkProfile {
    SHORT_DOCUMENT(1_000, 1_000, false),
    INTERVIEW_QA(600, 1_000, false),
    MEETING_DISCUSSION(650, 1_000, true),
    MONOLOGUE(850, 1_200, true),
    CONVERSATION(600, 1_000, true);

    private final int targetTokens;
    private final int maximumTokens;
    private final boolean expandNeighbours;

    ChunkProfile(int targetTokens, int maximumTokens, boolean expandNeighbours) {
        this.targetTokens = targetTokens;
        this.maximumTokens = maximumTokens;
        this.expandNeighbours = expandNeighbours;
    }

    public int targetTokens() { return targetTokens; }
    public int maximumTokens() { return maximumTokens; }
    public boolean expandNeighbours() { return expandNeighbours; }
}
