package com.kareem.cortex;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import org.json.JSONObject;

/**
 * PRIME identity guard.
 * A mention is not an identified person, and an inferred phrase is not a confirmed project.
 */
public final class EntityGraphMaintenance {
    public static final String VERSION="entity_graph_guard_005";
    private EntityGraphMaintenance(){}

    public static void run(VaultDb db){
        if(db==null)return;
        SQLiteDatabase s=db.getWritableDatabase();long now=System.currentTimeMillis();int peopleQuarantined=0,projectsQuarantined=0,identified=0,candidatesDismissed=0;
        s.beginTransaction();
        try{
            CognitiveStore.ensure(db);
            ContentValues q=new ContentValues();q.put("status","quarantined");q.put("updated_at",now);
            projectsQuarantined=s.update("entity_nodes",q,"status='active' AND upper(kind) LIKE '%PROJECT%' AND COALESCE(metadata_json,'') NOT LIKE '%\"created_from\":\"project_candidate\"%'",null);
            peopleQuarantined=s.update("entity_nodes",q,"status='active' AND (upper(kind) LIKE '%PERSON%' OR upper(kind) LIKE '%CONTACT%' OR upper(kind)='PEOPLE') AND COALESCE(metadata_json,'') LIKE '%\"migrated_from\":\"entities\"%'",null);

            Cursor pc=s.query("derived_items",new String[]{"id","title"},"kind='PROJECT_CANDIDATE' AND state IN ('pending','open')",null,null,null,null);
            while(pc.moveToNext()){long id=pc.getLong(0);String title=n(pc.getString(1));if(EntityQualityPolicy.plausibleProject(title))continue;ContentValues d=new ContentValues();d.put("state","dismissed");d.put("resolved_at",now);d.put("updated_at",now);candidatesDismissed+=s.update("derived_items",d,"id=?",new String[]{String.valueOf(id)});}pc.close();

            Cursor c=s.rawQuery("SELECT id,title,raw_text,metadata_json FROM knowledge_items WHERE type='CONTACT' AND source='contacts_sync'",null);
            while(c.moveToNext()){
                long memoryId=c.getLong(0);String original=n(c.getString(1)),raw=n(c.getString(2)),meta=n(c.getString(3));JSONObject srcMeta;try{srcMeta=new JSONObject(meta);}catch(Exception e){srcMeta=new JSONObject();}long contactId=srcMeta.optLong("contact_id",0);String phone=phoneIdentity(meta,raw);if(contactId<=0&&phone.isEmpty())continue;
                String name=EntityDisplayNamePolicy.cleanContactName(original);if(name.isEmpty())name=EntityDisplayNamePolicy.cleanContactName(firstLine(raw));if(name.isEmpty())name="Contact";
                String anchorKind=contactId>0?"contact_id":"phone";String identityValue=contactId>0?String.valueOf(contactId):phone;String key="person|"+anchorKind+"|"+identityValue;long entity=findEntity(s,key);boolean userRenamed=false;String oldMeta="";
                if(entity>0){Cursor e=s.query("entity_nodes",new String[]{"metadata_json"},"id=?",new String[]{String.valueOf(entity)},null,null,null,"1");if(e.moveToFirst())oldMeta=n(e.getString(0));e.close();userRenamed=oldMeta.contains("\"user_renamed\":true");}
                JSONObject em=new JSONObject();em.put("identity","phone");em.put("identity_anchor",anchorKind);if(contactId>0)em.put("contact_id",contactId);if(!phone.isEmpty())em.put("phone_identity",phone);em.put("source","contacts_sync");em.put("guard",VERSION);if(userRenamed)em.put("user_renamed",true);
                if(entity<=0){ContentValues v=new ContentValues();v.put("kind","PERSON");v.put("canonical_name",name);v.put("normalized_key",key);v.put("status","active");v.put("metadata_json",em.toString());v.put("created_at",now);v.put("updated_at",now);entity=s.insertWithOnConflict("entity_nodes",null,v,SQLiteDatabase.CONFLICT_IGNORE);if(entity<=0)entity=findEntity(s,key);}
                else{ContentValues v=new ContentValues();if(!userRenamed)v.put("canonical_name",name);v.put("status","active");v.put("metadata_json",em.toString());v.put("updated_at",now);s.update("entity_nodes",v,"id=?",new String[]{String.valueOf(entity)});}
                if(entity<=0)continue;identified++;
                addAlias(s,entity,"contacts_sync",original,1.0,now);if(!name.equals(original))addAlias(s,entity,"contacts_sync",name,1.0,now);if(!phone.isEmpty())addAlias(s,entity,"phone",phone,1.0,now);
                ContentValues l=new ContentValues();l.put("from_type","memory");l.put("from_id",memoryId);l.put("to_type","entity");l.put("to_id",entity);l.put("relation","identified_as");l.put("confidence",1.0);l.put("metadata_json","{\"identity\":\"phone\",\"anchor\":\""+anchorKind+"\",\"guard\":\""+VERSION+"\"}");l.put("created_at",now);s.insertWithOnConflict("source_links",null,l,SQLiteDatabase.CONFLICT_IGNORE);
            }c.close();
            s.setTransactionSuccessful();
        }catch(Throwable e){DiagnosticsLog.error(db,"EntityGraphMaintenance","run",e,"ENTITY_GRAPH_GUARD",0,0,0,0,0,null);}
        finally{s.endTransaction();}
        try{JSONObject m=new JSONObject();m.put("people_quarantined",peopleQuarantined);m.put("projects_quarantined",projectsQuarantined);m.put("weak_candidates_dismissed",candidatesDismissed);m.put("identified_contact_nodes",identified);m.put("policy",VERSION);DiagnosticsLog.info(db,"EntityGraphMaintenance","identity_guard","ok",0,0,0,0,0,0,m);}catch(Throwable ignored){}
    }

