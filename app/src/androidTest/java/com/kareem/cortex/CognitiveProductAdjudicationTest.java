package com.kareem.cortex;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
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
import java.io.FileInputStream;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * V2 evaluates whether Cortex behaves like a useful cognitive product, not only
 * whether Android surfaces open. It is deliberately non-destructive: it judges
 * synthesis, attention presentation, semantic coherence and cross-surface
 * consistency without completing/deleting real user items.
 */
@RunWith(AndroidJUnit4.class)
public class CognitiveProductAdjudicationTest {
    private static final String PKG="com.kareem.cortex";
    private Instrumentation inst; private UiDevice device; private Context target; private File out;
    private final JSONArray events=new JSONArray(); private final ArrayList<String> findings=new ArrayList<>();
    private int pass=0,warn=0,fail=0,step=0;

    @Test public void cognitiveProductAdjudication() throws Exception {
        inst=InstrumentationRegistry.getInstrumentation(); device=UiDevice.getInstance(inst); target=inst.getTargetContext();
        String stamp=new SimpleDateFormat("yyyyMMdd_HHmmss",Locale.US).format(new Date());
        out=new File(target.getExternalFilesDir(null),"self-user-test/CognitiveProductAdjudication_"+stamp);
        if(!out.mkdirs()&&!out.isDirectory()) throw new IllegalStateException("Cannot create adjudication report directory");
        finding("PASS","RUN","Cognitive/Product Adjudication V2 started");
        cortexSynthesisAdjudication();
        todayAttentionAdjudication();
        memoryAdjudication();
        crossSurfaceAdjudication();
        legacyAndDeveloperSurfaceAdjudication();
        writeReports();
    }

    private void cortexSynthesisAdjudication(){try{
        launch(ProposalAskCortexActivity.class); checkpoint("cortex_before_cognitive_prompt");
        UiObject2 local=device.findObject(By.text("Your data")); if(local!=null){local.click();SystemClock.sleep(250);}
        List<UiObject2> edits=device.findObjects(By.clazz(EditText.class)); if(edits.isEmpty()){finding("FAIL","SYNTHESIS","Ask Cortex input not found");return;}
        Set<String> before=textSet();
        String prompt="What needs my attention right now? Answer with one concise synthesized sentence. Do not quote, enumerate, or dump sources.";
        edits.get(edits.size()-1).setText(prompt); UiObject2 ask=device.findObject(By.text("Ask"));
        if(ask==null){finding("FAIL","SYNTHESIS","Ask button not found");return;} ask.click();
        SystemClock.sleep(10000); checkpoint("cortex_after_cognitive_prompt");
        Set<String> after=textSet(); ArrayList<String> added=new ArrayList<>(); for(String s:after)if(!before.contains(s)&&!s.equals(prompt))added.add(s);
        String candidate=bestAnswerCandidate(added);
        write(new File(out,"cortex_answer_candidate.txt"),candidate+"\n\nNEW_UI_TEXTS\n"+join(added));
        if(candidate.isEmpty()){finding("FAIL","SYNTHESIS","No new answer text could be isolated after Ask");return;}
        String low=candidate.toLowerCase(Locale.US);
        if("null".equals(low)||low.contains("cortex_structured_response")) finding("FAIL","SYNTHESIS","Internal/null model output leaked into answer");
        else finding("PASS","SYNTHESIS","A non-null answer was rendered");
        int refs=countMatches(candidate,Pattern.compile("\\[[0-9]+\\]"));
        int urls=countMatches(low,Pattern.compile("https?://"));
        int sourceSignals=refs+urls+countOccurrences(low,"cib:")+countOccurrences(low,"sources (");
        if(sourceSignals>=2||candidate.length()>700) finding("FAIL","SYNTHESIS","Answer resembles raw evidence/source dump instead of synthesis (chars="+candidate.length()+", source-signals="+sourceSignals+")");
        else finding("PASS","SYNTHESIS","Answer is bounded and does not resemble a raw evidence dump");
        if(candidate.length()<=360&&!candidate.matches("(?s).*\\n\\s*[-•0-9]+[.)]?.*")) finding("PASS","SYNTHESIS","Answer respects concise single-answer intent");
        else finding("WARN","SYNTHESIS","Answer may ignore concise one-sentence intent (chars="+candidate.length()+")");
    }catch(Throwable t){finding("FAIL","SYNTHESIS","Cortex cognitive prompt failed: "+brief(t));}}

