package com.kareem.cortex;

import android.content.ContentValues;
import android.database.Cursor;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Human ground-truth evaluation over Cognitive V2 shadow runs.
 *
 * This store is deliberately outside production relevance authority: verdicts are audit labels only.
 * It may add provenance links and feedback_events, but never changes raw relevance decisions,
 * derived_items, Review, Adaptive Learning, memory or Pulse.
 */
public final class CognitiveShadowEvaluationStore {
    public static final String POLICY="cognitive_shadow_eval_001";

    public static final String V2_BETTER="SHADOW_V2_BETTER";
    public static final String LEGACY_BETTER="SHADOW_LEGACY_BETTER";
    public static final String BOTH_OK="SHADOW_BOTH_OK";
    public static final String NEITHER="SHADOW_NEITHER";
    public static final String SKIP="SHADOW_SKIP_INSUFFICIENT";

    private static final int MIN_SUCCESSFUL_RUNS=150;
    private static final int MIN_LABELED_DISAGREEMENTS=60;
    private static final double MIN_VALID_OUTPUT_RATE=0.99;
    private static final double MIN_MISS_RECOVERY_PRECISION=0.85;
    private static final double MAX_FALSE_DERIVE_RATE=0.10;
    private static final double UNSAFE_FALSE_DERIVE_RATE=0.15;
    private static final double MAX_NOISE_REVIVAL_RATE=0.02;
    private static final long MAX_P50_MS=1000L;
    private static final long MAX_P95_MS=2500L;

    private CognitiveShadowEvaluationStore(){}

    public enum PromotionStatus { COLLECTING, READY, NEEDS_TUNING, UNSAFE }

    public static ArrayList<EvalCase> queue(VaultDb db,int limit){
        ArrayList<EvalCase> out=new ArrayList<>();
        if(db==null||limit<=0)return out;
        CognitiveStore.ensure(db);
        ensureShadowLinks(db);

        String sql=
                "SELECT mr.id,sl.to_id,mr.created_at,mr.output_json,mr.confidence,mr.latency_ms,"+
                "rs.source,rs.title,rs.body " +
                "FROM model_runs mr " +
                "JOIN source_links sl ON sl.from_type='model_run' AND sl.from_id=mr.id " +
                "AND sl.to_type='raw_signal' AND sl.relation='shadow_evaluated' " +
                "JOIN raw_signals rs ON rs.id=sl.to_id " +
                "WHERE mr.role='cognitive_shadow' AND mr.route='cognitive_v2_shadow' AND mr.state='complete' " +
                "AND NOT EXISTS (SELECT 1 FROM feedback_events f WHERE f.target_type='model_run' " +
                "AND f.target_id=mr.id AND f.event_type LIKE 'SHADOW_%') " +
                "ORDER BY CASE " +
                "WHEN mr.output_json LIKE '%V2_FOUND_MISSED_VALUE%' THEN 0 " +
                "WHEN mr.output_json LIKE '%IGNORE_DISAGREEMENT%' THEN 1 " +
                "WHEN mr.output_json LIKE '%V2_DOWNGRADE%' THEN 2 " +
                "ELSE 3 END,mr.created_at DESC LIMIT ?";

        Cursor c=db.getReadableDatabase().rawQuery(sql,new String[]{String.valueOf(Math.min(limit,100))});
        try{while(c.moveToNext()){try{out.add(from(c));}catch(Throwable ignored){}}}finally{c.close();}
        return out;
    }

    public static boolean verdict(VaultDb db,long modelRunId,String verdict){
        if(db==null||modelRunId<=0||!validVerdict(verdict))return false;
        CognitiveStore.ensure(db);

        Cursor existing=db.getReadableDatabase().rawQuery(
                "SELECT 1 FROM feedback_events WHERE target_type='model_run' AND target_id=? " +
                "AND event_type LIKE 'SHADOW_%' LIMIT 1",new String[]{String.valueOf(modelRunId)});
        boolean exists;try{exists=existing.moveToFirst();}finally{existing.close();}
        if(exists)return false;

        Cursor target=db.getReadableDatabase().rawQuery(
                "SELECT 1 FROM model_runs WHERE id=? AND role='cognitive_shadow' " +
                "AND route='cognitive_v2_shadow' AND state='complete' LIMIT 1",new String[]{String.valueOf(modelRunId)});
        boolean validTarget;try{validTarget=target.moveToFirst();}finally{target.close();}
        if(!validTarget)return false;

        ContentValues v=new ContentValues();v.put("target_type","model_run");v.put("target_id",modelRunId);v.put("event_type",verdict);v.put("value_json","{}");v.put("policy_version",POLICY);v.put("source_key","");v.put("candidate_kind","");v.put("scope_key","|");v.put("created_at",System.currentTimeMillis());
        return db.getWritableDatabase().insert("feedback_events",null,v)>0;
    }

