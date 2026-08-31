package com.kareem.cortex;

import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;

/** Adds human-product quality gates so a structurally green review cannot hide obvious cognitive failures. */
public final class StrictReviewGate {
    private StrictReviewGate(){}

    public static JSONObject evaluate(File root)throws Exception{
        JSONArray checks=new JSONArray();int pass=0,fail=0;
        JSONObject v3=json(new File(root,"V3/student_cases.json"));JSONArray cases=v3.optJSONArray("cases");if(cases==null)cases=new JSONArray();
        String now=answer(cases,0);boolean nowOk=!staleAttention(now);checks.put(row("NOW_VALIDITY",nowOk));if(nowOk)pass++;else fail++;
        JSONObject work=caseAt(cases,2);JSONArray ev=work.optJSONArray("retrieved_evidence");int bad=0,total=ev==null?0:ev.length();if(ev!=null)for(int i=0;i<ev.length();i++){JSONObject e=ev.optJSONObject(i);if(e!=null&&("SCREENSHOT".equals(e.optString("type"))||"IMAGE".equals(e.optString("type"))))bad++;}String workAnswer=work.optString("answer","");boolean wrongDomain=contains(workAnswer,"cib","spotify","suno","credit card","debit card","payment declined","bank transaction","google play");boolean workOk=!wrongDomain&&(total==0||bad==0);checks.put(row("WORK_PROJECT_DOMAIN",workOk));if(workOk)pass++;else fail++;
        String life=answer(cases,5);boolean lifeOk=!contradiction(life);checks.put(row("LIFECYCLE_CONSISTENCY",lifeOk));if(lifeOk)pass++;else fail++;
        boolean v5=new File(root,"V5/TEST_RESULT.txt").isFile()&&read(new File(root,"V5/TEST_RESULT.txt")).trim().startsWith("PASS");checks.put(row("V5_CONTRACT",v5));if(v5)pass++;else fail++;
        JSONObject out=new JSONObject().put("schema","CORTEX_STRICT_REVIEW_GATE_V2").put("pass",pass).put("failure",fail).put("all_green",fail==0).put("checks",checks);write(new File(root,"STRICT_REVIEW_GATE.json"),out.toString(2));return out;
    }

    private static boolean staleAttention(String s){String z=s.toLowerCase(Locale.ROOT);if(contains(z,"past-dated","verify whether still open"))return true;Pattern p=Pattern.compile("(?i)due\\s*:?\\s*(?:mon|tue|wed|thu|fri|sat|sun)[a-z]*,?\\s*25\\s+aug|due\\s*:?\\s*25\\s+aug");return p.matcher(z).find();}
    private static JSONObject row(String name,boolean ok)throws Exception{return new JSONObject().put("name",name).put("status",ok?"PASS":"FAIL");}
    private static JSONObject caseAt(JSONArray a,int i){JSONObject o=a.optJSONObject(i);return o==null?new JSONObject():o;}
    private static String answer(JSONArray a,int i){return caseAt(a,i).optString("answer","");}
    private static boolean contradiction(String s){String[] p=s.split("(?i)do not resurface as live:",2);if(p.length<2)return false;Set<String>a=lines(p[0]),b=lines(p[1]);for(String x:a)for(String y:b)if(overlap(x,y)>=.72)return true;return false;}
    private static Set<String> lines(String s){Set<String>o=new LinkedHashSet<>();for(String x:s.split("\\n")){String z=LocalSemanticEmbedder.norm(x.replaceFirst("^[•*-]\\s*",""));if(z.length()>10)o.add(z);}return o;}
    private static double overlap(String a,String b){Set<String>x=new HashSet<>(Arrays.asList(a.split(" "))),y=new HashSet<>(Arrays.asList(b.split(" ")));int n=0;for(String w:x)if(y.contains(w))n++;return x.isEmpty()||y.isEmpty()?0:n/(double)Math.min(x.size(),y.size());}
    private static boolean contains(String s,String...xs){String z=s.toLowerCase(Locale.ROOT);for(String x:xs)if(z.contains(x.toLowerCase(Locale.ROOT)))return true;return false;}
    private static JSONObject json(File f)throws Exception{return new JSONObject(read(f));}
    private static String read(File f)throws Exception{ByteArrayOutputStream b=new ByteArrayOutputStream();try(InputStream in=new FileInputStream(f)){byte[] x=new byte[8192];int n;while((n=in.read(x))>0)b.write(x,0,n);}return new String(b.toByteArray(),StandardCharsets.UTF_8);}
    private static void write(File f,String s)throws Exception{try(FileWriter w=new FileWriter(f)){w.write(s);}}
}