    private void todayAttentionAdjudication(){try{
        launch(CompactTodayActivity.class); checkpoint("today_cognitive_review"); String h=hierarchyText("today_cognitive_review_eval");
        if(h.contains("CURRENT SIGNAL")) finding("FAIL","ATTENTION","Legacy Current Signal hero is visible"); else finding("PASS","ATTENTION","Today begins as an attention surface without legacy hero");
        Pattern engine=Pattern.compile("(?i)(NOW|LATER|WATCHING)\\s*[•·]\\s*\\d{1,3}\\s*[•·]");
        if(engine.matcher(h).find()) finding("FAIL","ATTENTION","Internal attention score/band jargon is exposed on Today"); else finding("PASS","ATTENTION","Today does not expose raw attention score syntax");
        if(h.contains("WHY")&&h.contains("DONE")) finding("PASS","ATTENTION","Attention items expose an explanation/action boundary"); else finding("WARN","ATTENTION","WHY/DONE controls were not both visible in current data state");
        ArrayList<String> texts=new ArrayList<>(textSet()); int near=nearDuplicateCount(texts);
        if(near>=3) finding("WARN","ATTENTION","Today contains several near-duplicate visible text blocks; synthesis density may still be low (pairs="+near+")");
        else finding("PASS","ATTENTION","Today does not show heavy visible text duplication in the sampled viewport");
    }catch(Throwable t){finding("FAIL","ATTENTION","Today adjudication failed: "+brief(t));}}

    private void memoryAdjudication(){try{
        launch(ProposalPeopleProjectsActivity.class); checkpoint("memory_people_review"); String people=hierarchyText("memory_people_eval");
        boolean peopleTab=people.contains("People"); boolean search=people.contains("Search memory");
        if(peopleTab&&search) finding("PASS","MEMORY","Memory exposes people discovery/search"); else finding("FAIL","MEMORY","Memory primary discovery controls are missing");
        UiObject2 projects=device.findObject(By.text("Projects")); if(projects!=null){projects.click();SystemClock.sleep(500);checkpoint("memory_projects_review");String p=hierarchyText("memory_projects_eval");if(p.contains("Projects"))finding("PASS","MEMORY","Projects memory mode opens");else finding("WARN","MEMORY","Projects mode transition could not be confirmed");}
        else finding("WARN","MEMORY","Projects tab not found");
        if(people.contains("0 grounded reference")||people.contains("No people")||people.contains("No projects")) finding("WARN","MEMORY","Memory surface appears sparse despite Cortex retaining substantial memory; entity derivation should be reviewed");
    }catch(Throwable t){finding("FAIL","MEMORY","Memory adjudication failed: "+brief(t));}}

    private void crossSurfaceAdjudication(){try{
        launch(CompactTodayActivity.class); Set<String> today=meaningfulTexts(textSet()); checkpoint("cross_today");
        launch(SmartInboxActivity.class); Set<String> focus=meaningfulTexts(textSet()); checkpoint("cross_focus");
        int overlap=0;ArrayList<String> examples=new ArrayList<>();for(String a:today){for(String b:focus){if(similar(a,b)){overlap++;if(examples.size()<5)examples.add(a);break;}}}
        write(new File(out,"today_focus_overlap.txt"),"overlap="+overlap+"\n"+join(examples));
        if(overlap>=3) finding("WARN","CROSS-SURFACE","Today and Focus/Smart Inbox surface several overlapping cognitive items (overlap="+overlap+"); parallel attention products may be competing");
        else finding("PASS","CROSS-SURFACE","No strong duplicate attention surface was detected in sampled viewports");
    }catch(Throwable t){finding("WARN","CROSS-SURFACE","Cross-surface comparison unavailable: "+brief(t));}}

    private void legacyAndDeveloperSurfaceAdjudication(){try{
        launch(ProposalBriefActivity.class);SystemClock.sleep(250);if(device.hasObject(By.text("TODAY")))finding("PASS","PRODUCT-IA","Legacy Today entry resolves to current Today");else finding("WARN","PRODUCT-IA","Legacy Today redirect was not visually confirmed");
        launch(SettingsActivity.class);checkpoint("settings_product_review");String s=hierarchyText("settings_product_eval");int diagnostic=0;String[] terms={"Attention Evaluation","Relevance Evaluation","Capability Matrix","Cortex Audit","External Model Check","Correction Learning"};for(String term:terms)if(s.contains(term))diagnostic++;
        if(diagnostic>=3)finding("WARN","PRODUCT-IA","User Settings visibly mixes multiple developer/diagnostic surfaces (sample count="+diagnostic+"); consider a separate Developer/Diagnostics area");else finding("PASS","PRODUCT-IA","Settings sampled viewport is not dominated by developer diagnostics");
    }catch(Throwable t){finding("WARN","PRODUCT-IA","IA adjudication incomplete: "+brief(t));}}

