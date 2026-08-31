package com.kareem.cortex;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import org.json.JSONObject;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Unified cognitive schema layered on top of the existing Cortex database.
 * Legacy memory tables remain intact while PRIME-V2 data is normalized around
 * raw evidence, durable memory, derived intelligence, provenance, feedback and AI execution.
 */
public final class CognitiveSchema {
    public static final int DB_VERSION = 7;
    public static final String REVISION = "cognitive_004";
    private static final Set<String> READY = Collections.newSetFromMap(new ConcurrentHashMap<>());

    private CognitiveSchema(){}

    public static void ensure(SQLiteDatabase db){
        if(db==null)return;
        String readyKey=String.valueOf(db.getPath())+"|"+REVISION;
        if(READY.contains(readyKey))return;
        synchronized(CognitiveSchema.class){
            if(READY.contains(readyKey))return;
            createMeta(db);
            createRawSignals(db);
            createThreads(db);
            createDerivedItems(db);
            createEntityGraph(db);
            createSourceLinks(db);
            createFeedback(db);
            createAiJobs(db);
            createModelRuns(db);
            createDiagnostics(db);
            createRelevanceEvaluations(db);
            migrateLegacyEntities(db);
            backfillDerivedRouting(db);
            backfillFeedbackRouting(db);
            backfillCognitiveState(db);
            backfillDerivedV7(db);
            db.execSQL("INSERT OR REPLACE INTO schema_meta(key,value,updated_at) VALUES('cognitive_schema','"+REVISION+"',strftime('%s','now')*1000)");
            READY.add(readyKey);
        }
    }

    private static void createMeta(SQLiteDatabase db){db.execSQL("CREATE TABLE IF NOT EXISTS schema_meta(key TEXT PRIMARY KEY,value TEXT NOT NULL,updated_at INTEGER NOT NULL)");}

    private static void createRawSignals(SQLiteDatabase db){
        db.execSQL("CREATE TABLE IF NOT EXISTS raw_signals(id INTEGER PRIMARY KEY AUTOINCREMENT,kind TEXT NOT NULL,source TEXT,title TEXT,body TEXT,metadata_json TEXT,fingerprint TEXT UNIQUE,state TEXT DEFAULT 'filtered',disposition TEXT,importance INTEGER DEFAULT 0,reason TEXT,promoted_item_id INTEGER DEFAULT 0,occurred_at INTEGER NOT NULL,retention_until INTEGER DEFAULT 0,created_at INTEGER NOT NULL,updated_at INTEGER NOT NULL)");
        addColumn(db,"raw_signals","thread_id","INTEGER DEFAULT 0");addColumn(db,"raw_signals","confidence","REAL DEFAULT 0");addColumn(db,"raw_signals","policy_version","TEXT DEFAULT ''");addColumn(db,"raw_signals","filter_engine","TEXT DEFAULT ''");addColumn(db,"raw_signals","content_hash","TEXT DEFAULT ''");
        addColumn(db,"raw_signals","cognitive_state","TEXT DEFAULT ''");addColumn(db,"raw_signals","cognitive_version","TEXT DEFAULT ''");addColumn(db,"raw_signals","final_reason","TEXT DEFAULT ''");addColumn(db,"raw_signals","cognitive_updated_at","INTEGER DEFAULT 0");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_raw_signal_time ON raw_signals(occurred_at DESC)");db.execSQL("CREATE INDEX IF NOT EXISTS idx_raw_signal_disposition ON raw_signals(disposition,occurred_at DESC)");db.execSQL("CREATE INDEX IF NOT EXISTS idx_raw_signal_source ON raw_signals(source,occurred_at DESC)");db.execSQL("CREATE INDEX IF NOT EXISTS idx_raw_signal_thread ON raw_signals(thread_id,occurred_at ASC)");db.execSQL("CREATE INDEX IF NOT EXISTS idx_raw_signal_state ON raw_signals(state,updated_at DESC)");db.execSQL("CREATE INDEX IF NOT EXISTS idx_raw_cognitive_state ON raw_signals(cognitive_state,updated_at DESC)");
    }

