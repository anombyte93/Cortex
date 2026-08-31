package com.kareem.cortex;

import android.database.Cursor;
import java.util.*;

/** High-precision routing before generic semantic retrieval. */
public final class CognitiveIntentRouter {
    private CognitiveIntentRouter(){}

    public static GroundedAnswer tryAnswer(VaultDb db,String question){
        String q=n(question);String z=LocalSemanticEmbedder.norm(q);
        if(isWorkProjectQuery(z))return workProjects(db,q);
        if(isNoiseQuery(z))return noiseAudit(db,q);
        if(isLifecycleQuery(z))return lifecycle(db,q);
        return null;
    }

    private static GroundedAnswer workProjects(VaultDb db,String q){
        ArrayList<String> lines=new ArrayList<>();ArrayList<SemanticHit> src=new ArrayList<>();LinkedHashSet<String> keys=new LinkedHashSet<>();
        Cursor c=db.getReadableDatabase().rawQuery("SELECT id,title,body,kind,importance,updated_at FROM derived_items WHERE state IN ('open','pending') AND (kind='PROJECT_CANDIDATE' OR upper(kind)='ACTION' OR upper(kind)='WAITING' OR upper(kind)='DECISION') ORDER BY importance DESC,updated_at DESC LIMIT 160",null);
        try{while(c.moveToNext()){
            String t=n(c.getString(1)),b=n(c.getString(2)),kind=n(c.getString(3));String all=t+" "+b;
            if(!workText(all,kind)||excludedWorkDomain(all))continue;
            String key=canonical(all);if(key.isEmpty()||!keys.add(key))continue;
            lines.add(t+(b.isEmpty()?"":" — "+clip(b,180)));if(lines.size()>=8)break;
        }}finally{c.close();}

        ArrayList<SemanticHit> hits=SemanticIndex.searchForAsk(db,"project work deliverable budget drawings site client construction مشروع شغل ميزانية رسومات موقع عميل",24);
        for(SemanticHit h:hits){if(h==null||h.item==null||!workLike(h.item)||isVisual(h.item))continue;String key=canonical(n(h.item.title)+" "+n(h.item.summary)+" "+n(h.item.extractedText)+" "+n(h.item.rawText));if(key.isEmpty()||!keys.add(key))continue;src.add(h);String x=n(h.item.title);String b=!n(h.item.summary).isEmpty()?h.item.summary:h.snippet;lines.add(x+(n(b).isEmpty()?"":" — "+clip(b,180)));if(lines.size()>=8)break;}
        if(lines.isEmpty())return new GroundedAnswer(q,"I don't have a grounded open work/project thread right now.",.76,src,new ArrayList<String>(),new ArrayList<String>());
        return new GroundedAnswer(q,bullet("Open work/project threads I can ground",lines),.92,src,new ArrayList<String>(),new ArrayList<String>());
    }

    private static GroundedAnswer noiseAudit(VaultDb db,String q){
        ArrayList<SemanticHit> hits=SemanticIndex.searchForAskRaw(db,q,20);ArrayList<SemanticHit> keep=new ArrayList<>();ArrayList<String> noise=new ArrayList<>();ArrayList<String> context=new ArrayList<>();
        for(SemanticHit h:hits){KnowledgeItem k=h.item;if(k==null)continue;String label=n(k.title);if(isNoise(k)){noise.add(label.isEmpty()?k.type:label);keep.add(h);}else if(isContextOnly(k)){context.add(label.isEmpty()?k.type:label);keep.add(h);}}
        if(noise.isEmpty()&&context.isEmpty())return new GroundedAnswer(q,"I don't see enough grounded low-quality/context-only evidence to classify reliably.",.66,keep,new ArrayList<String>(),new ArrayList<String>());
        StringBuilder a=new StringBuilder();if(!noise.isEmpty()){a.append("Probably noise / low-value evidence:\n");for(String x:dedupe(noise,6))a.append("• ").append(clip(x,120)).append('\n');}if(!context.isEmpty()){if(a.length()>0)a.append('\n');a.append("Context, not an action:\n");for(String x:dedupe(context,6))a.append("• ").append(clip(x,120)).append('\n');}a.append("\nThese should not become tasks or attention items unless newer evidence gives them a concrete obligation.");
        return new GroundedAnswer(q,a.toString().trim(),.92,keep,new ArrayList<String>(),new ArrayList<String>());
    }

