package com.kareem.cortex;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.*;

/**
 * Structured action candidates produced by Brain.
 * Model output is never trusted as an executable command: types, required fields and provenance
 * are validated locally before anything can reach CortexActionDispatcher.
 */
public final class BrainActionStore {
    private BrainActionStore(){}

    public static final class Action {
        public final long rowId,jobId,sourceItemId;
        public final String key,type,title,status,sourceType,evidenceExcerpt;
        public final double confidence;
        public final JSONObject payload;
        public final JSONArray missing;
        Action(long rowId,long jobId,String key,String type,String title,String status,double confidence,JSONObject payload,JSONArray missing,long sourceItemId,String sourceType,String evidenceExcerpt){
            this.rowId=rowId;this.jobId=jobId;this.key=key;this.type=type;this.title=title;this.status=status;this.confidence=confidence;this.payload=payload;this.missing=missing;this.sourceItemId=sourceItemId;this.sourceType=sourceType;this.evidenceExcerpt=evidenceExcerpt;
        }
        public boolean ready(){return "READY".equals(status);}
    }

    public static final class Parsed {
        public final String answer,primaryIntent;
        public final int actionCount;
        Parsed(String answer,String primaryIntent,int actionCount){this.answer=answer;this.primaryIntent=primaryIntent;this.actionCount=actionCount;}
    }

    private static final Set<String> ALLOWED=new HashSet<>(Arrays.asList(
            "TASK","REMINDER","CALENDAR_EVENT","CALENDAR_RESCHEDULE","CALL","MESSAGE_DRAFT","EMAIL_DRAFT",
            "PROJECT_LINK","FOLLOW_UP","WAIT_FOR","KNOWLEDGE_NOTE","WEB_SEARCH","OPEN_APP"));

    public static void ensure(VaultDb db){
        SQLiteDatabase s=db.getWritableDatabase();
        s.execSQL("CREATE TABLE IF NOT EXISTS brain_action_suggestions(id INTEGER PRIMARY KEY AUTOINCREMENT,job_id INTEGER NOT NULL,action_key TEXT NOT NULL,type TEXT NOT NULL,title TEXT NOT NULL,status TEXT NOT NULL,confidence REAL DEFAULT 0,payload_json TEXT NOT NULL DEFAULT '{}',missing_json TEXT NOT NULL DEFAULT '[]',source_item_id INTEGER DEFAULT 0,source_type TEXT DEFAULT '',evidence_excerpt TEXT DEFAULT '',created_at INTEGER NOT NULL,updated_at INTEGER NOT NULL,UNIQUE(job_id,action_key))");
        s.execSQL("CREATE INDEX IF NOT EXISTS idx_brain_actions_job ON brain_action_suggestions(job_id,id)");
        s.execSQL("CREATE INDEX IF NOT EXISTS idx_brain_actions_status ON brain_action_suggestions(status,updated_at)");
    }

    /** Contract embedded inside the user request so it works across providers/fallbacks. */
    public static String request(String question){
        return "CORTEX_STRUCTURED_RESPONSE_V1\n"
                +"Answer the user's actual question, but return ONLY one valid JSON object and no markdown fences. Preserve Egyptian Arabic/English code-switching naturally in answer/title fields.\n"
                +"Schema: {\"schema_version\":1,\"answer\":\"natural user-facing answer\",\"primary_intent\":\"...\",\"suggested_actions\":[{\"id\":\"act_1\",\"type\":\"TASK|REMINDER|CALENDAR_EVENT|CALENDAR_RESCHEDULE|CALL|MESSAGE_DRAFT|EMAIL_DRAFT|PROJECT_LINK|FOLLOW_UP|WAIT_FOR|KNOWLEDGE_NOTE|WEB_SEARCH|OPEN_APP\",\"title\":\"...\",\"confidence\":0.0,\"source_ref\":\"THIS or M1/M2/... when supported by supplied Cortex evidence, otherwise empty\",\"payload\":{},\"missing_fields\":[]}]}\n"
                +"Rules: answer must always be a non-null human-readable string. suggest only actions that are actually useful; zero actions is valid. Never invent dates, times, phone numbers, email addresses, people, projects, calendar event IDs, app package IDs or other missing private facts. Facts and inferred intent must stay distinct. Use WAIT_FOR when the user is waiting on someone else; KNOWLEDGE_NOTE when information should only be kept; FOLLOW_UP for an unresolved follow-up. CALENDAR_EVENT needs a real date/time from evidence. CALENDAR_RESCHEDULE must never output or guess an event_id; Cortex will ask the user to select the existing event. REMINDER needs a real trigger/due date AND time. PROJECT_LINK must not output project IDs; Cortex will show confirmed projects locally. If required execution data is absent, list it in missing_fields rather than guessing. WEB_SEARCH is allowed; do not claim live deep research. source_ref may only reference THIS or the M-number labels present in supplied evidence; never output database IDs.\n\n"
                +"ACTUAL USER QUESTION:\n"+(question==null?"":question);
    }

