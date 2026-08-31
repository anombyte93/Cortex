package com.kareem.cortex;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Phone-only review runner. Runs without Shizuku, adb, Instrumentation or a PC.
 * It intentionally reuses the production cognitive stack and exports a ChatGPT-ready bundle.
 */
public final class SelfContainedReviewActivity extends Activity {
    private TextView status;
    private volatile boolean running;

    @Override public void onCreate(Bundle b){super.onCreate(b);CortexUi.applyWindow(this);build();}
    @Override protected void onResume(){super.onResume();if(!running)runReview();}

    private void build(){
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(CortexUi.BG);root.setPadding(CortexUi.dp(this,18),CortexUi.dp(this,18),CortexUi.dp(this,18),CortexUi.dp(this,18));
        TextView h=CortexUi.text(this,"Cortex self-contained review",22,CortexUi.TEXT);CortexUi.medium(h);root.addView(h);
        TextView sub=CortexUi.text(this,"Runs on this phone only — no Shizuku, adb, Wi-Fi debugging or PC required.",12,CortexUi.MUTED);sub.setPadding(0,CortexUi.dp(this,8),0,CortexUi.dp(this,14));root.addView(sub);
        ScrollView sv=new ScrollView(this);status=CortexUi.text(this,"Preparing…",12,CortexUi.TEXT);status.setGravity(Gravity.START);sv.addView(status);root.addView(sv,new LinearLayout.LayoutParams(-1,0,1));setContentView(root);CortexUi.fitSystemBars(this,root);
    }

    private void runReview(){running=true;new Thread(()->{
        try{
            line("Starting phone-only review…");
            File root=SelfContainedReviewRunner.run(this,s->runOnUiThread(()->line(s)));
            Uri uri=SelfContainedReviewRunner.publishZip(this,root);
            runOnUiThread(()->line("DONE\nSaved to Downloads/Cortex/"+root.getName()+".zip\n"+(uri==null?"":"MediaStore: "+uri)));
        }catch(Throwable t){runOnUiThread(()->line("FAILED\n"+t.getClass().getSimpleName()+": "+String.valueOf(t.getMessage())));}finally{running=false;}
    },"cortex-self-review").start();}
    private void line(String s){String old=status.getText()==null?"":status.getText().toString();status.setText((old.isEmpty()?"":old+"\n")+s);}
}

final class SelfContainedReviewRunner {
    interface Progress{void on(String s);}
    private SelfContainedReviewRunner(){}

    static File run(Context ctx,Progress p)throws Exception{
        String stamp=new SimpleDateFormat("yyyyMMdd_HHmmss",Locale.US).format(new Date());
        File root=new File(ctx.getExternalFilesDir(null),"self-user-test/FullCortexPhoneOnlyReview_"+stamp);
        if(!root.mkdirs()&&!root.isDirectory())throw new IOException("Cannot create review folder");
        VaultDb vault=new VaultDb(ctx);CognitiveSchema.ensure(vault.getWritableDatabase());CognitiveAdjudicationStore.ensure(vault.getWritableDatabase());
        JSONObject summary=new JSONObject().put("schema","CORTEX_PHONE_ONLY_REVIEW_V1").put("generated_at",System.currentTimeMillis()).put("full_fidelity",true).put("redaction",false).put("transport","in_app_no_shell");
        try{
            p.on("[1/4] V1 in-process surface/runtime health");summary.put("v1",runV1(ctx,root));
            p.on("[2/4] V2 cognitive/product adjudication");summary.put("v2",runV2(vault,root));
            p.on("[3/4] V3 teacher/student cognitive differential");summary.put("v3",runV3(ctx,vault,root));
            p.on("[4/4] V5 same-packet cognitive differential");summary.put("v5",runV5(vault,root));
            write(new File(root,"SUMMARY.json"),summary.toString(2));
            write(new File(root,"README.md"),"# Cortex Phone-Only Review\n\nThis bundle was generated inside Cortex itself without adb, Shizuku, wireless debugging, Instrumentation, or a PC. V1 is an in-process structural/runtime substitute for the UiAutomator instrumentation sweep. V2/V3/V5 exercise the same production cognitive stack and preserve full-fidelity evidence. For V5, adjudicate `V5/cognitive_packet.json` before opening `V5/student_decision.json`.\n");
        }finally{try{vault.close();}catch(Throwable ignored){}}
        return root;
    }

