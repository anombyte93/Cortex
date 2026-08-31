package com.kareem.cortex;

import android.database.Cursor;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/** Builds bounded Cognitive V2 input from canonical raw signal/thread evidence only. */
public final class CognitiveInputFactory {
    private CognitiveInputFactory(){}

    public static CognitiveInput load(VaultDb db,long signalId){
        if(db==null||signalId<=0)return null;
        CognitiveStore.ensure(db);
        Cursor c=db.getReadableDatabase().query(
                "raw_signals",
                new String[]{"kind","source","title","body","metadata_json","occurred_at","thread_id","disposition"},
                "id=?",new String[]{String.valueOf(signalId)},null,null,null,"1");
        if(!c.moveToFirst()){c.close();return null;}
        String kind=n(c.getString(0)),source=n(c.getString(1)),title=n(c.getString(2)),body=n(c.getString(3)),metadata=n(c.getString(4));
        long occurredAt=c.getLong(5),threadId=c.getLong(6);String baseline=n(c.getString(7));c.close();

        String latestText=cleanNotificationBody(title,body);if(latestText.isEmpty())latestText=title;
        String sender=senderFor(title,metadata);
        SignalFamily family=SignalFamilyClassifier.classify(kind,source,title,body);
        List<CognitiveMessage> context=threadId>0?loadThreadContext(db,threadId,signalId):Collections.emptyList();
        return new CognitiveInput(signalId,family,source,friendlyApp(source),sender,latestText,context,occurredAt,TimeZone.getDefault().getID(),baseline);
    }

    private static List<CognitiveMessage> loadThreadContext(VaultDb db,long threadId,long latestSignalId){
        Cursor c=db.getReadableDatabase().query(
                "raw_signals",
                new String[]{"id","title","body","metadata_json","occurred_at"},
                "thread_id=? AND id<=?",new String[]{String.valueOf(threadId),String.valueOf(latestSignalId)},
                null,null,"occurred_at DESC,id DESC","5");
        ArrayList<CognitiveMessage> out=new ArrayList<>();
        while(c.moveToNext()){
            String title=n(c.getString(1)),body=n(c.getString(2)),metadata=n(c.getString(3));long occurred=c.getLong(4);
            String text=cleanNotificationBody(title,body);if(text.isEmpty())text=title;
            boolean sensitive=SensitiveSignalPolicy.containsSecret(title+" "+body);
            out.add(new CognitiveMessage(direction(metadata),senderFor(title,metadata),text,occurred,sensitive));
        }
        c.close();Collections.reverse(out);return out;
    }

    private static String senderFor(String title,String metadata){
        try{
            JSONObject o=new JSONObject(n(metadata));
            String sender=n(o.optString("sender",""));if(!sender.isEmpty())return sender;
            String personHint=n(o.optString("person_hint",""));if(!personHint.isEmpty())return personHint;
        }catch(Throwable ignored){}
        return n(title);
    }

    private static String direction(String metadata){
        try{
            JSONObject o=new JSONObject(n(metadata));String d=n(o.optString("direction","")).toLowerCase(Locale.ROOT);
            if(d.contains("out")||d.contains("sent")||d.contains("self"))return "SENT_BY_SELF";
        }catch(Throwable ignored){}
        return "RECEIVED_FROM_OTHER";
    }

    private static String cleanNotificationBody(String title,String body){
        String h=n(title),b=n(body);if(!h.isEmpty()){String prefix=h+"\n";if(b.startsWith(prefix))b=b.substring(prefix.length()).trim();else if(b.equals(h))b="";}return b;
    }

    private static String friendlyApp(String source){if(source==null)return"";int index=source.lastIndexOf('.');return index>=0&&index+1<source.length()?source.substring(index+1):source;}
    private static String n(String s){return s==null?"":s.trim();}
}
