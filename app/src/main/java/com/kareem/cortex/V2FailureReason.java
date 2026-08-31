package com.kareem.cortex;

import java.util.Locale;

/** Explicit reasons that may transfer Cognitive V2 authority to Legacy. */
public enum V2FailureReason {
    LOW_CONFIDENCE,
    TIMEOUT,
    INVALID_CONTRACT,
    MODEL_FAILED,
    REVIEW_REQUIRED,
    APPLY_FAILED,
    STATE_TRANSITION_FAILED,
    MODE_DISABLED,
    SENSITIVE_BLOCKED,
    SUPERSEDED;

    public static V2FailureReason from(String value){
        String x=value==null?"":value.trim().toUpperCase(Locale.ROOT);
        if(x.isEmpty())return MODEL_FAILED;
        if("SUPERSEDED".equals(x))return SUPERSEDED;
        if(x.contains("TIMEOUT"))return TIMEOUT;
        if(x.contains("INVALID_CONTRACT")||x.contains("INVALID_RESULT")||x.contains("EMPTY_DERIVE"))return INVALID_CONTRACT;
        if(x.contains("LOW_CONFIDENCE"))return LOW_CONFIDENCE;
        if(x.contains("REVIEW"))return REVIEW_REQUIRED;
        if(x.contains("APPLY_FAILED"))return APPLY_FAILED;
        if(x.contains("STATE_TRANSITION"))return STATE_TRANSITION_FAILED;
        if(x.contains("CANARY_DISABLED")||x.contains("MODE_DISABLED")||x.contains("MODE_CHANGED"))return MODE_DISABLED;
        if(x.contains("SENSITIVE"))return SENSITIVE_BLOCKED;
        return MODEL_FAILED;
    }
}
