package com.kareem.cortex;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import org.json.JSONObject;
import java.util.Locale;

/**
 * Source-aware person resolution for communication evidence.
 * A name hint alone is never allowed to merge identities across unrelated sources.
 */
public final class CanonicalPersonResolver {
    private CanonicalPersonResolver(){}

    public static long resolveSignal(VaultDb db,long signalId,CommunicationEvidenceNormalizer.Result n){
        if(db==null||signalId<=0||n==null||!n.communication)return 0;
        String alias=clean(n.personHint);if(alias.isEmpty())return 0;
        String source=clean(n.source).toLowerCase(Locale.US);if(source.isEmpty())source="unknown";
        SQLiteDatabase sql=db.getWritableDatabase();CognitiveSchema.ensure(sql);
        String normalized=normalize(alias);if(normalized.isEmpty())return 0;

        long entityId=findAlias(sql,source,normalized);
        if(entityId<=0){
            // Only reuse a global canonical person when there is corroboration through an existing alias.
            long corroborated=findCorroborated(sql,normalized);
            entityId=corroborated>0?corroborated:createPerson(sql,alias,source,normalized);
            addAlias(sql,entityId,source,alias,normalized,corroborated>0?0.86:0.72);
        }
        if(entityId>0){
            String meta="{}";
            try{meta=new JSONObject().put("source",source).put("alias",alias).put("policy","source_scoped_identity").toString();}catch(Exception ignored){}
            link(sql,"raw_signal",signalId,"entity",entityId,"person_hint",0.78,meta);
        }
        return entityId;
    }

    private static long findAlias(SQLiteDatabase db,String source,String normalized){Cursor c=db.rawQuery("SELECT entity_id FROM entity_aliases WHERE source=? AND normalized_alias=? ORDER BY confidence DESC,id ASC LIMIT 1",new String[]{source,normalized});long id=c.moveToFirst()?c.getLong(0):0;c.close();return id;}

    private static long findCorroborated(SQLiteDatabase db,String normalized){
        Cursor c=db.rawQuery("SELECT entity_id,COUNT(DISTINCT source) n FROM entity_aliases WHERE normalized_alias=? GROUP BY entity_id HAVING n>=2 ORDER BY n DESC LIMIT 1",new String[]{normalized});long id=c.moveToFirst()?c.getLong(0):0;c.close();return id;
    }

    private static long createPerson(SQLiteDatabase db,String name,String source,String normalized){
        String key="person|"+source+"|"+normalized;ContentValues v=new ContentValues();long now=System.currentTimeMillis();v.put("kind","PERSON");v.put("canonical_name",name);v.put("normalized_key",key);v.put("status","active");try{v.put("metadata_json",new JSONObject().put("created_from","communication_hint").put("source",source).toString());}catch(Exception ignored){v.put("metadata_json","{}");}v.put("created_at",now);v.put("updated_at",now);long id=db.insertWithOnConflict("entity_nodes",null,v,SQLiteDatabase.CONFLICT_IGNORE);if(id>0)return id;Cursor c=db.rawQuery("SELECT id FROM entity_nodes WHERE normalized_key=? LIMIT 1",new String[]{key});long existing=c.moveToFirst()?c.getLong(0):0;c.close();return existing;
    }

    private static void addAlias(SQLiteDatabase db,long entityId,String source,String alias,String normalized,double confidence){if(entityId<=0)return;ContentValues v=new ContentValues();v.put("entity_id",entityId);v.put("source",source);v.put("alias",alias);v.put("normalized_alias",normalized);v.put("confidence",confidence);v.put("metadata_json","{\"policy\":\"source_scoped_identity\"}");v.put("created_at",System.currentTimeMillis());db.insertWithOnConflict("entity_aliases",null,v,SQLiteDatabase.CONFLICT_IGNORE);}

    private static void link(SQLiteDatabase db,String fromType,long fromId,String toType,long toId,String relation,double confidence,String meta){ContentValues v=new ContentValues();v.put("from_type",fromType);v.put("from_id",fromId);v.put("to_type",toType);v.put("to_id",toId);v.put("relation",relation);v.put("confidence",confidence);v.put("metadata_json",meta);v.put("created_at",System.currentTimeMillis());db.insertWithOnConflict("source_links",null,v,SQLiteDatabase.CONFLICT_IGNORE);}

    static String normalize(String s){return clean(s).toLowerCase(Locale.US).replace('أ','ا').replace('إ','ا').replace('آ','ا').replace('ى','ي').replaceAll("[^\\p{L}\\p{N}@+._ -]"," ").replaceAll("\\s+"," ").trim();}
    private static String clean(String s){return s==null?"":s.replace('\n',' ').replace('\r',' ').replaceAll("\\s+"," ").trim();}
}
