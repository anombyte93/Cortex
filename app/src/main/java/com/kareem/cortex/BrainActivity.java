package com.kareem.cortex;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/**
 * Legacy Brain entry point retained only for old internal intents/shortcuts.
 * Attention now lives exclusively in Today; Cortex is the Ask/Explore surface.
 */
public final class BrainActivity extends Activity {
    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        Intent i=new Intent(this,ProposalAskCortexActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT|Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(i);
        finish();
    }
}
