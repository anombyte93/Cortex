package com.kareem.cortex;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * The single runtime apply path for accepted Cognitive V2 authority decisions.
 * Qwen never receives a DB handle. All authoritative persistence happens in one short transaction.
 */
public final class CognitiveDecisionApplier {
    public static final double ACCEPT_CONFIDENCE=0.78;

    private CognitiveDecisionApplier(){}

    public static final class Validation {
        public final boolean accepted;
        public final CognitiveResult effectiveResult;
        public final V2FailureReason failureReason;

        private Validation(boolean accepted,CognitiveResult effectiveResult,V2FailureReason failureReason){
            this.accepted=accepted;
            this.effectiveResult=effectiveResult;
            this.failureReason=failureReason;
        }

        static Validation accept(CognitiveResult result){return new Validation(true,result,null);}
        static Validation reject(V2FailureReason reason){return new Validation(false,null,reason==null?V2FailureReason.MODEL_FAILED:reason);}
    }

    public static final class ApplyResult {
        public final long modelRunId;
        public final List<Long> derivedIds;
        public final CognitiveDisposition disposition;

        ApplyResult(long modelRunId,List<Long> derivedIds,CognitiveDisposition disposition){
            this.modelRunId=modelRunId;
            this.derivedIds=Collections.unmodifiableList(new ArrayList<>(derivedIds));
            this.disposition=disposition;
        }
    }

    /** One policy for Canary and Primary. Model IGNORE is deliberately softened to context. */
    static Validation validate(CognitiveResult result){
        if(result==null||result.disposition==null||Double.isNaN(result.confidence)){
            return Validation.reject(V2FailureReason.INVALID_CONTRACT);
        }
        if(result.disposition==CognitiveDisposition.IGNORE){
            CognitiveResult softened=new CognitiveResult(
                    CognitiveDisposition.CONTEXT,
                    clamp01(result.confidence),
                    "MODEL_IGNORE_SOFTENED",
                    Collections.emptyList()
            );
            return Validation.accept(softened);
        }
        if(result.disposition==CognitiveDisposition.REVIEW)return Validation.reject(V2FailureReason.REVIEW_REQUIRED);
        if(result.disposition==CognitiveDisposition.DERIVE){
            if(result.items==null||result.items.isEmpty())return Validation.reject(V2FailureReason.INVALID_CONTRACT);
            if(result.confidence<ACCEPT_CONFIDENCE)return Validation.reject(V2FailureReason.LOW_CONFIDENCE);
            for(CognitiveItem item:result.items)if(item==null||item.kind==null)return Validation.reject(V2FailureReason.INVALID_CONTRACT);
            return Validation.accept(result);
        }
        if(result.disposition==CognitiveDisposition.CONTEXT){
            return result.confidence>=ACCEPT_CONFIDENCE?Validation.accept(result):Validation.reject(V2FailureReason.LOW_CONFIDENCE);
        }
        return Validation.reject(V2FailureReason.INVALID_CONTRACT);
    }

