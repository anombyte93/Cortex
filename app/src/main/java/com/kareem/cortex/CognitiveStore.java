package com.kareem.cortex;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Small write API over the unified cognitive schema. Runtime V2 authority commits delegate to CognitiveDecisionApplier. */
public final class CognitiveStore {
    private CognitiveStore(){}

    public static void ensure(VaultDb db){CognitiveSchema.ensure(db.getWritableDatabase());}

    public static void link(VaultDb db,String fromType,long fromId,String toType,long toId,String relation,double confidence,String metadataJson){linkChecked(db,fromType,fromId,toType,toId,relation,confidence,metadataJson);}
    public static boolean linkChecked(VaultDb db,String fromType,long fromId,String toType,long toId,String relation,double confidence,String metadataJson){
        if(fromId<=0||toId<=0||empty(fromType)||empty(toType)||empty(relation))return false;
        ensure(db);ContentValues v=new ContentValues();v.put("from_type",fromType);v.put("from_id",fromId);v.put("to_type",toType);v.put("to_id",toId);v.put("relation",relation);v.put("confidence",confidence);v.put("metadata_json",n(metadataJson));v.put("created_at",System.currentTimeMillis());
        long id=db.getWritableDatabase().insertWithOnConflict("source_links",null,v,SQLiteDatabase.CONFLICT_IGNORE);if(id>0)return true;
        Cursor c=db.getReadableDatabase().query("source_links",new String[]{"id"},"from_type=? AND from_id=? AND to_type=? AND to_id=? AND relation=?",new String[]{fromType,String.valueOf(fromId),toType,String.valueOf(toId),relation},null,null,null,"1");boolean exists=c.moveToFirst();c.close();return exists;
    }

    public static long addDerived(VaultDb db,String kind,String title,String body,String state,double confidence,int importance,String fingerprint,String metadataJson){
        ensure(db);long now=System.currentTimeMillis();ContentValues v=new ContentValues();v.put("kind",n(kind).toUpperCase());v.put("title",empty(title)?friendly(kind):title.trim());v.put("body",n(body));v.put("state",empty(state)?"open":state);v.put("confidence",confidence);v.put("importance",importance);v.put("fingerprint",n(fingerprint));v.put("metadata_json",n(metadataJson));v.put("created_at",now);v.put("updated_at",now);
        SQLiteDatabase sql=db.getWritableDatabase();long id=sql.insertWithOnConflict("derived_items",null,v,SQLiteDatabase.CONFLICT_IGNORE);if(id>0)return id;
        if(empty(fingerprint))return id;
        Cursor c=sql.query("derived_items",new String[]{"id","state"},"fingerprint=?",new String[]{fingerprint},null,null,null,"1");long existing=0;String existingState="";if(c.moveToFirst()){existing=c.getLong(0);existingState=n(c.getString(1));}c.close();if(existing<=0)return 0;
        if(active(existingState))return existing;
        ContentValues history=new ContentValues();history.put("fingerprint",Fingerprint.text("historical-derived|"+fingerprint+"|"+existing));history.put("updated_at",now);if(sql.update("derived_items",history,"id=? AND fingerprint=?",new String[]{String.valueOf(existing),fingerprint})<=0)return 0;
        long retry=sql.insertWithOnConflict("derived_items",null,v,SQLiteDatabase.CONFLICT_IGNORE);if(retry>0)return retry;
        Cursor current=sql.query("derived_items",new String[]{"id","state"},"fingerprint=?",new String[]{fingerprint},null,null,null,"1");long currentId=0;if(current.moveToFirst()&&active(n(current.getString(1))))currentId=current.getLong(0);current.close();return currentId;
    }

