package com.kareem.cortex;

import static org.junit.Assert.*;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Collections;

@RunWith(AndroidJUnit4.class)
public final class FastCognitiveResultParserTest {

    @Test
    public void typeOnlyActionUsesGroundedDefaults() throws Exception {
        CognitiveResult result = FastCognitiveResultParser.parse("{\"t\":\"ACTION\"}", "Send file");
        assertEquals(CognitiveDisposition.DERIVE, result.disposition);
        assertEquals(0.82, result.confidence, 0.0001);
        assertEquals(1, result.items.size());
        CognitiveItem item = result.items.get(0);
        assertEquals(CognitiveKind.ACTION, item.kind);
        assertEquals("Send file", item.summary);
        assertTrue(item.requiresUserAction);
        assertFalse(item.requiresFollowUp);
        assertFalse(item.requiresContentExtraction);
        assertEquals("", item.person);
        assertNull(item.dueAt);
        assertEquals("POLICY_DEFAULT", FastCognitiveResultParser.confidenceSource("{\"t\":\"ACTION\"}"));
    }

    @Test
    public void actionMayCarryModelConfidence() throws Exception {
        CognitiveResult result = FastCognitiveResultParser.parse("{\"t\":\"ACTION\",\"c\":93}", "Send file");
        assertEquals(CognitiveDisposition.DERIVE, result.disposition);
        assertEquals(CognitiveKind.ACTION, result.items.get(0).kind);
        assertEquals(0.93, result.confidence, 0.0001);
        assertEquals("MODEL", FastCognitiveResultParser.confidenceSource("{\"t\":\"ACTION\",\"c\":93}"));
    }

    @Test
    public void shortEventAliasParses() throws Exception {
        CognitiveResult result = FastCognitiveResultParser.parse("{\"t\":\"EV\"}", "Dentist tomorrow at 4");
        assertEquals(CognitiveDisposition.DERIVE, result.disposition);
        assertEquals(CognitiveKind.EVENT, result.items.get(0).kind);
        assertEquals("Dentist tomorrow at 4", result.items.get(0).summary);
    }

    @Test
    public void eventTypeParses() throws Exception {
        CognitiveResult result = FastCognitiveResultParser.parse("{\"t\":\"EVENT\"}", "Dentist tomorrow at 4");
        assertEquals(CognitiveDisposition.DERIVE, result.disposition);
        assertEquals(CognitiveKind.EVENT, result.items.get(0).kind);
    }

    @Test
    public void contextTypeParses() throws Exception {
        CognitiveResult result = FastCognitiveResultParser.parse("{\"t\":\"CONTEXT\"}", "Thanks");
        assertEquals(CognitiveDisposition.CONTEXT, result.disposition);
        assertEquals(0.86, result.confidence, 0.0001);
        assertTrue(result.items.isEmpty());
    }

    @Test
    public void ignoreTypeParses() throws Exception {
        CognitiveResult result = FastCognitiveResultParser.parse("{\"t\":\"IGNORE\"}", "Charging 84%");
        assertEquals(CognitiveDisposition.IGNORE, result.disposition);
        assertEquals(0.70, result.confidence, 0.0001);
        assertTrue(result.items.isEmpty());
    }

    @Test
    public void emptyThinkWrapperDoesNotBreakTypeOnlyJson() throws Exception {
        String raw = "<think>\n\n</think>\n\n{\"t\":\"ACTION\"}";
        CognitiveResult result = FastCognitiveResultParser.parse(raw, "Send file");
        assertEquals(CognitiveDisposition.DERIVE, result.disposition);
        assertEquals(CognitiveKind.ACTION, result.items.get(0).kind);
        assertFalse(FastCognitiveResultParser.hasNonEmptyThinking(raw));
    }

    @Test
    public void oldDkEventWireStillParsesDuringTransition() throws Exception {
        CognitiveResult result = FastCognitiveResultParser.parse("{\"d\":\"EV\",\"k\":\"EVENT\"}", "Dentist tomorrow at 4");
        assertEquals(CognitiveDisposition.DERIVE, result.disposition);
        assertEquals(CognitiveKind.EVENT, result.items.get(0).kind);
    }

    @Test
    public void unknownTypeFailsClosed() throws Exception {
        try {
            FastCognitiveResultParser.parse("{\"t\":\"BANANA\"}", "Anything");
            fail("BANANA must be rejected");
        } catch (CognitiveContractException expected) {
            assertTrue(expected.getMessage().contains("Unknown fast cognitive type"));
        }
    }

