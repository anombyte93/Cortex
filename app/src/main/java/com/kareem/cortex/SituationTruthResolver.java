package com.kareem.cortex;

import android.database.Cursor;
import java.util.*;

/** Lifecycle/temporal truth layer used before attention ranking and Ask synthesis. */
public final class SituationTruthResolver {
    private SituationTruthResolver(){}

    private static final Set<String> LIVE_KINDS=new HashSet<>(Arrays.asList(
            "ACTION","REMINDER","WAITING","DECISION","ALERT","CHANGE","GOAL_SIGNAL","PROJECT_CANDIDATE"));

    public static GroundedAnswer tryAnswer(VaultDb db,String question){
        String q=n(question);
        if(!has(q,"attention right now","needs my attention","ongoing situations","ongoing situation","episodes",
                "upcoming deadlines","appointments","reminders matter","situations, not memories","situations not memories",
                "what is still open","what's still open","what is open","محتاج انتباهي","محتاج اهتمام","المواقف","المواعيد","التذكيرات","الحاجات المفتوحة")) return null;

        ArrayList<Situation> all=liveSituations(db,120);long now=System.currentTimeMillis();
        ArrayList<Situation> eligible=new ArrayList<>();for(Situation s:all){if(staleForAttention(s,now))continue;s.rank=rank(s,now);eligible.add(s);}all=eligible;
        if(all.isEmpty()) return new GroundedAnswer(question,"I don't have a reconciled live situation that needs surfacing right now.",0.78,new ArrayList<SemanticHit>(),new ArrayList<String>(),new ArrayList<String>());
        all.sort((a,b)->Double.compare(b.rank,a.rank));

        ArrayList<Situation> chosen=new ArrayList<>();HashSet<String> seen=new HashSet<>();
        for(Situation s:all){String key=lifeKey(s);if(key.isEmpty()||!seen.add(key))continue;chosen.add(s);if(chosen.size()>=6)break;}

        ArrayList<SemanticHit> sources=new ArrayList<>();StringBuilder ans=new StringBuilder("Current reconciled situations:\n");
        for(Situation s:chosen){ans.append("• ").append(s.title);String timing=timing(s,now);if(!timing.isEmpty())ans.append(" — ").append(timing);String next=nextMove(s,now);if(!next.isEmpty())ans.append(". Next: ").append(next);ArrayList<SemanticHit> ev=SemanticIndex.searchForAskRaw(db,s.title+" "+s.body,3);SemanticHit support=firstSupporting(s,ev);if(support!=null){boolean exists=false;for(SemanticHit old:sources)if(old.item.id==support.item.id){exists=true;break;}if(!exists){sources.add(support);ans.append(" [M").append(sources.size()).append("]");}}ans.append('\n');}
        return new GroundedAnswer(question,ans.toString().trim(),0.93,sources,new ArrayList<String>(),new ArrayList<String>());
    }

    public static boolean allowAskMemory(VaultDb db,KnowledgeItem item){
        if(item==null)return false;String text=canonical(item.title+" "+item.summary+" "+item.extractedText+" "+item.rawText);if(text.isEmpty())return true;
        Cursor c=db.getReadableDatabase().rawQuery("SELECT title,body,state,resolved_at FROM derived_items WHERE state IN ('dismissed','resolved','done','closed') ORDER BY updated_at DESC LIMIT 240",null);
        try{while(c.moveToNext()){String d=canonical(n(c.getString(0))+" "+n(c.getString(1)));if(d.length()<16)continue;if(tokenOverlap(text,d)>=0.72)return false;}}finally{c.close();}return true;
    }

    private static ArrayList<Situation> liveSituations(VaultDb db,int limit){
        ArrayList<Situation> out=new ArrayList<>();Cursor c=db.getReadableDatabase().rawQuery("SELECT id,kind,title,body,state,confidence,importance,thread_id,anchor_signal_id,created_at,updated_at,resolved_at FROM derived_items WHERE state IN ('open','pending') ORDER BY updated_at DESC LIMIT ?",new String[]{String.valueOf(limit*3)});
        try{while(c.moveToNext()){String kind=n(c.getString(1)).toUpperCase(Locale.ROOT);if(!LIVE_KINDS.contains(kind))continue;Situation s=new Situation();s.id=c.getLong(0);s.kind=kind;s.title=n(c.getString(2));s.body=n(c.getString(3));s.state=n(c.getString(4));s.confidence=c.getDouble(5);s.importance=c.getInt(6);s.threadId=c.getLong(7);s.signalId=c.getLong(8);s.createdAt=c.getLong(9);s.updatedAt=c.getLong(10);long anchor=s.createdAt>0?s.createdAt:(s.updatedAt>0?s.updatedAt:System.currentTimeMillis());s.targetAt=TemporalResolver.resolveForAttention(s.title+" "+s.body,anchor);if(shadowedByClosed(db,s)||resolvedByNewerThreadEvidence(db,s))continue;out.add(s);if(out.size()>=limit)break;}}finally{c.close();}return out;
    }

    private static boolean staleForAttention(Situation s,long now){
        if(s.targetAt<=0)return false;long overdue=now-s.targetAt;if(overdue<=0)return false;
        if(overdue>96L*3600000L)return true;
        boolean freshAfterTarget=s.updatedAt>s.targetAt&&now-s.updatedAt<24L*3600000L;
        if(overdue>36L*3600000L&&!freshAfterTarget)return true;
        String z=LocalSemanticEmbedder.norm(s.title+" "+s.body);
        if(overdue>18L*3600000L&&has(z,"tomorrow","today","tonight","this morning","this afternoon","بكرة","غدا","غداً","النهاردة","اليوم","الليلة")&&!freshAfterTarget)return true;
        return false;
    }