    private static void createThreads(SQLiteDatabase db){
        db.execSQL("CREATE TABLE IF NOT EXISTS signal_threads(id INTEGER PRIMARY KEY AUTOINCREMENT,kind TEXT NOT NULL,source TEXT NOT NULL,external_key TEXT NOT NULL,title TEXT,participant_key TEXT,state TEXT DEFAULT 'open',summary TEXT,metadata_json TEXT,started_at INTEGER NOT NULL,last_event_at INTEGER NOT NULL,created_at INTEGER NOT NULL,updated_at INTEGER NOT NULL,UNIQUE(kind,source,external_key))");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_threads_recent ON signal_threads(last_event_at DESC)");db.execSQL("CREATE INDEX IF NOT EXISTS idx_threads_state ON signal_threads(state,last_event_at DESC)");db.execSQL("CREATE INDEX IF NOT EXISTS idx_threads_active_key ON signal_threads(kind,source,external_key,state,last_event_at DESC)");
    }

    private static void createDerivedItems(SQLiteDatabase db){
        db.execSQL("CREATE TABLE IF NOT EXISTS derived_items(id INTEGER PRIMARY KEY AUTOINCREMENT,kind TEXT NOT NULL,title TEXT NOT NULL,body TEXT,state TEXT DEFAULT 'open',confidence REAL DEFAULT 0,importance INTEGER DEFAULT 0,fingerprint TEXT,metadata_json TEXT,created_at INTEGER NOT NULL,updated_at INTEGER NOT NULL,resolved_at INTEGER DEFAULT 0)");
        addColumn(db,"derived_items","source_key","TEXT DEFAULT ''");addColumn(db,"derived_items","thread_id","INTEGER DEFAULT 0");addColumn(db,"derived_items","anchor_signal_id","INTEGER DEFAULT 0");addColumn(db,"derived_items","candidate_kind","TEXT DEFAULT ''");addColumn(db,"derived_items","semantic_key","TEXT DEFAULT ''");
        addColumn(db,"derived_items","urgency","INTEGER DEFAULT 0");addColumn(db,"derived_items","person_key","TEXT DEFAULT ''");addColumn(db,"derived_items","due_at","INTEGER DEFAULT 0");addColumn(db,"derived_items","requires_user_action","INTEGER DEFAULT 0");addColumn(db,"derived_items","requires_follow_up","INTEGER DEFAULT 0");addColumn(db,"derived_items","requires_content_extraction","INTEGER DEFAULT 0");addColumn(db,"derived_items","model_run_id","INTEGER DEFAULT 0");addColumn(db,"derived_items","priority_score","REAL DEFAULT 0");
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_derived_fingerprint ON derived_items(fingerprint) WHERE fingerprint IS NOT NULL AND fingerprint<>''");db.execSQL("CREATE INDEX IF NOT EXISTS idx_derived_kind_state ON derived_items(kind,state,importance DESC,updated_at DESC)");db.execSQL("CREATE INDEX IF NOT EXISTS idx_derived_recent ON derived_items(updated_at DESC)");db.execSQL("CREATE INDEX IF NOT EXISTS idx_derived_review_route ON derived_items(kind,state,candidate_kind,source_key,thread_id,updated_at DESC)");db.execSQL("CREATE INDEX IF NOT EXISTS idx_derived_anchor ON derived_items(anchor_signal_id,kind,state)");db.execSQL("CREATE INDEX IF NOT EXISTS idx_derived_semantic ON derived_items(thread_id,kind,state,semantic_key,updated_at DESC)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_derived_priority ON derived_items(state,priority_score DESC,urgency DESC,updated_at DESC)");db.execSQL("CREATE INDEX IF NOT EXISTS idx_derived_due ON derived_items(state,due_at ASC)");db.execSQL("CREATE INDEX IF NOT EXISTS idx_derived_model_run ON derived_items(model_run_id)");db.execSQL("CREATE INDEX IF NOT EXISTS idx_derived_content_queue ON derived_items(requires_content_extraction,state,importance DESC)");
    }

    private static void createEntityGraph(SQLiteDatabase db){
        db.execSQL("CREATE TABLE IF NOT EXISTS entity_nodes(id INTEGER PRIMARY KEY AUTOINCREMENT,kind TEXT NOT NULL,canonical_name TEXT NOT NULL,normalized_key TEXT NOT NULL UNIQUE,status TEXT DEFAULT 'active',metadata_json TEXT,created_at INTEGER NOT NULL,updated_at INTEGER NOT NULL)");db.execSQL("CREATE INDEX IF NOT EXISTS idx_entity_kind ON entity_nodes(kind,canonical_name)");
        db.execSQL("CREATE TABLE IF NOT EXISTS entity_aliases(id INTEGER PRIMARY KEY AUTOINCREMENT,entity_id INTEGER NOT NULL,source TEXT,alias TEXT NOT NULL,normalized_alias TEXT NOT NULL,confidence REAL DEFAULT 0,metadata_json TEXT,created_at INTEGER NOT NULL,UNIQUE(entity_id,source,normalized_alias))");db.execSQL("CREATE INDEX IF NOT EXISTS idx_entity_alias_lookup ON entity_aliases(normalized_alias,source)");
    }

