package com.kareem.cortex;

import static org.junit.Assert.*;

import android.content.Context;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

@RunWith(AndroidJUnit4.class)
public final class CognitiveLatencyBenchmark {
    private static final String TAG = "CognitiveLatencyBenchmark";

    @Test
    public void warmAuthorityMeetsLatencyGateWithAndWithoutShadow() throws Exception {
        Context app = InstrumentationRegistry.getInstrumentation().getTargetContext().getApplicationContext();
        assertTrue("Qwen3-1.7B must be installed before benchmark", LocalModelManager.installed(app));

        boolean oldShadow = CognitiveFeatureFlags.shadowEnabled(app);
        try {
            warmModel(app);

            CognitiveFeatureFlags.setShadowEnabled(app, false);
            PhaseResult shadowOff = runPhase(app, cases(), false);
            assertGate("shadow_off", shadowOff);

            CognitiveFeatureFlags.setShadowEnabled(app, true);
            PhaseResult shadowOn = runPhase(app, cases(), true);
            assertGate("shadow_on", shadowOn);
            assertEquals("Shadow must never block authority", 0, shadowOn.shadowBlocksAuthority);
            assertEquals(
                    "Every contending shadow attempt should be SKIPPED_BUSY",
                    shadowOn.total,
                    shadowOn.shadowSkippedBusy
            );
        } finally {
            CognitiveFeatureFlags.setShadowEnabled(app, oldShadow);
        }
    }

    /**
     * This gate is explicitly a warm-authority benchmark. Warm the exact production prompt/parser
     * path with two non-corpus signals so model pages, tokenizer/chat template and native kernels
     * are resident before measured samples start. Cold-start behavior remains covered by the later
     * controlled real E2E gate and is intentionally not mixed into this warm latency distribution.
     */
    private static void warmModel(Context app) throws Exception {
        LocalQwenBrain brain = new LocalQwenBrain(app);
        List<CognitiveInput> warmups = Arrays.asList(
                new CognitiveInput(
                        0,
                        SignalFamily.COMMUNICATION,
                        "",
                        "Warmup",
                        "Other",
                        "Please review the sample before noon.",
                        Collections.emptyList(),
                        System.currentTimeMillis(),
                        "Africa/Cairo",
                        ""
                ),
                new CognitiveInput(
                        0,
                        SignalFamily.COMMUNICATION,
                        "",
                        "Warmup",
                        "Other",
                        "هأكدلك النتيجة بعد شوية",
                        Collections.emptyList(),
                        System.currentTimeMillis(),
                        "Africa/Cairo",
                        ""
                )
        );

        int index = 0;
        for (CognitiveInput input : warmups) {
            index++;
            LocalBrainRun run = brain.classifyWithTelemetry(
                    input,
                    LocalInferenceCoordinator.Priority.AUTHORITATIVE
            );
            Log.i(
                    TAG,
                    "warmup=" + index
                            + " total_ms=" + run.totalMs
                            + " native_total_ms=" + run.nativeTotalMs
                            + " model_load_ms=" + run.modelLoadMs
                            + " generation_ms=" + run.generationMs
                            + " prompt_chars=" + run.promptChars
                            + " tokens=" + run.tokensGenerated
                            + " raw=" + compactRaw(run.rawOutput)
            );
        }
    }

