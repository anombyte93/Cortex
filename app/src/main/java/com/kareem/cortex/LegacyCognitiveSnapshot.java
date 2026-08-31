package com.kareem.cortex;

/** Frozen legacy decision used only for V2 shadow comparison. */
public final class LegacyCognitiveSnapshot {
    public final String disposition;
    public final String candidateKind;
    public final double confidence;
    public final String engine;

    public LegacyCognitiveSnapshot(String disposition,String candidateKind,double confidence,String engine){
        this.disposition=n(disposition);this.candidateKind=n(candidateKind);this.confidence=confidence;this.engine=n(engine);
    }

    private static String n(String s){return s==null?"":s.trim();}
}