    public static void setDerivedRouting(VaultDb db,long derivedId,String sourceKey,long threadId,long anchorSignalId,String candidateKind){setDerivedRoutingChecked(db,derivedId,sourceKey,threadId,anchorSignalId,candidateKind,"");}
    public static boolean setDerivedRoutingChecked(VaultDb db,long derivedId,String sourceKey,long threadId,long anchorSignalId,String candidateKind,String semanticKey){
        if(db==null||derivedId<=0)return false;ensure(db);ContentValues v=new ContentValues();v.put("source_key",n(sourceKey));v.put("thread_id",Math.max(0,threadId));v.put("anchor_signal_id",Math.max(0,anchorSignalId));v.put("candidate_kind",n(candidateKind).toUpperCase());if(!empty(semanticKey))v.put("semantic_key",semanticKey);v.put("updated_at",System.currentTimeMillis());return db.getWritableDatabase().update("derived_items",v,"id=?",new String[]{String.valueOf(derivedId)})>0;
    }

    /** Compatibility helper retained for non-authority callers; production V2 writes use CognitiveDecisionApplier. */
    public static long addCognitiveItem(VaultDb db,CognitiveItem item,long signalId,long threadId,long modelRunId,String policy){
        if(db==null||item==null||item.kind==null||signalId<=0||modelRunId<=0)return 0;
        ensure(db);SQLiteDatabase sql=db.getWritableDatabase();long now=System.currentTimeMillis();String kind=item.kind.name();String source=sourceForSignal(sql,signalId);double confidence=modelConfidence(sql,modelRunId);
        String semantic=Fingerprint.text(kind+"|"+n(item.summary)+"|"+n(item.person)+"|"+(item.dueAt==null?0:item.dueAt));
        String fingerprint=Fingerprint.text("cognitive-v2-compat|"+n(policy)+"|"+signalId+"|"+semantic);
        JSONObject meta=new JSONObject();try{meta.put("engine","cognitive_v2");meta.put("policy",n(policy));meta.put("authority","COMPAT");meta.put("raw_signal_id",signalId);meta.put("thread_id",Math.max(0,threadId));meta.put("model_run_id",modelRunId);}catch(Throwable ignored){}
        ContentValues v=new ContentValues();v.put("kind",kind);v.put("title",empty(item.summary)?friendly(kind):item.summary);v.put("body",n(item.summary));v.put("state","open");v.put("confidence",confidence);v.put("importance",clamp100(item.importance));v.put("urgency",clamp100(item.urgency));v.put("person_key",n(item.person));v.put("due_at",item.dueAt==null?0:Math.max(0,item.dueAt));v.put("requires_user_action",item.requiresUserAction?1:0);v.put("requires_follow_up",item.requiresFollowUp?1:0);v.put("requires_content_extraction",item.requiresContentExtraction?1:0);v.put("model_run_id",modelRunId);v.put("priority_score",clamp100(item.importance));v.put("source_key",source);v.put("thread_id",Math.max(0,threadId));v.put("anchor_signal_id",signalId);v.put("candidate_kind",kind);v.put("semantic_key",semantic);v.put("fingerprint",fingerprint);v.put("metadata_json",meta.toString());v.put("created_at",now);v.put("updated_at",now);
        long id=sql.insertWithOnConflict("derived_items",null,v,SQLiteDatabase.CONFLICT_IGNORE);if(id>0)return id;
        Cursor c=sql.query("derived_items",new String[]{"id","model_run_id"},"fingerprint=?",new String[]{fingerprint},null,null,null,"1");long existing=0,owner=0;if(c.moveToFirst()){existing=c.getLong(0);owner=c.getLong(1);}c.close();return existing>0&&owner==modelRunId?existing:0;
    }

    /** Backward-compatible Canary API. The single authority transaction lives in CognitiveDecisionApplier. */
    public static CanaryApply applyCanaryAuthority(VaultDb db,long signalId,long threadId,CognitiveResult result,LocalBrainRun run,long latencyMs,String inputHash,String policy){
        return applyCanaryAuthority(db,signalId,threadId,result,run,latencyMs,inputHash,policy,CognitiveAuthorityRouter.RoutingReason.HASH_CANARY.name(),-1);
    }

