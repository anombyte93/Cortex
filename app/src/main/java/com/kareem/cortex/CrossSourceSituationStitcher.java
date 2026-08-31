package com.kareem.cortex;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import org.json.JSONObject;
import java.util.*;

/**
 * Conservatively links fresh evidence to an already-open situation when multiple signals agree.
 * It never creates a new situation and never reopens a resolved one.
 */
public final class CrossSourceSituationStitcher {
    private static final long WINDOW=7L*24L*60L*60L*1000L;
    private CrossSourceSituationStitcher(){}

    public static long stitchSignal(VaultDb db,long signalId,long personEntityId){
        if(db==null||signalId<=0)return 0;SQLiteDatabase sql=db.getWritableDatabase();CognitiveSchema.ensure(sql);
        Signal s=signal(sql,signalId);if(s==null)return 0;
        Set<String> words=tokens(s.title+" "+s.body);if(words.isEmpty()&&personEntityId<=0)return 0;
        long cutoff=Math.max(0,s.at-WINDOW);Cursor c=sql.rawQuery("SELECT id,title,body,updated_at,metadata_json FROM derived_items WHERE state='open' AND kind IN ('SITUATION','ACTION','WAITING','DECISION','REMINDER') AND updated_at>=? ORDER BY updated_at DESC LIMIT 120",new String[]{String.valueOf(cutoff)});
        long best=0;double bestScore=0;while(c.moveToNext()){
            long id=c.getLong(0);String title=n(c.getString(1)),body=n(c.getString(2));double score=jaccard(words,tokens(title+" "+body));
            boolean samePerson=personEntityId>0&&linkedToEntity(sql,"derived",id,personEntityId);
            if(samePerson)score+=0.42;
            if(score>bestScore){bestScore=score;best=id;}
        }c.close();
        // Semantic similarity alone is insufficient. Require either same person + topic support, or very strong topic overlap.
        boolean accept=best>0&&(bestScore>=0.62||(personEntityId>0&&bestScore>=0.48));if(!accept)return 0;
        ContentValues v=new ContentValues();v.put("from_type","raw_signal");v.put("from_id",signalId);v.put("to_type","derived");v.put("to_id",best);v.put("relation","supports_situation");v.put("confidence",Math.min(.96,bestScore));try{v.put("metadata_json",new JSONObject().put("policy","cross_source_stitch_v1").put("person_entity_id",personEntityId).put("score",bestScore).toString());}catch(Exception ignored){v.put("metadata_json","{}");}v.put("created_at",System.currentTimeMillis());sql.insertWithOnConflict("source_links",null,v,SQLiteDatabase.CONFLICT_IGNORE);return best;
    }

    private static boolean linkedToEntity(SQLiteDatabase db,String type,long id,long entity){Cursor c=db.rawQuery("SELECT 1 FROM source_links WHERE from_type=? AND from_id=? AND to_type='entity' AND to_id=? LIMIT 1",new String[]{type,String.valueOf(id),String.valueOf(entity)});boolean ok=c.moveToFirst();c.close();return ok;}
    private static Signal signal(SQLiteDatabase db,long id){Cursor c=db.rawQuery("SELECT title,body,occurred_at FROM raw_signals WHERE id=? LIMIT 1",new String[]{String.valueOf(id)});Signal s=c.moveToFirst()?new Signal(n(c.getString(0)),n(c.getString(1)),c.getLong(2)):null;c.close();return s;}
    private static Set<String> tokens(String x){String z=LocalSemanticEmbedder.norm(n(x));Set<String> out=new LinkedHashSet<>();for(String w:z.split(" "))if(w.length()>=3&&!stop(w))out.add(w);return out;}
    private static boolean stop(String w){String[] s={"the","and","for","this","that","with","from","your","you","was","are","في","من","على","الى","إلى","عن","هو","هي","كان","كانت","ده","دي"};for(String x:s)if(x.equals(w))return true;return false;}
    private static double jaccard(Set<String>a,Set<String>b){if(a.isEmpty()||b.isEmpty())return 0;int inter=0;for(String x:a)if(b.contains(x))inter++;int union=a.size()+b.size()-inter;return union==0?0:inter/(double)union;}
    private static String n(String s){return s==null?"":s.trim();}
    private static final class Signal{final String title,body;final long at;Signal(String t,String b,long a){title=t;body=b;at=a;}}
}
