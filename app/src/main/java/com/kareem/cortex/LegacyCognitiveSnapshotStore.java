package com.kareem.cortex;

import android.database.Cursor;

/** Reads the best available legacy decision without mutating it. */
public final class LegacyCognitiveSnapshotStore {
    private LegacyCognitiveSnapshotStore(){}

    public static LegacyCognitiveSnapshot get(VaultDb db,long signalId){
        if(db==null||signalId<=0)return new LegacyCognitiveSnapshot("","",0,"");
        CognitiveStore.ensure(db);
        Cursor e=db.getReadableDatabase().query(
                "relevance_evaluations",
                new String[]{"final_disposition","final_candidate","final_confidence","final_engine","learned_disposition","learned_candidate","learned_confidence"},
                "signal_id=?",new String[]{String.valueOf(signalId)},null,null,null,"1");
        if(e.moveToFirst()){
            String finalDisposition=n(e.getString(0));
            if(!finalDisposition.isEmpty()){
                LegacyCognitiveSnapshot result=new LegacyCognitiveSnapshot(finalDisposition,e.getString(1),e.getDouble(2),e.getString(3));e.close();return result;
            }
            String learned=n(e.getString(4));
            if(!learned.isEmpty()){
                LegacyCognitiveSnapshot result=new LegacyCognitiveSnapshot(learned,e.getString(5),e.getDouble(6),"legacy_learned");e.close();return result;
            }
        }
        e.close();
        Cursor r=db.getReadableDatabase().query(
                "raw_signals",new String[]{"disposition","confidence","filter_engine"},
                "id=?",new String[]{String.valueOf(signalId)},null,null,null,"1");
        LegacyCognitiveSnapshot result=r.moveToFirst()
                ?new LegacyCognitiveSnapshot(r.getString(0),"",r.getDouble(1),r.getString(2))
                :new LegacyCognitiveSnapshot("","",0,"");
        r.close();return result;
    }

    private static String n(String s){return s==null?"":s.trim();}
}
