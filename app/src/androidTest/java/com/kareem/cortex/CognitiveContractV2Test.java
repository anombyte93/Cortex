package com.kareem.cortex;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.Collections;
import org.junit.Test;

public final class CognitiveContractV2Test {

    @Test public void validWaitingContractParsesAndValidates() throws Exception {
        String modelOutput = "{"
                + "\"disposition\":\"DERIVE\","
                + "\"confidence\":0.93,"
                + "\"reason\":\"Another person made a future commitment.\","
                + "\"items\":[{"
                + "\"kind\":\"WAITING\","
                + "\"summary\":\"Wait for Mona's revised quotation tonight.\","
                + "\"importance\":72,"
                + "\"urgency\":55,"
                + "\"person\":\"Mona\","
                + "\"due_at\":null,"
                + "\"requires_user_action\":false,"
                + "\"requires_follow_up\":true,"
                + "\"requires_content_extraction\":false"
                + "}]}";

        CognitiveResult result = CognitiveResultValidator.validate(
                CognitiveResultParser.parse(modelOutput)
        );

        assertEquals(CognitiveDisposition.DERIVE, result.disposition);
        assertEquals(0.93, result.confidence, 0.0001);
        assertEquals(1, result.items.size());
        assertEquals(CognitiveKind.WAITING, result.items.get(0).kind);
        assertTrue(result.items.get(0).requiresFollowUp);
    }

    @Test public void unknownKindFails() {
        expectFailure(() -> CognitiveResultValidator.validate(CognitiveResultParser.parse(
                "{\"disposition\":\"DERIVE\",\"confidence\":0.9,\"items\":[{\"kind\":\"ALIEN\",\"summary\":\"x\"}]}"
        )));
    }

    @Test public void invalidJsonFails() {
        expectFailure(() -> CognitiveResultValidator.validate(CognitiveResultParser.parse("not-json")));
    }

    @Test public void deriveWithoutItemsFails() {
        expectFailure(() -> CognitiveResultValidator.validate(CognitiveResultParser.parse(
                "{\"disposition\":\"DERIVE\",\"confidence\":0.9,\"items\":[]}"
        )));
    }

    @Test public void missingSummaryFails() {
        expectFailure(() -> CognitiveResultValidator.validate(CognitiveResultParser.parse(
                "{\"disposition\":\"DERIVE\",\"confidence\":0.9,\"items\":[{\"kind\":\"WAITING\"}]}"
        )));
    }

    @Test public void moreThanFiveItemsFails() {
        StringBuilder b = new StringBuilder("{\"disposition\":\"DERIVE\",\"confidence\":0.9,\"items\":[");
        for (int i = 0; i < 6; i++) {
            if (i > 0) b.append(',');
            b.append("{\"kind\":\"MESSAGE\",\"summary\":\"item ").append(i).append("\"}");
        }
        b.append("]}");
        expectFailure(() -> CognitiveResultValidator.validate(CognitiveResultParser.parse(b.toString())));
    }

    @Test public void nanConfidenceFails() {
        CognitiveResult raw = new CognitiveResult(
                CognitiveDisposition.CONTEXT,
                Double.NaN,
                "invalid confidence",
                Collections.emptyList()
        );
        expectFailure(() -> CognitiveResultValidator.validate(raw));
    }

    @Test public void structuralInvariantsAreApplied() throws Exception {
        ArrayList<CognitiveItem> items = new ArrayList<>();
        items.add(new CognitiveItem(CognitiveKind.ACTION, "Send drawing", 150, -5, "", null, false, false, false));
        items.add(new CognitiveItem(CognitiveKind.WAITING, "Wait for reply", 60, 40, "Mona", null, false, false, false));
        CognitiveResult result = CognitiveResultValidator.validate(new CognitiveResult(CognitiveDisposition.DERIVE, 0.8, "", items));
        assertTrue(result.items.get(0).requiresUserAction);
        assertEquals(100, result.items.get(0).importance);
        assertEquals(0, result.items.get(0).urgency);
        assertTrue(result.items.get(1).requiresFollowUp);
    }

    private static void expectFailure(CheckedRunnable runnable) {
        try {
            runnable.run();
            fail("Expected CognitiveContractException");
        } catch (CognitiveContractException expected) {
            // expected
        } catch (Exception other) {
            fail("Expected CognitiveContractException, got " + other.getClass().getSimpleName());
        }
    }

    private interface CheckedRunnable {
        void run() throws Exception;
    }
}
