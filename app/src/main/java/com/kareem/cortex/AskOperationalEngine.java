package com.kareem.cortex;

import android.database.Cursor;
import java.util.*;

/** Answers state questions from live Cortex state instead of semantic similarity. */
public final class AskOperationalEngine {
    private AskOperationalEngine(){}

    public static GroundedAnswer tryAnswer(VaultDb db,String question){
        String q=n(question),norm=LocalSemanticEmbedder.norm(q);if(q.isEmpty())return null;
        if(isRunningApps(norm))return runningApps(db,q);
        if(isPhoneContext(norm))return phoneContext(db,q);
        if(isAttention(norm))return attention(db,q);
        if(isWaiting(norm))return waiting(db,q);
        if(isRecentDecisions(norm))return decisions(db,q);
        if(isGoals(norm))return kinds(db,q,"Your active goals",new String[]{"GOAL_SIGNAL"},12);
        if(isIdeas(norm))return kinds(db,q,"Ideas and opportunities currently in Cortex",new String[]{"IDEA","OPPORTUNITY","INSIGHT","HYPOTHESIS"},12);
        if(isContextlessProject(norm))return new GroundedAnswer(q,"Tell me which project you mean, and I’ll ground the answer in that project’s Cortex context.",1.0,new ArrayList<SemanticHit>(),new ArrayList<String>(),new ArrayList<String>());
        return null;
    }

    private static GroundedAnswer runningApps(VaultDb db,String q){
        try{
            PhoneContextStore.ensure(db);int count=PhoneContextStore.activeProcessCount(db);String s=PhoneContextStore.activeProcessSummary(db,35);
            if(count<=0||s.isEmpty())return new GroundedAnswer(q,"Cortex doesn’t have a current system-process snapshot yet. Standard foreground/recent-app context may still be available; Shizuku access is needed for the deeper running-process snapshot.",.98,new ArrayList<SemanticHit>(),new ArrayList<String>(),new ArrayList<String>());
            return new GroundedAnswer(q,"Latest running-process state ("+count+" visible process names):\n"+s,.98,new ArrayList<SemanticHit>(),new ArrayList<String>(),new ArrayList<String>());
        }catch(Throwable e){return new GroundedAnswer(q,"Cortex couldn’t read the current running-process state right now.",.80,new ArrayList<SemanticHit>(),new ArrayList<String>(),new ArrayList<String>());}
    }

    private static GroundedAnswer phoneContext(VaultDb db,String q){
        try{
            PhoneContextStore.ensure(db);String s=PhoneContextStore.recentSummary(db,6L*60L*60L*1000L,14);
            if(s.isEmpty())return new GroundedAnswer(q,"Cortex doesn’t have recent phone-context events yet. Enable Phone context access for Notification, Accessibility and Usage Access, then use the phone normally.",.98,new ArrayList<SemanticHit>(),new ArrayList<String>(),new ArrayList<String>());
            return new GroundedAnswer(q,"Recent phone context:\n"+s,.98,new ArrayList<SemanticHit>(),new ArrayList<String>(),new ArrayList<String>());
        }catch(Throwable e){return new GroundedAnswer(q,"Cortex couldn’t read the local phone-context timeline right now.",.80,new ArrayList<SemanticHit>(),new ArrayList<String>(),new ArrayList<String>());}
    }

    /**
     * One attention answer everywhere: Ask reads the exact same canonical snapshot as Today,
     * the proactive digest and BriefComposer. The legacy actions table is not an attention source.
     */
    private static GroundedAnswer attention(VaultDb db,String q){
        ContactSafetyMaintenance.run(db);ReviewQueueStore.expireStale(db);PrimeBriefStore.Snapshot s=PrimeBriefStore.load(db);
        LinkedHashSet<String> lines=new LinkedHashSet<>();ArrayList<String> loops=new ArrayList<>();
        addAttention(lines,loops,s.actions,"Action",5);
        addAttention(lines,loops,s.waiting,"Waiting",4);
        addAttention(lines,loops,s.decisions,"Decision",3);
        addAttention(lines,loops,s.changes,"Change",3);
        for(ReviewQueueStore.Item r:s.reviews){String payload=!n(r.body).isEmpty()?r.body:r.title;if(payload==null||payload.trim().isEmpty())continue;String line="Review "+friendly(r.candidateKind)+": "+clip(payload,160);lines.add(line);if(lines.size()>=10)break;}
        String answer;
        if(lines.isEmpty())answer="Nothing currently deserves your attention in Cortex.";
        else{StringBuilder out=new StringBuilder("Here’s what currently needs your attention:\n");int i=0;for(String x:lines){out.append("• ").append(x).append('\n');if(++i>=10)break;}answer=out.toString().trim();}
        return new GroundedAnswer(q,answer,.99,new ArrayList<SemanticHit>(),loops,new ArrayList<String>());
    }