    @Test
    public void eventFamilyOverridesCollapsedAction() throws Exception {
        FastCognitiveSemanticGuard.Outcome outcome = guard(
                SignalFamily.EVENT,
                "Dentist appointment tomorrow at 4 PM",
                "{\"t\":\"ACTION\"}"
        );
        assertTrue(outcome.overridden);
        assertEquals("family_event", outcome.rule);
        assertEquals(CognitiveDisposition.DERIVE, outcome.result.disposition);
        assertEquals(CognitiveKind.EVENT, outcome.result.items.get(0).kind);
    }

    @Test
    public void contentFamilyOverridesCollapsedAction() throws Exception {
        FastCognitiveSemanticGuard.Outcome outcome = guard(
                SignalFamily.CONTENT,
                "Ahmed sent a voice message",
                "{\"t\":\"ACTION\"}"
        );
        assertTrue(outcome.overridden);
        assertEquals("family_content", outcome.rule);
        assertEquals(CognitiveKind.CONTENT, outcome.result.items.get(0).kind);
        assertTrue(outcome.result.items.get(0).requiresContentExtraction);
    }

    @Test
    public void arabicSenderPromiseOverridesCollapsedAction() throws Exception {
        FastCognitiveSemanticGuard.Outcome outcome = guard(
                SignalFamily.COMMUNICATION,
                "هبعتلك النسخة المعدلة بكرة",
                "{\"t\":\"ACTION\"}"
        );
        assertTrue(outcome.overridden);
        assertEquals("sender_future_promise", outcome.rule);
        assertEquals(CognitiveKind.WAITING, outcome.result.items.get(0).kind);
        assertTrue(outcome.result.items.get(0).requiresFollowUp);
        assertFalse(outcome.result.items.get(0).requiresUserAction);
    }

    @Test
    public void englishSenderPromiseOverridesCollapsedAction() throws Exception {
        FastCognitiveSemanticGuard.Outcome outcome = guard(
                SignalFamily.COMMUNICATION,
                "I will send you the signed contract tonight.",
                "{\"t\":\"ACTION\"}"
        );
        assertTrue(outcome.overridden);
        assertEquals(CognitiveKind.WAITING, outcome.result.items.get(0).kind);
    }

    @Test
    public void mixedSenderPromiseOverridesCollapsedAction() throws Exception {
        FastCognitiveSemanticGuard.Outcome outcome = guard(
                SignalFamily.COMMUNICATION,
                "هبعتلك the revised BOQ بكرة morning",
                "{\"t\":\"ACTION\"}"
        );
        assertTrue(outcome.overridden);
        assertEquals(CognitiveKind.WAITING, outcome.result.items.get(0).kind);
    }

    @Test
    public void acknowledgementsOverrideCollapsedActionToContext() throws Exception {
        FastCognitiveSemanticGuard.Outcome arabic = guard(
                SignalFamily.COMMUNICATION,
                "شكراً يا كريم",
                "{\"t\":\"ACTION\"}"
        );
        FastCognitiveSemanticGuard.Outcome ack = guard(
                SignalFamily.COMMUNICATION,
                "تمام، وصلت",
                "{\"t\":\"ACTION\"}"
        );
        assertTrue(arabic.overridden);
        assertTrue(ack.overridden);
        assertEquals(CognitiveDisposition.CONTEXT, arabic.result.disposition);
        assertEquals(CognitiveDisposition.CONTEXT, ack.result.disposition);
        assertTrue(arabic.result.items.isEmpty());
        assertTrue(ack.result.items.isEmpty());
    }

    @Test
    public void correctExplicitActionIsNotRewritten() throws Exception {
        FastCognitiveSemanticGuard.Outcome outcome = guard(
                SignalFamily.COMMUNICATION,
                "ممكن send me the final DWG النهاردة؟",
                "{\"t\":\"ACTION\"}"
        );
        assertFalse(outcome.overridden);
        assertEquals(CognitiveDisposition.DERIVE, outcome.result.disposition);
        assertEquals(CognitiveKind.ACTION, outcome.result.items.get(0).kind);
    }

    private static FastCognitiveSemanticGuard.Outcome guard(
            SignalFamily family,
            String text,
            String raw
    ) throws Exception {
        CognitiveInput input = new CognitiveInput(
                0,
                family,
                "",
                "Test",
                "Other",
                text,
                Collections.emptyList(),
                System.currentTimeMillis(),
                "Africa/Cairo",
                ""
        );
        CognitiveResult model = FastCognitiveResultParser.parse(raw, text);
        return FastCognitiveSemanticGuard.reconcile(input, model);
    }
}