    public static ApplyResult apply(
            VaultDb db,
            long signalId,
            long threadId,
            CognitiveResult result,
            LocalBrainRun run,
            long latencyMs,
            String inputHash,
            CognitiveAuthorityMode authorityMode,
            String policy,
            String routingReason,
            int routingBucket,
            long generation
    ){
        if(db==null||signalId<=0||result==null||result.disposition==null||run==null){
            throw new IllegalArgumentException("invalid cognitive authority apply input");
        }
        if(authorityMode!=CognitiveAuthorityMode.CANARY&&authorityMode!=CognitiveAuthorityMode.V2_PRIMARY){
            throw new IllegalArgumentException("invalid V2 authority mode");
        }
        Validation validation=validate(result);
        if(!validation.accepted)throw new IllegalArgumentException("unaccepted V2 decision: "+validation.failureReason);
        CognitiveResult effective=validation.effectiveResult;
        String route=routeFor(authorityMode);
        String routeReason=normalizedRoutingReason(authorityMode,routingReason);
        String cleanPolicy=n(policy);
        if(cleanPolicy.isEmpty())cleanPolicy=authorityMode==CognitiveAuthorityMode.V2_PRIMARY?CognitiveAdjudicatorV2.PRIMARY_POLICY:CognitiveAdjudicatorV2.CANARY_POLICY;

        CognitiveStore.ensure(db);
        SQLiteDatabase sql=db.getWritableDatabase();
        ArrayList<Long> derivedIds=new ArrayList<>();
        long modelRunId=0;
        sql.beginTransaction();
        try{
            if(!latestMayCommit(sql,signalId,threadId))throw new IllegalStateException("STALE_GENERATION");

            JSONObject output=output(signalId,effective,run,cleanPolicy,route,authorityMode,routeReason,routingBucket,generation);
            modelRunId=AiJobStore.modelRun(
                    db,0,1,"cognitive_authority","local",LocalModelManager.MODEL_NAME,
                    route,"complete",n(inputHash),Math.max(0,latencyMs),0,run.tokensGenerated,
                    effective.confidence,output.toString(),""
            );
            if(modelRunId<=0)throw new IllegalStateException("V2 model_run persistence failed");

            String routeMeta=metadata(cleanPolicy,route,authorityMode,routeReason,routingBucket,generation).toString();
            if(!CognitiveStore.linkChecked(db,"model_run",modelRunId,"raw_signal",signalId,"authoritative_evaluated",effective.confidence,routeMeta)){
                throw new IllegalStateException("V2 model-to-signal provenance failed");
            }

            CognitiveItem strongest=null;
            if(effective.disposition==CognitiveDisposition.DERIVE){
                for(CognitiveItem item:effective.items){
                    long derivedId=persistDerivedItem(db,item,signalId,threadId,modelRunId,effective.confidence,cleanPolicy,route,authorityMode);
                    if(derivedId<=0)throw new IllegalStateException("V2 derived item persistence failed");
                    derivedIds.add(derivedId);
                    String authorityMeta=authorityLinkMetadata(authorityMode,cleanPolicy,route);
                    if(!CognitiveStore.linkChecked(db,"raw_signal",signalId,"derived",derivedId,"supports",1.0,authorityMeta)){
                        throw new IllegalStateException("V2 signal provenance failed");
                    }
                    if(!CognitiveStore.linkChecked(db,"model_run",modelRunId,"derived",derivedId,"generated",effective.confidence,routeMeta)){
                        throw new IllegalStateException("V2 model provenance failed");
                    }
                    if(threadId>0&&!CognitiveStore.linkChecked(db,"derived",derivedId,"thread",threadId,"derived_from_thread",1.0,authorityMeta)){
                        throw new IllegalStateException("V2 thread provenance failed");
                    }
                    if(strongest==null||item.importance>strongest.importance||(item.importance==strongest.importance&&item.urgency>strongest.urgency))strongest=item;
                }
            }

            long now=System.currentTimeMillis();
            ContentValues raw=new ContentValues();
            raw.put("cognitive_version",cleanPolicy);
            raw.put("cognitive_updated_at",now);
            raw.put("updated_at",now);
            raw.put("filter_engine","cognitive_v2");
            raw.put("policy_version",cleanPolicy);
            raw.put("confidence",effective.confidence);
            raw.put("reason",n(effective.reason));
            raw.put("final_reason",finalReason(authorityMode,effective,routeReason));

            if(effective.disposition==CognitiveDisposition.DERIVE){
                raw.put("cognitive_state","DERIVED");raw.put("state","derived");
                raw.put("disposition",strongest==null?"CONTEXT":strongest.kind.name());
                raw.put("importance",strongest==null?0:clamp100(strongest.importance));
            }else if(effective.disposition==CognitiveDisposition.CONTEXT){
                raw.put("cognitive_state","CONTEXT_ONLY");raw.put("state","context");raw.put("disposition","CONTEXT");raw.put("importance",0);
            }else throw new IllegalStateException("unsupported authoritative disposition "+effective.disposition);

            int updated;
            if(threadId>0){
                updated=sql.update(
                        "raw_signals",raw,
                        "id=? AND id=(SELECT id FROM raw_signals WHERE thread_id=? ORDER BY occurred_at DESC,id DESC LIMIT 1)",
                        new String[]{String.valueOf(signalId),String.valueOf(threadId)}
                );
                if(updated!=1)throw new IllegalStateException("STALE_GENERATION");
            }else{
                updated=sql.update("raw_signals",raw,"id=?",new String[]{String.valueOf(signalId)});
                if(updated!=1)throw new IllegalStateException("V2 raw state transition failed");
            }

            sql.setTransactionSuccessful();
            return new ApplyResult(modelRunId,derivedIds,effective.disposition);
        }finally{sql.endTransaction();}
    }

