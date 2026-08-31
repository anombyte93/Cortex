package com.kareem.cortex;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.*;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Locale;

/** Local-only quality lab for PRIME-V2 relevance decisions. Never feeds audit labels into learning. */
public class RelevanceEvaluationActivity extends Activity {
    VaultDb db;LinearLayout body;RelevanceSmokeTest.Result smoke;
    int dp(int x){return CortexUi.dp(this,x);}

    @Override public void onCreate(Bundle b){super.onCreate(b);CortexUi.applyWindow(this);db=new VaultDb(this);build();}
    @Override protected void onResume(){super.onResume();refresh();}

    void build(){LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(CortexUi.BG);ScrollView sv=new ScrollView(this);body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(dp(20),dp(14),dp(20),dp(30));sv.addView(body);root.addView(sv,new LinearLayout.LayoutParams(-1,0,1));setContentView(root);CortexUi.fitSystemBars(this,root);}

    void refresh(){
        body.removeAllViews();
        LinearLayout head=new LinearLayout(this);head.setGravity(Gravity.CENTER_VERTICAL);TextView back=CortexUi.plain(this,"‹",34,CortexUi.TEXT);back.setGravity(Gravity.CENTER);back.setOnClickListener(v->finish());head.addView(back,new LinearLayout.LayoutParams(dp(42),dp(48)));TextView h=CortexUi.plain(this,"Relevance evaluation",27,CortexUi.TEXT);CortexUi.medium(h);head.addView(h,new LinearLayout.LayoutParams(0,-2,1));body.addView(head);
        TextView intro=CortexUi.text(this,"Real notification/thread decisions only. Audit labels and Cognitive V2 shadow verdicts measure quality but never train Cortex, change Adaptive Learning or mutate Pulse.",12,CortexUi.MUTED);intro.setPadding(dp(2),0,dp(2),dp(12));body.addView(intro);

        body.addView(CortexUi.section(this,"Regression guard"));
        TextView run=CortexUi.action(this,"Run 50-case deterministic smoke test",CortexUi.ACCENT,false);run.setOnClickListener(v->{smoke=RelevanceSmokeTest.run();refresh();});body.addView(run,new LinearLayout.LayoutParams(-1,dp(44)));
        if(smoke!=null){StringBuilder s=new StringBuilder();s.append(smoke.passed).append(" / ").append(smoke.total).append(" passed");if(!smoke.failures.isEmpty()){s.append("\n");for(int i=0;i<Math.min(6,smoke.failures.size());i++)s.append("\n").append(smoke.failures.get(i));if(smoke.failures.size()>6)s.append("\n\n+").append(smoke.failures.size()-6).append(" more failures");}metricCard("Smoke result",s.toString(),smoke.ok()?CortexUi.SAGE:android.graphics.Color.rgb(238,184,94));}

        JSONObject m=RelevanceEvaluationStore.matrix(db);
        body.addView(CortexUi.section(this,"Coverage"));metricCard("Observed cases",longVal(m,"total")+" pipeline decisions\n"+longVal(m,"with_model")+" received local Qwen adjudication • "+longVal(m,"exact_ground_truth_cases")+" have exact ground truth",CortexUi.TEXT);
        body.addView(CortexUi.section(this,"Safety"));long falseActions=longVal(m,"observed_false_actions"),gaps=longVal(m,"thread_gap_violations"),unverified=longVal(m,"unverified_final_actions");metricCard("Action safety",falseActions+" observed false Actions\n"+unverified+" final Actions still unverified",falseActions==0?CortexUi.SAGE:android.graphics.Color.rgb(246,124,118));metricCard("Thread boundaries",gaps+" thread episodes contain a >48h internal gap",gaps==0?CortexUi.SAGE:android.graphics.Color.rgb(246,124,118));

        body.addView(CortexUi.section(this,"Layer value"));metricCard("Adaptive learning",longVal(m,"adaptive_helped")+" helped • "+longVal(m,"adaptive_harmed")+" harmed\nChanged a deterministic result in "+longVal(m,"learned_changed_rule")+" cases",CortexUi.TEXT);metricCard("Local Qwen",longVal(m,"model_helped")+" helped • "+longVal(m,"model_harmed")+" harmed\n"+longVal(m,"rule_model_disagreement")+" semantic disagreements with learned rules",CortexUi.TEXT);long hi=longVal(m,"model_high_confidence_cases"),hiCorrect=longVal(m,"model_high_confidence_correct");String calibration=hi==0?"No high-confidence labeled model cases yet.":hiCorrect+" / "+hi+" high-confidence model cases matched ground truth ("+pct(hiCorrect,hi)+")";metricCard("Confidence calibration",calibration,CortexUi.TEXT);

        body.addView(CortexUi.section(this,"Review evidence"));long accepted=longVal(m,"confirmed_reviews"),rejected=longVal(m,"rejected_reviews");metricCard("Review outcomes",accepted+" confirmed • "+rejected+" rejected"+(accepted+rejected==0?"":" • "+pct(accepted,accepted+rejected)+" acceptance"),CortexUi.TEXT);

        addCognitiveShadowSection();

        body.addView(CortexUi.section(this,"Audit real cases"));TextView note=CortexUi.text(this,"Priority order: durable outputs first, then rule↔model disagreements, then Review/context. Pick what the message actually means; Skip if the notification preview is insufficient.",11,CortexUi.MUTED);note.setPadding(dp(2),0,dp(2),dp(10));body.addView(note);ArrayList<RelevanceEvaluationStore.EvalCase> cases=RelevanceEvaluationStore.auditQueue(db,8);if(cases.isEmpty()){TextView done=CortexUi.text(this,"No unlabeled evaluation cases yet. Let Cortex collect normal notification traffic and come back later.",13,CortexUi.MUTED);done.setPadding(dp(2),dp(8),dp(2),dp(20));body.addView(done);}else for(RelevanceEvaluationStore.EvalCase x:cases)addCase(x);
    }