    public static Parsed parseAndStore(VaultDb db,long jobId,String raw,KnowledgeItem focal,GroundedAnswer grounded){
        ensure(db);String original=n(raw).trim();JSONObject root=parseObject(original);String answer=safeNaturalFallback(original),primary="";ArrayList<Action> actions=new ArrayList<>();
        if(root!=null){
            answer=jsonString(root,"answer");
            if(answer.isEmpty())answer="Cortex received the model response, but it did not contain a usable natural-language answer.";
            primary=jsonString(root,"primary_intent");JSONArray a=root.optJSONArray("suggested_actions");
            if(a!=null)for(int i=0;i<a.length()&&actions.size()<8;i++){
                JSONObject o=a.optJSONObject(i);if(o==null)continue;String type=jsonString(o,"type").toUpperCase(Locale.ROOT);if(!ALLOWED.contains(type))continue;
                String title=jsonString(o,"title");if(title.isEmpty())continue;String key=jsonString(o,"id").replaceAll("[^A-Za-z0-9_-]","");if(key.isEmpty())key="act_"+(i+1);
                JSONObject payload=o.optJSONObject("payload");if(payload==null)payload=new JSONObject();if("CALENDAR_RESCHEDULE".equals(type))payload.remove("event_id");if("PROJECT_LINK".equals(type))payload.remove("project_id");
                double confidence=Math.max(0,Math.min(1,o.optDouble("confidence",0.65)));
                KnowledgeItem source=resolveSource(jsonString(o,"source_ref"),focal,grounded);Validation v=validate(type,payload,o.optJSONArray("missing_fields"),source!=null);
                long sourceId=source==null?0:source.id;String sourceType=source==null?"":n(source.type);String excerpt=source==null?"":excerpt(source);
                actions.add(new Action(0,jobId,key,type,title,v.status,confidence,payload,v.missing,sourceId,sourceType,excerpt));
            }
        }
        replace(db,jobId,actions);return new Parsed(answer,primary,actions.size());
    }

    public static ArrayList<Action> list(VaultDb db,long jobId){
        ensure(db);ArrayList<Action> out=new ArrayList<>();Cursor c=db.getReadableDatabase().query("brain_action_suggestions",null,"job_id=?",new String[]{String.valueOf(jobId)},null,null,"id ASC");
        while(c.moveToNext()){
            JSONObject p;JSONArray m;try{p=new JSONObject(s(c,"payload_json"));}catch(Exception e){p=new JSONObject();}try{m=new JSONArray(s(c,"missing_json"));}catch(Exception e){m=new JSONArray();}
            out.add(new Action(g(c,"id"),g(c,"job_id"),s(c,"action_key"),s(c,"type"),s(c,"title"),s(c,"status"),d(c,"confidence"),p,m,g(c,"source_item_id"),s(c,"source_type"),s(c,"evidence_excerpt")));
        }c.close();return out;
    }

    public static void markStatus(VaultDb db,long rowId,String status){if(db==null||rowId<=0)return;ensure(db);ContentValues v=new ContentValues();v.put("status",n(status).toUpperCase(Locale.ROOT));v.put("updated_at",System.currentTimeMillis());db.getWritableDatabase().update("brain_action_suggestions",v,"id=?",new String[]{String.valueOf(rowId)});}

    private static void replace(VaultDb db,long jobId,List<Action> actions){
        SQLiteDatabase s=db.getWritableDatabase();long now=System.currentTimeMillis();s.beginTransaction();try{s.delete("brain_action_suggestions","job_id=?",new String[]{String.valueOf(jobId)});for(Action a:actions){ContentValues v=new ContentValues();v.put("job_id",jobId);v.put("action_key",a.key);v.put("type",a.type);v.put("title",a.title);v.put("status",a.status);v.put("confidence",a.confidence);v.put("payload_json",a.payload.toString());v.put("missing_json",a.missing.toString());v.put("source_item_id",a.sourceItemId);v.put("source_type",a.sourceType);v.put("evidence_excerpt",a.evidenceExcerpt);v.put("created_at",now);v.put("updated_at",now);s.insertWithOnConflict("brain_action_suggestions",null,v,SQLiteDatabase.CONFLICT_REPLACE);}s.setTransactionSuccessful();}finally{s.endTransaction();}}