    private static void createSourceLinks(SQLiteDatabase db){
        db.execSQL("CREATE TABLE IF NOT EXISTS source_links(id INTEGER PRIMARY KEY AUTOINCREMENT,from_type TEXT NOT NULL,from_id INTEGER NOT NULL,to_type TEXT NOT NULL,to_id INTEGER NOT NULL,relation TEXT NOT NULL,confidence REAL DEFAULT 0,metadata_json TEXT,created_at INTEGER NOT NULL,UNIQUE(from_type,from_id,to_type,to_id,relation))");db.execSQL("CREATE INDEX IF NOT EXISTS idx_source_links_from ON source_links(from_type,from_id,relation)");db.execSQL("CREATE INDEX IF NOT EXISTS idx_source_links_to ON source_links(to_type,to_id,relation)");
    }

    private static void createFeedback(SQLiteDatabase db){
        db.execSQL("CREATE TABLE IF NOT EXISTS feedback_events(id INTEGER PRIMARY KEY AUTOINCREMENT,target_type TEXT NOT NULL,target_id INTEGER NOT NULL,event_type TEXT NOT NULL,value_json TEXT,policy_version TEXT,created_at INTEGER NOT NULL)");
        addColumn(db,"feedback_events","source_key","TEXT DEFAULT ''");addColumn(db,"feedback_events","candidate_kind","TEXT DEFAULT ''");addColumn(db,"feedback_events","scope_key","TEXT DEFAULT ''");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_feedback_target ON feedback_events(target_type,target_id,created_at DESC)");db.execSQL("CREATE INDEX IF NOT EXISTS idx_feedback_type ON feedback_events(event_type,created_at DESC)");db.execSQL("CREATE INDEX IF NOT EXISTS idx_feedback_learning ON feedback_events(candidate_kind,source_key,created_at DESC)");db.execSQL("CREATE INDEX IF NOT EXISTS idx_feedback_scope ON feedback_events(scope_key,event_type,created_at DESC)");
    }

    private static void createAiJobs(SQLiteDatabase db){
        db.execSQL("CREATE TABLE IF NOT EXISTS ai_jobs(id INTEGER PRIMARY KEY AUTOINCREMENT,job_key TEXT UNIQUE,kind TEXT NOT NULL,state TEXT NOT NULL DEFAULT 'queued',priority INTEGER DEFAULT 50,source_mode TEXT DEFAULT 'auto',input_json TEXT,progress_json TEXT,output_json TEXT,error TEXT,created_at INTEGER NOT NULL,updated_at INTEGER NOT NULL,started_at INTEGER DEFAULT 0,completed_at INTEGER DEFAULT 0)");db.execSQL("CREATE INDEX IF NOT EXISTS idx_ai_jobs_state ON ai_jobs(state,priority DESC,created_at ASC)");db.execSQL("CREATE INDEX IF NOT EXISTS idx_ai_jobs_recent ON ai_jobs(updated_at DESC)");
    }

    private static void createModelRuns(SQLiteDatabase db){
        db.execSQL("CREATE TABLE IF NOT EXISTS model_runs(id INTEGER PRIMARY KEY AUTOINCREMENT,job_id INTEGER DEFAULT 0,pass_index INTEGER DEFAULT 0,role TEXT,provider TEXT,model TEXT,route TEXT,state TEXT,input_hash TEXT,latency_ms INTEGER DEFAULT 0,tokens_in INTEGER DEFAULT 0,tokens_out INTEGER DEFAULT 0,confidence REAL DEFAULT 0,output_json TEXT,error TEXT,created_at INTEGER NOT NULL)");db.execSQL("CREATE INDEX IF NOT EXISTS idx_model_runs_job ON model_runs(job_id,pass_index,id)");db.execSQL("CREATE INDEX IF NOT EXISTS idx_model_runs_model ON model_runs(provider,model,created_at DESC)");
    }

