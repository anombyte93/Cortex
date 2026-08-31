package com.kareem.cortex;

/**
 * Compatibility facade for callers that still use the original BriefEngine API.
 * BriefComposer + PrimeBriefStore are the single brief/attention authority.
 */
public final class BriefEngine {
    private BriefEngine(){}
    public static String daily(VaultDb db){return BriefComposer.compose(db,false);}
    public static String weekly(VaultDb db){return BriefComposer.compose(db,true);}
}
