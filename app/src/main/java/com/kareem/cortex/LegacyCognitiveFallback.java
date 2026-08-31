package com.kareem.cortex;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import org.json.JSONObject;

/**
 * The only production gateway into legacy cognitive authority/model adjudication.
 * Every V2 transfer is explicit and idempotently claimed from a V2 local state.
 */
public final class LegacyCognitiveFallback {
    private static final String LEGACY_VERSION="legacy-cognitive-003";
    private LegacyCognitiveFallback(){}

    public static void fallback(Context context,long signalId,long threadId,V2FailureReason reason){
        if(context==null||signalId<=0)return;
        Context app=context.getApplicationContext();VaultDb db=new VaultDb(app);
        try{fallback(app,db,signalId,threadId,reason,null,true,true);}
        finally{try{db.close();}catch(Throwable ignored){}}
    }

    /** Normal LEGACY authority also passes through this gateway so ThreadModelAdjudicator has one caller. */
    static void runLegacyAuthority(Context context,VaultDb db,long signalId,long threadId){
        if(db==null||signalId<=0)return;
        runLegacy(context,db,signalId,threadId,"","",true);
    }

    /** Recovery/test entry point using the caller's DB handle. */
    static boolean fallback(
            Context context,VaultDb db,long signalId,long threadId,V2FailureReason reason,
            boolean requireClaim,boolean enqueueLegacyModel
    ){
        return fallback(context,db,signalId,threadId,reason,null,requireClaim,enqueueLegacyModel);
    }

    /** Internal path may carry mode explicitly when failure happens before the V2 policy is persisted. */
    static boolean fallback(
            Context context,VaultDb db,long signalId,long threadId,V2FailureReason reason,
            CognitiveAuthorityMode explicitMode,boolean requireClaim,boolean enqueueLegacyModel
    ){
        if(db==null||signalId<=0)return false;
        V2FailureReason why=reason==null?V2FailureReason.MODEL_FAILED:reason;
        if(why==V2FailureReason.SUPERSEDED){markSuperseded(db,signalId,currentVersion(db,signalId));return false;}
        if(threadId>0&&latestSignalId(db.getReadableDatabase(),threadId)!=signalId){markSuperseded(db,signalId,currentVersion(db,signalId));return false;}

        String version=currentVersion(db,signalId);
        String prefix=explicitMode==CognitiveAuthorityMode.V2_PRIMARY||version.startsWith(CognitiveAdjudicatorV2.PRIMARY_POLICY)
                ?"V2 primary":"V2 canary";
        if(requireClaim&&!claim(db,signalId,prefix,why))return false;
        return runLegacy(context,db,signalId,threadId,prefix,why.name(),enqueueLegacyModel);
    }

    /** Old relevance-job recovery may request a retry, but the actual enqueue still lives here. */
    static void resumeLegacyModel(Context context,long threadId,long signalId){
        if(context==null||threadId<=0||signalId<=0)return;
        VaultDb db=new VaultDb(context.getApplicationContext());
        try{
            if(latestSignalId(db.getReadableDatabase(),threadId)!=signalId)return;
            if(RawSignalStore.shouldEnqueueLegacyModel(db,signalId))ThreadModelAdjudicator.enqueue(context.getApplicationContext(),threadId,signalId);
        }finally{try{db.close();}catch(Throwable ignored){}}
    }

    private static boolean runLegacy(
            Context context,VaultDb db,long signalId,long threadId,
            String fallbackPrefix,String fallbackReason,boolean enqueueLegacyModel
    ){
        Stored stored=load(db,signalId);if(stored==null)return false;
        try{
            RawSignalStore.runLegacyPipeline(db,signalId,threadId,stored.signal,stored.fast);
            RawSignalStore.syncLegacyCognitiveState(db,signalId,fallbackPrefix,fallbackReason);
            DiagnosticsLog.info(
                    db,"LegacyCognitiveFallback",
                    fallbackReason.isEmpty()?"LEGACY_AUTHORITY":"V2_EXPLICIT_FALLBACK",
                    fallbackReason.isEmpty()?"legacy":fallbackReason,
                    0,threadId,signalId,0,0,0,metadata(fallbackPrefix,fallbackReason)
            );
            if(enqueueLegacyModel&&context!=null&&threadId>0&&RawSignalStore.shouldEnqueueLegacyModel(db,signalId)){
                ThreadModelAdjudicator.enqueue(context.getApplicationContext(),threadId,signalId);
            }
            return true;
        }catch(Throwable error){
            String detail=error.getClass().getSimpleName()+(error.getMessage()==null?"":": "+error.getMessage());
            CognitiveStore.updateRawCognitiveState(
                    db,signalId,"LEGACY_UNRESOLVED",LEGACY_VERSION,
                    (fallbackPrefix.isEmpty()?"Legacy authority failed":fallbackPrefix+" fallback ("+fallbackReason+") failed")+": "+detail
            );
            try{DiagnosticsLog.error(db,"LegacyCognitiveFallback","legacy_authority",error,"LEGACY_FALLBACK_FAILED",0,threadId,signalId,0,0,null);}catch(Throwable ignored){}
            return false;
        }
    }