    private static void createDiagnostics(SQLiteDatabase db){
        db.execSQL("CREATE TABLE IF NOT EXISTS diagnostics_log(id INTEGER PRIMARY KEY AUTOINCREMENT,created_at INTEGER NOT NULL,severity TEXT NOT NULL,component TEXT NOT NULL,event TEXT NOT NULL,status TEXT,error_class TEXT,error_code TEXT,item_id INTEGER DEFAULT 0,thread_id INTEGER DEFAULT 0,signal_id INTEGER DEFAULT 0,job_id INTEGER DEFAULT 0,model_run_id INTEGER DEFAULT 0,latency_ms INTEGER DEFAULT 0,metadata_json TEXT)");db.execSQL("CREATE INDEX IF NOT EXISTS idx_diag_recent ON diagnostics_log(created_at DESC)");db.execSQL("CREATE INDEX IF NOT EXISTS idx_diag_component ON diagnostics_log(component,severity,created_at DESC)");db.execSQL("CREATE INDEX IF NOT EXISTS idx_diag_signal ON diagnostics_log(signal_id,created_at DESC)");db.execSQL("CREATE INDEX IF NOT EXISTS idx_diag_job ON diagnostics_log(job_id,created_at DESC)");
    }

    private static void createRelevanceEvaluations(SQLiteDatabase db){
        db.execSQL("CREATE TABLE IF NOT EXISTS relevance_evaluations(id INTEGER PRIMARY KEY AUTOINCREMENT,signal_id INTEGER NOT NULL UNIQUE,thread_id INTEGER DEFAULT 0,source_key TEXT,det_disposition TEXT,det_candidate TEXT,det_confidence REAL DEFAULT 0,learned_disposition TEXT,learned_candidate TEXT,learned_confidence REAL DEFAULT 0,model_disposition TEXT,model_candidate TEXT,model_confidence REAL DEFAULT 0,model_run_id INTEGER DEFAULT 0,final_disposition TEXT,final_candidate TEXT,final_confidence REAL DEFAULT 0,final_engine TEXT,review_id INTEGER DEFAULT 0,user_verdict TEXT,user_candidate TEXT,created_at INTEGER NOT NULL,updated_at INTEGER NOT NULL)");db.execSQL("CREATE INDEX IF NOT EXISTS idx_eval_source ON relevance_evaluations(source_key,updated_at DESC)");db.execSQL("CREATE INDEX IF NOT EXISTS idx_eval_thread ON relevance_evaluations(thread_id,updated_at DESC)");db.execSQL("CREATE INDEX IF NOT EXISTS idx_eval_verdict ON relevance_evaluations(user_verdict,updated_at DESC)");db.execSQL("CREATE INDEX IF NOT EXISTS idx_eval_model ON relevance_evaluations(model_disposition,updated_at DESC)");
    }

    private static void migrateLegacyEntities(SQLiteDatabase db){
        if(!tableExists(db,"entities"))return;
        db.execSQL("INSERT OR IGNORE INTO entity_nodes(kind,canonical_name,normalized_key,status,metadata_json,created_at,updated_at) SELECT upper(COALESCE(kind,'UNKNOWN')),trim(value),lower(trim(COALESCE(kind,'UNKNOWN')))||'|'||lower(trim(value)),'active','{\"migrated_from\":\"entities\"}',MIN(created_at),MAX(created_at) FROM entities WHERE value IS NOT NULL AND trim(value)<>'' GROUP BY lower(trim(COALESCE(kind,'UNKNOWN')))||'|'||lower(trim(value))");
        db.execSQL("INSERT OR IGNORE INTO source_links(from_type,from_id,to_type,to_id,relation,confidence,metadata_json,created_at) SELECT 'memory',e.item_id,'entity',n.id,'mentions',COALESCE(e.confidence,0.5),'{\"migrated_from\":\"entities\"}',e.created_at FROM entities e JOIN entity_nodes n ON n.normalized_key=lower(trim(COALESCE(e.kind,'UNKNOWN')))||'|'||lower(trim(e.value)) WHERE e.value IS NOT NULL AND trim(e.value)<>''");
    }

