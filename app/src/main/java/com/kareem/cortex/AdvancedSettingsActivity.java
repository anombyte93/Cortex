package com.kareem.cortex;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.*;

/** Keeps diagnostic, evaluation and internal tooling out of the primary product navigation. */
public final class AdvancedSettingsActivity extends Activity {
    int dp(int x){return CortexUi.dp(this,x);}
    @Override public void onCreate(Bundle b){super.onCreate(b);CortexUi.applyWindow(this);build();}

    private void build(){
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackground(CortexUi.aurora(this));
        ScrollView sv=new ScrollView(this);LinearLayout body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(dp(20),dp(14),dp(20),dp(28));sv.addView(body);root.addView(sv,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout head=new LinearLayout(this);head.setGravity(Gravity.CENTER_VERTICAL);TextView back=CortexUi.plain(this,"‹",34,CortexUi.TEXT);back.setGravity(Gravity.CENTER);back.setOnClickListener(v->finish());head.addView(back,new LinearLayout.LayoutParams(dp(42),dp(48)));LinearLayout ht=new LinearLayout(this);ht.setOrientation(LinearLayout.VERTICAL);TextView eye=CortexUi.plain(this,"ADVANCED",10,CortexUi.AURORA);CortexUi.medium(eye);ht.addView(eye);TextView title=CortexUi.plain(this,"Diagnostics & internals",27,CortexUi.TEXT);CortexUi.bold(title);ht.addView(title);head.addView(ht,new LinearLayout.LayoutParams(0,-2,1));body.addView(head);
        TextView intro=CortexUi.text(this,"These tools help inspect, benchmark and repair Cortex. They are not part of the normal daily workflow.",12,CortexUi.MUTED);intro.setPadding(dp(2),dp(6),dp(2),dp(12));body.addView(intro);

        body.addView(CortexUi.section(this,"Evaluation"));
        row(body,"Attention evaluation","Inspect what Cortex surfaces, why, and how ranking changes",AttentionEvaluationActivity.class);
        row(body,"Review queue","Resolve uncertain actions, waiting items, decisions and project candidates",ReviewQueueActivity.class);
        row(body,"Relevance evaluation","Inspect relevance decisions and evidence quality",RelevanceEvaluationActivity.class);
        if(BuildConfig.DEBUG)row(body,"Cognitive V2 Shadow","Compare local Qwen V2 with legacy decisions without changing production intelligence",CognitiveShadowActivity.class);

        body.addView(CortexUi.section(this,"Runtime & capabilities"));
        row(body,"Capabilities","See what is active, ready, blocked or failed",CapabilityMatrixActivity.class);
        row(body,"Environment & diagnostics","Provider health, runtime state, phone context, OCR and recovery",EnvironmentActivity.class);
        row(body,"Cortex status","Internal health and system status",CortexStatusActivity.class);
        row(body,"Audit","Inspect internal audit information",CortexAuditActivity.class);
        row(body,"External model check","Verify external model connectivity",ExternalModelCheckActivity.class);
        row(body,"Visual intelligence","Inspect visual understanding tools",VisualIntelligenceActivity.class);
        row(body,"OCR test","Run OCR diagnostics",OcrTestActivity.class);
        setContentView(root);CortexUi.fitSystemBars(this,root);
    }

    private void row(LinearLayout parent,String title,String sub,Class<?> cls){
        LinearLayout c=CortexUi.card(this,18);c.setOrientation(LinearLayout.HORIZONTAL);c.setGravity(Gravity.CENTER_VERTICAL);c.setPadding(dp(14),dp(12),dp(10),dp(12));LinearLayout tx=new LinearLayout(this);tx.setOrientation(LinearLayout.VERTICAL);TextView t=CortexUi.plain(this,title,14,CortexUi.TEXT);CortexUi.medium(t);tx.addView(t);TextView s=CortexUi.text(this,sub,10,CortexUi.MUTED);s.setPadding(0,dp(3),0,0);tx.addView(s);c.addView(tx,new LinearLayout.LayoutParams(0,-2,1));TextView go=CortexUi.plain(this,"›",24,CortexUi.MUTED);go.setGravity(Gravity.CENTER);c.addView(go,new LinearLayout.LayoutParams(dp(30),dp(42)));c.setOnClickListener(v->startActivity(new Intent(this,cls)));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,0,0,dp(8));parent.addView(c,p);
    }
}
