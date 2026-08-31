package com.kareem.cortex;

import android.app.*;
import android.content.*;
import android.os.Bundle;
import android.provider.Settings;
import android.view.*;
import android.widget.*;

/** Settings contains user-facing configuration. Internal tooling lives behind one Advanced entry. */
public class SettingsActivity extends Activity {
    int dp(int x){return CortexUi.dp(this,x);}
    @Override public void onCreate(Bundle b){super.onCreate(b);CortexUi.applyWindow(this);build();}
    void build(){
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackground(CortexUi.aurora(this));
        ScrollView sv=new ScrollView(this);LinearLayout body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(dp(20),dp(14),dp(20),dp(26));sv.addView(body);root.addView(sv,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout head=new LinearLayout(this);head.setOrientation(LinearLayout.HORIZONTAL);head.setGravity(Gravity.CENTER_VERTICAL);TextView back=CortexUi.plain(this,"‹",34,CortexUi.TEXT);back.setGravity(Gravity.CENTER);back.setOnClickListener(v->finish());head.addView(back,new LinearLayout.LayoutParams(dp(42),dp(48)));TextView h=CortexUi.plain(this,"Settings",29,CortexUi.TEXT);CortexUi.bold(h);head.addView(h,new LinearLayout.LayoutParams(0,-2,1));body.addView(head);

        body.addView(CortexUi.section(this,"Intelligence"));
        String remote=OpenRouterKeyStore.has(this)?"Primary reasoning · "+OpenRouterModelConfig.generationModel(this):"Configure external reasoning";
        row(body,"Reasoning",remote,OpenRouterSettingsActivity.class);
        row(body,"Vision & fallback",GeminiKeyStore.has(this)?"Vision provider configured":"Optional cloud vision and provider fallback",GeminiSettingsActivity.class);

        body.addView(CortexUi.section(this,"Awareness & capture"));
        row(body,"Phone awareness","Notifications, current app/window context and recent app usage",PhoneContextAccessActivity.class);
        row(body,"Voice transcription","Transcription providers and language preferences",AsrSettingsActivity.class);
        actionRow(body,"Screen understanding",CortexScreenAccessibilityService.connected()?"Ready":"Enable explicit screen understanding",()->{try{startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));}catch(Throwable ignored){}});

        body.addView(CortexUi.section(this,"Memory & learning"));
        row(body,"Prompts","Reusable prompts and saved results",PromptLibraryActivity.class);
        row(body,"Corrections","Correct Cortex understanding and manage approved learning",CorrectionLearningActivity.class);
        row(body,"Data & integrations","Backup, restore, privacy, calendar and contacts",FeatureHubActivity.class);

        body.addView(CortexUi.section(this,"Advanced"));
        row(body,"Diagnostics & internals","Evaluation, capability status, audits, OCR and recovery tools",AdvancedSettingsActivity.class);
        setContentView(root);CortexUi.fitSystemBars(this,root);
    }
    void row(LinearLayout parent,String title,String sub,Class<?> cls){actionRow(parent,title,sub,()->startActivity(new Intent(this,cls)));}
    void actionRow(LinearLayout parent,String title,String sub,Runnable action){LinearLayout c=CortexUi.card(this,18);c.setOrientation(LinearLayout.HORIZONTAL);c.setGravity(Gravity.CENTER_VERTICAL);c.setPadding(dp(14),dp(12),dp(10),dp(12));LinearLayout text=new LinearLayout(this);text.setOrientation(LinearLayout.VERTICAL);TextView t=CortexUi.plain(this,title,15,CortexUi.TEXT);CortexUi.medium(t);text.addView(t);TextView s=CortexUi.text(this,sub,11,CortexUi.MUTED);s.setPadding(0,dp(3),0,0);text.addView(s);c.addView(text,new LinearLayout.LayoutParams(0,-2,1));TextView go=CortexUi.plain(this,"›",25,CortexUi.MUTED);go.setGravity(Gravity.CENTER);c.addView(go,new LinearLayout.LayoutParams(dp(32),dp(44)));c.setOnClickListener(v->action.run());LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,0,0,dp(8));parent.addView(c,p);}
}
