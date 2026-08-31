package com.kareem.cortex;

import org.json.JSONObject;

/**
 * Stable V5 entry point for Cortex's student decision.
 *
 * The previous implementation merely serialized already-derived rows, which made the
 * Teacher/Student differential circular. The student now performs evidence-grounded
 * reconciliation through CognitiveStudentReasoner over the exact same packet seen by
 * the teacher.
 */
public final class CognitivePacketStudentAdapter {
    private CognitivePacketStudentAdapter(){}

    public static JSONObject decide(JSONObject packet){
        return CognitiveStudentReasoner.decide(packet);
    }
}
