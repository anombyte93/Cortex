package com.kareem.cortex;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.*;

/**
 * Proactive delivery over the same canonical attention read model used by Today.
 * This class never derives a second open-loop truth from the legacy actions table.
 */
public final class ProactiveEngine {
    private static final long DAY=86400000L;
    private ProactiveEngine(){}

    public static ArrayList<ProactiveSignal> scan(Context ctx,VaultDb db,int limit){
        ContactSafetyMaintenance.run(db);
        PrimeBriefStore.Snapshot s=PrimeBriefStore.load(db);
        SharedPreferences sp=ctx.getSharedPreferences("proactive",Context.MODE_PRIVATE);
        long now=System.currentTimeMillis();ArrayList<ProactiveSignal> out=new ArrayList<>();
        add(out,sp,now,s.actions,"OPEN_LOOP");
        add(out,sp,now,s.waiting,"WAITING");
        add(out,sp,now,s.decisions,"DECISION");
        add(out,sp,now,s.changes,"CHANGE");
        out.sort((a,b)->Double.compare(b.priority,a.priority));
        LinkedHashMap<String,ProactiveSignal> uniq=new LinkedHashMap<>();for(ProactiveSignal x:out){String key=x.kind+"|"+x.itemId+"|"+LocalSemanticEmbedder.norm(x.title);if(!uniq.containsKey(key))uniq.put(key,x);}
        ArrayList<ProactiveSignal> clean=new ArrayList<>(uniq.values());if(clean.size()>limit)return new ArrayList<>(clean.subList(0,limit));return clean;
    }

    private static void add(ArrayList<ProactiveSignal> out,SharedPreferences sp,long now,List<PrimeBriefStore.Item> xs,String kind){
        if(xs==null)return;for(PrimeBriefStore.Item x:xs){if(x==null||x.id<=0||x.attentionBand==AttentionEngine.Band.QUIET)continue;long last=sp.getLong("surfaced_derived_"+x.id,0),cooldown=cooldown(x.attentionBand);if(last>0&&now-last<cooldown)continue;String title=clean(x.title);if(title.isEmpty())title=fallback(x.attentionKind);String body=clean(x.body);String reason=clean(x.whyNow);double p=Math.max(0,Math.min(1,x.attentionScore/100.0));out.add(new ProactiveSignal(kind,title,clip(body,220),reason,x.id,p));}
    }

    public static void markSurfaced(Context ctx,Collection<ProactiveSignal> xs){SharedPreferences.Editor e=ctx.getSharedPreferences("proactive",Context.MODE_PRIVATE).edit();long now=System.currentTimeMillis();for(ProactiveSignal s:xs)if(s.itemId>0)e.putLong("surfaced_derived_"+s.itemId,now);e.apply();}

    private static long cooldown(AttentionEngine.Band b){if(b==AttentionEngine.Band.NOW)return DAY;if(b==AttentionEngine.Band.LATER)return 3*DAY;return 7*DAY;}
    private static String fallback(String kind){String x=clean(kind).toLowerCase(Locale.ROOT).replace('_',' ');return x.isEmpty()?"Cortex attention item":Character.toUpperCase(x.charAt(0))+x.substring(1);}
    private static String clip(String s,int n){String x=clean(s).replaceAll("\\s+"," ");return x.length()<=n?x:x.substring(0,n)+"…";}
    private static String clean(String s){return s==null?"":s.trim();}
}
