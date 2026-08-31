package com.kareem.cortex;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Tolerant type-only fast cognitive wire parser. */
public final class FastCognitiveResultParser {
    private static final Pattern THINK = Pattern.compile("(?is)<think>(.*?)</think>");

    private FastCognitiveResultParser() {}

    public static CognitiveResult parse(String raw) throws CognitiveContractException {
        return parse(raw, "");
    }

    public static CognitiveResult parse(
            String raw,
            String groundedFallback
    ) throws CognitiveContractException {
        if (raw == null || raw.trim().isEmpty()) {
            throw new CognitiveContractException("Empty fast cognitive response");
        }

        try {
            JSONObject root = new JSONObject(extractJson(stripThinking(raw)));

            // Primary 7.1.1 contract: {"t":"TYPE"}. Keep only narrow transition support for the
            // immediately previous d/k wire so deployed/cached prompts cannot crash the parser.
            String type = clean(root.optString("t", "")).toUpperCase(Locale.ROOT);
            if (type.isEmpty()) {
                String d = clean(root.optString("d", "")).toUpperCase(Locale.ROOT);
                if ("D".equals(d) || "DERIVE".equals(d)) {
                    type = clean(root.optString("k", "")).toUpperCase(Locale.ROOT);
                } else {
                    type = d;
                }
            }

            // Older verbose results remain readable during staged migration.
            if (type.isEmpty() && root.has("disposition")) {
                return CognitiveResultParser.parse(raw);
            }
            if (type.isEmpty()) {
                throw new CognitiveContractException("Missing fast cognitive type");
            }

            Classification classification = classify(type);
            double confidence = root.has("c") && !root.isNull("c")
                    ? confidence(root.get("c"))
                    : defaultConfidence(classification);

            List<CognitiveItem> items = new ArrayList<>();
            if (classification.disposition == CognitiveDisposition.DERIVE) {
                String summary = root.has("s") && !root.isNull("s")
                        ? clean(root.optString("s", ""))
                        : groundedSummary(groundedFallback);
                if (summary.isEmpty()) {
                    throw new CognitiveContractException("Fast derived item has no grounded summary");
                }

                CognitiveKind kind = classification.kind;
                items.add(new CognitiveItem(
                        kind,
                        summary,
                        importanceFor(kind),
                        urgencyFor(kind),
                        "",
                        null,
                        kind == CognitiveKind.ACTION,
                        kind == CognitiveKind.WAITING,
                        kind == CognitiveKind.CONTENT
                ));
            }

            return new CognitiveResult(
                    classification.disposition,
                    confidence,
                    "fast_type_only",
                    items
            );
        } catch (CognitiveContractException error) {
            throw error;
        } catch (Throwable error) {
            throw new CognitiveContractException("Invalid fast cognitive JSON", error);
        }
    }

    /** Telemetry provenance for confidence; policy defaults are never presented as model claims. */
    public static String confidenceSource(String raw) {
        if (raw == null || raw.trim().isEmpty()) return "POLICY_DEFAULT";
        try {
            JSONObject root = new JSONObject(extractJson(stripThinking(raw)));
            return (root.has("c") && !root.isNull("c"))
                    || (root.has("confidence") && !root.isNull("confidence"))
                    ? "MODEL"
                    : "POLICY_DEFAULT";
        } catch (Throwable ignored) {
            return "POLICY_DEFAULT";
        }
    }

    public static boolean hasNonEmptyThinking(String raw) {
        if (raw == null || raw.isEmpty()) return false;
        Matcher matcher = THINK.matcher(raw);
        boolean foundClosed = false;
        while (matcher.find()) {
            foundClosed = true;
            if (!matcher.group(1).trim().isEmpty()) return true;
        }
        if (foundClosed) return false;

        String lower = raw.toLowerCase(Locale.ROOT);
        int start = lower.indexOf("<think>");
        if (start < 0) return false;
        int contentStart = start + "<think>".length();
        int jsonStart = raw.indexOf('{', contentStart);
        String remainder = jsonStart >= 0
                ? raw.substring(contentStart, jsonStart)
                : raw.substring(contentStart);
        return !remainder.trim().isEmpty();
    }