    private static void addAttention(LinkedHashSet<String> lines,ArrayList<String> loops,List<PrimeBriefStore.Item> xs,String label,int limit){
        if(xs==null)return;int added=0;for(PrimeBriefStore.Item x:xs){if(x==null||x.attentionBand==AttentionEngine.Band.QUIET)continue;String payload=!n(x.body).isEmpty()?x.body:x.title;if(n(payload).isEmpty())continue;String line=label+": "+clip(payload,180);if(lines.add(line)){if("Action".equals(label)||"Waiting".equals(label))loops.add(line);if(++added>=limit||lines.size()>=10)break;}}
    }

    private static boolean productNoise(String text){String z=LocalSemanticEmbedder.norm(text);return has(z,"cib","bank","credit card","debit card","card declined","transaction","otp","spotify","suno","google play","subscription","amount due","payment due","balance","خصم","بطاقة","معاملة","رصيد","اشتراك");}

    private static GroundedAnswer waiting(VaultDb db,String q){
        PrimeBriefStore.Snapshot s=PrimeBriefStore.load(db);LinkedHashSet<String> xs=new LinkedHashSet<>();ArrayList<String> loops=new ArrayList<>();
        for(PrimeBriefStore.Item item:s.waiting){String x=!n(item.body).isEmpty()?item.body:item.title;if(!n(x).isEmpty()&&!productNoise(x))xs.add(clip(x,180));if(xs.size()>=12)break;}
        loops.addAll(xs);String answer;
        if(xs.isEmpty())answer="Cortex doesn’t currently have any confirmed Waiting items.";
        else{StringBuilder out=new StringBuilder("You’re currently waiting on:\n");for(String x:xs)out.append("• ").append(x).append('\n');answer=out.toString().trim();}
        return new GroundedAnswer(q,answer,.99,new ArrayList<SemanticHit>(),loops,new ArrayList<String>());
    }

    private static GroundedAnswer decisions(VaultDb db,String q){
        PrimeBriefStore.Snapshot s=PrimeBriefStore.load(db);ArrayList<String> xs=new ArrayList<>();
        for(PrimeBriefStore.Item item:s.decisions){String x=!n(item.body).isEmpty()?item.body:item.title;if(!n(x).isEmpty()&&!productNoise(x))xs.add(clip(x,220));if(xs.size()>=10)break;}
        String answer;if(xs.isEmpty())answer="I don’t have any confirmed recent decisions in the current Cortex decision state.";else{StringBuilder out=new StringBuilder("Recent confirmed decisions:\n");for(String x:xs)out.append("• ").append(x).append('\n');answer=out.toString().trim();}
        return new GroundedAnswer(q,answer,.99,new ArrayList<SemanticHit>(),new ArrayList<String>(),xs);
    }

    private static GroundedAnswer kinds(VaultDb db,String q,String heading,String[] kinds,int limit){
        CognitiveStore.ensure(db);StringBuilder marks=new StringBuilder();for(int i=0;i<kinds.length;i++){if(i>0)marks.append(',');marks.append('?');}
        String sql="SELECT kind,title,body FROM derived_items WHERE state IN ('open','confirmed') AND kind IN ("+marks+") ORDER BY importance DESC,updated_at DESC LIMIT ?";String[] args=new String[kinds.length+1];System.arraycopy(kinds,0,args,0,kinds.length);args[kinds.length]=String.valueOf(limit);LinkedHashSet<String> lines=new LinkedHashSet<>();
        Cursor c=db.getReadableDatabase().rawQuery(sql,args);while(c.moveToNext()){String kind=n(c.getString(0)),body=n(c.getString(2));if(body.isEmpty())body=n(c.getString(1));if(!body.isEmpty())lines.add(friendly(kind)+": "+clip(body,220));}c.close();
        if(lines.isEmpty())return new GroundedAnswer(q,"Cortex doesn’t have any confirmed items for that yet.",.96,new ArrayList<SemanticHit>(),new ArrayList<String>(),new ArrayList<String>());
        StringBuilder out=new StringBuilder(heading).append(":\n");for(String x:lines)out.append("• ").append(x).append('\n');return new GroundedAnswer(q,out.toString().trim(),.97,new ArrayList<SemanticHit>(),new ArrayList<String>(),new ArrayList<String>());
    }

