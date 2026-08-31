package com.kareem.cortex;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

/** Process-death recovery for the legacy relevance adjudicator. Semantic baselines are never reset to CONTEXT. */
public final class AdjudicationRecovery {
    private static final long STALE_MS=5L*60L*1000L;
    private static final String UNIQUE_WORK="cortex-adjudication-recovery";
    private AdjudicationRecovery(){}

    public static int run(Context context,VaultDb db){
        if(context==null||db==null)return 0;
        CognitiveStore.ensure(db);RelevanceDecisionStatusStore.ensure(db);
        long now=System.currentTimeMillis(),cutoff=now-STALE_MS;
        ArrayList<Target> retry=new ArrayList<>();SQLiteDatabase sql=db.getWritableDatabase();
        Cursor c=sql.query(
                "ai_jobs",new String[]{"id","input_json","updated_at"},
                "kind='relevance_adjudication' AND state IN ('queued','running') AND updated_at<?",
                new String[]{String.valueOf(cutoff)},null,null,"updated_at ASC","100"
        );
        while(c.moveToNext()){
            long jobId=c.getLong(0),lastUpdated=c.getLong(2),staleAge=Math.max(0,now-lastUpdated);
            String input=c.getString(1)==null?"":c.getString(1);long threadId=0,signalId=0;
            try{JSONObject o=new JSONObject(input);threadId=o.optLong("thread_id",0);signalId=o.optLong("latest_signal_id",0);}catch(Exception ignored){}
            ContentValues j=new ContentValues();j.put("state","failed");j.put("error","PROCESS_INTERRUPTED");j.put("progress_json",progress(now));j.put("updated_at",now);j.put("completed_at",now);sql.update("ai_jobs",j,"id=?",new String[]{String.valueOf(jobId)});
            ContentValues r=new ContentValues();r.put("state","interrupted");r.put("error","PROCESS_INTERRUPTED");sql.update("model_runs",r,"job_id=? AND state NOT IN ('complete','invalid','superseded')",new String[]{String.valueOf(jobId)});
            if(signalId>0)RelevanceDecisionStatusStore.modelStatus(db,signalId,"PROCESS_INTERRUPTED");
            if(threadId>0&&signalId>0&&latestSignalId(sql,threadId)==signalId)retry.add(new Target(threadId,signalId));
            DiagnosticsLog.info(db,"AdjudicationRecovery","process_interrupted","recovered",0,threadId,signalId,jobId,0,staleAge,null);
        }
        c.close();
        // ThreadModelAdjudicator is never invoked directly here; LegacyCognitiveFallback owns that gateway.
        if(LocalModelManager.installed(context))for(Target t:retry)LegacyCognitiveFallback.resumeLegacyModel(context,t.threadId,t.signalId);
        return retry.size();
    }

    public static void schedule(Context context){
        if(context==null)return;
        try{
            PeriodicWorkRequest request=new PeriodicWorkRequest.Builder(AdjudicationRecoveryWorker.class,15,TimeUnit.MINUTES).build();
            WorkManager.getInstance(context.getApplicationContext()).enqueueUniquePeriodicWork(UNIQUE_WORK,ExistingPeriodicWorkPolicy.KEEP,request);
        }catch(Throwable ignored){}
    }

    private static long latestSignalId(SQLiteDatabase sql,long threadId){Cursor c=sql.rawQuery("SELECT id FROM raw_signals WHERE thread_id=? ORDER BY occurred_at DESC,id DESC LIMIT 1",new String[]{String.valueOf(threadId)});long id=c.moveToFirst()?c.getLong(0):0;c.close();return id;}
    private static String progress(long now){try{return new JSONObject().put("stage","Interrupted").put("code","process_interrupted").put("percent",100).put("detail","Android process ended before local adjudication completed; safe retry may be scheduled").put("at",now).toString();}catch(Exception e){return"{}";}}
    private static final class Target{final long threadId,signalId;Target(long t,long s){threadId=t;signalId=s;}}
}
