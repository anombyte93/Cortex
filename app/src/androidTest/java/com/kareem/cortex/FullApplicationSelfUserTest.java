package com.kareem.cortex;

import android.app.Instrumentation;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.SystemClock;
import android.widget.EditText;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Strict instrumented self-user regression aligned with the current Now / Inbox / + / Atlas / Ask shell. */
@RunWith(AndroidJUnit4.class)
public class FullApplicationSelfUserTest {
    private static final String PKG="com.kareem.cortex";
    private Instrumentation inst; private UiDevice device; private Context target; private File out;
    private final JSONArray events=new JSONArray(); private final ArrayList<String> findings=new ArrayList<>();
    private int pass=0,warn=0,fail=0,step=0;

    private static final Class<?>[] SURFACES=new Class<?>[]{
            CompactTodayActivity.class, InboxActivity.class, ProposalCaptureActivity.class,
            ProposalPeopleProjectsActivity.class, ProposalAskCortexActivity.class, SettingsActivity.class,
            VaultActivity.class, PromptLibraryActivity.class, SmartInboxActivity.class, ReviewQueueActivity.class,
            AttentionEvaluationActivity.class, RelevanceEvaluationActivity.class, CorrectionLearningActivity.class,
            FeatureHubActivity.class, PhoneContextAccessActivity.class, CapabilityMatrixActivity.class,
            EnvironmentActivity.class, CortexStatusActivity.class, CortexAuditActivity.class,
            ExternalModelCheckActivity.class, VisualIntelligenceActivity.class, OcrTestActivity.class,
            AsrSettingsActivity.class, OpenRouterSettingsActivity.class, GeminiSettingsActivity.class
    };

    @Test public void fullApplicationSelfUserTest() throws Exception {
        inst=InstrumentationRegistry.getInstrumentation(); device=UiDevice.getInstance(inst); target=inst.getTargetContext();
        String stamp=new SimpleDateFormat("yyyyMMdd_HHmmss",Locale.US).format(new Date());
        out=new File(target.getExternalFilesDir(null),"self-user-test/FullCortexSelfUserTest_"+stamp);
        if(!out.mkdirs()&&!out.isDirectory())throw new IllegalStateException("Cannot create report directory");
        try{device.executeShellCommand("logcat -c");}catch(Throwable ignored){}
        record("RUN","START","PASS","Current Cortex V2 self-user test started",null);pass++;
        launcherAndNavigation();
        surfaceCoverage();
        safePersistenceRoundTrip();
        attentionLifecycleRoundTrip();
        nowBehaviorInspection();
        cortexAskSmoke();
        legacyEscapeChecks();
        finalDiagnostics();
        writeReports();
        if(fail>0)throw new AssertionError(fail+" self-user test failure(s); report="+out.getAbsolutePath());
    }

    private void launcherAndNavigation(){try{
        launch(CompactTodayActivity.class);checkpoint("launcher_now");expectAny("Launcher resolves to current Now surface","NOW","What matters now");
        String[] tabs={"Now","Inbox","Atlas","Ask"};
        for(String tab:tabs){
            launch(CompactTodayActivity.class);
            UiObject2 x=device.wait(Until.findObject(By.text(tab)),2500);
            if(x==null){finding("FAIL","NAV","Bottom tab missing: "+tab);continue;}
            x.click();SystemClock.sleep(650);checkpoint("nav_"+safe(tab));
            if(PKG.equals(device.getCurrentPackageName()))finding("PASS","NAV",tab+" stayed inside Cortex");else finding("FAIL","NAV",tab+" escaped to "+device.getCurrentPackageName());
        }
        launch(CompactTodayActivity.class);UiObject2 plus=device.wait(Until.findObject(By.text("+")),2000);
        if(plus==null)finding("FAIL","NAV","Capture + control missing");
        else{plus.click();SystemClock.sleep(500);checkpoint("nav_capture_plus");if(PKG.equals(device.getCurrentPackageName()))finding("PASS","NAV","Capture + stayed inside Cortex");else finding("FAIL","NAV","Capture + escaped Cortex");device.pressBack();}
    }catch(Throwable t){finding("FAIL","NAV","Navigation crashed: "+brief(t));}}