    private static boolean claim(VaultDb db,long signalId,String prefix,V2FailureReason reason){
        long now=System.currentTimeMillis();ContentValues v=new ContentValues();
        v.put("cognitive_state","LEGACY_FALLBACK_RUNNING");v.put("final_reason",prefix+" fallback ("+reason.name()+") -> Legacy authority starting");v.put("cognitive_updated_at",now);v.put("updated_at",now);
        int updated=db.getWritableDatabase().update(
                "raw_signals",v,"id=? AND cognitive_state IN ('LOCAL_QUEUED','LOCAL_RUNNING')",new String[]{String.valueOf(signalId)}
        );
        return updated==1;
    }

    private static Stored load(VaultDb db,long signalId){
        Cursor c=db.getReadableDatabase().query(
                "raw_signals",new String[]{"kind","source","title","body","metadata_json","occurred_at","disposition","importance","reason","confidence"},
                "id=?",new String[]{String.valueOf(signalId)},null,null,null,"1"
        );
        if(!c.moveToFirst()){c.close();return null;}
        String kind=n(c.getString(0)),source=n(c.getString(1)),title=n(c.getString(2)),body=n(c.getString(3)),meta=n(c.getString(4));long occurred=c.getLong(5);String disposition=n(c.getString(6));int importance=c.getInt(7);String reason=n(c.getString(8));double confidence=c.getDouble(9);c.close();
        boolean ongoing=false;try{ongoing=new JSONObject(meta).optBoolean("ongoing",false);}catch(Throwable ignored){}
        MasterRelevanceFilter.Signal signal=new MasterRelevanceFilter.Signal(kind,source,title,body,meta,occurred,ongoing);
        MasterRelevanceFilter.Disposition d;try{d=MasterRelevanceFilter.Disposition.valueOf(disposition);}catch(Throwable ignored){d=MasterRelevanceFilter.Disposition.CONTEXT;}
        return new Stored(signal,new MasterRelevanceFilter.Decision(d,importance,reason,"",confidence));
    }

    private static void markSuperseded(VaultDb db,long signalId,String version){
        CognitiveStore.updateRawCognitiveState(db,signalId,"SUPERSEDED",empty(version)?LEGACY_VERSION:version,"Newer signal superseded V2 authority before Legacy fallback");
    }

    private static String currentVersion(VaultDb db,long signalId){Cursor c=db.getReadableDatabase().query("raw_signals",new String[]{"cognitive_version"},"id=?",new String[]{String.valueOf(signalId)},null,null,null,"1");String value=c.moveToFirst()?n(c.getString(0)):"";c.close();return value;}
    private static long latestSignalId(SQLiteDatabase sql,long threadId){Cursor c=sql.rawQuery("SELECT id FROM raw_signals WHERE thread_id=? ORDER BY occurred_at DESC,id DESC LIMIT 1",new String[]{String.valueOf(threadId)});long id=c.moveToFirst()?c.getLong(0):0;c.close();return id;}
    private static JSONObject metadata(String prefix,String reason){JSONObject o=new JSONObject();try{o.put("fallback_prefix",prefix);o.put("failure_reason",reason);}catch(Throwable ignored){}return o;}
    private static boolean empty(String value){return value==null||value.trim().isEmpty();}
    private static String n(String value){return value==null?"":value.trim();}

    private static final class Stored{
        final MasterRelevanceFilter.Signal signal;final MasterRelevanceFilter.Decision fast;
        Stored(MasterRelevanceFilter.Signal signal,MasterRelevanceFilter.Decision fast){this.signal=signal;this.fast=fast;}
    }
}