    public static Metrics metrics(VaultDb db){
        Metrics m=new Metrics();if(db==null)return m;CognitiveStore.ensure(db);ensureShadowLinks(db);Map<Long,String> verdicts=latestVerdicts(db);ArrayList<Long> latencies=new ArrayList<>();
        Cursor c=db.getReadableDatabase().query("model_runs",new String[]{"id","state","output_json","latency_ms"},"role='cognitive_shadow' AND route='cognitive_v2_shadow'",null,null,null,"id ASC");
        try{
            while(c.moveToNext()){
                long runId=c.getLong(0);String state=n(c.getString(1));JSONObject root=json(c.getString(2));long latency=Math.max(0,c.getLong(3));m.totalRuns++;
                if("complete".equalsIgnoreCase(state)){
                    m.successfulRuns++;latencies.add(latency);String comparison=root.optString("comparison","");JSONObject v2=root.optJSONObject("v2");String v2Disposition=v2==null?"":v2.optString("disposition","");String kind=firstKind(v2);
                    if("V2_FOUND_MISSED_VALUE".equals(comparison))m.missedValueCases++;if("IGNORE_DISAGREEMENT".equals(comparison))m.ignoreDisagreements++;if("V2_DOWNGRADE".equals(comparison))m.v2Downgrades++;if(isDisagreement(comparison))m.disagreementRuns++;observe(m,v2Disposition,kind);
                    String verdict=verdicts.get(runId);
                    if(validEvaluationVerdict(verdict)){
                        m.labeled++;if(V2_BETTER.equals(verdict))m.v2Better++;else if(LEGACY_BETTER.equals(verdict))m.legacyBetter++;else if(BOTH_OK.equals(verdict))m.bothOk++;else if(NEITHER.equals(verdict))m.neither++;
                        if(isDisagreement(comparison))m.labeledDisagreements++;
                        if("V2_FOUND_MISSED_VALUE".equals(comparison)){m.labeledMisses++;if(approved(verdict))m.approvedMisses++;}
                        if("DERIVE".equalsIgnoreCase(v2Disposition)){m.labeledV2Derives++;if(LEGACY_BETTER.equals(verdict)||NEITHER.equals(verdict))m.badV2Derives++;}
                        if("IGNORE_DISAGREEMENT".equals(comparison)){m.labeledIgnoreDisagreements++;if(LEGACY_BETTER.equals(verdict)||NEITHER.equals(verdict))m.badNoiseRevivals++;}
                        if(approved(verdict))approveKind(m,kind);
                    }else if(SKIP.equals(verdict))m.skippedLabels++;
                }else if("failed".equalsIgnoreCase(state))m.failedRuns++;else if("skipped".equalsIgnoreCase(state))m.skippedRuns++;
            }
        }finally{c.close();}
        if(!latencies.isEmpty()){Collections.sort(latencies);long sum=0;for(Long x:latencies)sum+=x==null?0:x;m.avgLatencyMs=sum/(double)latencies.size();m.p50LatencyMs=percentile(latencies,0.50);m.p95LatencyMs=percentile(latencies,0.95);}
        m.validOutputRate=ratio(m.successfulRuns,m.successfulRuns+m.failedRuns);m.missRecoveryPrecision=ratio(m.approvedMisses,m.labeledMisses);m.falseDeriveRate=ratio(m.badV2Derives,m.labeledV2Derives);m.noiseRevivalRate=ratio(m.badNoiseRevivals,m.labeledIgnoreDisagreements);m.shadowDerivedMutations=countShadowDerivedMutations(db);m.noProductionMutation=m.shadowDerivedMutations==0;return m;
    }

    public static PromotionStatus promotionStatus(Metrics m){
        if(m==null)return PromotionStatus.COLLECTING;
        if(!m.noProductionMutation)return PromotionStatus.UNSAFE;
        if(m.successfulRuns<MIN_SUCCESSFUL_RUNS||m.labeledDisagreements<MIN_LABELED_DISAGREEMENTS)return PromotionStatus.COLLECTING;
        if(m.validOutputRate<MIN_VALID_OUTPUT_RATE||m.noiseRevivalRate>MAX_NOISE_REVIVAL_RATE||m.falseDeriveRate>UNSAFE_FALSE_DERIVE_RATE)return PromotionStatus.UNSAFE;
        if(m.missRecoveryPrecision<MIN_MISS_RECOVERY_PRECISION||m.falseDeriveRate>MAX_FALSE_DERIVE_RATE)return PromotionStatus.NEEDS_TUNING;
        if(!m.requiredKindsApproved())return PromotionStatus.COLLECTING;
        if(m.p50LatencyMs>=MAX_P50_MS||m.p95LatencyMs>=MAX_P95_MS)return PromotionStatus.NEEDS_TUNING;
        return PromotionStatus.READY;
    }