    private void surfaceCoverage(){for(Class<?> cls:SURFACES){String name=cls.getSimpleName();try{launch(cls);checkpoint("surface_"+safe(name));if(PKG.equals(device.getCurrentPackageName()))finding("PASS","SURFACE",name+" opened");else finding("FAIL","SURFACE",name+" left package: "+device.getCurrentPackageName());screenSanity(name);safeScroll();}catch(Throwable t){finding("FAIL","SURFACE",name+" failed: "+brief(t));}}}

    private void safePersistenceRoundTrip(){VaultDb db=null;SQLiteDatabase s=null;try{
        db=new VaultDb(target);CognitiveStore.ensure(db);s=db.getWritableDatabase();s.beginTransaction();long now=System.currentTimeMillis();String marker="SELF_USER_TEST_"+now;ContentValues v=new ContentValues();v.put("kind","ACTION");v.put("title",marker);v.put("body","Synthetic rolled-back instrumentation item");v.put("source_key","instrumented_self_user_test");v.put("state","open");v.put("confidence",1.0);v.put("importance",60);v.put("thread_id",0);v.put("anchor_signal_id",0);v.put("created_at",now);v.put("updated_at",now);long id=s.insert("derived_items",null,v);Cursor c=s.rawQuery("SELECT title FROM derived_items WHERE id=?",new String[]{String.valueOf(id)});boolean ok=c.moveToFirst()&&marker.equals(c.getString(0));c.close();finding(ok?"PASS":"FAIL","PERSISTENCE",ok?"Derived item round-tripped inside rollback transaction":"Derived round-trip failed");
    }catch(Throwable t){finding("FAIL","PERSISTENCE",brief(t));}finally{if(s!=null&&s.inTransaction())try{s.endTransaction();}catch(Throwable ignored){}if(db!=null)try{db.close();}catch(Throwable ignored){}}}

    /** Acting suppresses only the evidence version acted on; newer evidence can reopen the durable loop. */
    private void attentionLifecycleRoundTrip(){VaultDb db=null;SQLiteDatabase s=null;try{
        db=new VaultDb(target);CognitiveStore.ensure(db);s=db.getWritableDatabase();s.beginTransaction();long now=System.currentTimeMillis();ContentValues v=new ContentValues();v.put("kind","ACTION");v.put("title","Attention lifecycle diagnostic");v.put("body","Synthetic rolled-back action");v.put("source_key","instrumented_self_user_test");v.put("state","open");v.put("confidence",1.0);v.put("importance",90);v.put("thread_id",0);v.put("anchor_signal_id",0);v.put("created_at",now);v.put("updated_at",now);long id=s.insert("derived_items",null,v);
        PrimeBriefStore.Item first=new PrimeBriefStore.Item(id,"ACTION","Attention lifecycle diagnostic","Synthetic rolled-back action","instrumented_self_user_test","open",1.0,90,0,0,now);AttentionEngine.Decision base1=AttentionEngine.evaluate(first,System.currentTimeMillis());AttentionLearning.record(db,id,"acted");SystemClock.sleep(5);AttentionEngine.Decision acted=AttentionLearning.apply(db,first,base1);boolean suppressed=acted.band==AttentionEngine.Band.QUIET;
        long newer=System.currentTimeMillis()+1000;ContentValues u=new ContentValues();u.put("updated_at",newer);u.put("body","New evidence updated this obligation");s.update("derived_items",u,"id=?",new String[]{String.valueOf(id)});PrimeBriefStore.Item second=new PrimeBriefStore.Item(id,"ACTION","Attention lifecycle diagnostic","New evidence updated this obligation","instrumented_self_user_test","open",1.0,90,0,0,newer);AttentionEngine.Decision base2=AttentionEngine.evaluate(second,System.currentTimeMillis());AttentionEngine.Decision reopened=AttentionLearning.apply(db,second,base2);boolean canReopen=reopened.band!=AttentionEngine.Band.QUIET;
        finding(suppressed&&canReopen?"PASS":"FAIL","ATTENTION",suppressed&&canReopen?"Acted version suppressed and newer evidence reopened the loop":"Attention lifecycle regression: suppressed="+suppressed+" reopened="+canReopen);
    }catch(Throwable t){finding("FAIL","ATTENTION","Lifecycle round-trip failed: "+brief(t));}finally{if(s!=null&&s.inTransaction())try{s.endTransaction();}catch(Throwable ignored){}if(db!=null)try{db.close();}catch(Throwable ignored){}}}

