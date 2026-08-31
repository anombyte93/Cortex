package com.kareem.cortex;

import android.content.Context;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class CognitiveV2Harness {

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    private CognitiveV2Harness() {}

    public interface Callback {
        void complete(Report report);
    }

    public static void runAsync(Context context, Callback callback) {
        Context app = context.getApplicationContext();
        EXECUTOR.execute(() -> {
            Report report = run(app);
            if (callback != null) callback.complete(report);
        });
    }

    public static Report run(Context context) {
        LocalQwenBrain brain = new LocalQwenBrain(context);
        List<TestCase> cases = corpus();
        List<TestResult> results = new ArrayList<>();
        int passed = 0;

        for (TestCase test : cases) {
            try {
                LocalBrainRun run = brain.classifyWithTelemetry(test.input);
                boolean dispositionOk = run.result.disposition == test.expectedDisposition;
                boolean kindOk = test.expectedKind == null
                        || containsKind(run.result, test.expectedKind);
                boolean ok = dispositionOk && kindOk;
                if (ok) passed++;
                results.add(TestResult.success(test.name, ok, run));
            } catch (Throwable t) {
                results.add(TestResult.failure(
                        test.name,
                        t.getClass().getSimpleName() + ": " + safe(t.getMessage())
                ));
            }
        }

        return new Report(passed, cases.size(), results);
    }

    private static boolean containsKind(CognitiveResult result, CognitiveKind expected) {
        for (CognitiveItem item : result.items) {
            if (item.kind == expected) return true;
        }
        return false;
    }

    private static List<TestCase> corpus() {
        List<TestCase> x = new ArrayList<>();

        x.add(test(
                "battery_noise",
                SignalFamily.SYSTEM,
                "Android System",
                "",
                "Charging · 84%",
                CognitiveDisposition.IGNORE,
                null
        ));

        x.add(test(
                "media_noise",
                SignalFamily.SYSTEM,
                "Spotify",
                "",
                "Now playing: The Weeknd",
                CognitiveDisposition.IGNORE,
                null
        ));

        x.add(test(
                "ordinary_message",
                SignalFamily.COMMUNICATION,
                "WhatsApp",
                "Ahmed",
                "شكراً يا باشا",
                CognitiveDisposition.CONTEXT,
                null
        ));

        x.add(test(
                "action_ar",
                SignalFamily.COMMUNICATION,
                "WhatsApp",
                "Ahmed",
                "ممكن تبعتلي الـ PDF النهائي النهاردة؟",
                CognitiveDisposition.DERIVE,
                CognitiveKind.ACTION
        ));

        x.add(test(
                "waiting_ar",
                SignalFamily.COMMUNICATION,
                "WhatsApp",
                "Mona",
                "هبعتلك النسخة المعدلة بكرة",
                CognitiveDisposition.DERIVE,
                CognitiveKind.WAITING
        ));

        x.add(test(
                "event",
                SignalFamily.EVENT,
                "Calendar",
                "",
                "Dentist tomorrow at 4 PM",
                CognitiveDisposition.DERIVE,
                CognitiveKind.EVENT
        ));

        x.add(test(
                "voice_note",
                SignalFamily.CONTENT,
                "WhatsApp",
                "Ahmed",
                "Ahmed sent a voice message",
                CognitiveDisposition.DERIVE,
                CognitiveKind.CONTENT
        ));

        x.add(test(
                "instagram_reel",
                SignalFamily.SOCIAL,
                "Instagram",
                "Sara",
                "Sara sent you a reel",
                CognitiveDisposition.DERIVE,
                CognitiveKind.CONTENT
        ));

        x.add(test(
                "decision",
                SignalFamily.COMMUNICATION,
                "WhatsApp",
                "Client",
                "تمام، نعتمد التصميم الجديد",
                CognitiveDisposition.DERIVE,
                CognitiveKind.DECISION
        ));

        x.add(test(
                "delivery",
                SignalFamily.DELIVERY,
                "Amazon",
                "",
                "Your package will arrive today",
                CognitiveDisposition.DERIVE,
                CognitiveKind.EVENT
        ));

        return x;
    }

    private static TestCase test(
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
        return new TestCase(name, input, disposition, kind);
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    public static final class TestCase {
        final String name;
        final CognitiveInput input;
        final CognitiveDisposition expectedDisposition;
        final CognitiveKind expectedKind;

        TestCase(
                String name,
                CognitiveInput input,
                CognitiveDisposition disposition,
                CognitiveKind kind
        ) {
            this.name = name;
            this.input = input;
            this.expectedDisposition = disposition;
            this.expectedKind = kind;
        }
    }

    public static final class TestResult {
        public final String name;
        public final boolean passed;
        public final LocalBrainRun run;
        public final String error;

        private TestResult(String name, boolean passed, LocalBrainRun run, String error) {
            this.name = name;
            this.passed = passed;
            this.run = run;
            this.error = error;
        }

        static TestResult success(String name, boolean passed, LocalBrainRun run) {
            return new TestResult(name, passed, run, "");
        }

        static TestResult failure(String name, String error) {
            return new TestResult(name, false, null, error);
        }
    }

    public static final class Report {
        public final int passed;
        public final int total;
        public final List<TestResult> results;

        Report(int passed, int total, List<TestResult> results) {
            this.passed = passed;
            this.total = total;
            this.results = Collections.unmodifiableList(new ArrayList<>(results));
        }
    }
}