    public static String promotionReason(Metrics m){
        PromotionStatus status=promotionStatus(m);if(status==PromotionStatus.READY)return"READY_FOR_AUTHORITATIVE_CANARY";if(m==null)return"MORE_DATA";if(!m.noProductionMutation)return"SHADOW_MUTATED_PRODUCTION";
        if(status==PromotionStatus.COLLECTING){if(m.successfulRuns<MIN_SUCCESSFUL_RUNS)return"COLLECT_MORE_SHADOW_RUNS";if(m.labeledDisagreements<MIN_LABELED_DISAGREEMENTS)return"LABEL_MORE_DISAGREEMENTS";return"COLLECT_SEMANTIC_COVERAGE";}
        if(status==PromotionStatus.UNSAFE){if(m.validOutputRate<MIN_VALID_OUTPUT_RATE)return"CONTRACT_OR_INFERENCE_RELIABILITY";if(m.noiseRevivalRate>MAX_NOISE_REVIVAL_RATE)return"NOISE_REVIVAL_SAFETY";return"FALSE_DERIVE_SAFETY";}
        if(m.missRecoveryPrecision<MIN_MISS_RECOVERY_PRECISION||m.falseDeriveRate>MAX_FALSE_DERIVE_RATE)return"PROMPT_TUNING";return"RUNTIME_TUNING";
    }

    static int ensureShadowLinks(VaultDb db){
        int linked=0;Cursor c=db.getReadableDatabase().query("model_runs",new String[]{"id","output_json"},"role='cognitive_shadow' AND route='cognitive_v2_shadow' AND state='complete'",null,null,null,"id ASC");
        try{while(c.moveToNext()){long runId=c.getLong(0),signalId=json(c.getString(1)).optLong("signal_id",0);if(runId>0&&signalId>0&&rawSignalExists(db,signalId)&&CognitiveStore.linkChecked(db,"model_run",runId,"raw_signal",signalId,"shadow_evaluated",1.0,"{\"policy\":\""+POLICY+"\"}"))linked++;}}finally{c.close();}return linked;
    }

    private static EvalCase from(Cursor c)throws Exception{
        long runId=c.getLong(0),signalId=c.getLong(1),createdAt=c.getLong(2),latency=c.getLong(5);JSONObject root=new JSONObject(n(c.getString(3))),legacy=root.optJSONObject("legacy"),v2=root.optJSONObject("v2");String kind="",summary="";
        if(v2!=null){JSONArray items=v2.optJSONArray("items");if(items!=null&&items.length()>0){JSONObject first=items.optJSONObject(0);if(first!=null){kind=first.optString("kind","");summary=first.optString("summary","");}}}
        return new EvalCase(runId,signalId,createdAt,c.getString(6),c.getString(7),c.getString(8),legacy==null?"":legacy.optString("disposition",""),legacy==null?"":legacy.optString("candidate_kind",""),v2==null?"":v2.optString("disposition",""),kind,summary,c.getDouble(4),root.optString("comparison",""),latency);
    }