    private static JSONObject runV1(Context ctx,File root)throws Exception{
        JSONArray checks=new JSONArray();int pass=0,fail=0;
        Class<?>[] classes={CompactTodayActivity.class,ProposalPeopleProjectsActivity.class,ProposalCaptureActivity.class,ProposalAskCortexActivity.class,SettingsActivity.class,VaultActivity.class,SmartInboxActivity.class,ReviewQueueActivity.class,FeatureHubActivity.class};
        for(Class<?> c:classes){JSONObject o=new JSONObject().put("surface",c.getSimpleName());try{Class.forName(c.getName());ctx.getPackageManager().getActivityInfo(new android.content.ComponentName(ctx,c),0);o.put("status","PASS");pass++;}catch(Throwable t){o.put("status","FAIL").put("error",t.toString());fail++;}checks.put(o);}        
        VaultDb db=new VaultDb(ctx);try{SQLiteDatabase s=db.getWritableDatabase();s.rawQuery("SELECT 1",null).close();checks.put(new JSONObject().put("surface","VaultDb").put("status","PASS"));pass++;}catch(Throwable t){checks.put(new JSONObject().put("surface","VaultDb").put("status","FAIL").put("error",t.toString()));fail++;}finally{db.close();}
        JSONObject out=new JSONObject().put("pass",pass).put("failure",fail).put("checks",checks).put("note","In-process structural/runtime substitute; no UiAutomator screenshots or shell diagnostics in phone-only mode.");
        write(new File(root,"V1/report.json"),out.toString(2));return out;
    }

    private static JSONObject runV2(VaultDb vault,File root)throws Exception{
        JSONArray cases=new JSONArray();String[] qs={"What needs my attention right now?","What work or project threads are still open?","Which items are probably noise rather than actions?","Audit lifecycle truth: what is still live and what is resolved?"};
        int pass=0,warn=0;for(String q:qs){JSONObject c=new JSONObject().put("question",q);try{GroundedAnswer g=SecondBrainEngine.ask(vault,q);String a=g==null?"":nz(g.answer);boolean ok=!a.isEmpty()&&!looksGarbage(a)&&!looksPromptLeak(a);c.put("status",ok?"PASS":"WARN").put("answer",a).put("source_count",g==null?0:g.sources.size());if(ok)pass++;else warn++;}catch(Throwable t){c.put("status","WARN").put("error",t.toString());warn++;}cases.put(c);}JSONObject out=new JSONObject().put("pass",pass).put("warning",warn).put("cases",cases);write(new File(root,"V2/report.json"),out.toString(2));return out;
    }

    private static JSONObject runV3(Context ctx,VaultDb vault,File root)throws Exception{
        File dir=new File(root,"V3");dir.mkdirs();exportDatabase(vault,new File(dir,"database_pre"));exportSharedPreferences(ctx,new File(dir,"shared_preferences_pre"));
        JSONArray cases=new JSONArray();String[] qs={
                "What needs my attention right now? Prioritize situations, not memories, and recommend the best next move.",
                "What ongoing situations or episodes are visible in my data? Group related evidence together.",
                "What work or project threads are still open, even if Cortex has not confirmed a project name?",
                "What upcoming deadlines, appointments, or reminders matter most and why?",
                "Which retrieved items are probably noise or context rather than actions?",
                "Audit lifecycle truth: which obligations are genuinely still live, and which similar memories must NOT be resurfaced because they were dismissed, resolved, done, or closed?"};
        for(String q:qs){JSONObject o=new JSONObject().put("question",q);long start=System.currentTimeMillis();try{LocalAskRouter.Result r=LocalAskRouter.ask(ctx,vault,q);o.put("job_id",r.jobId).put("provider",r.provider).put("source_mode",r.sourceMode).put("answer",nz(r.answer)).put("grounded_draft",r.grounded==null?"":nz(r.grounded.answer)).put("duration_ms",System.currentTimeMillis()-start).put("router_total_ms",r.totalMs).put("retrieval_ms",r.retrievalMs).put("prompt_build_ms",r.promptBuildMs).put("model_load_ms",r.modelLoadMs).put("generation_ms",r.generationMs).put("tokens_generated",r.tokensGenerated).put("tokens_per_second",r.tokensPerSecond).put("cache_hit",r.cacheHit).put("error",nz(r.error));JSONArray src=new JSONArray();if(r.grounded!=null)for(SemanticHit h:r.grounded.sources){JSONObject s=new JSONObject().put("item_id",h.item==null?0:h.item.id).put("score",h.score).put("snippet",h.snippet);if(h.item!=null)s.put("title",h.item.title).put("type",h.item.type).put("source",h.item.source).put("summary",h.item.summary).put("raw_text",h.item.rawText).put("extracted_text",h.item.extractedText).put("metadata_json",h.item.metadataJson);src.put(s);}o.put("retrieved_evidence",src);}catch(Throwable t){o.put("error",t.toString());}cases.put(o);}        
        write(new File(dir,"student_cases.json"),new JSONObject().put("schema","CORTEX_TEACHER_STUDENT_PHONE_ONLY_V4").put("cases",cases).toString(2));exportDatabase(vault,new File(dir,"database_post"));
        JSONObject out=new JSONObject().put("case_count",cases.length()).put("full_fidelity",true);write(new File(dir,"README.json"),out.toString(2));return out;
    }

