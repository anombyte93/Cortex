package com.kareem.cortex;

import android.database.Cursor;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;

/** Debug/evaluation read model over shadow model_runs plus explicit human feedback. */
public final class CognitiveShadowStore {
    private CognitiveShadowStore(){}

    public static Stats stats(VaultDb db){
        CognitiveStore.ensure(db);Stats s=new Stats();
        Cursor c=db.getReadableDatabase().query("model_runs",new String[]{"id","state","output_json"},
                "role='cognitive_shadow' AND route='cognitive_v2_shadow'",null,null,null,"id DESC","1000");
        while(c.moveToNext()){
            long id=c.getLong(0);String state=n(c.getString(1));JSONObject o=json(c.getString(2));String comparison=o.optString("comparison","");String outcome=o.optString("outcome","");
            s.total++;
            JSONObject v2=o.optJSONObject("v2");String v2Disposition=v2==null?"":v2.optString("disposition","");
            if("complete".equalsIgnoreCase(state)){
                s.analyzed++;
                if("BOTH_CONTEXT".equals(comparison)||"BOTH_IGNORE".equals(comparison)||"BOTH_DERIVE".equals(comparison))s.agreement++;
                if("V2_FOUND_MISSED_VALUE".equals(comparison))s.missedValue++;
                if("DERIVED_KIND_DISAGREEMENT".equals(comparison))s.derivedKindDisagreement++;
                if("V2_DOWNGRADE".equals(comparison))s.downgrade++;
                if("IGNORE_DISAGREEMENT".equals(comparison))s.ignoreDisagreement++;
                countKinds(s,v2);
            }else if("skipped".equalsIgnoreCase(state)||"SKIPPED".equalsIgnoreCase(outcome)){
                s.skipped++;
            }else if("failed".equalsIgnoreCase(state)){
                s.errors++;
                if("INVALID_CONTRACT".equalsIgnoreCase(o.optString("failure_kind","")))s.invalidContract++;
            }

            String feedback=feedback(db,id);
            if(!feedback.isEmpty()){
                s.rated++;
                if("SHADOW_V2_BETTER".equals(feedback))s.v2Better++;
                else if("SHADOW_LEGACY_BETTER".equals(feedback))s.legacyBetter++;
                else if("SHADOW_NEITHER".equals(feedback))s.neither++;

                if("DERIVE".equalsIgnoreCase(v2Disposition)){
                    s.ratedDerives++;
                    if("SHADOW_LEGACY_BETTER".equals(feedback)||"SHADOW_NEITHER".equals(feedback))s.falseDerives++;
                }
                if("V2_FOUND_MISSED_VALUE".equals(comparison)){
                    s.ratedMisses++;
                    if("SHADOW_V2_BETTER".equals(feedback))s.recoveredMisses++;
                }
            }
        }
        c.close();
        s.shadowDerivedMutations=countShadowDerivedMutations(db);
        return s;
    }

    public static ArrayList<Entry> disagreements(VaultDb db,int limit){
        CognitiveStore.ensure(db);ArrayList<Entry> out=new ArrayList<>();int scan=Math.max(50,Math.min(1000,limit*20));
        Cursor c=db.getReadableDatabase().query("model_runs",new String[]{"id","state","output_json","created_at","confidence","latency_ms"},
                "role='cognitive_shadow' AND route='cognitive_v2_shadow' AND state='complete'",null,null,null,"id DESC",String.valueOf(scan));
        while(c.moveToNext()&&out.size()<limit){
            long runId=c.getLong(0),createdAt=c.getLong(3),latency=c.getLong(5);double confidence=c.getDouble(4);JSONObject o=json(c.getString(2));String comparison=o.optString("comparison","");
            if("BOTH_CONTEXT".equals(comparison)||"BOTH_IGNORE".equals(comparison)||"BOTH_DERIVE".equals(comparison))continue;
            long signalId=o.optLong("signal_id",0);SignalPreview preview=preview(db,signalId);JSONObject legacy=o.optJSONObject("legacy"),v2=o.optJSONObject("v2");JSONArray items=v2==null?null:v2.optJSONArray("items");JSONObject first=items!=null&&items.length()>0?items.optJSONObject(0):null;
            out.add(new Entry(runId,signalId,createdAt,latency,confidence,comparison,
                    legacy==null?"":legacy.optString("disposition",""),legacy==null?"":legacy.optString("candidate_kind",""),
                    v2==null?"":v2.optString("disposition",""),first==null?"":first.optString("kind",""),first==null?"":first.optString("summary",""),
                    preview.source,preview.title,preview.body,feedback(db,runId)));
        }
        c.close();return out;
    }