    private static PhaseResult runPhase(
            Context app,
            List<Case> cases,
            boolean contendWithShadow
    ) throws Exception {
        LocalQwenBrain brain = new LocalQwenBrain(app);
        ArrayList<Long> queue = new ArrayList<>();
        ArrayList<Long> total = new ArrayList<>();
        ArrayList<Integer> generated = new ArrayList<>();

        int valid = 0;
        int classification = 0;
        int canaryEligible = 0;
        int nonEmptyThinking = 0;
        int shadowSkippedBusy = 0;
        int shadowBlocksAuthority = 0;

        for (Case test : cases) {
            long caseStarted = System.currentTimeMillis();
            try {
                LocalBrainRun run;

                if (!contendWithShadow) {
                    run = brain.classifyWithTelemetry(
                            test.input,
                            LocalInferenceCoordinator.Priority.AUTHORITATIVE
                    );
                } else {
                    FutureTask<LocalBrainRun> authority = new FutureTask<>(
                            () -> brain.classifyWithTelemetry(
                                    test.input,
                                    LocalInferenceCoordinator.Priority.AUTHORITATIVE
                            )
                    );
                    Thread thread = new Thread(authority, "cortex-benchmark-authority");
                    thread.start();

                    long waitDeadline = System.currentTimeMillis() + 1_000L;
                    while (LocalInferenceCoordinator.authorityPendingCount() <= 0
                            && !authority.isDone()
                            && System.currentTimeMillis() < waitDeadline) {
                        Thread.sleep(2L);
                    }
                    assertTrue(
                            "Authority did not reach coordinator in benchmark window",
                            LocalInferenceCoordinator.authorityPendingCount() > 0 || authority.isDone()
                    );

                    long beforeShadow = System.currentTimeMillis();
                    try {
                        brain.classifyWithTelemetry(
                                test.input,
                                LocalInferenceCoordinator.Priority.SHADOW
                        );
                        shadowBlocksAuthority++;
                    } catch (BrainException expectedBusy) {
                        if (LocalInferenceCoordinator.isBusy(expectedBusy)) {
                            shadowSkippedBusy++;
                        } else {
                            throw expectedBusy;
                        }
                    }
                    long shadowElapsed = System.currentTimeMillis() - beforeShadow;
                    if (shadowElapsed >= 500L) shadowBlocksAuthority++;

                    run = authority.get(60, TimeUnit.SECONDS);
                }

                boolean matched = matches(run.result, test);
                boolean eligible = canaryEligible(run.result);
                valid++;
                if (matched) classification++;
                if (eligible) canaryEligible++;
                if (FastCognitiveResultParser.hasNonEmptyThinking(run.rawOutput)) nonEmptyThinking++;

                queue.add(run.queueWaitMs);
                total.add(run.totalMs);
                generated.add(run.tokensGenerated);

                Log.i(
                        TAG,
                        (contendWithShadow ? "shadow_on" : "shadow_off")
                                + " case=" + test.name
                                + " valid=true"
                                + " matched=" + matched
                                + " expected_disposition=" + test.disposition
                                + " expected_kind=" + (test.kind == null ? "NONE" : test.kind.name())
                                + " actual_disposition=" + run.result.disposition
                                + " actual_kind=" + actualKind(run.result)
                                + " raw=" + compactRaw(run.rawOutput)
                                + " queue_wait_ms=" + run.queueWaitMs
                                + " native_total_ms=" + run.nativeTotalMs
                                + " total_ms=" + run.totalMs
                                + " model_load_ms=" + run.modelLoadMs
                                + " generation_ms=" + run.generationMs
                                + " prompt_chars=" + run.promptChars
                                + " tokens=" + run.tokensGenerated
                                + " tok_s=" + run.tokensPerSecond
                                + " cache_hit=" + run.cacheHit
                                + " wire_schema=" + run.wireSchema
                                + " confidence=" + run.result.confidence
                                + " confidence_source=" + run.confidenceSource
                                + " canary_eligible=" + eligible
                );
            } catch (Throwable error) {
                long elapsed = Math.max(0L, System.currentTimeMillis() - caseStarted);
                queue.add(0L);
                total.add(elapsed);
                generated.add(0);
                Log.e(
                        TAG,
                        (contendWithShadow ? "shadow_on" : "shadow_off")
                                + " case=" + test.name
                                + " valid=false"
                                + " expected_disposition=" + test.disposition
                                + " expected_kind=" + (test.kind == null ? "NONE" : test.kind.name())
                                + " queue_wait_ms=0"
                                + " total_ms=" + elapsed
                                + " tokens=0"
                                + " canary_eligible=false"
                                + " error=" + error.getClass().getSimpleName()
                                + ":" + String.valueOf(error.getMessage())
                );
            }
        }

        PhaseResult result = new PhaseResult(
                cases.size(),
                valid,
                classification,
                canaryEligible,
                nonEmptyThinking,
                percentileLong(queue, 50),
                percentileLong(queue, 95),
                percentileLong(total, 50),
                percentileLong(total, 95),
                percentileInt(generated, 50),
                maxInt(generated),
                shadowSkippedBusy,
                shadowBlocksAuthority
        );

        Log.i(TAG, result.describe(contendWithShadow ? "shadow_on" : "shadow_off"));
        return result;
    }

