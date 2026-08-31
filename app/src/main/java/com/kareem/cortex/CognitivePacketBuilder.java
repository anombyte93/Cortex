package com.kareem.cortex;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.*;

/** Builds the exact transport-independent state packet used by both teacher and student. */
public final class CognitivePacketBuilder {
    private CognitivePacketBuilder(){}

    public static final class Packet {
        public final JSONObject json;
        public final Set<String> validRefs;
        Packet(JSONObject json,Set<String> validRefs){this.json=json;this.validRefs=Collections.unmodifiableSet(validRefs);}
    }

    public static Packet build(VaultDb vault,String question){
        SQLiteDatabase db=vault.getReadableDatabase();
        long now=System.currentTimeMillis();
        LinkedHashSet<String> refs=new LinkedHashSet<>();
        JSONObject root=new JSONObject();
        JSONObject state=new JSONObject();
        try{
            root.put("schema_version",CognitiveDecisionContract.VERSION);
            root.put("packet_id","cp_"+now);
            root.put("generated_at",now);
            root.put("question",n(question).isEmpty()?"What should Cortex do with this state?":n(question));
            state.put("attention",derived(db,refs,"NOW",new String[]{"ALERT","ACTION","REMINDER","CHANGE"},24));
            state.put("waiting",derived(db,refs,"WAITING",new String[]{"WAITING"},20));
            state.put("decisions",derived(db,refs,"DECISION",new String[]{"DECISION"},20));
            state.put("goals",derived(db,refs,"GOAL",new String[]{"GOAL_SIGNAL","PROJECT_CANDIDATE"},20));
            state.put("situations",derived(db,refs,"SITUATION",new String[]{"ACTION","REMINDER","WAITING","DECISION","ALERT","CHANGE","GOAL_SIGNAL","PROJECT_CANDIDATE"},60));
            root.put("current_state",state);
            root.put("new_evidence",evidence(db,refs,now));
            JSONArray allowed=new JSONArray();for(String x:CognitiveDecisionContract.ALLOWED)allowed.put(x);root.put("allowed_decisions",allowed);
            root.put("fidelity","FULL");
            root.put("redaction",false);
        }catch(Exception e){throw new IllegalStateException("Cannot build cognitive packet",e);}
        return new Packet(root,refs);
    }

    private static JSONArray derived(SQLiteDatabase db,Set<String> refs,String role,String[] kinds,int limit)throws Exception{
        JSONArray out=new JSONArray(); if(kinds==null||kinds.length==0)return out;
        StringBuilder qs=new StringBuilder();for(int i=0;i<kinds.length;i++){if(i>0)qs.append(',');qs.append('?');}
        ArrayList<String> args=new ArrayList<>(Arrays.asList(kinds));args.add(String.valueOf(limit));
        Cursor c=db.rawQuery("SELECT id,kind,title,body,state,confidence,importance,source_key,thread_id,anchor_signal_id,candidate_kind,semantic_key,metadata_json,created_at,updated_at,resolved_at FROM derived_items WHERE state IN ('open','pending') AND kind IN ("+qs+") ORDER BY importance DESC,updated_at DESC LIMIT ?",args.toArray(new String[0]));
        try{while(c.moveToNext()){
            String ref="S"+(refs.size()+1);refs.add(ref);
            JSONObject o=new JSONObject();
            o.put("ref",ref).put("role",role).put("local_id",c.getLong(0)).put("kind",s(c,1)).put("title",s(c,2)).put("body",s(c,3)).put("state",s(c,4)).put("confidence",c.getDouble(5)).put("importance",c.getInt(6)).put("source_key",s(c,7)).put("thread_id",c.getLong(8)).put("anchor_signal_id",c.getLong(9)).put("candidate_kind",s(c,10)).put("semantic_key",s(c,11)).put("metadata_json",s(c,12)).put("created_at",c.getLong(13)).put("updated_at",c.getLong(14)).put("resolved_at",c.getLong(15));
            out.put(o);
        }}finally{c.close();}
        return out;
    }

    private static JSONArray evidence(SQLiteDatabase db,Set<String> refs,long now)throws Exception{
        JSONArray out=new JSONArray();
        Cursor r=db.rawQuery("SELECT id,kind,source,title,body,metadata_json,state,disposition,importance,reason,thread_id,confidence,occurred_at,created_at,updated_at FROM raw_signals ORDER BY occurred_at DESC LIMIT 100",null);
        try{while(r.moveToNext()){
            String ref="E"+(refs.size()+1);refs.add(ref);
            JSONObject o=new JSONObject().put("ref",ref).put("origin","raw_signal").put("local_id",r.getLong(0)).put("kind",s(r,1)).put("source",s(r,2)).put("title",s(r,3)).put("body",s(r,4)).put("metadata_json",s(r,5)).put("state",s(r,6)).put("disposition",s(r,7)).put("importance",r.getInt(8)).put("reason",s(r,9)).put("thread_id",r.getLong(10)).put("confidence",r.getDouble(11)).put("occurred_at",r.getLong(12)).put("created_at",r.getLong(13)).put("updated_at",r.getLong(14));
            out.put(o);
        }}finally{r.close();}
        Cursor k=db.rawQuery("SELECT id,type,source,title,raw_text,extracted_text,summary,category,tags,status,metadata_json,created_at,updated_at FROM knowledge_items ORDER BY updated_at DESC LIMIT 60",null);
        try{while(k.moveToNext()){
            String ref="E"+(refs.size()+1);refs.add(ref);
            JSONObject o=new JSONObject().put("ref",ref).put("origin","knowledge_item").put("local_id",k.getLong(0)).put("type",s(k,1)).put("source",s(k,2)).put("title",s(k,3)).put("raw_text",s(k,4)).put("extracted_text",s(k,5)).put("summary",s(k,6)).put("category",s(k,7)).put("tags",s(k,8)).put("status",s(k,9)).put("metadata_json",s(k,10)).put("created_at",k.getLong(11)).put("updated_at",k.getLong(12));
            out.put(o);
        }}finally{k.close();}
        return out;
    }

    private static String s(Cursor c,int i){return c.isNull(i)?"":c.getString(i);}
    private static String n(String s){return s==null?"":s.trim();}
}
