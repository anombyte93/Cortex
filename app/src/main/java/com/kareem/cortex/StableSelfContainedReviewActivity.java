package com.kareem.cortex;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** UI-only shell for the resilient foreground review service. */
public final class StableSelfContainedReviewActivity extends Activity {
    private TextView status;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Runnable poll=new Runnable(){@Override public void run(){refresh();handler.postDelayed(this,1000);}};

    @Override public void onCreate(Bundle b){super.onCreate(b);CortexUi.applyWindow(this);build();}
    @Override protected void onStart(){super.onStart();SelfContainedReviewService.start(this);handler.post(poll);}
    @Override protected void onStop(){handler.removeCallbacks(poll);super.onStop();}

    private void build(){
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(CortexUi.BG);root.setPadding(CortexUi.dp(this,18),CortexUi.dp(this,18),CortexUi.dp(this,18),CortexUi.dp(this,18));
        TextView h=CortexUi.text(this,"Cortex self-contained review",22,CortexUi.TEXT);CortexUi.medium(h);root.addView(h);
        TextView sub=CortexUi.text(this,"Runs in a foreground service with a WakeLock. Closing or recreating this screen will not restart V3.",12,CortexUi.MUTED);sub.setPadding(0,CortexUi.dp(this,8),0,CortexUi.dp(this,14));root.addView(sub);
        ScrollView sv=new ScrollView(this);status=CortexUi.text(this,"Preparing…",12,CortexUi.TEXT);status.setGravity(Gravity.START);sv.addView(status);root.addView(sv,new LinearLayout.LayoutParams(-1,0,1));setContentView(root);CortexUi.fitSystemBars(this,root);
    }

    private void refresh(){
        String phase=SelfContainedReviewService.phase(this), text=SelfContainedReviewService.status(this);
        if(text==null||text.trim().isEmpty())text="Preparing…";
        status.setText(text+(("RUNNING".equals(phase))?"\n\nYou can leave this screen; the review will continue.":""));
    }
}
