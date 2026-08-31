package com.kareem.cortex;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Base64;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * V4 Teacher/Student differential.
 * Full-fidelity by design: no field-name or content redaction is performed.
 * The pre-run snapshot is the teacher evidence world; post-run captures the exact
 * AI job/model traces produced by the student probes. V4 additionally probes lifecycle
 * truth reconciliation so dismissed/resolved obligations cannot be resurrected by similarity.
 */
@RunWith(AndroidJUnit4.class)
public class TeacherStudentCognitiveDifferentialTest {
  private Context ctx; private VaultDb vault; private File out;

  @Test public void teacherStudentCognitiveDifferential() throws Exception {
    ctx=InstrumentationRegistry.getInstrumentation().getTargetContext();
    vault=new VaultDb(ctx); CognitiveSchema.ensure(vault.getWritableDatabase());
    String stamp=new SimpleDateFormat("yyyyMMdd_HHmmss",Locale.US).format(new Date());
    out=new File(ctx.getExternalFilesDir(null),"self-user-test/TeacherStudentCognitiveDifferential_"+stamp);
    if(!out.mkdirs()&&!out.isDirectory()) throw new IllegalStateException("cannot create V4 output");
    exportDatabase(new File(out,"database_pre"));
    exportSharedPreferences(new File(out,"shared_preferences_pre"));
    exportInternalFileManifest(new File(out,"internal_file_manifest_pre.json"));
    runStudentCases();
    exportDatabase(new File(out,"database_post"));
    exportSharedPreferences(new File(out,"shared_preferences_post"));
    writeManifest();
  }

  private void exportDatabase(File root) throws Exception {
    if(!root.mkdirs()&&!root.isDirectory()) throw new IOException("cannot create "+root);
    SQLiteDatabase db=vault.getReadableDatabase();
    JSONArray names=new JSONArray();
    Cursor tables=db.rawQuery("SELECT name,sql FROM sqlite_master WHERE type='table' AND name NOT LIKE 'android_%' ORDER BY name",null);
    try{while(tables.moveToNext()){
      String name=tables.getString(0); if(name==null||name.trim().isEmpty())continue;
      names.put(new JSONObject().put("name",name).put("sql",tables.getString(1)));
      exportTable(db,name,new File(root,safe(name)+".jsonl"));
    }}finally{tables.close();}
    write(new File(root,"_schema.json"),new JSONObject().put("tables",names).toString(2));
  }

  private void exportTable(SQLiteDatabase db,String table,File f) throws Exception {
    try(FileWriter w=new FileWriter(f)){Cursor c=null;try{
      c=db.rawQuery("SELECT * FROM `"+table.replace("`","``")+"`",null); String[] cols=c.getColumnNames();
      while(c.moveToNext()){JSONObject o=new JSONObject();for(int i=0;i<cols.length;i++){
        String k=cols[i]; switch(c.getType(i)){
          case Cursor.FIELD_TYPE_INTEGER:o.put(k,c.getLong(i));break;
          case Cursor.FIELD_TYPE_FLOAT:o.put(k,c.getDouble(i));break;
          case Cursor.FIELD_TYPE_BLOB:byte[] b=c.getBlob(i);o.put(k,b==null?JSONObject.NULL:Base64.encodeToString(b,Base64.NO_WRAP));break;
          case Cursor.FIELD_TYPE_NULL:o.put(k,JSONObject.NULL);break;
          default:o.put(k,c.getString(i));
        }
      } w.write(o.toString()); w.write('\n');}
    }finally{if(c!=null)c.close();}}
  }

  private void exportSharedPreferences(File dst) throws Exception {
    if(!dst.mkdirs()&&!dst.isDirectory())throw new IOException("cannot create "+dst);
    File data=ctx.getApplicationInfo().dataDir==null?null:new File(ctx.getApplicationInfo().dataDir,"shared_prefs");
    if(data==null||!data.isDirectory()){write(new File(dst,"_status.txt"),"shared_prefs directory not available\n");return;}
    File[] fs=data.listFiles(); if(fs==null)return; for(File f:fs)if(f.isFile())copy(f,new File(dst,f.getName()));
  }

  private void exportInternalFileManifest(File dst) throws Exception {
    JSONArray a=new JSONArray(); File data=ctx.getApplicationInfo().dataDir==null?null:new File(ctx.getApplicationInfo().dataDir);
    if(data!=null)walkManifest(data,data,a,0); write(dst,new JSONObject().put("root",data==null?"":data.getAbsolutePath()).put("files",a).toString(2));
  }
  private void walkManifest(File root,File f,JSONArray out,int depth) throws Exception {
    if(f==null||depth>8)return; File[] xs=f.listFiles(); if(xs==null)return; for(File x:xs){String rel=x.getAbsolutePath().substring(root.getAbsolutePath().length());JSONObject o=new JSONObject().put("path",rel).put("directory",x.isDirectory()).put("bytes",x.isFile()?x.length():0).put("last_modified",x.lastModified());out.put(o);if(x.isDirectory())walkManifest(root,x,out,depth+1);}
  }