    private static KnowledgeItem resolveSource(String ref,KnowledgeItem focal,GroundedAnswer g){String r=n(ref).trim().toUpperCase(Locale.ROOT);if("THIS".equals(r))return focal;if(r.matches("M[1-9][0-9]*")&&g!=null){try{int i=Integer.parseInt(r.substring(1))-1;if(i>=0&&i<g.sources.size())return g.sources.get(i).item;}catch(Throwable ignored){}}return null;}

    private static Validation validate(String type,JSONObject p,JSONArray modelMissing,boolean hasSource){
        LinkedHashSet<String> missing=new LinkedHashSet<>();if(modelMissing!=null)for(int i=0;i<modelMissing.length();i++){String x=n(modelMissing.optString(i,"")).trim();if(!x.isEmpty()&&!"null".equalsIgnoreCase(x))missing.add(x);}
        if("CALENDAR_EVENT".equals(type)){if(!has(p,"start_time")&&!(has(p,"date")&&has(p,"time")))missing.add("date/time");}
        else if("CALENDAR_RESCHEDULE".equals(type)){missing.add("select existing calendar event");if(!has(p,"new_start_time")&&!(has(p,"new_date")&&has(p,"new_time")))missing.add("new date/time");}
        else if("REMINDER".equals(type)){if(!has(p,"trigger_time")&&!has(p,"due_at")&&!(has(p,"due_date")&&has(p,"due_time")))missing.add("trigger date/time");}
        else if("CALL".equals(type)){if(!has(p,"phone_number"))missing.add("phone number");}
        else if("MESSAGE_DRAFT".equals(type)){if(!has(p,"phone_number")&&!has(p,"recipient"))missing.add("recipient");if(!has(p,"body")&&!has(p,"message"))missing.add("message");}
        else if("EMAIL_DRAFT".equals(type)){if(!has(p,"to"))missing.add("recipient email");}
        else if("PROJECT_LINK".equals(type)){if(!hasSource)missing.add("source capture");}
        else if("WEB_SEARCH".equals(type)){if(!has(p,"query"))missing.add("search query");}
        else if("OPEN_APP".equals(type)){if(!has(p,"package"))missing.add("exact app");}
        JSONArray a=new JSONArray();for(String x:missing)a.put(x);return new Validation(missing.isEmpty()?"READY":"NEEDS_DETAILS",a);
    }
    private static boolean has(JSONObject p,String k){Object x=p.opt(k);return x!=null&&x!=JSONObject.NULL&&!String.valueOf(x).trim().isEmpty()&&!"null".equalsIgnoreCase(String.valueOf(x).trim());}

    private static JSONObject parseObject(String raw){if(raw==null)return null;String x=raw.trim();if(x.startsWith("```")){int nl=x.indexOf('\n');if(nl>=0)x=x.substring(nl+1);int end=x.lastIndexOf("```");if(end>=0)x=x.substring(0,end);}int a=x.indexOf('{'),b=x.lastIndexOf('}');if(a<0||b<=a)return null;try{return new JSONObject(x.substring(a,b+1));}catch(Exception e){return null;}}
    private static String jsonString(JSONObject o,String key){if(o==null||!o.has(key)||o.isNull(key))return"";Object v=o.opt(key);if(v==null||v==JSONObject.NULL)return"";String x=String.valueOf(v).trim();return"null".equalsIgnoreCase(x)?"":x;}
    private static String safeNaturalFallback(String raw){String x=n(raw).trim();if(x.isEmpty()||"null".equalsIgnoreCase(x))return"Cortex did not receive a usable answer.";if(x.startsWith("{")&&x.endsWith("}"))return"Cortex received a structured response that could not be presented safely.";return x;}
    private static String excerpt(KnowledgeItem k){String x=!n(k.extractedText).trim().isEmpty()?k.extractedText:(!n(k.rawText).trim().isEmpty()?k.rawText:(!n(k.summary).trim().isEmpty()?k.summary:k.title));x=n(x).replaceAll("\\s+"," ").trim();return x.length()>420?x.substring(0,420)+"…":x;}
    private static String s(Cursor c,String name){int i=c.getColumnIndex(name);return i<0||c.isNull(i)?"":c.getString(i);}private static long g(Cursor c,String name){int i=c.getColumnIndex(name);return i<0?0:c.getLong(i);}private static double d(Cursor c,String name){int i=c.getColumnIndex(name);return i<0?0:c.getDouble(i);}private static String n(String s){return s==null?"":s;}
    private static final class Validation{final String status;final JSONArray missing;Validation(String status,JSONArray missing){this.status=status;this.missing=missing;}}
}