    private static void assertGate(String phase, PhaseResult result) {
        // Keep all acceptance thresholds unchanged. Better diagnostics must never turn a red gate
        // green by weakening correctness or latency requirements.
        assertEquals(phase + ": valid JSON/contract must be 100% for a 10-case corpus", result.total, result.valid);
        assertEquals(phase + ": every benchmark result must be canary-eligible", result.total, result.canaryEligible);
        assertEquals(phase + ": actual reasoning tokens are not allowed", 0, result.nonEmptyThinking);
        assertTrue(phase + ": authority queue P95 must be <500ms, got " + result.queueP95, result.queueP95 < 500L);
        assertTrue(phase + ": generated-token P50 must be <=70, got " + result.tokensP50, result.tokensP50 <= 70);
        assertTrue(phase + ": generated-token max must be <=96, got " + result.tokensMax, result.tokensMax <= 96);

        assertEquals(phase + ": classification regression", result.total, result.classification);
        assertTrue(phase + ": authority total P50 must be <=8s, got " + result.totalP50, result.totalP50 <= 8_000L);
        assertTrue(phase + ": authority total P95 must be <=12s, got " + result.totalP95, result.totalP95 <= 12_000L);
    }

    private static boolean matches(CognitiveResult result, Case test) {
        if (result == null || result.disposition != test.disposition) return false;
        if (test.kind == null) return true;
        for (CognitiveItem item : result.items) {
            if (item != null && item.kind == test.kind) return true;
        }
        return false;
    }

    private static String actualKind(CognitiveResult result) {
        if (result == null || result.items == null || result.items.isEmpty()) return "NONE";
        for (CognitiveItem item : result.items) {
            if (item != null && item.kind != null) return item.kind.name();
        }
        return "NONE";
    }

    private static String compactRaw(String raw) {
        if (raw == null) return "<null>";
        String compact = raw
                .replace('\n', ' ')
                .replace('\r', ' ')
                .replace('\t', ' ')
                .replaceAll("\\s+", " ")
                .trim();
        if (compact.length() > 120) compact = compact.substring(0, 120) + "…";
        return compact.isEmpty() ? "<empty>" : compact;
    }

    private static boolean canaryEligible(CognitiveResult result) {
        if (result == null || result.disposition == null) return false;
        switch (result.disposition) {
            case DERIVE:
                return result.confidence >= 0.80
                        && result.items != null
                        && !result.items.isEmpty();
            case CONTEXT:
                return result.confidence >= 0.85;
            case IGNORE:
            case REVIEW:
            default:
                return false;
        }
    }

    private static List<Case> cases() {
        return Arrays.asList(
                test("action_ar", SignalFamily.COMMUNICATION, "WhatsApp", "Ahmed",
                        "ممكن تبعتلي الـ PDF النهائي النهاردة؟",
                        CognitiveDisposition.DERIVE, CognitiveKind.ACTION),
                test("action_en", SignalFamily.COMMUNICATION, "Messages", "Mona",
                        "Please call me before 5 PM to confirm the booking.",
                        CognitiveDisposition.DERIVE, CognitiveKind.ACTION),

                test("waiting_ar", SignalFamily.COMMUNICATION, "WhatsApp", "Mona",
                        "هبعتلك النسخة المعدلة بكرة",
                        CognitiveDisposition.DERIVE, CognitiveKind.WAITING),
                test("waiting_en", SignalFamily.COMMUNICATION, "Email", "Client",
                        "I will send you the signed contract tonight.",
                        CognitiveDisposition.DERIVE, CognitiveKind.WAITING),

                test("event", SignalFamily.EVENT, "Calendar", "",
                        "Dentist appointment tomorrow at 4 PM",
                        CognitiveDisposition.DERIVE, CognitiveKind.EVENT),

                test("content", SignalFamily.CONTENT, "WhatsApp", "Ahmed",
                        "Ahmed sent a voice message",
                        CognitiveDisposition.DERIVE, CognitiveKind.CONTENT),

                test("context_thanks", SignalFamily.COMMUNICATION, "WhatsApp", "Ahmed",
                        "شكراً يا كريم",
                        CognitiveDisposition.CONTEXT, null),
                test("context_ack", SignalFamily.COMMUNICATION, "Messages", "Mona",
                        "تمام، وصلت",
                        CognitiveDisposition.CONTEXT, null),

                test("mixed_action", SignalFamily.COMMUNICATION, "WhatsApp", "Ahmed",
                        "ممكن send me the final DWG النهاردة؟",
                        CognitiveDisposition.DERIVE, CognitiveKind.ACTION),
                test("mixed_waiting", SignalFamily.COMMUNICATION, "WhatsApp", "Mona",
                        "هبعتلك the revised BOQ بكرة morning",
                        CognitiveDisposition.DERIVE, CognitiveKind.WAITING)
        );
    }