    private void launch(Class<?> cls){Intent i=new Intent(target,cls).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP);target.startActivity(i);device.wait(Until.hasObject(By.pkg(PKG)),5000);SystemClock.sleep(900);}
    private Set<String> textSet(){HashSet<String> out=new HashSet<>();try{for(UiObject2 o:device.findObjects(By.pkg(PKG))){String t=o.getText();if(t!=null){t=t.replaceAll("\\s+"," ").trim();if(t.length()>=2)out.add(t);}}}catch(Throwable ignored){}return out;}
    private Set<String> meaningfulTexts(Set<String> in){HashSet<String> o=new HashSet<>();for(String s:in){String x=s.replaceAll("\\s+"," ").trim();if(x.length()>=20&&!x.matches("(?i)^(today|memory|capture|cortex|why|done|snooze|hide).*$"))o.add(x);}return o;}
    private String bestAnswerCandidate(List<String> xs){String best="";for(String s:xs){if(s==null)continue;String x=s.trim();String low=x.toLowerCase(Locale.US);if(x.length()<18)continue;if(low.equals("thinking of useful next moves…")||low.startsWith("ask cortex")||low.startsWith("your data")||low.startsWith("sources ("))continue;if(x.length()>best.length())best=x;}return best;}
    private int nearDuplicateCount(List<String> xs){int n=0;for(int i=0;i<xs.size();i++){String a=norm(xs.get(i));if(a.length()<24)continue;for(int j=i+1;j<xs.size();j++){String b=norm(xs.get(j));if(b.length()<24)continue;if(similar(a,b)){n++;break;}}}return n;}
    private boolean similar(String a,String b){String x=norm(a),y=norm(b);if(x.length()<20||y.length()<20)return false;if(x.equals(y))return true;String shorter=x.length()<y.length()?x:y,longer=x.length()<y.length()?y:x;return shorter.length()>=30&&longer.contains(shorter.substring(0,Math.min(shorter.length(),42)));}
    private String norm(String s){return s==null?"":s.toLowerCase(Locale.US).replaceAll("[^\\p{L}\\p{N}]+"," ").replaceAll("\\s+"," ").trim();}
    private String hierarchyText(String label)throws Exception{File f=new File(out,String.format(Locale.US,"%03d_%s.xml",++step,safe(label)));device.dumpWindowHierarchy(f);return read(f);}
    private void checkpoint(String label){step++;String base=String.format(Locale.US,"%03d_%s",step,safe(label));try{device.takeScreenshot(new File(out,base+".png"));}catch(Throwable ignored){}try{device.dumpWindowHierarchy(new File(out,base+".xml"));}catch(Throwable ignored){}try{events.put(new JSONObject().put("step",step).put("label",label).put("package",String.valueOf(device.getCurrentPackageName())).put("time_ms",System.currentTimeMillis()));}catch(Throwable ignored){}}
    private void finding(String sev,String area,String msg){if("PASS".equals(sev))pass++;else if("FAIL".equals(sev))fail++;else warn++;findings.add(sev+" · "+area+" · "+msg);try{events.put(new JSONObject().put("severity",sev).put("area",area).put("message",msg).put("time_ms",System.currentTimeMillis()));}catch(Throwable ignored){}}
    private void writeReports()throws Exception{JSONObject root=new JSONObject().put("schema","CORTEX_COGNITIVE_PRODUCT_ADJUDICATION_V2").put("generated_at",System.currentTimeMillis()).put("pass",pass).put("warning",warn).put("failure",fail).put("events",events);write(new File(out,"report.json"),root.toString(2));StringBuilder md=new StringBuilder("# Cortex Cognitive / Product Adjudication V2\n\n**Result:** ").append(pass).append(" pass · ").append(warn).append(" warning · ").append(fail).append(" failure\n\nThis suite judges cognitive/product behavior, not only Android runtime health. A green V1 does not imply a green V2.\n\n## Findings\n\n");for(String f:findings)md.append("- ").append(f).append("\n");md.append("\n## Evidence\n\nPNG/XML checkpoints plus answer and cross-surface evidence are included. The suite is non-destructive and does not complete/delete real user items.\n");write(new File(out,"report.md"),md.toString());}
    private static int countMatches(String s,Pattern p){int n=0;Matcher m=p.matcher(s==null?"":s);while(m.find())n++;return n;}
    private static int countOccurrences(String s,String x){int n=0,p=0;while(s!=null&&x!=null&&!x.isEmpty()&&(p=s.indexOf(x,p))>=0){n++;p+=x.length();}return n;}
    private static String join(Iterable<String> xs){StringBuilder b=new StringBuilder();for(String x:xs)b.append(x).append('\n');return b.toString();}
    private static String read(File f)throws Exception{byte[] b=new byte[(int)Math.min(f.length(),2_000_000)];try(FileInputStream in=new FileInputStream(f)){int n=in.read(b);return n<=0?"":new String(b,0,n,"UTF-8");}}
    private static void write(File f,String s)throws Exception{File p=f.getParentFile();if(p!=null&&!p.exists())p.mkdirs();try(FileWriter w=new FileWriter(f)){w.write(s==null?"":s);}}
    private static String safe(String s){return s==null?"step":s.toLowerCase(Locale.US).replaceAll("[^a-z0-9_-]+","_");}
    private static String brief(Throwable t){String m=t==null?"":String.valueOf(t.getMessage());if(m.length()>180)m=m.substring(0,180);return t==null?"unknown":t.getClass().getSimpleName()+(m.isEmpty()?"":": "+m);}
}