    private void nowBehaviorInspection(){try{
        launch(CompactTodayActivity.class);checkpoint("now_behavior");
        if(device.hasObject(By.text("CURRENT SIGNAL")))finding("FAIL","NOW","Legacy CURRENT SIGNAL hero returned");else finding("PASS","NOW","Legacy Current Signal hero absent");
        if(device.hasObject(By.text("Building your current picture…")))finding("FAIL","NOW","Loading placeholder remained after render");else finding("PASS","NOW","Loading placeholder cleared after render");
    }catch(Throwable t){finding("FAIL","NOW",brief(t));}}

    private void cortexAskSmoke(){try{
        launch(ProposalAskCortexActivity.class);checkpoint("ask_before");UiObject2 local=device.findObject(By.text("Your data"));if(local!=null)local.click();
        List<UiObject2> edits=device.findObjects(By.clazz(EditText.class));if(edits.isEmpty()){finding("FAIL","ASK","Ask input not found");return;}
        edits.get(edits.size()-1).setText("What needs my attention? Answer in one short sentence.");UiObject2 ask=device.findObject(By.text("Ask"));if(ask==null){finding("FAIL","ASK","Ask button not found");return;}ask.click();SystemClock.sleep(5000);checkpoint("ask_after");
        if(device.hasObject(By.text("null")))finding("FAIL","ASK","Literal null rendered as an answer");else finding("PASS","ASK","No literal null answer rendered");
        if(device.hasObject(By.textContains("CORTEX_STRUCTURED_RESPONSE_V1")))finding("FAIL","ASK","Internal structured prompt leaked into UI");else finding("PASS","ASK","Internal response contract did not leak into UI");
    }catch(Throwable t){finding("WARN","ASK","Ask smoke could not complete: "+brief(t));}}

    private void legacyEscapeChecks(){
        try{launch(ProposalBriefActivity.class);SystemClock.sleep(350);checkpoint("legacy_brief_redirect");if(device.hasObject(By.text("CURRENT SIGNAL")))finding("FAIL","LEGACY","Old Today implementation is reachable");else if(device.hasObject(By.text("NOW"))||device.hasObject(By.text("What matters now")))finding("PASS","LEGACY","Legacy Brief redirects to current Now");else finding("WARN","LEGACY","Brief redirect could not be confirmed visually");}catch(Throwable t){finding("WARN","LEGACY",brief(t));}
        try{launch(BrainActivity.class);SystemClock.sleep(350);checkpoint("legacy_brain_redirect");if(PKG.equals(device.getCurrentPackageName()))finding("PASS","LEGACY","Legacy Brain entry stayed inside current Cortex shell");else finding("FAIL","LEGACY","Legacy Brain escaped Cortex");}catch(Throwable t){finding("WARN","LEGACY","Legacy Brain redirect probe failed: "+brief(t));}
    }