    private void addCognitiveShadowSection(){
        CognitiveShadowEvaluationStore.Metrics cm=CognitiveShadowEvaluationStore.metrics(db);
        CognitiveShadowEvaluationStore.PromotionStatus status=CognitiveShadowEvaluationStore.promotionStatus(cm);
        body.addView(CortexUi.section(this,"Cognitive V2 shadow"));

        String statusText=shadowStatusLabel(status)+"\n"+
                cm.successfulRuns+" / 150 successful shadow runs • "+cm.labeledDisagreements+" / 60 disagreements human-labeled\n"+
                "Reason: "+pretty(CognitiveShadowEvaluationStore.promotionReason(cm));
        metricCard("Promotion gate",statusText,shadowStatusColor(status));

        metricCard("Coverage",
                cm.successfulRuns+" analyzed • "+cm.failedRuns+" failed • "+cm.skippedRuns+" skipped\n"+
                cm.labeled+" human verdicts • "+cm.skippedLabels+" preview-insufficient skips",
                cm.failedRuns==0?CortexUi.TEXT:android.graphics.Color.rgb(238,184,94));

        metricCard("Miss recovery",
                ratioPct(cm.missRecoveryPrecision,cm.labeledMisses)+" of human-labeled V2_FOUND_MISSED_VALUE cases approved\n"+
                cm.approvedMisses+" approved / "+cm.labeledMisses+" labeled • target ≥85%",
                cm.labeledMisses>0&&cm.missRecoveryPrecision>=0.85?CortexUi.SAGE:CortexUi.TEXT);

        metricCard("False derives",
                ratioPct(cm.falseDeriveRate,cm.labeledV2Derives)+" bad among human-labeled V2 derives\n"+
                cm.badV2Derives+" bad / "+cm.labeledV2Derives+" labeled • gate ≤10% • unsafe >15%",
                cm.labeledV2Derives>0&&cm.falseDeriveRate>0.10?android.graphics.Color.rgb(246,124,118):CortexUi.TEXT);

        metricCard("Ignore safety",
                ratioPct(cm.noiseRevivalRate,cm.labeledIgnoreDisagreements)+" false revival among labeled IGNORE disagreements\n"+
                cm.badNoiseRevivals+" bad / "+cm.labeledIgnoreDisagreements+" labeled • target ≤2%",
                cm.labeledIgnoreDisagreements>0&&cm.noiseRevivalRate>0.02?android.graphics.Color.rgb(246,124,118):CortexUi.TEXT);

        long attempted=cm.successfulRuns+cm.failedRuns;
        metricCard("Contract reliability",
                ratioPct(cm.validOutputRate,attempted)+" valid output rate • target ≥99%\n"+
                cm.successfulRuns+" successful / "+attempted+" attempted cognitive runs",
                attempted>0&&cm.validOutputRate<0.99?android.graphics.Color.rgb(246,124,118):CortexUi.TEXT);

        metricCard("Local speed",
                "P50 "+cm.p50LatencyMs+" ms • P95 "+cm.p95LatencyMs+" ms • avg "+Math.round(cm.avgLatencyMs)+" ms\n"+
                "Promotion target: P50 <1000 ms • P95 <2500 ms",
                (cm.successfulRuns>0&&(cm.p50LatencyMs>=1000||cm.p95LatencyMs>=2500))?android.graphics.Color.rgb(238,184,94):CortexUi.TEXT);

        metricCard("Semantic evidence",
                "Approved: ACTION "+cm.approvedAction+" • WAITING "+cm.approvedWaiting+" • EVENT "+cm.approvedEvent+" • CONTENT "+cm.approvedContent+"\n"+
                "Observed: ACTION "+cm.actionObserved+" • WAITING "+cm.waitingObserved+" • EVENT "+cm.eventObserved+" • CONTENT "+cm.contentObserved+" • CONTEXT "+cm.contextObserved+" • IGNORE "+cm.ignoreObserved,
                cm.requiredKindsApproved()?CortexUi.SAGE:CortexUi.TEXT);

        metricCard("Production mutation guard",
                "Shadow-created derived items: "+cm.shadowDerivedMutations+" (must remain 0)\n"+
                "Verdicts write evaluation feedback only; Pulse and final relevance remain untouched.",
                cm.noProductionMutation?CortexUi.SAGE:android.graphics.Color.rgb(246,124,118));

        TextView evalNote=CortexUi.text(this,"Review real shadow disagreements below. V2 better / Legacy better / Both acceptable / Neither are ground-truth audit labels only. Skip removes an insufficient preview from the queue without training Cortex.",11,CortexUi.MUTED);evalNote.setPadding(dp(2),dp(4),dp(2),dp(10));body.addView(evalNote);
        ArrayList<CognitiveShadowEvaluationStore.EvalCase> shadow=CognitiveShadowEvaluationStore.queue(db,8);
        if(shadow.isEmpty()){
            TextView done=CortexUi.text(this,"No unlabeled successful Cognitive V2 shadow cases are available yet.",13,CortexUi.MUTED);done.setPadding(dp(2),dp(8),dp(2),dp(18));body.addView(done);
        }else for(CognitiveShadowEvaluationStore.EvalCase x:shadow)addShadowCase(x);
    }