    private static long persistDerivedItem(
            VaultDb db,CognitiveItem item,long signalId,long threadId,long modelRunId,double confidence,
            String policy,String route,CognitiveAuthorityMode mode
    ){
        SQLiteDatabase sql=db.getWritableDatabase();long now=System.currentTimeMillis();String kind=item.kind.name();
        String source=sourceForSignal(sql,signalId);
        String semantic=Fingerprint.text(kind+"|"+n(item.summary)+"|"+n(item.person)+"|"+(item.dueAt==null?0:item.dueAt));
        String fingerprint=Fingerprint.text("cognitive-v2|"+policy+"|"+signalId+"|"+semantic);
        JSONObject meta=new JSONObject();
        try{
            meta.put("engine","cognitive_v2");meta.put("policy",policy);meta.put("route",route);meta.put("authority_mode",mode.name());
            meta.put("raw_signal_id",signalId);meta.put("thread_id",Math.max(0,threadId));meta.put("model_run_id",modelRunId);
        }catch(Throwable ignored){}

        ContentValues v=new ContentValues();
        v.put("kind",kind);v.put("title",empty(item.summary)?friendly(kind):item.summary);v.put("body",n(item.summary));v.put("state","open");
        v.put("confidence",confidence);v.put("importance",clamp100(item.importance));v.put("urgency",clamp100(item.urgency));v.put("person_key",n(item.person));
        v.put("due_at",item.dueAt==null?0:Math.max(0,item.dueAt));v.put("requires_user_action",item.requiresUserAction?1:0);v.put("requires_follow_up",item.requiresFollowUp?1:0);v.put("requires_content_extraction",item.requiresContentExtraction?1:0);
        v.put("model_run_id",modelRunId);v.put("priority_score",clamp100(item.importance));v.put("source_key",source);v.put("thread_id",Math.max(0,threadId));v.put("anchor_signal_id",signalId);v.put("candidate_kind",kind);v.put("semantic_key",semantic);v.put("fingerprint",fingerprint);v.put("metadata_json",meta.toString());v.put("created_at",now);v.put("updated_at",now);
        long id=sql.insertWithOnConflict("derived_items",null,v,SQLiteDatabase.CONFLICT_IGNORE);if(id>0)return id;
        Cursor c=sql.query("derived_items",new String[]{"id","model_run_id"},"fingerprint=?",new String[]{fingerprint},null,null,null,"1");
        long existing=0,owner=0;if(c.moveToFirst()){existing=c.getLong(0);owner=c.getLong(1);}c.close();
        return existing>0&&owner==modelRunId?existing:0;
    }

    private static boolean latestMayCommit(SQLiteDatabase sql,long signalId,long threadId){
        if(threadId<=0){
            Cursor c=sql.query("raw_signals",new String[]{"id"},"id=?",new String[]{String.valueOf(signalId)},null,null,null,"1");boolean exists=c.moveToFirst();c.close();return exists;
        }
        Cursor c=sql.rawQuery("SELECT id FROM raw_signals WHERE thread_id=? ORDER BY occurred_at DESC,id DESC LIMIT 1",new String[]{String.valueOf(threadId)});
        long latest=c.moveToFirst()?c.getLong(0):0;c.close();return latest==signalId;
    }