    private static Case test(
            String name,
            SignalFamily family,
            String app,
            String sender,
            String text,
            CognitiveDisposition disposition,
            CognitiveKind kind
    ) {
        CognitiveInput input = new CognitiveInput(
                0,
                family,
                "",
                app,
                sender,
                text,
                Collections.emptyList(),
                System.currentTimeMillis(),
                "Africa/Cairo",
                ""
        );
        return new Case(name, input, disposition, kind);
    }

    private static long percentileLong(List<Long> values, int percentile) {
        ArrayList<Long> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int index = Math.max(0, Math.min(
                sorted.size() - 1,
                (int) Math.ceil((percentile / 100.0) * sorted.size()) - 1
        ));
        return sorted.get(index);
    }

    private static int percentileInt(List<Integer> values, int percentile) {
        ArrayList<Integer> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int index = Math.max(0, Math.min(
                sorted.size() - 1,
                (int) Math.ceil((percentile / 100.0) * sorted.size()) - 1
        ));
        return sorted.get(index);
    }

    private static int maxInt(List<Integer> values) {
        int max = 0;
        for (int value : values) max = Math.max(max, value);
        return max;
    }

    private static final class Case {
        final String name;
        final CognitiveInput input;
        final CognitiveDisposition disposition;
        final CognitiveKind kind;

        Case(
                String name,
                CognitiveInput input,
                CognitiveDisposition disposition,
                CognitiveKind kind
        ) {
            this.name = name;
            this.input = input;
            this.disposition = disposition;
            this.kind = kind;
        }
    }

    private static final class PhaseResult {
        final int total;
        final int valid;
        final int classification;
        final int canaryEligible;
        final int nonEmptyThinking;
        final long queueP50;
        final long queueP95;
        final long totalP50;
        final long totalP95;
        final int tokensP50;
        final int tokensMax;
        final int shadowSkippedBusy;
        final int shadowBlocksAuthority;

        PhaseResult(
                int total,
                int valid,
                int classification,
                int canaryEligible,
                int nonEmptyThinking,
                long queueP50,
                long queueP95,
                long totalP50,
                long totalP95,
                int tokensP50,
                int tokensMax,
                int shadowSkippedBusy,
                int shadowBlocksAuthority
        ) {
            this.total = total;
            this.valid = valid;
            this.classification = classification;
            this.canaryEligible = canaryEligible;
            this.nonEmptyThinking = nonEmptyThinking;
            this.queueP50 = queueP50;
            this.queueP95 = queueP95;
            this.totalP50 = totalP50;
            this.totalP95 = totalP95;
            this.tokensP50 = tokensP50;
            this.tokensMax = tokensMax;
            this.shadowSkippedBusy = shadowSkippedBusy;
            this.shadowBlocksAuthority = shadowBlocksAuthority;
        }

        String describe(String phase) {
            return phase
                    + " valid=" + valid + "/" + total
                    + " classification=" + classification + "/" + total
                    + " canary_eligible=" + canaryEligible + "/" + total
                    + " think=" + nonEmptyThinking
                    + " queue_p50=" + queueP50
                    + " queue_p95=" + queueP95
                    + " total_p50=" + totalP50
                    + " total_p95=" + totalP95
                    + " tokens_p50=" + tokensP50
                    + " tokens_max=" + tokensMax
                    + " shadow_skipped_busy=" + shadowSkippedBusy
                    + " shadow_blocks_authority=" + shadowBlocksAuthority;
        }
    }
}