    private static Map<Long,String> latestVerdicts(VaultDb db){HashMap<Long,String> out=new HashMap<>();Cursor c=db.getReadableDatabase().rawQuery("SELECT target_id,event_type FROM feedback_events WHERE target_type='model_run' AND event_type LIKE 'SHADOW_%' ORDER BY id DESC",null);try{while(c.moveToNext()){long id=c.getLong(0);if(!out.containsKey(id))out.put(id,n(c.getString(1)));}}finally{c.close();}return out;}
    private static void observe(Metrics m,String disposition,String kind){if("CONTEXT".equalsIgnoreCase(disposition))m.contextObserved++;if("IGNORE".equalsIgnoreCase(disposition))m.ignoreObserved++;if("ACTION".equalsIgnoreCase(kind))m.actionObserved++;else if("WAITING".equalsIgnoreCase(kind))m.waitingObserved++;else if("EVENT".equalsIgnoreCase(kind))m.eventObserved++;else if("CONTENT".equalsIgnoreCase(kind))m.contentObserved++;}
    private static void approveKind(Metrics m,String kind){if("ACTION".equalsIgnoreCase(kind))m.approvedAction++;else if("WAITING".equalsIgnoreCase(kind))m.approvedWaiting++;else if("EVENT".equalsIgnoreCase(kind))m.approvedEvent++;else if("CONTENT".equalsIgnoreCase(kind))m.approvedContent++;}
    private static boolean isDisagreement(String comparison){String x=n(comparison);return !("BOTH_CONTEXT".equals(x)||"BOTH_IGNORE".equals(x)||"BOTH_DERIVE".equals(x));}
    private static boolean approved(String verdict){return V2_BETTER.equals(verdict)||BOTH_OK.equals(verdict);}
    private static boolean validEvaluationVerdict(String v){return V2_BETTER.equals(v)||LEGACY_BETTER.equals(v)||BOTH_OK.equals(v)||NEITHER.equals(v);}
    private static boolean validVerdict(String v){return validEvaluationVerdict(v)||SKIP.equals(v);}
    private static boolean rawSignalExists(VaultDb db,long id){Cursor c=db.getReadableDatabase().rawQuery("SELECT 1 FROM raw_signals WHERE id=? LIMIT 1",new String[]{String.valueOf(id)});try{return c.moveToFirst();}finally{c.close();}}
    private static JSONObject json(String s){try{return new JSONObject(n(s));}catch(Throwable ignored){return new JSONObject();}}
    private static String firstKind(JSONObject v2){if(v2==null)return"";JSONArray a=v2.optJSONArray("items");if(a==null||a.length()==0)return"";JSONObject x=a.optJSONObject(0);return x==null?"":n(x.optString("kind",""));}
    private static long percentile(ArrayList<Long> sorted,double p){if(sorted.isEmpty())return 0;int index=(int)Math.ceil(p*sorted.size())-1;index=Math.max(0,Math.min(sorted.size()-1,index));Long x=sorted.get(index);return x==null?0:x;}
    private static double ratio(long a,long b){return b<=0?0:a/(double)b;}
    private static int countShadowDerivedMutations(VaultDb db){Cursor c=db.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM derived_items WHERE COALESCE(metadata_json,'') LIKE '%cognitive_v2_shadow%' OR COALESCE(metadata_json,'') LIKE '%cognitive_shadow_001%' OR COALESCE(metadata_json,'') LIKE '%cognitive_shadow_eval_001%'",null);try{return c.moveToFirst()?c.getInt(0):0;}finally{c.close();}}
    private static String n(String s){return s==null?"":s.trim();}

    public static final class EvalCase {
        public final long modelRunId,signalId,createdAt,latencyMs;public final String source,title,body,legacyDisposition,legacyCandidate,v2Disposition,v2Kind,v2Summary,comparison;public final double v2Confidence;
        EvalCase(long modelRunId,long signalId,long createdAt,String source,String title,String body,String legacyDisposition,String legacyCandidate,String v2Disposition,String v2Kind,String v2Summary,double v2Confidence,String comparison,long latencyMs){this.modelRunId=modelRunId;this.signalId=signalId;this.createdAt=createdAt;this.source=n(source);this.title=n(title);this.body=n(body);this.legacyDisposition=n(legacyDisposition);this.legacyCandidate=n(legacyCandidate);this.v2Disposition=n(v2Disposition);this.v2Kind=n(v2Kind);this.v2Summary=n(v2Summary);this.v2Confidence=v2Confidence;this.comparison=n(comparison);this.latencyMs=latencyMs;}
    }

    public static final class Metrics {
        public long totalRuns,successfulRuns,failedRuns,skippedRuns,labeled,v2Better,legacyBetter,bothOk,neither,skippedLabels,disagreementRuns,labeledDisagreements,missedValueCases,labeledMisses,approvedMisses,ignoreDisagreements,labeledIgnoreDisagreements,badNoiseRevivals,v2Downgrades,labeledV2Derives,badV2Derives,actionObserved,waitingObserved,eventObserved,contentObserved,contextObserved,ignoreObserved,approvedAction,approvedWaiting,approvedEvent,approvedContent;
        public double avgLatencyMs,validOutputRate,missRecoveryPrecision,falseDeriveRate,noiseRevivalRate;public long p50LatencyMs,p95LatencyMs;public int shadowDerivedMutations;public boolean noProductionMutation=true;
        public boolean requiredKindsApproved(){return approvedAction>0&&approvedWaiting>0&&approvedEvent>0&&approvedContent>0;}
    }
}
