package com.kareem.cortex;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/**
 * Legacy compatibility entry point.
 *
 * ProposalBriefActivity used to render an independent Today dashboard. Keeping two Today
 * implementations let old intents escape the attention-first product shell. This class now owns
 * no UI; every legacy entry lands on CompactTodayActivity.
 */
public final class ProposalBriefActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Intent i = new Intent(this, CompactTodayActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(i);
        finish();
    }
}