    /** One-time v6 extraction of hot routing fields out of metadata JSON. */
    private static void backfillDerivedRouting(SQLiteDatabase db){
        Cursor c=db.rawQuery("SELECT id,kind,metadata_json,source_key,thread_id,anchor_signal_id,candidate_kind FROM derived_items WHERE COALESCE(source_key,'')='' OR COALESCE(thread_id,0)=0 OR COALESCE(anchor_signal_id,0)=0 OR (kind='REVIEW' AND COALESCE(candidate_kind,'')='')",null);
        while(c.moveToNext()){
            long id=c.getLong(0),thread=c.getLong(4),anchor=c.getLong(5);String kind=n(c.getString(1)),meta=n(c.getString(2)),source=n(c.getString(3)),candidate=n(c.getString(6));
            try{JSONObject o=new JSONObject(meta);if(source.isEmpty())source=o.optString("source","");if(thread<=0)thread=o.optLong("thread_id",0);if(anchor<=0){anchor=o.optLong("raw_signal_id",0);if(anchor<=0)anchor=o.optLong("latest_signal_id",0);}if(candidate.isEmpty()&&"REVIEW".equalsIgnoreCase(kind))candidate=o.optString("candidate_kind","").toUpperCase();}catch(Exception ignored){}
            ContentValues v=new ContentValues();v.put("source_key",source);v.put("thread_id",thread);v.put("anchor_signal_id",anchor);v.put("candidate_kind",candidate);db.update("derived_items",v,"id=?",new String[]{String.valueOf(id)});
        }c.close();
    }

    private static void backfillFeedbackRouting(SQLiteDatabase db){
        db.execSQL("UPDATE feedback_events SET source_key=COALESCE((SELECT source_key FROM derived_items d WHERE d.id=feedback_events.target_id),'') WHERE target_type='derived' AND COALESCE(source_key,'')='' ");
        db.execSQL("UPDATE feedback_events SET candidate_kind=COALESCE((SELECT candidate_kind FROM derived_items d WHERE d.id=feedback_events.target_id),'') WHERE target_type='derived' AND COALESCE(candidate_kind,'')='' ");
        db.execSQL("UPDATE feedback_events SET scope_key=COALESCE(source_key,'')||'|'||COALESCE(candidate_kind,'') WHERE COALESCE(scope_key,'')='' ");
    }

    private static void backfillCognitiveState(SQLiteDatabase db){
        db.execSQL("UPDATE raw_signals SET cognitive_state=CASE WHEN COALESCE(promoted_item_id,0)>0 THEN 'DERIVED' WHEN UPPER(COALESCE(disposition,''))='IGNORE' THEN 'IGNORED_NOISE' WHEN UPPER(COALESCE(disposition,''))='REVIEW' THEN 'REVIEW_REQUIRED' WHEN UPPER(COALESCE(disposition,''))='CONTEXT' THEN 'CONTEXT_ONLY' ELSE 'LEGACY_UNRESOLVED' END WHERE COALESCE(cognitive_state,'')=''");
        db.execSQL("UPDATE raw_signals SET cognitive_version='legacy-cognitive-003' WHERE COALESCE(cognitive_version,'')=''");
        db.execSQL("UPDATE raw_signals SET final_reason='Migrated from legacy disposition: ' || COALESCE(disposition,'none') WHERE COALESCE(final_reason,'')=''");
        db.execSQL("UPDATE raw_signals SET cognitive_updated_at=CASE WHEN COALESCE(updated_at,0)>0 THEN updated_at ELSE created_at END WHERE COALESCE(cognitive_updated_at,0)=0");
    }

    private static void backfillDerivedV7(SQLiteDatabase db){
        db.execSQL("UPDATE derived_items SET requires_user_action=1 WHERE UPPER(kind)='ACTION' AND COALESCE(requires_user_action,0)=0");
        db.execSQL("UPDATE derived_items SET requires_follow_up=1 WHERE UPPER(kind)='WAITING' AND COALESCE(requires_follow_up,0)=0");
        db.execSQL("UPDATE derived_items SET priority_score=COALESCE(importance,0) WHERE COALESCE(priority_score,0)=0");
    }

    private static void addColumn(SQLiteDatabase db,String table,String column,String definition){if(!hasColumn(db,table,column))db.execSQL("ALTER TABLE "+table+" ADD COLUMN "+column+" "+definition);}
    private static boolean tableExists(SQLiteDatabase db,String table){Cursor c=db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name=? LIMIT 1",new String[]{table});boolean exists=c.moveToFirst();c.close();return exists;}
    private static boolean hasColumn(SQLiteDatabase db,String table,String column){Cursor c=db.rawQuery("PRAGMA table_info("+table+")",null);boolean found=false;while(c.moveToNext()){int i=c.getColumnIndex("name");if(i>=0&&column.equals(c.getString(i))){found=true;break;}}c.close();return found;}
    private static String n(String s){return s==null?"":s.trim();}
}