    private static boolean resolvedByNewerThreadEvidence(VaultDb db,Situation s){
        if(s.threadId<=0)return false;Cursor c=db.getReadableDatabase().rawQuery("SELECT title,body,occurred_at FROM raw_signals WHERE thread_id=? AND occurred_at>=? ORDER BY occurred_at DESC LIMIT 24",new String[]{String.valueOf(s.threadId),String.valueOf(Math.max(0,s.createdAt-86400000L))});
        try{while(c.moveToNext()){String z=LocalSemanticEmbedder.norm(n(c.getString(0))+" "+n(c.getString(1)));if(isResolutionText(z))return true;}}finally{c.close();}return false;
    }

    private static boolean isResolutionText(String z){return has(z,"you're all set","you’re all set","setup complete","set up successfully","completed successfully","completed","resolved","done","cancelled","canceled","تم بنجاح","تم الاعداد","تم الإعداد","خلص","اتعمل");}

    private static boolean shadowedByClosed(VaultDb db,Situation live){
        String key=canonical(live.title+" "+live.body);if(key.isEmpty())return false;Cursor c=db.getReadableDatabase().rawQuery("SELECT title,body,thread_id,updated_at FROM derived_items WHERE id<>? AND state IN ('dismissed','resolved','done','closed') ORDER BY updated_at DESC LIMIT 160",new String[]{String.valueOf(live.id)});
        try{while(c.moveToNext()){long thread=c.getLong(2);if(live.threadId>0&&thread==live.threadId)return true;String x=canonical(n(c.getString(0))+" "+n(c.getString(1)));if(tokenOverlap(key,x)>=0.78&&c.getLong(3)>=live.createdAt)return true;}}finally{c.close();}return false;
    }

    private static double rank(Situation s,long now){
        double importance=Math.max(0,Math.min(1,s.importance/100.0)),confidence=Math.max(0,Math.min(1,s.confidence));double temporal=.30;
        if(s.targetAt>0){double h=(s.targetAt-now)/3600000.0;if(h>48)temporal=.12;else if(h>24)temporal=.30;else if(h>6)temporal=.58;else if(h>0)temporal=.95;else{double overdue=-h;temporal=overdue<=12?.92:overdue<=24?.70:.30;}}
        double action=("ACTION".equals(s.kind)||"REMINDER".equals(s.kind))?1:("WAITING".equals(s.kind)?.64:("DECISION".equals(s.kind)?.74:.50));double age=Math.max(0,(now-s.updatedAt)/3600000.0),freshness=Math.exp(-age/120.0);return .42*temporal+.24*action+.18*importance+.08*confidence+.08*freshness;
    }

    private static String timing(Situation s,long now){if(s.targetAt<=0)return "time not grounded";double h=(s.targetAt-now)/3600000.0;if(h<-24)return "past-dated; verify before surfacing";if(h<=0)return "due/past due";if(h<=6)return "within a few hours";if(h<=24)return "later today / within 24 hours";if(h<=48)return "tomorrow / within about 2 days";return "future-dated";}
    private static String nextMove(Situation s,long now){if(s.targetAt>now+6L*3600000L)return "keep scheduled and surface only when preparation is actually needed";if("WAITING".equals(s.kind))return "check whether the dependency changed, then follow up only if still unresolved";if("DECISION".equals(s.kind))return "resolve the decision or identify the smallest missing information";if("ALERT".equals(s.kind)||"CHANGE".equals(s.kind))return "verify impact before creating an action";return "complete it, schedule it, or explicitly dismiss it so it cannot resurface";}

    private static String lifeKey(Situation s){return s.threadId>0?"thread:"+s.threadId:canonical(s.title+" "+s.body);}
    private static SemanticHit firstSupporting(Situation s,ArrayList<SemanticHit> ev){String target=canonical(s.title+" "+s.body);for(SemanticHit h:ev){String e=canonical((h.item.title==null?"":h.item.title)+" "+(h.item.summary==null?"":h.item.summary)+" "+h.snippet);if(tokenOverlap(target,e)>=0.34)return h;}return null;}
    private static String canonical(String s){String x=LocalSemanticEmbedder.norm(n(s));StringBuilder b=new StringBuilder();for(String w:x.split("[^\\p{L}\\p{Nd}]+")){if(w.length()<2||STOP.contains(w))continue;if(b.length()>0)b.append(' ');b.append(w);}return b.toString();}
    private static double tokenOverlap(String a,String b){HashSet<String>x=new HashSet<>(Arrays.asList(a.split(" "))),y=new HashSet<>(Arrays.asList(b.split(" ")));x.remove("");y.remove("");if(x.isEmpty()||y.isEmpty())return 0;int inter=0;for(String w:x)if(y.contains(w))inter++;return inter/(double)Math.min(x.size(),y.size());}
    private static final Set<String> STOP=new HashSet<>(Arrays.asList("the","a","an","to","of","and","or","for","in","on","at","is","are","be","my","your","this","that","من","في","على","الى","إلى","اللي","ده","دي","و","او","أو"));
    private static boolean has(String q,String...xs){String z=LocalSemanticEmbedder.norm(q);for(String x:xs)if(z.contains(LocalSemanticEmbedder.norm(x)))return true;return false;}
    private static String n(String s){return s==null?"":s.trim();}
    private static final class Situation{long id,threadId,signalId,createdAt,updatedAt,targetAt;String kind,title,body,state;double confidence,rank;int importance;}
}