    private static Classification classify(String type) throws CognitiveContractException {
        switch (clean(type).toUpperCase(Locale.ROOT)) {
            case "ACTION":
            case "AC":
                return derived(CognitiveKind.ACTION);

            case "WAITING":
            case "WA":
                return derived(CognitiveKind.WAITING);

            case "DECISION":
            case "DE":
                return derived(CognitiveKind.DECISION);

            case "EVENT":
            case "EV":
                return derived(CognitiveKind.EVENT);

            case "CONTENT":
            case "CO":
                return derived(CognitiveKind.CONTENT);

            case "REMINDER":
            case "RE":
                return derived(CognitiveKind.REMINDER);

            case "INSIGHT":
            case "IN":
                return derived(CognitiveKind.INSIGHT);

            case "MEMORY":
            case "ME":
                return derived(CognitiveKind.MEMORY);

            case "MESSAGE":
            case "MS":
                return derived(CognitiveKind.MESSAGE);

            case "CONTEXT":
            case "C":
                return new Classification(CognitiveDisposition.CONTEXT, null);

            case "IGNORE":
            case "I":
                return new Classification(CognitiveDisposition.IGNORE, null);

            case "REVIEW":
            case "R":
                return new Classification(CognitiveDisposition.REVIEW, null);

            default:
                throw new CognitiveContractException("Unknown fast cognitive type: " + type);
        }
    }

    private static Classification derived(CognitiveKind kind) {
        return new Classification(CognitiveDisposition.DERIVE, kind);
    }

    private static double defaultConfidence(Classification x) {
        switch (x.disposition) {
            case DERIVE:
                return 0.82;
            case CONTEXT:
                return 0.86;
            case IGNORE:
                return 0.70;
            case REVIEW:
            default:
                return 0.50;
        }
    }

    private static double confidence(Object value) throws CognitiveContractException {
        Object parsed = value;
        if (!(parsed instanceof Number)) {
            try {
                parsed = Double.parseDouble(String.valueOf(parsed));
            } catch (Throwable error) {
                throw new CognitiveContractException("Invalid fast confidence");
            }
        }
        double c = ((Number) parsed).doubleValue();
        if (Double.isNaN(c) || Double.isInfinite(c) || c < 0.0 || c > 100.0) {
            throw new CognitiveContractException("Fast confidence out of range");
        }
        return c <= 1.0 ? c : c / 100.0;
    }

    private static String groundedSummary(String source) {
        if (source == null) return "";
        String x = source.replace('\n', ' ').replace('\r', ' ').trim().replaceAll("\\s+", " ");
        if (x.length() > 180) x = x.substring(0, 180);
        return x.trim();
    }

    private static int importanceFor(CognitiveKind kind) {
        switch (kind) {
            case ACTION: return 70;
            case WAITING: return 65;
            case EVENT: return 70;
            case CONTENT: return 55;
            default: return 50;
        }
    }

    private static int urgencyFor(CognitiveKind kind) {
        switch (kind) {
            case ACTION: return 60;
            case WAITING: return 50;
            case EVENT: return 70;
            case CONTENT: return 35;
            default: return 40;
        }
    }

    private static String stripThinking(String raw) {
        return THINK.matcher(raw).replaceAll("").trim();
    }

    private static String extractJson(String raw) throws CognitiveContractException {
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end < start) {
            throw new CognitiveContractException("No JSON object in fast cognitive response");
        }
        return raw.substring(start, end + 1);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static final class Classification {
        final CognitiveDisposition disposition;
        final CognitiveKind kind;

        Classification(CognitiveDisposition disposition, CognitiveKind kind) {
            this.disposition = disposition;
            this.kind = kind;
        }
    }
}