    public static CanaryApply applyCanaryAuthority(
            VaultDb db,long signalId,long threadId,CognitiveResult result,LocalBrainRun run,long latencyMs,
            String inputHash,String policy,String routingReason,int routingBucket
    ){
        try{
            CognitiveDecisionApplier.ApplyResult applied=CognitiveDecisionApplier.apply(
                    db,signalId,threadId,result,run,latencyMs,inputHash,
                    CognitiveAuthorityMode.CANARY,policy,routingReason,routingBucket,0L
            );
            return new CanaryApply(applied.modelRunId,applied.derivedIds,applied.disposition);
        }catch(IllegalStateException error){
            if("STALE_GENERATION".equals(error.getMessage()))throw new IllegalStateException("CANARY_SUPERSEDED");
            throw error;
        }
    }

    public static boolean updateRawCognitiveState(VaultDb db,long signalId,String cognitiveState,String version,String finalReason){
        if(db==null||signalId<=0)return false;ensure(db);long now=System.currentTimeMillis();ContentValues v=new ContentValues();v.put("cognitive_state",n(cognitiveState));v.put("cognitive_version",n(version));v.put("final_reason",n(finalReason));v.put("cognitive_updated_at",now);v.put("updated_at",now);return db.getWritableDatabase().update("raw_signals",v,"id=?",new String[]{String.valueOf(signalId)})==1;
    }

    public static void feedback(VaultDb db,String targetType,long targetId,String eventType,String valueJson,String policyVersion){
        if(targetId<=0||empty(targetType)||empty(eventType))return;ensure(db);String source="",candidate="";
        if("derived".equalsIgnoreCase(targetType)){
            Cursor c=db.getReadableDatabase().query("derived_items",new String[]{"source_key","candidate_kind","kind"},"id=?",new String[]{String.valueOf(targetId)},null,null,null,"1");
            if(c.moveToFirst()){source=n(c.getString(0));candidate=n(c.getString(1));if(candidate.isEmpty()&&"REVIEW".equalsIgnoreCase(n(c.getString(2))))candidate="UNKNOWN";}c.close();
        }
        ContentValues v=new ContentValues();v.put("target_type",targetType);v.put("target_id",targetId);v.put("event_type",eventType);v.put("value_json",n(valueJson));v.put("policy_version",n(policyVersion));v.put("source_key",source);v.put("candidate_kind",candidate.toUpperCase());v.put("scope_key",source+"|"+candidate.toUpperCase());v.put("created_at",System.currentTimeMillis());db.getWritableDatabase().insert("feedback_events",null,v);
    }

    public static String schemaRevision(VaultDb db){ensure(db);Cursor c=db.getReadableDatabase().query("schema_meta",new String[]{"value"},"key='cognitive_schema'",null,null,null,null,"1");String x=c.moveToFirst()?c.getString(0):"";c.close();return x==null?"":x;}

    private static String sourceForSignal(SQLiteDatabase sql,long signalId){Cursor c=sql.query("raw_signals",new String[]{"source"},"id=?",new String[]{String.valueOf(signalId)},null,null,null,"1");String x=c.moveToFirst()?n(c.getString(0)):"";c.close();return x;}
    private static double modelConfidence(SQLiteDatabase sql,long modelRunId){Cursor c=sql.query("model_runs",new String[]{"confidence"},"id=?",new String[]{String.valueOf(modelRunId)},null,null,null,"1");double x=c.moveToFirst()?c.getDouble(0):0;c.close();return x;}
    private static boolean active(String state){return"open".equalsIgnoreCase(n(state))||"pending".equalsIgnoreCase(n(state));}
    private static String friendly(String kind){String x=n(kind).toLowerCase(Locale.ROOT).replace('_',' ');return x.isEmpty()?"Derived intelligence":Character.toUpperCase(x.charAt(0))+x.substring(1);}
    private static int clamp100(int x){return Math.max(0,Math.min(100,x));}
    private static boolean empty(String s){return s==null||s.trim().isEmpty();}
    private static String n(String s){return s==null?"":s;}

    public static final class CanaryApply{
        public final long modelRunId;public final List<Long> derivedIds;public final CognitiveDisposition disposition;
        CanaryApply(long modelRunId,List<Long> derivedIds,CognitiveDisposition disposition){this.modelRunId=modelRunId;this.derivedIds=java.util.Collections.unmodifiableList(new ArrayList<>(derivedIds));this.disposition=disposition;}
    }
}
