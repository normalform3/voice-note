package com.voicenote.provider;

public class ProviderException extends RuntimeException {
    public enum Kind { RETRYABLE_REJECTION, FINAL_REJECTION, AMBIGUOUS_SUBMISSION }
    private final Kind kind;
    private final String code;
    public ProviderException(Kind kind, String code, String message) { super(message); this.kind = kind; this.code = code; }
    public Kind getKind() { return kind; }
    public String getCode() { return code; }
}