    private static JSONObject runV5(VaultDb vault,File root)throws Exception{
        File dir=new File(root,"V5");dir.mkdirs();SQLiteDatabase db=vault.getWritableDatabase();String q="Given my complete current Cortex state, what is useful now, what belongs together, what changed or became stale, and what should Cortex do next?";CognitivePacketBuilder.Packet p=CognitivePacketBuilder.build(vault,q);long rowId=CognitiveAdjudicationStore.savePacket(db,p.json);JSONObject student=CognitivePacketStudentAdapter.decide(p.json);CognitiveDecisionContract.Validation sv=CognitiveDecisionContract.validate(student.toString(),p.validRefs);CognitiveAdjudicationStore.saveStudent(db,rowId,student.toString(),sv);JSONObject quality=quality(student,p.json);
        write(new File(dir,"cognitive_packet.json"),p.json.toString(2));write(new File(dir,"teacher_prompt.txt"),CognitiveDecisionContract.teacherPrompt(p.json));write(new File(dir,"student_decision.json"),student.toString(2));write(new File(dir,"student_validation.json"),new JSONObject().put("valid",sv.valid()).put("errors",new JSONArray(sv.errors)).toString(2));write(new File(dir,"student_quality.json"),quality.toString(2));boolean pass=sv.valid()&&quality.optInt("ungrounded_interactive")==0&&quality.optInt("missing_next_action")==0&&quality.optInt("evidence_usage_failure")==0;write(new File(dir,"TEST_RESULT.txt"),(pass?"PASS":"FAIL")+"\n");JSONObject out=new JSONObject().put("pass",pass).put("student_valid",sv.valid()).put("student_quality",quality).put("adjudication_row_id",rowId).put("same_packet",true);write(new File(dir,"README.json"),out.toString(2));return out;
    }

