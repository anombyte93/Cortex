package com.kareem.cortex;

import java.util.ArrayList;

/** Intentional-capture view over Cortex's canonical knowledge_items table. No parallel database. */
public final class InboxStore {
    private InboxStore(){}

    public static ArrayList<KnowledgeItem> recent(VaultDb db,int limit){
        ArrayList<KnowledgeItem> all=db.lexicalSearch("",Math.max(limit*4,120)),out=new ArrayList<>();
        for(KnowledgeItem k:all){if(isIntentional(k)){out.add(k);if(out.size()>=limit)break;}}
        return out;
    }

    public static ArrayList<KnowledgeItem> search(VaultDb db,String query,int limit){
        ArrayList<KnowledgeItem> all=(query==null||query.trim().isEmpty())?db.lexicalSearch("",Math.max(limit*4,120)):db.search(query),out=new ArrayList<>();
        for(KnowledgeItem k:all){if(isIntentional(k)){out.add(k);if(out.size()>=limit)break;}}
        return out;
    }

    public static long addNote(VaultDb db,String text){
        String clean=text==null?"":text.trim();if(clean.isEmpty())return 0;
        String meta="{\"intentional_capture\":true,\"capture_surface\":\"inbox_composer\"}";
        long id=db.insert("TEXT","inbox_composer",AutoClassifier.title(clean,"text/plain"),clean,AutoClassifier.category(clean,"text/plain"),AutoClassifier.tags(clean,AutoClassifier.category(clean,"text/plain"))+",intentional,inbox","",Fingerprint.text("inbox|"+clean),meta);
        return id<0?-id:id;
    }

    public static boolean isIntentional(KnowledgeItem k){
        if(k==null)return false;String s=k.source==null?"":k.source,t=k.tags==null?"":k.tags,m=k.metadataJson==null?"":k.metadataJson;
        return "android_share".equals(s)||"audio_import".equals(s)||"inbox_composer".equals(s)||s.contains("capture")||t.contains("intentional")||m.contains("\"intentional_capture\":true");
    }

    public static String processingState(KnowledgeItem k){
        if(k==null)return"unknown";String m=k.metadataJson==null?"":k.metadataJson;
        if(m.contains("\"link_content_state\":\"understood\""))return"understood";
        if(m.contains("\"link_content_state\":\"fetch_failed\""))return"fetch_failed";
        if(m.contains("\"link_content_state\":\"pending_content\""))return"pending_content";
        return k.status==null||k.status.isEmpty()?"saved":k.status;
    }
}