    private static boolean isRunningApps(String q){return has(q,"what apps are running","what is running on my phone","running apps","running processes","background apps","background processes","ايه الابلكيشنات الشغاله","إيه الابلكيشنات الشغالة","ايه التطبيقات الشغاله","إيه التطبيقات الشغالة","ايه شغال على الموبايل","إيه شغال على الموبايل","البرامج اللي شغاله","البرامج اللي شغالة");}
    private static boolean isPhoneContext(String q){return has(q,"what app am i using","what app was i using","what was i doing on my phone","what was i doing","recent apps","last apps","current app","phone context","what did i open","what have i been doing on my phone","كنت فاتح ايه","كنت فاتح إيه","انا فاتح ايه","أنا فاتح إيه","كنت بعمل ايه على الموبايل","كنت بعمل إيه على الموبايل","آخر ابلكيشنات","اخر ابلكيشنات","آخر تطبيقات","اخر تطبيقات","عملت ايه على الموبايل","عملت إيه على الموبايل");}
    private static boolean isAttention(String q){return has(q,"what still needs my attention","what needs my attention","what needs me","what do i need to do","what should i do","needs attention","open loops","open loop","pending actions","follow ups","follow-up","محتاج انتباهي","محتاج مني","محتاج اعمل ايه","محتاج أعمل ايه","ايه اللي محتاجني","إيه اللي محتاجني","ايه اللي محتاج اهتمامي","إيه اللي محتاج اهتمامي","ايه المعلق","إيه المعلق","متابعة");}
    private static boolean isWaiting(String q){return has(q,"what am i waiting for","what am i waiting on","what are we waiting for","waiting on","waiting for","مستني ايه","مستنى ايه","منتظر ايه","في انتظار ايه");}
    private static boolean isRecentDecisions(String q){return has(q,"what did i decide recently","recent decisions","what have i decided","قررت ايه مؤخرا","قررت ايه قريب","ايه القرارات الاخيره","إيه القرارات الأخيرة");}
    private static boolean isGoals(String q){return has(q,"what are my goals","what am i trying to achieve","my active goals","my goals","اهدافي ايه","أهدافي ايه","ايه اهدافي","إيه أهدافي","هدفي ايه","هدفي إيه");}
    private static boolean isIdeas(String q){return has(q,"what ideas do i have","what opportunities do i have","ideas and opportunities","my ideas","my opportunities","افكاري ايه","أفكاري ايه","ايه الفرص","إيه الفرص","ايه الافكار","إيه الأفكار");}
    private static boolean isContextlessProject(String q){return has(q,"this project","المشروع ده","المشروع دا","البروجكت ده","البروجكت دا");}
    private static boolean has(String t,String... xs){for(String x:xs)if(t.contains(LocalSemanticEmbedder.norm(x)))return true;return false;}
    private static String friendly(String x){String k=n(x).toUpperCase(Locale.ROOT);if("GOAL_SIGNAL".equals(k))return"Goal";if("IDEA".equals(k))return"Idea";if("OPPORTUNITY".equals(k))return"Opportunity";if("INSIGHT".equals(k))return"Insight";if("HYPOTHESIS".equals(k))return"Hypothesis";String low=k.toLowerCase(Locale.ROOT).replace('_',' ');return low.isEmpty()?"item":low;}
    private static String clip(String s,int n){String x=s==null?"":s.replaceAll("\\s+"," ").trim();return x.length()<=n?x:x.substring(0,n)+"…";}
    private static String n(String s){return s==null?"":s.trim();}
}