    public static boolean rename(VaultDb db,long entityId,String newName){String x=n(newName).replaceAll("\\s+"," ");if(db==null||entityId<=0||x.length()<2||x.length()>80)return false;try{SQLiteDatabase s=db.getWritableDatabase();Cursor c=s.query("entity_nodes",new String[]{"metadata_json"},"id=? AND status='active'",new String[]{String.valueOf(entityId)},null,null,null,"1");if(!c.moveToFirst()){c.close();return false;}String meta=n(c.getString(0));c.close();JSONObject o;try{o=new JSONObject(meta);}catch(Exception e){o=new JSONObject();}o.put("user_renamed",true);o.put("guard",VERSION);ContentValues v=new ContentValues();v.put("canonical_name",x);v.put("metadata_json",o.toString());v.put("updated_at",System.currentTimeMillis());return s.update("entity_nodes",v,"id=?",new String[]{String.valueOf(entityId)})>0;}catch(Throwable ignored){return false;}}

    public static boolean dismissEntity(VaultDb db,long entityId){if(db==null||entityId<=0)return false;try{ContentValues v=new ContentValues();v.put("status","dismissed");v.put("updated_at",System.currentTimeMillis());int n=db.getWritableDatabase().update("entity_nodes",v,"id=?",new String[]{String.valueOf(entityId)});if(n>0)CognitiveStore.feedback(db,"entity",entityId,"dismiss","{\"reason\":\"not_an_entity\"}",VERSION);return n>0;}catch(Throwable ignored){return false;}}

    public static boolean dismissCandidate(VaultDb db,long id){if(db==null||id<=0)return false;try{ContentValues v=new ContentValues();v.put("state","dismissed");v.put("resolved_at",System.currentTimeMillis());v.put("updated_at",System.currentTimeMillis());int n=db.getWritableDatabase().update("derived_items",v,"id=? AND kind='PROJECT_CANDIDATE'",new String[]{String.valueOf(id)});if(n>0)CognitiveStore.feedback(db,"derived",id,"dismiss","{\"reason\":\"not_a_project\"}",VERSION);return n>0;}catch(Throwable ignored){return false;}}

    private static long findEntity(SQLiteDatabase s,String key){Cursor c=s.query("entity_nodes",new String[]{"id"},"normalized_key=?",new String[]{key},null,null,null,"1");long id=c.moveToFirst()?c.getLong(0):0;c.close();return id;}
    private static void addAlias(SQLiteDatabase s,long entity,String source,String alias,double confidence,long now){String a=n(alias);if(a.isEmpty())return;ContentValues v=new ContentValues();v.put("entity_id",entity);v.put("source",source);v.put("alias",a);v.put("normalized_alias",LocalSemanticEmbedder.norm(a));v.put("confidence",confidence);v.put("metadata_json","{\"guard\":\""+VERSION+"\"}");v.put("created_at",now);s.insertWithOnConflict("entity_aliases",null,v,SQLiteDatabase.CONFLICT_IGNORE);}
    private static String phoneIdentity(String metadata,String raw){try{String x=new JSONObject(n(metadata)).optString("phone_identity","");String c=PhoneNumberNormalizer.canonical(x);if(!c.isEmpty())return c;}catch(Exception ignored){}int i=n(raw).indexOf("Phone:");if(i<0)return"";String p=n(raw).substring(i+6).trim().split("\\n",2)[0].trim();return PhoneNumberNormalizer.canonical(p);}
    private static String firstLine(String x){String s=n(x);int i=s.indexOf('\n');return n(i<0?s:s.substring(0,i));}
    private static String n(String s){return s==null?"":s.trim();}
}