  private void runStudentCases() throws Exception {
    JSONArray cases=new JSONArray(); String[] qs={
      "What needs my attention right now? Prioritize situations, not memories, and recommend the best next move.",
      "What ongoing situations or episodes are visible in my data? Group related evidence together.",
      "What work or project threads are still open, even if Cortex has not confirmed a project name?",
      "What upcoming deadlines, appointments, or reminders matter most and why?",
      "Which retrieved items are probably noise or context rather than actions?",
      "Audit lifecycle truth: which obligations are genuinely still live, and which similar memories must NOT be resurfaced because they were dismissed, resolved, done, or closed?"
    };
    for(String q:qs){JSONObject o=new JSONObject().put("question",q);long start=System.currentTimeMillis();try{
      LocalAskRouter.Result r=LocalAskRouter.ask(ctx,vault,q);
      o.put("job_id",r.jobId).put("provider",r.provider).put("source_mode",r.sourceMode).put("answer",r.answer==null?"":r.answer).put("grounded_draft",r.grounded==null?"":r.grounded.answer).put("duration_ms",System.currentTimeMillis()-start).put("router_total_ms",r.totalMs).put("retrieval_ms",r.retrievalMs).put("prompt_build_ms",r.promptBuildMs).put("model_load_ms",r.modelLoadMs).put("generation_ms",r.generationMs).put("tokens_generated",r.tokensGenerated).put("tokens_per_second",r.tokensPerSecond).put("cache_hit",r.cacheHit).put("error",r.error);
      JSONArray src=new JSONArray(); if(r.grounded!=null)for(SemanticHit h:r.grounded.sources){JSONObject s=new JSONObject().put("item_id",h.item==null?0:h.item.id).put("score",h.score).put("snippet",h.snippet);if(h.item!=null)s.put("title",h.item.title).put("type",h.item.type).put("source",h.item.source).put("summary",h.item.summary).put("raw_text",h.item.rawText).put("extracted_text",h.item.extractedText).put("metadata_json",h.item.metadataJson);src.put(s);}o.put("retrieved_evidence",src);
      JSONArray loops=new JSONArray();if(r.grounded!=null)for(String x:r.grounded.openLoops)loops.put(x);o.put("open_loops",loops);
      JSONArray dec=new JSONArray();if(r.grounded!=null)for(String x:r.grounded.decisions)dec.put(x);o.put("decisions",dec);
    }catch(Throwable t){o.put("error",t.getClass().getSimpleName()+": "+String.valueOf(t.getMessage()));}cases.put(o);}
    write(new File(out,"student_cases.json"),new JSONObject().put("schema","CORTEX_TEACHER_STUDENT_V4").put("cases",cases).toString(2));
  }

  private void writeManifest() throws Exception {
    JSONObject m=new JSONObject().put("schema","CORTEX_TEACHER_STUDENT_COGNITIVE_DIFFERENTIAL_V4").put("generated_at",System.currentTimeMillis()).put("full_fidelity",true).put("redaction",false).put("purpose","Give ChatGPT the same full application evidence world, independently construct the best cognitive answer, then compare against Cortex retrieval, situation construction, lifecycle reconciliation, linking, temporal reasoning, suppression, prioritization, synthesis and action choice.");
    write(new File(out,"README.json"),m.toString(2));
    write(new File(out,"README.md"),"# Cortex V4 — Teacher/Student Cognitive Differential\n\nFULL-FIDELITY export. No sensitive-field redaction is performed. database_pre is the teacher evidence world before probes. student_cases.json contains Cortex answers plus the exact evidence Cortex retrieved. database_post captures resulting ai_jobs/model_runs and any other state changes caused by the probes. V4 explicitly tests lifecycle truth: closed/dismissed/resolved obligations must not be resurrected by semantic similarity. SharedPreferences are copied verbatim. Send the combined ZIP to ChatGPT; the teacher analysis must be constructed independently from database_pre before reading student_cases.json.\n");
  }

  private static void copy(File a,File b)throws Exception{try(InputStream in=new FileInputStream(a);OutputStream out=new FileOutputStream(b)){byte[] buf=new byte[65536];int n;while((n=in.read(buf))>0)out.write(buf,0,n);}}
  private static String safe(String s){return s==null?"table":s.replaceAll("[^A-Za-z0-9._-]+","_");}
  private static void write(File f,String s)throws Exception{File p=f.getParentFile();if(p!=null&&!p.exists())p.mkdirs();try(FileWriter w=new FileWriter(f)){w.write(s==null?"":s);}}
}
