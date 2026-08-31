package com.kareem.cortex;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class CognitiveInput {

    public final long signalId;
    public final SignalFamily family;
    public final String sourcePackage;
    public final String sourceApp;
    public final String sender;
    public final String latestText;
    public final List<CognitiveMessage> recentContext;
    public final long occurredAt;
    public final String timezone;
    public final String baselineDecision;

    public CognitiveInput(
            long signalId,
            SignalFamily family,
            String sourcePackage,
            String sourceApp,
            String sender,
            String latestText,
            List<CognitiveMessage> recentContext,
            long occurredAt,
            String timezone,
            String baselineDecision
    ) {
        this.signalId = signalId;
        this.family = family == null ? SignalFamily.UNKNOWN : family;
        this.sourcePackage = clean(sourcePackage);
        this.sourceApp = clean(sourceApp);
        this.sender = clean(sender);
        this.latestText = clean(latestText);
        this.recentContext = Collections.unmodifiableList(
                recentContext == null
                        ? Collections.emptyList()
                        : new ArrayList<>(recentContext)
        );
        this.occurredAt = occurredAt;
        this.timezone = clean(timezone);
        this.baselineDecision = clean(baselineDecision);
    }

    private static String clean(String s) {
        return s == null ? "" : s.trim();
    }
}
