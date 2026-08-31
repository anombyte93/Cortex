package com.kareem.cortex;

import android.content.Context;
import android.provider.Settings;
import java.util.ArrayList;

/** Lightweight observable health for donor-derived capture surfaces. */
public final class SourceHealthSnapshot {
    private SourceHealthSnapshot(){}

    public static final class Row {public final String name,state,detail;Row(String n,String s,String d){name=n;state=s;detail=d;}}

    public static ArrayList<Row> collect(Context c,VaultDb db){
        ArrayList<Row> out=new ArrayList<>();
        out.add(new Row("Notifications",notificationListenerEnabled(c)?"active":"needs_attention",notificationListenerEnabled(c)?"listener enabled":"enable Cortex notification access"));
        out.add(new Row("Analysis queue",db.pendingCount()>0?"working":"ready",db.pendingCount()+" pending · "+db.failedCount()+" failed/retryable"));
        int intentional=InboxStore.recent(db,500).size();out.add(new Row("Intentional Inbox",intentional>0?"active":"ready",intentional+" captured items"));
        int pendingLinks=0,failedLinks=0;for(KnowledgeItem k:InboxStore.recent(db,500)){String s=InboxStore.processingState(k);if("pending_content".equals(s))pendingLinks++;else if("fetch_failed".equals(s))failedLinks++;}
        out.add(new Row("Link enrichment",failedLinks>0?"degraded":pendingLinks>0?"working":"ready",pendingLinks+" pending · "+failedLinks+" failed"));
        return out;
    }

    private static boolean notificationListenerEnabled(Context c){try{String v=Settings.Secure.getString(c.getContentResolver(),"enabled_notification_listeners");return v!=null&&v.contains(c.getPackageName());}catch(Throwable e){return false;}}
}