    private void launch(Class<?> cls){Intent i=new Intent(target,cls).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP);target.startActivity(i);device.wait(Until.hasObject(By.pkg(PKG)),5000);SystemClock.sleep(850);}
    private void safeScroll(){try{int w=device.getDisplayWidth(),h=device.getDisplayHeight();device.swipe(w/2,(int)(h*.78f),w/2,(int)(h*.30f),18);SystemClock.sleep(180);device.swipe(w/2,(int)(h*.30f),w/2,(int)(h*.72f),18);}catch(Throwable ignored){}}
    private void screenSanity(String name){try{if(device.hasObject(By.text("null")))finding("FAIL","RENDER",name+" renders literal null");if(device.hasObject(By.textContains("Exception"))||device.hasObject(By.textContains("has stopped")))finding("FAIL","RENDER",name+" shows exception/crash text");}catch(Throwable ignored){}}
    private void expectAny(String message,String... values){for(String x:values)if(device.wait(Until.hasObject(By.text(x)),700)){finding("PASS","ASSERT",message);return;}finding("FAIL","ASSERT",message+" — none of expected labels found");}
    private void finalDiagnostics(){try{write(new File(out,"logcat.txt"),device.executeShellCommand("logcat -d -t 1600"));}catch(Throwable t){finding("WARN","DIAGNOSTICS",brief(t));}try{write(new File(out,"activity_stack.txt"),device.executeShellCommand("dumpsys activity activities | head -n 220"));}catch(Throwable ignored){}try{write(new File(out,"package.txt"),device.executeShellCommand("dumpsys package "+PKG+" | head -n 260"));}catch(Throwable ignored){}}
    private void checkpoint(String label){step++;String base=String.format(Locale.US,"%03d_%s",step,safe(label));try{device.takeScreenshot(new File(out,base+".png"));}catch(Throwable t){finding("WARN","CAPTURE","Screenshot failed: "+brief(t));}try{device.dumpWindowHierarchy(new File(out,base+".xml"));}catch(Throwable t){finding("WARN","CAPTURE","Hierarchy failed: "+brief(t));}}
    private void finding(String severity,String area,String message){if("PASS".equals(severity))pass++;else if("FAIL".equals(severity))fail++;else warn++;findings.add(severity+" · "+area+" · "+message);record("FINDING",area,severity,message,null);}
    private void record(String type,String area,String severity,String message,JSONObject extra){try{JSONObject o=new JSONObject().put("type",type).put("area",area).put("severity",severity).put("message",message).put("time_ms",System.currentTimeMillis());if(extra!=null)o.put("extra",extra);events.put(o);}catch(Throwable ignored){}}
    private void writeReports()throws Exception{JSONObject root=new JSONObject().put("schema","CORTEX_INSTRUMENTED_SELF_USER_TEST_V4").put("package",PKG).put("generated_at",System.currentTimeMillis()).put("pass",pass).put("warning",warn).put("failure",fail).put("events",events);write(new File(out,"report.json"),root.toString(2));StringBuilder md=new StringBuilder("# Cortex Instrumented Self-User Test V4\n\n**Result:** ").append(pass).append(" pass · ").append(warn).append(" warning · ").append(fail).append(" failure\n\n");for(String f:findings)md.append("- ").append(f).append('\n');write(new File(out,"report.md"),md.toString());write(new File(out,"REPORT_DIRECTORY.txt"),out.getAbsolutePath()+"\n");}
    private static void write(File f,String s)throws Exception{File p=f.getParentFile();if(p!=null&&!p.exists())p.mkdirs();try(FileWriter w=new FileWriter(f)){w.write(s==null?"":s);}}
    private static String safe(String s){return s==null?"step":s.toLowerCase(Locale.US).replaceAll("[^a-z0-9_-]+","_");}
    private static String brief(Throwable t){String m=t==null?"":String.valueOf(t.getMessage());if(m.length()>180)m=m.substring(0,180);return t==null?"unknown":t.getClass().getSimpleName()+(m.isEmpty()?"":": "+m);}
}