    static Uri publishZip(Context ctx,File root)throws Exception{
        File zip=new File(ctx.getCacheDir(),root.getName()+".zip");zip(root,zip,root.getParentFile());ContentValues v=new ContentValues();v.put(MediaStore.Downloads.DISPLAY_NAME,zip.getName());v.put(MediaStore.Downloads.MIME_TYPE,"application/zip");if(android.os.Build.VERSION.SDK_INT>=29)v.put(MediaStore.Downloads.RELATIVE_PATH,"Download/Cortex");Uri uri=ctx.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,v);if(uri==null)return null;try(InputStream in=new FileInputStream(zip);OutputStream out=ctx.getContentResolver().openOutputStream(uri)){byte[] b=new byte[65536];int n;while((n=in.read(b))>0)out.write(b,0,n);}return uri;
    }

    private static JSONObject quality(JSONObject student,JSONObject packet)throws Exception{JSONArray ds=student.optJSONArray("decisions");if(ds==null)ds=new JSONArray();int interactive=0,ungrounded=0,missing=0,bound=0;for(int i=0;i<ds.length();i++){JSONObject d=ds.optJSONObject(i);if(d==null)continue;JSONArray refs=d.optJSONArray("evidence_refs");int n=refs==null?0:refs.length();bound+=n;String t=d.optString("type");boolean active="SURFACE_NOW".equals(t)||"ASK_USER".equals(t)||"PROPOSE_ACTION".equals(t);if(active){interactive++;if(n==0)ungrounded++;Object a=d.opt("next_action");if(a==null||a==JSONObject.NULL||String.valueOf(a).trim().isEmpty())missing++;}}JSONArray ev=packet.optJSONArray("new_evidence");return new JSONObject().put("decisions",ds.length()).put("interactive",interactive).put("evidence_refs_bound",bound).put("ungrounded_interactive",ungrounded).put("missing_next_action",missing).put("evidence_usage_failure",ev!=null&&ev.length()>0&&bound==0?1:0);}

    private static void exportDatabase(VaultDb vault,File root)throws Exception{root.mkdirs();SQLiteDatabase db=vault.getReadableDatabase();JSONArray names=new JSONArray();Cursor tables=db.rawQuery("SELECT name,sql FROM sqlite_master WHERE type='table' AND name NOT LIKE 'android_%' ORDER BY name",null);try{while(tables.moveToNext()){String name=tables.getString(0);if(name==null||name.trim().isEmpty())continue;names.put(new JSONObject().put("name",name).put("sql",tables.getString(1)));exportTable(db,name,new File(root,safe(name)+".jsonl"));}}finally{tables.close();}write(new File(root,"_schema.json"),new JSONObject().put("tables",names).toString(2));}
    private static void exportTable(SQLiteDatabase db,String table,File f)throws Exception{try(FileWriter w=new FileWriter(f)){Cursor c=null;try{c=db.rawQuery("SELECT * FROM `"+table.replace("`","``")+"`",null);String[] cols=c.getColumnNames();while(c.moveToNext()){JSONObject o=new JSONObject();for(int i=0;i<cols.length;i++){switch(c.getType(i)){case Cursor.FIELD_TYPE_INTEGER:o.put(cols[i],c.getLong(i));break;case Cursor.FIELD_TYPE_FLOAT:o.put(cols[i],c.getDouble(i));break;case Cursor.FIELD_TYPE_BLOB:byte[] b=c.getBlob(i);o.put(cols[i],b==null?JSONObject.NULL:Base64.encodeToString(b,Base64.NO_WRAP));break;case Cursor.FIELD_TYPE_NULL:o.put(cols[i],JSONObject.NULL);break;default:o.put(cols[i],c.getString(i));}}w.write(o.toString());w.write('\n');}}finally{if(c!=null)c.close();}}}
    private static void exportSharedPreferences(Context ctx,File dst)throws Exception{dst.mkdirs();File data=ctx.getApplicationInfo().dataDir==null?null:new File(ctx.getApplicationInfo().dataDir,"shared_prefs");if(data==null||!data.isDirectory())return;File[] fs=data.listFiles();if(fs==null)return;for(File f:fs)if(f.isFile())copy(f,new File(dst,f.getName()));}
    private static void zip(File f,File out,File base)throws Exception{try(ZipOutputStream z=new ZipOutputStream(new FileOutputStream(out))){zipRec(f,z,base);}}
    private static void zipRec(File f,ZipOutputStream z,File base)throws Exception{if(f.isDirectory()){File[] xs=f.listFiles();if(xs!=null)for(File x:xs)zipRec(x,z,base);return;}String name=f.getAbsolutePath().substring(base.getAbsolutePath().length()+1).replace(File.separatorChar,'/');z.putNextEntry(new ZipEntry(name));try(InputStream in=new FileInputStream(f)){byte[] b=new byte[65536];int n;while((n=in.read(b))>0)z.write(b,0,n);}z.closeEntry();}
    private static void copy(File a,File b)throws Exception{try(InputStream in=new FileInputStream(a);OutputStream out=new FileOutputStream(b)){byte[] buf=new byte[65536];int n;while((n=in.read(buf))>0)out.write(buf,0,n);}}
    private static void write(File f,String s)throws Exception{File p=f.getParentFile();if(p!=null&&!p.exists())p.mkdirs();try(FileWriter w=new FileWriter(f)){w.write(s==null?"":s);}}
    private static String safe(String s){return s==null?"table":s.replaceAll("[^A-Za-z0-9._-]+","_");}
    private static String nz(String s){return s==null?"":s.trim();}
    private static boolean looksPromptLeak(String s){String z=s==null?"":s.toUpperCase(Locale.ROOT);return z.contains("GROUNDED DRAFT:")||z.contains("QUESTION:")||z.contains("EVIDENCE:");}
    private static boolean looksGarbage(String s){if(s==null||s.trim().isEmpty())return true;try{return OcrGarbageGate.assessText(s).score<0.30;}catch(Throwable t){return false;}}
}