    private static GroundedAnswer lifecycle(VaultDb db,String q){
        ArrayList<LifeRow> closedRows=new ArrayList<>(),liveRows=new ArrayList<>();
        Cursor b=db.getReadableDatabase().rawQuery("SELECT title,body,state,thread_id,updated_at FROM derived_items WHERE state IN ('dismissed','resolved','done','closed') ORDER BY updated_at DESC LIMIT 240",null);
        try{while(b.moveToNext()){String t=n(b.getString(0)),body=n(b.getString(1)),state=n(b.getString(2));LifeRow r=new LifeRow(t+" "+body,t+" — "+state+(body.isEmpty()?"":" · "+clip(body,130)),b.getLong(3),b.getLong(4));if(!duplicateLife(closedRows,r))closedRows.add(r);}}finally{b.close();}
        Cursor a=db.getReadableDatabase().rawQuery("SELECT title,body,kind,thread_id,updated_at FROM derived_items WHERE state IN ('open','pending') ORDER BY importance DESC,updated_at DESC LIMIT 240",null);
        try{while(a.moveToNext()){
            String t=n(a.getString(0)),body=n(a.getString(1));long thread=a.getLong(3);LifeRow r=new LifeRow(t+" "+body,t+(body.isEmpty()?"":" — "+clip(body,150)),thread,a.getLong(4));
            if(matchesAny(closedRows,r)||duplicateLife(liveRows,r))continue;
            if(thread>0&&threadResolved(db,thread,a.getLong(4)))continue;
            liveRows.add(r);
        }}finally{a.close();}
        StringBuilder out=new StringBuilder();out.append("Still live:\n");if(liveRows.isEmpty())out.append("• None I can ground.\n");else for(LifeRow r:limitLife(liveRows,6))out.append("• ").append(r.display).append('\n');out.append("\nDo not resurface as live:\n");if(closedRows.isEmpty())out.append("• No recently closed obligations found.\n");else for(LifeRow r:limitLife(closedRows,6))out.append("• ").append(r.display).append('\n');
        return new GroundedAnswer(q,out.toString().trim(),.96,new ArrayList<SemanticHit>(),new ArrayList<String>(),new ArrayList<String>());
    }

    private static boolean threadResolved(VaultDb db,long thread,long since){Cursor c=db.getReadableDatabase().rawQuery("SELECT title,body FROM raw_signals WHERE thread_id=? AND occurred_at>=? ORDER BY occurred_at DESC LIMIT 24",new String[]{String.valueOf(thread),String.valueOf(Math.max(0,since-86400000L))});try{while(c.moveToNext()){String z=LocalSemanticEmbedder.norm(n(c.getString(0))+" "+n(c.getString(1)));if(has(z,"you're all set","you’re all set","setup complete","set up successfully","completed successfully","resolved","done","cancelled","canceled","تم بنجاح","تم الإعداد","تم الاعداد","خلص","اتعمل"))return true;}}finally{c.close();}return false;}

    private static boolean workLike(KnowledgeItem k){if(k==null||isVisual(k))return false;String raw=n(k.title)+" "+n(k.summary)+" "+n(k.extractedText)+" "+n(k.rawText)+" "+n(k.category)+" "+n(k.tags);String x=LocalSemanticEmbedder.norm(raw);return workText(x,n(k.type))&&!excludedWorkDomain(x);}
    private static boolean workText(String text,String kind){String x=LocalSemanticEmbedder.norm(text);boolean domain=has(x,"project","deliverable","drawing","drawings","site","client","budget","contractor","construction","architect","design","tender","boq","مشروع","شغل","موقع","ميزانية","رسومات","عميل","مقاول","تصميم");boolean taskish=has(x,"open","pending","follow up","review","send","submit","deadline","meeting","waiting","approve","مفتوح","متابعة","راجع","ابعت","تسليم","موعد","مستني","اعتماد");return domain&&(taskish||"PROJECT_CANDIDATE".equalsIgnoreCase(kind)||"ACTION".equalsIgnoreCase(kind)||"WAITING".equalsIgnoreCase(kind)||"DECISION".equalsIgnoreCase(kind));}
    private static boolean excludedWorkDomain(String text){String x=LocalSemanticEmbedder.norm(text);return has(x,"cib","bank","banking","credit card","debit card","spotify","suno","google play","payment declined","transaction","balance","amount due","otp","verification code","sign in","security alert","بنك","بطاقة","فيزا","ماستركارد","معاملة","رصيد","دفع","مرفوض");}
    private static boolean isVisual(KnowledgeItem k){return k!=null&&("SCREENSHOT".equals(k.type)||"IMAGE".equals(k.type));}
    private static boolean isNoise(KnowledgeItem k){String raw=n(k.title)+" "+n(k.summary)+" "+n(k.extractedText)+" "+n(k.rawText);String x=LocalSemanticEmbedder.norm(raw);if(AskSourcePolicy.isSelfUiScreenshot(k))return true;if(("SCREENSHOT".equals(k.type)||"IMAGE".equals(k.type))&&OcrGarbageGate.assessText(raw).score<.48)return true;return has(x,"screenshot saved","package installer","system ui","systemui","play store download","response ready");}
    private static boolean isContextOnly(KnowledgeItem k){String x=LocalSemanticEmbedder.norm(n(k.category)+" "+n(k.tags)+" "+n(k.summary));return has(x,"reference","research","context","contact","links & research")&&!has(x,"todo","action","follow up","reminder","decision","deadline","appointment","لازم","متابعة","موعد");}
    private static boolean isWorkProjectQuery(String z){return has(z,"work","project","projects","project threads","work threads","شغل","مشروع","مشاريع")&&has(z,"open","still","ongoing","thread","threads","مفتوح","مستمرة","مستمر");}
    private static boolean isNoiseQuery(String z){return has(z,"noise","context rather than actions","not actions","probably noise","ضوضاء","مش مهمة","مش actions");}
    private static boolean isLifecycleQuery(String z){return has(z,"lifecycle","genuinely still live","must not be resurfaced","dismissed","resolved","done","closed","مقفول","اتحل","خلص");}

