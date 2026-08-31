package com.kareem.cortex;

import android.content.Context;
import android.database.Cursor;

import java.util.ArrayList;

/** Recovers abandoned V2 authority states after process/service restart. */
public final class CognitiveRecoverySweep {
    private static final long STALE_MS=60_000L;
    private CognitiveRecoverySweep(){}

    public static int run(Context context,VaultDb db){
        return run(context,db,true);
    }

    static int run(Context context,VaultDb db,boolean enqueueLegacyModel){
        if(db==null)return 0;
        CognitiveStore.ensure(db);
        long now=System.currentTimeMillis(),cutoff=now-STALE_MS;
        ArrayList<Target> targets=new ArrayList<>();
        Cursor c=db.getReadableDatabase().query(
                "raw_signals",
                new String[]{"id","thread_id","cognitive_state","cognitive_version","cognitive_updated_at"},
                "cognitive_state IN ('LOCAL_QUEUED','LOCAL_RUNNING') AND cognitive_updated_at<?",
                new String[]{String.valueOf(cutoff)},null,null,"cognitive_updated_at ASC","100"
        );
        while(c.moveToNext()){
            targets.add(new Target(c.getLong(0),c.getLong(1),n(c.getString(2)),n(c.getString(3)),c.getLong(4)));
        }
        c.close();

        int recovered=0;
        for(Target t:targets){
            if(t.threadId>0&&latestSignalId(db,t.threadId)!=t.signalId){
                CognitiveStore.updateRawCognitiveState(
                        db,t.signalId,"SUPERSEDED",t.version,
                        "Recovery superseded stale V2 generation; a newer thread signal exists"
                );
                DiagnosticsLog.info(db,"CognitiveRecoverySweep","STALE_GENERATION","superseded",0,t.threadId,t.signalId,0,0,Math.max(0,now-t.updatedAt),null);
                continue;
            }
            boolean applied=LegacyCognitiveFallback.fallback(
                    context,db,t.signalId,t.threadId,V2FailureReason.MODEL_FAILED,true,enqueueLegacyModel
            );
            if(applied)recovered++;
            DiagnosticsLog.info(
                    db,"CognitiveRecoverySweep","MODEL_FAILED",applied?"legacy_fallback":"already_recovered",
                    0,t.threadId,t.signalId,0,0,Math.max(0,now-t.updatedAt),null
            );
        }
        return recovered;
    }

    private static long latestSignalId(VaultDb db,long threadId){
        Cursor c=db.getReadableDatabase().rawQuery(
                "SELECT id FROM raw_signals WHERE thread_id=? ORDER BY occurred_at DESC,id DESC LIMIT 1",
                new String[]{String.valueOf(threadId)}
        );
        long id=c.moveToFirst()?c.getLong(0):0;c.close();return id;
    }

    private static String n(String value){return value==null?"":value.trim();}

    private static final class Target{
        final long signalId,threadId,updatedAt;
        final String state,version;
        Target(long signalId,long threadId,String state,String version,long updatedAt){
            this.signalId=signalId;this.threadId=threadId;this.state=state;this.version=version;this.updatedAt=updatedAt;
        }
    }
}
