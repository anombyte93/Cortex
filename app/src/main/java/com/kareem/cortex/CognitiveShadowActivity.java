package com.kareem.cortex;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.*;

import java.util.ArrayList;
import java.util.Locale;

/** Debug-only human evaluation surface for Cognitive V2 shadow output. */
public final class CognitiveShadowActivity extends Activity {
    private VaultDb db;private LinearLayout body;
    int dp(int x){return CortexUi.dp(this,x);}

    @Override public void onCreate(Bundle b){super.onCreate(b);CortexUi.applyWindow(this);db=new VaultDb(this);build();}
    @Override protected void onResume(){super.onResume();refresh();}
    @Override protected void onDestroy(){super.onDestroy();if(db!=null)try{db.close();}catch(Throwable ignored){}}

    private void build(){LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackground(CortexUi.aurora(this));ScrollView sv=new ScrollView(this);body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(dp(20),dp(14),dp(20),dp(28));sv.addView(body);root.addView(sv,new LinearLayout.LayoutParams(-1,0,1));setContentView(root);CortexUi.fitSystemBars(this,root);}

    private void refresh(){
        body.removeAllViews();LinearLayout head=new LinearLayout(this);head.setGravity(Gravity.CENTER_VERTICAL);TextView back=CortexUi.plain(this,"‹",34,CortexUi.TEXT);back.setGravity(Gravity.CENTER);back.setOnClickListener(v->finish());head.addView(back,new LinearLayout.LayoutParams(dp(42),dp(48)));TextView h=CortexUi.plain(this,"Cognitive V2 Shadow",27,CortexUi.TEXT);CortexUi.medium(h);head.addView(h,new LinearLayout.LayoutParams(0,-2,1));body.addView(head);
        TextView intro=CortexUi.text(this,"Legacy remains authoritative. Local Qwen V2 is telemetry only: no derived items, Review, memory or Pulse mutations.",12,CortexUi.MUTED);intro.setPadding(dp(2),0,dp(2),dp(12));body.addView(intro);

        boolean enabled=CognitiveFeatureFlags.shadowEnabled(this);TextView toggle=CortexUi.action(this,enabled?"Shadow mode ON — tap to pause":"Shadow mode OFF — tap to enable",enabled?CortexUi.SAGE:CortexUi.MUTED,false);toggle.setOnClickListener(v->{CognitiveFeatureFlags.setShadowEnabled(this,!CognitiveFeatureFlags.shadowEnabled(this));refresh();});body.addView(toggle,new LinearLayout.LayoutParams(-1,dp(44)));

        CognitiveShadowStore.Stats s=CognitiveShadowStore.stats(db);body.addView(CortexUi.section(this,"Observed shadow runs"));
        metric("Signals observed",s.total+" shadow records • "+s.analyzed+" analyzed • "+s.skipped+" skips • "+s.errors+" errors\nInvalid contract/JSON: "+s.invalidContract+" • "+pct(s.invalidJsonRate()),s.invalidJsonRate()>=1.0?bad():CortexUi.TEXT);
        metric("Exact agreement",s.agreement+" exact agreements\n"+s.missedValue+" V2 found missed value • "+s.derivedKindDisagreement+" derived-kind disagreements\n"+s.downgrade+" V2 downgrades • "+s.ignoreDisagreement+" ignore disagreements",CortexUi.TEXT);
        metric("Required semantic coverage","ACTION "+s.actionCount+" • WAITING "+s.waitingCount+" • EVENT "+s.eventCount+" • CONTENT "+s.contentCount,s.requiredKindsObserved()?CortexUi.SAGE:CortexUi.TEXT);

        String rated=s.rated==0?"No human ratings yet.":s.rated+" rated • V2 better "+s.v2Better+" • Legacy better "+s.legacyBetter+" • Neither "+s.neither+"\nFalse derive among rated V2 derives: "+pct(s.falseDeriveRate())+"\nRated legacy-context misses recovered by V2: "+s.recoveredMisses+" / "+s.ratedMisses+" • "+pct(s.missRecoveryRate());
        metric("Human evaluation",rated,(s.ratedDerives>0&&s.falseDeriveRate()>=10.0)?bad():CortexUi.TEXT);

        body.addView(CortexUi.section(this,"Promotion safety gate"));
        String safety="Meaningful analyzed: "+s.analyzed+" / 200\nInvalid JSON <1%: "+yesNo(s.invalidJsonRate()<1.0)+"\nFalse derives <10%: "+yesNo(s.ratedDerives>0&&s.falseDeriveRate()<10.0)+"\nACTION/WAITING/EVENT/CONTENT observed: "+yesNo(s.requiredKindsObserved())+"\nShadow-created derived items: "+s.shadowDerivedMutations+" (must stay 0)";
        boolean safe=s.shadowDerivedMutations==0;metric("Shadow must remain non-authoritative",safety,safe?CortexUi.SAGE:bad());

        body.addView(CortexUi.section(this,"Disagreements to rate"));ArrayList<CognitiveShadowStore.Entry> entries=CognitiveShadowStore.disagreements(db,20);
        if(entries.isEmpty()){TextView none=CortexUi.text(this,"No recent shadow disagreements yet. Let normal notification traffic accumulate.",13,CortexUi.MUTED);none.setPadding(dp(2),dp(8),dp(2),dp(20));body.addView(none);}else for(CognitiveShadowStore.Entry e:entries)addEntry(e);
    }

