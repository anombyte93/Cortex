package com.kareem.cortex;

import java.util.ArrayList;
import java.util.List;

public final class CognitiveResultValidator {

    private static final int MAX_ITEMS = 5;
    private static final int MAX_SUMMARY = 240;
    private static final int MAX_REASON = 400;
    private static final int MAX_PERSON = 120;

    private CognitiveResultValidator() {}

    public static CognitiveResult validate(CognitiveResult raw) throws CognitiveContractException {
        if (raw == null) {
            throw new CognitiveContractException("Result is null");
        }

        if (raw.disposition == null) {
            throw new CognitiveContractException("Missing disposition");
        }

        if (Double.isNaN(raw.confidence) || Double.isInfinite(raw.confidence)) {
            throw new CognitiveContractException("Invalid confidence");
        }

        double confidence = clamp(raw.confidence, 0.0, 1.0);

        if (raw.items.size() > MAX_ITEMS) {
            throw new CognitiveContractException("Too many cognitive items");
        }

        List<CognitiveItem> validated = new ArrayList<>();
        for (CognitiveItem item : raw.items) {
            validated.add(validateItem(item));
        }

        if (raw.disposition == CognitiveDisposition.DERIVE && validated.isEmpty()) {
            throw new CognitiveContractException("DERIVE requires at least one item");
        }

        // IGNORE and CONTEXT may never sneak durable intelligence through the items array.
        if (raw.disposition == CognitiveDisposition.IGNORE
                || raw.disposition == CognitiveDisposition.CONTEXT) {
            validated.clear();
        }

        return new CognitiveResult(
                raw.disposition,
                confidence,
                truncate(raw.reason, MAX_REASON),
                validated
        );
    }

    private static CognitiveItem validateItem(CognitiveItem item) throws CognitiveContractException {
        if (item == null || item.kind == null) {
            throw new CognitiveContractException("Invalid cognitive item");
        }

        String summary = item.summary == null ? "" : item.summary.trim();
        if (summary.isEmpty()) {
            throw new CognitiveContractException("Derived item has no summary");
        }
        summary = truncate(summary, MAX_SUMMARY);

        String person = truncate(item.person, MAX_PERSON);
        boolean userAction = item.requiresUserAction;
        boolean followUp = item.requiresFollowUp;

        // Structural invariants.
        if (item.kind == CognitiveKind.ACTION) {
            userAction = true;
        }
        if (item.kind == CognitiveKind.WAITING) {
            followUp = true;
        }

        return new CognitiveItem(
                item.kind,
                summary,
                clamp(item.importance, 0, 100),
                clamp(item.urgency, 0, 100),
                person,
                item.dueAt,
                userAction,
                followUp,
                item.requiresContentExtraction
        );
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String truncate(String value, int maximum) {
        if (value == null) return "";
        String clean = value.trim();
        return clean.length() <= maximum ? clean : clean.substring(0, maximum);
    }
}
