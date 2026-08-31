package com.kareem.cortex;

import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Narrow post-model semantic reconciliation for facts that are already explicit in trusted input
 * structure or in the surface form of the latest communication.
 *
 * <p>The local model still runs first and remains the authority for ambiguous language. This guard
 * only corrects deterministic contradictions such as an EVENT family being labelled ACTION, an
 * acknowledgement-only message being labelled durable work, or the sender's explicit promise being
 * inverted into an action for the user. Keeping this layer narrow prevents a small greedy model's
 * class-collapse from becoming durable Cortex state without pretending that policy-derived
 * confidence came from the model.</p>
 */
public final class FastCognitiveSemanticGuard {
    private static final Set<String> ACK_ONLY = ackOnly();

    private FastCognitiveSemanticGuard() {}

    public static Outcome reconcile(CognitiveInput input, CognitiveResult modelResult) {
        if (input == null || modelResult == null) {
            return new Outcome(modelResult, false, "");
        }

        if (input.family == SignalFamily.EVENT) {
            return reconcileDerived(input, modelResult, CognitiveKind.EVENT, 0.98, "family_event");
        }
        if (input.family == SignalFamily.CONTENT) {
            return reconcileDerived(input, modelResult, CognitiveKind.CONTENT, 0.96, "family_content");
        }

        if (input.family != SignalFamily.COMMUNICATION) {
            return new Outcome(modelResult, false, "");
        }

        String normalized = normalize(input.latestText);
        if (normalized.isEmpty()) {
            return new Outcome(modelResult, false, "");
        }

        if (ACK_ONLY.contains(normalized)) {
            return reconcileContext(modelResult, 0.92, "ack_only");
        }

        if (senderPromisesFutureAction(normalized)) {
            return reconcileDerived(input, modelResult, CognitiveKind.WAITING, 0.92, "sender_future_promise");
        }

        if (senderExplicitlyRequestsUserAction(normalized)) {
            return reconcileDerived(input, modelResult, CognitiveKind.ACTION, 0.92, "explicit_user_request");
        }

        return new Outcome(modelResult, false, "");
    }

    private static Outcome reconcileDerived(
            CognitiveInput input,
            CognitiveResult modelResult,
            CognitiveKind kind,
            double confidence,
            String rule
    ) {
        if (matches(modelResult, CognitiveDisposition.DERIVE, kind)) {
            return new Outcome(modelResult, false, "");
        }

        String summary = groundedSummary(input.latestText);
        if (summary.isEmpty()) {
            return new Outcome(modelResult, false, "");
        }

        CognitiveItem item = new CognitiveItem(
                kind,
                summary,
                importanceFor(kind),
                urgencyFor(kind),
                "",
                null,
                kind == CognitiveKind.ACTION,
                kind == CognitiveKind.WAITING,
                kind == CognitiveKind.CONTENT
        );
        CognitiveResult corrected = new CognitiveResult(
                CognitiveDisposition.DERIVE,
                confidence,
                "fast_semantic_guard:" + rule,
                Collections.singletonList(item)
        );
        return new Outcome(corrected, true, rule);
    }

    private static Outcome reconcileContext(
            CognitiveResult modelResult,
            double confidence,
            String rule
    ) {
        if (matches(modelResult, CognitiveDisposition.CONTEXT, null)) {
            return new Outcome(modelResult, false, "");
        }
        CognitiveResult corrected = new CognitiveResult(
                CognitiveDisposition.CONTEXT,
                confidence,
                "fast_semantic_guard:" + rule,
                Collections.emptyList()
        );
        return new Outcome(corrected, true, rule);
    }

    private static boolean matches(
            CognitiveResult result,
            CognitiveDisposition disposition,
            CognitiveKind kind
    ) {
        if (result == null || result.disposition != disposition) return false;
        if (kind == null) return true;
        if (result.items == null) return false;
        for (CognitiveItem item : result.items) {
            if (item != null && item.kind == kind) return true;
        }
        return false;
    }

    private static boolean senderPromisesFutureAction(String normalized) {
        String x = normalized;
        if (x.startsWith("انا ")) x = x.substring(4).trim();

        if (startsWithAny(x,
                "i will ",
                "i ll ",
                "i am going to ",
                "i m going to ")) {
            return true;
        }

        return startsWithAny(x,
                "هبعت",
                "هارسل",
                "هرسل",
                "هاكد",
                "هراجع",
                "هرجع",
                "هكلم",
                "هقول",
                "هجهز",
                "هخلص",
                "هعمل",
                "هشارك",
                "هسلم",
                "هوصل",
                "هرد",
                "هشوف",
                "هتابع",
                "هرفع",
                "هعدل",
                "هرتب",
                "هحجز",
                "هدفع",
                "هحول",
                "هكمل");
    }

    private static boolean senderExplicitlyRequestsUserAction(String normalized) {
        return startsWithAny(normalized,
                "please ",
                "can you ",
                "could you ",
                "would you ",
                "kindly ",
                "send me ",
                "call me ",
                "ممكن ",
                "لو سمحت ",
                "ابعتلي ",
                "ابعت لي ");
    }

    private static boolean startsWithAny(String text, String... prefixes) {
        for (String prefix : prefixes) {
            if (text.startsWith(prefix)) return true;
        }
        return false;
    }

    private static String groundedSummary(String source) {
        if (source == null) return "";
        String x = source
                .replace('\n', ' ')
                .replace('\r', ' ')
                .trim()
                .replaceAll("\\s+", " ");
        if (x.length() > 180) x = x.substring(0, 180);
        return x.trim();
    }

    private static String normalize(String value) {
        if (value == null) return "";
        return value
                .toLowerCase(Locale.ROOT)
                .replaceAll("[\\u064B-\\u065F\\u0670]", "")
                .replace('أ', 'ا')
                .replace('إ', 'ا')
                .replace('آ', 'ا')
                .replace('ى', 'ي')
                .replaceAll("[^\\p{L}\\p{N}]+", " ")
                .trim()
                .replaceAll("\\s+", " ");
    }

    private static Set<String> ackOnly() {
        HashSet<String> out = new HashSet<>();
        Collections.addAll(out,
                "شكرا",
                "شكرا يا كريم",
                "تمام",
                "تمام وصلت",
                "وصلت",
                "تسلم",
                "thanks",
                "thank you",
                "got it",
                "noted",
                "ok",
                "okay");
        return Collections.unmodifiableSet(out);
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

    public static final class Outcome {
        public final CognitiveResult result;
        public final boolean overridden;
        public final String rule;

        Outcome(CognitiveResult result, boolean overridden, String rule) {
            this.result = result;
            this.overridden = overridden;
            this.rule = rule == null ? "" : rule;
        }
    }
}
