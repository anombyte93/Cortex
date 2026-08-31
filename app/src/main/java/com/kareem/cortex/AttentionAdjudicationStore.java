package com.kareem.cortex;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

/** Cached model refinements. Today only reads this table; it never performs network work. */
public final class AttentionAdjudicationStore {
    public static final String VERSION="attention_ai_001";
    private static final long TTL_MS=12L*60L*60L*1000L;
    private AttentionAdjudicationStore(){}

    /**
     * This table is an optional refinement cache, never a prerequisite for the canonical Today
     * read model. Schema creation may transiently contend with another writer on real devices;
     * failure therefore degrades to deterministic attention instead of blanking the brief.
     */
    public static void ensure(VaultDb db){
        if(db==null)return;try{
            db.getWritableDatabase().execSQL("CREATE TABLE IF NOT EXISTS attention_adjudications (derived_id INTEGER PRIMARY KEY,item_updated_at INTEGER NOT NULL,baseline_score INTEGER NOT NULL,model_score INTEGER NOT NULL,final_score INTEGER NOT NULL,band TEXT NOT NULL,why_now TEXT NOT NULL,confidence REAL NOT NULL,provider TEXT NOT NULL,evidence TEXT NOT NULL,policy_version TEXT NOT NULL,adjudicated_at INTEGER NOT NULL,expires_at INTEGER NOT NULL)");
            db.getWritableDatabase().execSQL("CREATE INDEX IF NOT EXISTS idx_attention_adjudications_expiry ON attention_adjudications(expires_at)");
        }catch(Throwable e){DiagnosticsLog.error(db,"AttentionAdjudicationStore","ensure",e,"ATTENTION_CACHE_SCHEMA",0,0,0,0,0,null);}
    }

    public static AttentionEngine.Decision applyFresh(VaultDb db,PrimeBriefStore.Item item,AttentionEngine.Decision baseline){
        if(db==null||item==null||baseline==null)return baseline;Cursor c=null;try{
            ensure(db);long now=System.currentTimeMillis();
            c=db.getReadableDatabase().query("attention_adjudications",new String[]{"item_updated_at","final_score","band","why_now","confidence","expires_at"},"derived_id=?",new String[]{String.valueOf(item.id)},null,null,null,"1");
            if(!c.moveToFirst())return baseline;
            if(c.getLong(0)!=item.updatedAt||c.getLong(5)<=now||c.getDouble(4)<0.72)return baseline;
            AttentionEngine.Band band;try{band=AttentionEngine.Band.valueOf(c.getString(2));}catch(Exception e){return baseline;}
            return new AttentionEngine.Decision(c.getInt(1),band,c.getString(3),baseline.urgency,baseline.consequence,baseline.responsibility,baseline.temporalPressure,baseline.openLoopPressure,baseline.novelty,Math.max(baseline.confidence,c.getDouble(4)));
        }catch(Throwable e){DiagnosticsLog.error(db,"AttentionAdjudicationStore","apply_fresh",e,"ATTENTION_CACHE_READ",item.id,item.threadId,item.signalId,0,0,null);return baseline;}finally{if(c!=null)try{c.close();}catch(Throwable ignored){}}
    }

    public static void save(VaultDb db,PrimeBriefStore.Item item,AttentionEngine.Decision baseline,int modelScore,AttentionEngine.Decision merged,double confidence,String provider,String evidence){
        if(db==null||item==null||baseline==null||merged==null)return;try{ensure(db);long now=System.currentTimeMillis();ContentValues v=new ContentValues();
            v.put("derived_id",item.id);v.put("item_updated_at",item.updatedAt);v.put("baseline_score",baseline.score);v.put("model_score",modelScore);v.put("final_score",merged.score);v.put("band",merged.band.name());v.put("why_now",n(merged.whyNow));v.put("confidence",confidence);v.put("provider",n(provider));v.put("evidence",clip(evidence,900));v.put("policy_version",VERSION);v.put("adjudicated_at",now);v.put("expires_at",now+TTL_MS);
            db.getWritableDatabase().insertWithOnConflict("attention_adjudications",null,v,SQLiteDatabase.CONFLICT_REPLACE);
        }catch(Throwable e){DiagnosticsLog.error(db,"AttentionAdjudicationStore","save",e,"ATTENTION_CACHE_WRITE",item.id,item.threadId,item.signalId,0,0,null);}
    }

    public static boolean fresh(VaultDb db,long derivedId,long itemUpdatedAt){Cursor c=null;try{ensure(db);c=db.getReadableDatabase().query("attention_adjudications",new String[]{"derived_id"},"derived_id=? AND item_updated_at=? AND expires_at>?",new String[]{String.valueOf(derivedId),String.valueOf(itemUpdatedAt),String.valueOf(System.currentTimeMillis())},null,null,null,"1");return c.moveToFirst();}catch(Throwable e){DiagnosticsLog.error(db,"AttentionAdjudicationStore","fresh",e,"ATTENTION_CACHE_READ",derivedId,0,0,0,0,null);return false;}finally{if(c!=null)try{c.close();}catch(Throwable ignored){}}}
    private static String n(String s){return s==null?"":s.trim();}
    private static String clip(String s,int n){String x=n(s).replaceAll("\\s+"," ");return x.length()<=n?x:x.substring(0,n)+"…";}
}
