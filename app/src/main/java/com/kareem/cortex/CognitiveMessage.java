package com.kareem.cortex;

public final class CognitiveMessage {

    public final String direction;
    public final String sender;
    public final String text;
    public final long occurredAt;
    public final boolean sensitiveRedacted;

    public CognitiveMessage(
            String direction,
            String sender,
            String text,
            long occurredAt,
            boolean sensitiveRedacted
    ) {
        this.direction = clean(direction);
        this.sender = clean(sender);
        this.text = clean(text);
        this.occurredAt = occurredAt;
        this.sensitiveRedacted = sensitiveRedacted;
    }

    private static String clean(String s) {
        return s == null ? "" : s.trim();
    }
}