    private static JSONObject output(
            long signalId,CognitiveResult result,LocalBrainRun run,String policy,String route,CognitiveAuthorityMode mode,
            String routingReason,int routingBucket,long generation
    ){
        JSONObject root=new JSONObject();
        try{
            root.put("schema","cognitive_authority_002");root.put("signal_id",signalId);root.put("policy",policy);root.put("route",route);root.put("authority_mode",mode.name());root.put("routing_reason",routingReason);root.put("routing_bucket",routingBucket);root.put("generation",generation);root.put("outcome","ACCEPTED");root.put("disposition",result.disposition.name());root.put("confidence",result.confidence);root.put("reason",clip(result.reason,300));
            JSONArray items=new JSONArray();
            for(CognitiveItem item:result.items){
                if(item==null||item.kind==null)continue;
                JSONObject x=new JSONObject();x.put("kind",item.kind.name());x.put("summary",clip(item.summary,240));x.put("importance",item.importance);x.put("urgency",item.urgency);x.put("person",clip(item.person,120));x.put("due_at",item.dueAt==null?0:item.dueAt);x.put("requires_user_action",item.requiresUserAction);x.put("requires_follow_up",item.requiresFollowUp);x.put("requires_content_extraction",item.requiresContentExtraction);items.put(x);
            }
            root.put("items",items);root.put("queue_wait_ms",run.queueWaitMs);root.put("native_total_ms",run.nativeTotalMs);root.put("total_ms",run.totalMs);root.put("prompt_chars",run.promptChars);root.put("tokens_generated",run.tokensGenerated);root.put("tokens_per_second",run.tokensPerSecond);root.put("cache_hit",run.cacheHit);root.put("wire_schema",run.wireSchema);root.put("enqueued_at",run.enqueuedAt);root.put("native_started_at",run.nativeStartedAt);root.put("native_finished_at",run.nativeFinishedAt);root.put("generation_ms",run.generationMs);root.put("model_load_ms",run.modelLoadMs);root.put("duration_ms",run.durationMs);
        }catch(Throwable ignored){}
        return root;
    }

    private static JSONObject metadata(String policy,String route,CognitiveAuthorityMode mode,String routingReason,int routingBucket,long generation){
        JSONObject meta=new JSONObject();
        try{meta.put("policy",policy);meta.put("route",route);meta.put("authority_mode",mode.name());meta.put("routing_reason",routingReason);meta.put("routing_bucket",routingBucket);meta.put("generation",generation);}catch(Throwable ignored){}
        return meta;
    }

    private static String authorityLinkMetadata(CognitiveAuthorityMode mode,String policy,String route){
        JSONObject meta=new JSONObject();
        try{meta.put("authority",mode.name());meta.put("policy",policy);meta.put("route",route);}catch(Throwable ignored){}
        return meta.toString();
    }

    private static String finalReason(CognitiveAuthorityMode mode,CognitiveResult result,String routingReason){
        String prefix=mode==CognitiveAuthorityMode.V2_PRIMARY?"V2 primary":"V2 canary";
        String reason=prefix+" accepted local result at confidence "+String.format(Locale.US,"%.2f",result.confidence)+" via "+routingReason;
        if("MODEL_IGNORE_SOFTENED".equals(result.reason))reason+=": MODEL_IGNORE_SOFTENED";
        return reason;
    }

    private static String routeFor(CognitiveAuthorityMode mode){return mode==CognitiveAuthorityMode.V2_PRIMARY?"cognitive_v2_primary":"cognitive_v2_canary";}
    private static String normalizedRoutingReason(CognitiveAuthorityMode mode,String value){String clean=n(value).trim();if(!clean.isEmpty())return clean;return mode==CognitiveAuthorityMode.V2_PRIMARY?CognitiveAuthorityRouter.RoutingReason.PRIMARY.name():CognitiveAuthorityRouter.RoutingReason.HASH_CANARY.name();}
    private static String sourceForSignal(SQLiteDatabase sql,long signalId){Cursor c=sql.query("raw_signals",new String[]{"source"},"id=?",new String[]{String.valueOf(signalId)},null,null,null,"1");String x=c.moveToFirst()?n(c.getString(0)):"";c.close();return x;}
    private static String friendly(String kind){String x=n(kind).toLowerCase(Locale.ROOT).replace('_',' ');return x.isEmpty()?"Derived intelligence":Character.toUpperCase(x.charAt(0))+x.substring(1);}
    private static double clamp01(double x){return Math.max(0,Math.min(1,x));}
    private static int clamp100(int x){return Math.max(0,Math.min(100,x));}
    private static String clip(String s,int max){String x=n(s).trim();return x.length()<=max?x:x.substring(0,max);}
    private static boolean empty(String s){return s==null||s.trim().isEmpty();}
    private static String n(String s){return s==null?"":s;}
}