    private void metric(String title,String value,int color){LinearLayout c=CortexUi.card(this,18);TextView t=CortexUi.plain(this,title,12,CortexUi.MUTED);CortexUi.medium(t);c.addView(t);TextView v=CortexUi.text(this,value,14,color);v.setPadding(0,dp(6),0,0);c.addView(v);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,0,0,dp(8));body.addView(c,p);}

    private void addEntry(CognitiveShadowStore.Entry e){
        LinearLayout c=CortexUi.card(this,18);String src=friendly(e.source);TextView title=CortexUi.plain(this,(src.isEmpty()?"Signal":src)+(e.title.isEmpty()?"":" • "+clip(e.title,70))+" • #"+e.signalId,13,CortexUi.TEXT);CortexUi.medium(title);c.addView(title);
        TextView evidence=CortexUi.text(this,clip(e.body.isEmpty()?e.title:e.body,420),13,CortexUi.TEXT);evidence.setTextIsSelectable(true);evidence.setPadding(0,dp(7),0,dp(8));c.addView(evidence);
        String legacy=pretty(e.legacyDisposition)+(e.legacyKind.isEmpty()?"":" → "+pretty(e.legacyKind));String v2=pretty(e.v2Disposition)+(e.v2Kind.isEmpty()?"":" → "+pretty(e.v2Kind));TextView comparison=CortexUi.text(this,"Legacy  "+legacy+"\nV2      "+v2+(e.v2Summary.isEmpty()?"":"\n"+e.v2Summary)+"\n"+e.comparison+" • "+e.latencyMs+" ms",11,CortexUi.MUTED);comparison.setPadding(0,0,0,dp(8));c.addView(comparison);
        if(!e.feedback.isEmpty()){TextView rating=CortexUi.text(this,"Rated: "+e.feedback.replace("SHADOW_","").replace('_',' '),11,CortexUi.SAGE);rating.setPadding(0,0,0,dp(6));c.addView(rating);}
        LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.HORIZONTAL);button(row,e,"Legacy better","SHADOW_LEGACY_BETTER");button(row,e,"V2 better","SHADOW_V2_BETTER");button(row,e,"Neither","SHADOW_NEITHER");c.addView(row);
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,0,0,dp(10));body.addView(c,p);
    }

    private void button(LinearLayout row,CognitiveShadowStore.Entry e,String label,String event){TextView b=CortexUi.action(this,label,CortexUi.ACCENT,false);b.setOnClickListener(v->{CognitiveShadowStore.rate(db,e.modelRunId,event);Toast.makeText(this,"Saved",Toast.LENGTH_SHORT).show();refresh();});LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(40),1);p.setMargins(0,0,dp(5),0);row.addView(b,p);}
    private String friendly(String source){String x=source==null?"":source.trim();int i=x.lastIndexOf('.');if(i>=0&&i+1<x.length())x=x.substring(i+1);return x;}
    private String pretty(String s){String x=s==null?"":s.trim().toLowerCase(Locale.ROOT).replace('_',' ');return x.isEmpty()?"—":Character.toUpperCase(x.charAt(0))+x.substring(1);}
    private String clip(String s,int n){String x=s==null?"":s.trim();return x.length()<=n?x:x.substring(0,n)+"…";}
    private String pct(double value){return String.format(Locale.US,"%.1f%%",value);}
    private String yesNo(boolean yes){return yes?"YES":"NO";}
    private int bad(){return android.graphics.Color.rgb(246,124,118);}
}
