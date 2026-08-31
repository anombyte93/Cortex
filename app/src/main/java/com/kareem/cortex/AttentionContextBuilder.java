package com.kareem.cortex;

import android.content.Context;
import android.database.Cursor;
import java.util.*;

/** Builds compact context only when every grounded source is explicitly cloud-eligible. */
public final class AttentionContextBuilder {
    private AttentionContextBuilder(){}
    public static final class Pack {public final String text;public final int evidenceCount;Pack(String t,int n){text=t;evidenceCount=n;}public boolean usable(){return evidenceCount>0&&!text.trim().isEmpty();}}

    public static Pack build(Context context,VaultDb db,PrimeBriefStore.Item item){
        if(context==null||db==null||item==null||item.id<=0)return new Pack("",0);CognitiveStore.ensure(db);LinkedHashMap<Long,KnowledgeItem> evidence=new LinkedHashMap<>();int linked=0;
        Cursor links=db.getReadableDatabase().query("source_links",new String[]{"from_id"},"from_type='memory' AND to_type='derived' AND to_id=?",new String[]{String.valueOf(item.id)},null,null,"created_at DESC","8");
        while(links.moveToNext()){linked++;KnowledgeItem k=db.getById(links.getLong(0));if(k!=null&&CloudEvidencePolicy.canSend(context,k))evidence.put(k.id,k);}links.close();
        // Mixed or unknown provenance stays local-only. Do not send the derived candidate text
        // unless every grounded source that produced it passed the explicit cloud allow-list.
        if(linked<=0||evidence.size()!=linked)return new Pack("",0);

        StringBuilder out=new StringBuilder();out.append("CANDIDATE\nKind: ").append(item.kind).append("\nTitle: ").append(clip(item.title,180)).append("\nBody: ").append(clip(item.body,700)).append("\nImportance: ").append(item.importance).append("\nClassifier confidence: ").append(String.format(Locale.US,"%.2f",item.confidence)).append("\n\nGROUNDED EVIDENCE\n");
        int n=0;for(KnowledgeItem k:evidence.values()){String body=!empty(k.summary)?k.summary:(!empty(k.extractedText)?k.extractedText:k.rawText);out.append("[E").append(++n).append("] ").append(clip(k.title,120)).append("\n").append(clip(body,550)).append("\n");if(n>=5)break;}
        return new Pack(out.toString().trim(),n);
    }

    private static boolean empty(String s){return s==null||s.trim().isEmpty();}
    private static String clip(String s,int n){String x=s==null?"":s.replaceAll("\\s+"," ").trim();return x.length()<=n?x:x.substring(0,n)+"…";}
}
