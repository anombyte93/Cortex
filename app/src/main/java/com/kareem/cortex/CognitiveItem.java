package com.kareem.cortex;

public final class CognitiveItem {

    public final CognitiveKind kind;
    public final String summary;
    public final int importance;
    public final int urgency;
    public final String person;
    /** Unix epoch milliseconds, null when unknown. */
    public final Long dueAt;
    public final boolean requiresUserAction;
    public final boolean requiresFollowUp;
    public final boolean requiresContentExtraction;

    public CognitiveItem(
            CognitiveKind kind,
            String summary,
            int importance,
            int urgency,
            String person,
            Long dueAt,
            boolean requiresUserAction,
            boolean requiresFollowUp,
            boolean requiresContentExtraction
    ) {
        this.kind = kind;
        this.summary = summary == null ? "" : summary.trim();
        this.importance = importance;
        this.urgency = urgency;
        this.person = person == null ? "" : person.trim();
        this.dueAt = dueAt;
        this.requiresUserAction = requiresUserAction;
        this.requiresFollowUp = requiresFollowUp;
        this.requiresContentExtraction = requiresContentExtraction;
    }
}