    void metricCard(String title,String value,int valueColor){LinearLayout c=CortexUi.card(this,18);TextView h=CortexUi.plain(this,title,12,CortexUi.MUTED);CortexUi.medium(h);c.addView(h);TextView v=CortexUi.text(this,value,14,valueColor);v.setPadding(0,dp(6),0,0);c.addView(v);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,0,0,dp(8));body.addView(c,p);}

    void addShadowCase(CognitiveShadowEvaluationStore.EvalCase x){
        LinearLayout c=CortexUi.card(this,18);
        TextView title=CortexUi.plain(this,shadowTitle(x),13,CortexUi.TEXT);CortexUi.medium(title);c.addView(title);
        TextView evidence=CortexUi.text(this,clip(x.body.isEmpty()?x.title:x.body,420),13,CortexUi.TEXT);evidence.setPadding(0,dp(7),0,dp(10));evidence.setTextIsSelectable(true);c.addView(evidence);

        String legacy=pretty(x.legacyDisposition)+(x.legacyCandidate.isEmpty()?"":" → "+pretty(x.legacyCandidate));
        String v2=pretty(x.v2Disposition)+(x.v2Kind.isEmpty()?"":" → "+pretty(x.v2Kind))+" • "+Math.round(x.v2Confidence*100)+"%";
        String result="Legacy\n"+legacy+"\n\nCognitive V2\n"+v2+(x.v2Summary.isEmpty()?"":"\n"+x.v2Summary)+"\n\n"+pretty(x.comparison)+" • "+x.latencyMs+" ms";
        TextView stages=CortexUi.text(this,result,11,CortexUi.MUTED);stages.setPadding(0,0,0,dp(10));c.addView(stages);

        shadowVerdictRow(c,x,new String[]{"V2 better","Legacy better"},new String[]{CognitiveShadowEvaluationStore.V2_BETTER,CognitiveShadowEvaluationStore.LEGACY_BETTER});
        shadowVerdictRow(c,x,new String[]{"Both acceptable","Neither"},new String[]{CognitiveShadowEvaluationStore.BOTH_OK,CognitiveShadowEvaluationStore.NEITHER});
        TextView skip=CortexUi.action(this,"Skip — preview insufficient",CortexUi.MUTED,false);skip.setOnClickListener(v->saveShadowVerdict(x,CognitiveShadowEvaluationStore.SKIP));LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(-1,dp(40));sp.setMargins(0,dp(4),0,0);c.addView(skip,sp);

        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,0,0,dp(10));body.addView(c,p);
    }

    void shadowVerdictRow(LinearLayout parent,CognitiveShadowEvaluationStore.EvalCase x,String[] labels,String[] verdicts){
        LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.HORIZONTAL);
        for(int i=0;i<labels.length;i++){final String verdict=verdicts[i];TextView b=CortexUi.action(this,labels[i],CortexUi.ACCENT,false);b.setOnClickListener(v->saveShadowVerdict(x,verdict));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(40),1);p.setMargins(0,0,dp(6),dp(5));row.addView(b,p);}parent.addView(row);
    }

    void saveShadowVerdict(CognitiveShadowEvaluationStore.EvalCase x,String verdict){boolean ok=CognitiveShadowEvaluationStore.verdict(db,x.modelRunId,verdict);Toast.makeText(this,ok?"Shadow evaluation saved":"Case already has a shadow verdict",Toast.LENGTH_SHORT).show();refresh();}

    void addCase(RelevanceEvaluationStore.EvalCase x){LinearLayout c=CortexUi.card(this,18);TextView title=CortexUi.plain(this,caseTitle(x),13,CortexUi.TEXT);CortexUi.medium(title);c.addView(title);TextView text=CortexUi.text(this,clip(x.body.isEmpty()?x.title:x.body,360),13,CortexUi.TEXT);text.setPadding(0,dp(7),0,dp(8));text.setTextIsSelectable(true);c.addView(text);String pipe="Rule "+decision(x.detDisposition,x.detCandidate,x.detConfidence)+"\nLearned "+decision(x.learnedDisposition,x.learnedCandidate,x.learnedConfidence)+"\nQwen "+(x.hasModel()?decision(x.modelDisposition,x.modelCandidate,x.modelConfidence):"not run")+"\nFinal "+decision(x.finalDisposition,x.finalCandidate,x.finalConfidence)+" • "+cleanEngine(x.finalEngine);TextView stages=CortexUi.text(this,pipe,11,x.disagreement()?android.graphics.Color.rgb(238,184,94):CortexUi.MUTED);stages.setPadding(0,0,0,dp(10));c.addView(stages);labelRow(c,x,new String[]{"IGNORE","CONTEXT","REVIEW"});labelRow(c,x,new String[]{"ACTION","WAITING","DECISION"});TextView skip=CortexUi.action(this,"Skip — preview insufficient",CortexUi.MUTED,false);skip.setOnClickListener(v->{RelevanceEvaluationStore.auditSkip(db,x.signalId);refresh();});LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(-1,dp(40));sp.setMargins(0,dp(6),0,0);c.addView(skip,sp);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,0,0,dp(10));body.addView(c,p);}

    void labelRow(LinearLayout parent,RelevanceEvaluationStore.EvalCase x,String[] labels){LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.HORIZONTAL);for(String label:labels){TextView b=CortexUi.action(this,pretty(label),CortexUi.ACCENT,false);b.setOnClickListener(v->{boolean ok=RelevanceEvaluationStore.auditLabel(db,x.signalId,label);Toast.makeText(this,ok?"Labeled "+pretty(label):"Case already has a verdict",Toast.LENGTH_SHORT).show();refresh();});LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(40),1);p.setMargins(0,0,dp(6),dp(5));row.addView(b,p);}parent.addView(row);}

    String shadowTitle(CognitiveShadowEvaluationStore.EvalCase x){String src=x.source;int i=src.lastIndexOf('.');if(i>=0&&i<src.length()-1)src=src.substring(i+1);String t=x.title.trim();return (src.isEmpty()?"Signal":src)+(t.isEmpty()?"":" • "+clip(t,72))+" • #"+x.signalId;}
    String shadowStatusLabel(CognitiveShadowEvaluationStore.PromotionStatus status){if(status==CognitiveShadowEvaluationStore.PromotionStatus.READY)return"READY FOR AUTHORITATIVE CANARY";if(status==CognitiveShadowEvaluationStore.PromotionStatus.NEEDS_TUNING)return"NEEDS TUNING";return status.name().replace('_',' ');}
    int shadowStatusColor(CognitiveShadowEvaluationStore.PromotionStatus status){if(status==CognitiveShadowEvaluationStore.PromotionStatus.READY)return CortexUi.SAGE;if(status==CognitiveShadowEvaluationStore.PromotionStatus.UNSAFE)return android.graphics.Color.rgb(246,124,118);if(status==CognitiveShadowEvaluationStore.PromotionStatus.NEEDS_TUNING)return android.graphics.Color.rgb(238,184,94);return CortexUi.TEXT;}
    String ratioPct(double ratio,long denominator){return denominator<=0?"—":String.format(Locale.US,"%.1f%%",ratio*100.0);}
    String caseTitle(RelevanceEvaluationStore.EvalCase x){String src=x.source;int i=src.lastIndexOf('.');if(i>=0&&i<src.length()-1)src=src.substring(i+1);String t=x.title.trim();return (src.isEmpty()?"Signal":src)+(t.isEmpty()?"":" • "+clip(t,72))+" • #"+x.signalId;}
    String decision(String disposition,String candidate,double confidence){String d=disposition==null||disposition.isEmpty()?"—":pretty(disposition);if("REVIEW".equalsIgnoreCase(disposition)&&candidate!=null&&!candidate.isEmpty())d+="("+pretty(candidate)+")";return d+" "+Math.round(confidence*100)+"%";}
    String cleanEngine(String s){if(s==null||s.isEmpty())return"policy";return s.replace('_',' ').replace('+','/');}
    String pretty(String s){if(s==null||s.isEmpty())return"—";String x=s.toLowerCase(Locale.ROOT).replace('_',' ');return Character.toUpperCase(x.charAt(0))+x.substring(1);}
    String clip(String s,int n){String x=s==null?"":s.trim();return x.length()<=n?x:x.substring(0,n)+"…";}
    long longVal(JSONObject o,String key){return o==null?0:o.optLong(key,0);}String pct(long a,long b){return b<=0?"—":String.format(Locale.US,"%.1f%%",(a*100.0)/b);}
}
