package com.kareem.cortex;

import java.util.Collections;
import java.util.List;

public final class CognitiveResult {

    public final CognitiveDisposition disposition;
    public final double confidence;
    public final String reason;
    public final List<CognitiveItem> items;

    public CognitiveResult(
            CognitiveDisposition disposition,
            double confidence,
            String reason,
            List<CognitiveItem> items
    ) {
        this.disposition = disposition;
        this.confidence = confidence;
        this.reason = reason == null ? "" : reason.trim();
        this.items = Collections.unmodifiableList(
                items == null ? Collections.emptyList() : items
        );
    }

    public boolean hasDerivedItems() {
        return !items.isEmpty();
    }
}
