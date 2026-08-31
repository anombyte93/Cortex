package com.kareem.cortex;

import android.content.Context;
import android.database.Cursor;
import androidx.annotation.NonNull;
import androidx.work.*;
import java.util.*;

/** Background-only refinement. Failure never changes Today; deterministic attention remains valid. */
public final class AttentionAiWorker extends Worker {
    private static final int BATCH=4;
    public AttentionAiWorker(@NonNull Context c,@NonNull WorkerParameters p){super(c,p);}
    @NonNull @Override public Result doWork(){Context ctx=getApplicationContext();if(!ExternalBrainProvider.configured(ctx))return Result.success();VaultDb db=new VaultDb(ctx);try{
        CognitiveStore.ensure(db);AttentionAdjudicationStore.ensure(db);ArrayList<Candidate> candidates=new ArrayList<>();long now=System.currentTimeMillis();
        Cursor c=db.getReadableDatabase().rawQuery("SELECT id,kind,title,body,source_key,state,confidence,importance,thread_id,anchor_signal_id,updated_at FROM derived_items WHERE state='open' AND kind IN ('ACTION','WAITING','DECISION') ORDER BY updated_at DESC LIMIT 160",null);
        try{while(c.moveToNext()){
            PrimeBriefStore.Item item=new PrimeBriefStore.Item(c.getLong(0),c.getString(1),c.getString(2),c.getString(3),c.getString(4),c.getString(5),c.getDouble(6),c.getInt(7),c.getLong(8),c.getLong(9),c.getLong(10));
            if(AttentionAdjudicationStore.fresh(db,item.id,item.updatedAt))continue;
            AttentionEngine.Decision baseline=AttentionEngine.evaluate(item,now);candidates.add(new Candidate(item,baseline));
        }}finally{c.close();}
        candidates.sort((a,b)->Integer.compare(b.baseline.score,a.baseline.score));int done=0,eligibleRemaining=0;
        for(Candidate cand:candidates){if(isStopped())break;AttentionContextBuilder.Pack pack=AttentionContextBuilder.build(ctx,db,cand.item);if(!pack.usable())continue;if(done>=BATCH){eligibleRemaining++;continue;}try{AttentionAiAdjudicator.Result r=AttentionAiAdjudicator.adjudicate(ctx,cand.item,cand.baseline,pack);if(r!=null)AttentionAdjudicationStore.save(db,cand.item,cand.baseline,r.modelScore,r.merged,r.confidence,r.provider,r.evidence);}catch(Throwable ignored){}done++;}
        if(eligibleRemaining>0&&!isStopped())AttentionAiScheduler.continueChain(ctx);return Result.success();
    }catch(Throwable e){return Result.success();}finally{try{db.close();}catch(Throwable ignored){}}
    }
    static final class Candidate{final PrimeBriefStore.Item item;final AttentionEngine.Decision baseline;Candidate(PrimeBriefStore.Item i,AttentionEngine.Decision b){item=i;baseline=b;}}
}