    private static boolean duplicateLife(List<LifeRow> rows,LifeRow r){for(LifeRow x:rows)if(sameLife(x,r))return true;return false;}
    private static boolean matchesAny(List<LifeRow> rows,LifeRow r){for(LifeRow x:rows)if(sameLife(x,r))return true;return false;}
    private static boolean sameLife(LifeRow a,LifeRow b){if(a.thread>0&&b.thread>0&&a.thread==b.thread)return true;String x=canonical(a.raw),y=canonical(b.raw);double o=overlap(x,y);if(o>=.68&&Math.abs(a.updated-b.updated)<=7L*86400000L)return true;return false;}
    private static double overlap(String a,String b){Set<String>x=new HashSet<>(Arrays.asList(a.split(" "))),y=new HashSet<>(Arrays.asList(b.split(" ")));x.remove("");y.remove("");if(x.isEmpty()||y.isEmpty())return 0;int n=0;for(String w:x)if(y.contains(w))n++;return n/(double)Math.min(x.size(),y.size());}
    private static String canonical(String s){String x=LocalSemanticEmbedder.norm(n(s));StringBuilder b=new StringBuilder();for(String w:x.split("[^\\p{L}\\p{Nd}]+")){if(w.length()<2||STOP.contains(w))continue;if(b.length()>0)b.append(' ');b.append(w);}return b.toString();}
    private static final Set<String> STOP=new HashSet<>(Arrays.asList("the","a","an","to","of","and","or","for","in","on","at","is","are","be","my","your","this","that","من","في","على","الى","إلى","اللي","ده","دي","و","او","أو"));
    private static boolean has(String z,String...xs){for(String x:xs)if(z.contains(LocalSemanticEmbedder.norm(x)))return true;return false;}
    private static ArrayList<String> dedupe(List<String> xs,int max){LinkedHashMap<String,String> m=new LinkedHashMap<>();for(String x:xs){String k=canonical(x);if(!k.isEmpty()&&!m.containsKey(k))m.put(k,x);if(m.size()>=max)break;}return new ArrayList<>(m.values());}
    private static ArrayList<LifeRow> limitLife(List<LifeRow> xs,int max){ArrayList<LifeRow> o=new ArrayList<>();for(LifeRow x:xs){o.add(x);if(o.size()>=max)break;}return o;}
    private static String bullet(String head,List<String> xs){StringBuilder s=new StringBuilder(head).append(":\n");for(String x:xs)s.append("• ").append(x).append('\n');return s.toString().trim();}
    private static String clip(String s,int n){String x=n(s).replaceAll("\\s+"," ");return x.length()<=n?x:x.substring(0,n)+"…";}
    private static String n(String s){return s==null?"":s.trim();}
    private static final class LifeRow{final String raw,display;final long thread,updated;LifeRow(String r,String d,long t,long u){raw=r;display=d;thread=t;updated=u;}}
}