    public static void rate(VaultDb db,long modelRunId,String eventType){
        if(modelRunId<=0)return;if(!"SHADOW_V2_BETTER".equals(eventType)&&!"SHADOW_LEGACY_BETTER".equals(eventType)&&!"SHADOW_NEITHER".equals(eventType))return;
        CognitiveStore.feedback(db,"model_run",modelRunId,eventType,"",CognitiveAdjudicatorV2.POLICY);
    }

    private static void countKinds(Stats s,JSONObject v2){
        if(v2==null)return;JSONArray items=v2.optJSONArray("items");if(items==null)return;
        for(int i=0;i<items.length();i++){
            JSONObject item=items.optJSONObject(i);if(item==null)continue;String kind=item.optString("kind","");
            if("ACTION".equalsIgnoreCase(kind))s.actionCount++;
            else if("WAITING".equalsIgnoreCase(kind))s.waitingCount++;
            else if("EVENT".equalsIgnoreCase(kind))s.eventCount++;
            else if("CONTENT".equalsIgnoreCase(kind))s.contentCount++;
        }
    }

    private static int countShadowDerivedMutations(VaultDb db){
        Cursor c=db.getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM derived_items WHERE COALESCE(metadata_json,'') LIKE '%cognitive_v2_shadow%' OR COALESCE(metadata_json,'') LIKE '%cognitive_shadow_001%'",null);
        try{return c.moveToFirst()?c.getInt(0):0;}finally{c.close();}
    }

    private static String feedback(VaultDb db,long runId){
        Cursor c=db.getReadableDatabase().query("feedback_events",new String[]{"event_type"},
                "target_type='model_run' AND target_id=? AND event_type IN ('SHADOW_V2_BETTER','SHADOW_LEGACY_BETTER','SHADOW_NEITHER')",
                new String[]{String.valueOf(runId)},null,null,"id DESC","1");String x=c.moveToFirst()?n(c.getString(0)):"";c.close();return x;
    }

    private static SignalPreview preview(VaultDb db,long signalId){
        Cursor c=db.getReadableDatabase().query("raw_signals",new String[]{"source","title","body"},"id=?",new String[]{String.valueOf(signalId)},null,null,null,"1");SignalPreview p=c.moveToFirst()?new SignalPreview(c.getString(0),c.getString(1),c.getString(2)):new SignalPreview("","","");c.close();return p;
    }

    private static JSONObject json(String value){try{return new JSONObject(n(value));}catch(Throwable ignored){return new JSONObject();}}
    private static String n(String s){return s==null?"":s.trim();}

    private static final class SignalPreview{final String source,title,body;SignalPreview(String s,String t,String b){source=n(s);title=n(t);body=n(b);}}

    public static final class Stats{
        public int total,analyzed,agreement,missedValue,derivedKindDisagreement,downgrade,ignoreDisagreement,skipped,errors,invalidContract;
        public int rated,v2Better,legacyBetter,neither,ratedDerives,falseDerives,ratedMisses,recoveredMisses;
        public int actionCount,waitingCount,eventCount,contentCount,shadowDerivedMutations;
        public double falseDeriveRate(){return ratedDerives<=0?0:(falseDerives*100.0)/ratedDerives;}
        public double missRecoveryRate(){return ratedMisses<=0?0:(recoveredMisses*100.0)/ratedMisses;}
        public double invalidJsonRate(){int attempts=analyzed+errors;return attempts<=0?0:(invalidContract*100.0)/attempts;}
        public boolean requiredKindsObserved(){return actionCount>0&&waitingCount>0&&eventCount>0&&contentCount>0;}
    }

    public static final class Entry{
        public final long modelRunId,signalId,createdAt,latencyMs;public final double confidence;public final String comparison,legacyDisposition,legacyKind,v2Disposition,v2Kind,v2Summary,source,title,body,feedback;
        Entry(long run,long signal,long created,long latency,double confidence,String comparison,String ld,String lk,String vd,String vk,String summary,String source,String title,String body,String feedback){this.modelRunId=run;this.signalId=signal;this.createdAt=created;this.latencyMs=latency;this.confidence=confidence;this.comparison=n(comparison);this.legacyDisposition=n(ld);this.legacyKind=n(lk);this.v2Disposition=n(vd);this.v2Kind=n(vk);this.v2Summary=n(summary);this.source=n(source);this.title=n(title);this.body=n(body);this.feedback=n(feedback);}
    }
}
