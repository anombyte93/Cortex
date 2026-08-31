package com.kareem.cortex;

import android.app.Notification;
import android.os.Bundle;
import java.util.Locale;

/**
 * Normalizes communication-shaped evidence before it reaches person/situation reasoning.
 * Inspired by Mnemo/Bridge capture heuristics, but emits hints only; Cortex remains truth authority.
 */
public final class CommunicationEvidenceNormalizer {
    private CommunicationEvidenceNormalizer(){}

    public static final class Result {
        public final String kind, source, personHint, title, body;
        public final boolean communication;
        Result(String kind,String source,String personHint,String title,String body,boolean communication){
            this.kind=kind;this.source=source;this.personHint=personHint;this.title=title;this.body=body;this.communication=communication;
        }
    }

    public static Result fromNotification(String pkg, Notification n, Bundle extras){
        String title=clean(extras==null?null:extras.getCharSequence(Notification.EXTRA_TITLE));
        String text=clean(extras==null?null:extras.getCharSequence(Notification.EXTRA_TEXT));
        String big=clean(extras==null?null:extras.getCharSequence(Notification.EXTRA_BIG_TEXT));
        if(!big.isEmpty())text=big;
        String latest=latestMessage(extras);if(!latest.isEmpty())text=latest;
        String source=sourceForPackage(pkg),kind=kindFor(pkg,n,extras,title,text);
        String person=("message".equals(kind)||"email".equals(kind)||"call".equals(kind))?personHint(title,text):"";
        return new Result(kind,source,person,title,text,!"notification".equals(kind));
    }

    public static String sourceForPackage(String pkg){
        String p=clean(pkg).toLowerCase(Locale.US);
        if("com.whatsapp".equals(p))return"whatsapp";
        if(p.contains("facebook.orca"))return"messenger";
        if(p.contains("instagram"))return"instagram";
        if(p.contains("telegram"))return"telegram";
        if(p.contains("snapchat"))return"snapchat";
        if("com.google.android.gm".equals(p))return"gmail";
        if(p.contains("outlook"))return"outlook";
        return p.isEmpty()?"notification":p;
    }

    private static String kindFor(String pkg,Notification n,Bundle e,String title,String body){
        String p=clean(pkg).toLowerCase(Locale.US),all=(title+" "+body).toLowerCase(Locale.US);
        if(n!=null&&Notification.CATEGORY_CALL.equals(n.category))return"call";
        if(n!=null&&Notification.CATEGORY_EMAIL.equals(n.category))return"email";
        if(e!=null&&e.getParcelableArray(Notification.EXTRA_MESSAGES)!=null)return"message";
        if("com.google.android.gm".equals(p)||p.contains("outlook"))return"email";
        if("com.whatsapp".equals(p)||p.contains("facebook.orca")||p.contains("telegram")||p.contains("snapchat"))return"message";
        if(p.contains("instagram")&&(all.contains("message")||all.contains("sent you")||all.contains("رسالة")))return"message";
        if(all.contains("missed call")||all.contains("incoming call")||all.contains("مكالمة فائتة"))return"call";
        return"notification";
    }

    private static String personHint(String title,String body){
        String t=clean(title);
        if(!t.isEmpty()&&!looksGeneric(t))return trim(t,120);
        String b=clean(body);
        int sep=b.indexOf(':');
        if(sep>0&&sep<80)return trim(b.substring(0,sep),120);
        return"";
    }

    private static boolean looksGeneric(String s){String x=s.toLowerCase(Locale.US);return x.equals("whatsapp")||x.equals("messenger")||x.equals("instagram")||x.equals("telegram")||x.equals("gmail")||x.equals("outlook")||x.equals("missed call");}

    private static String latestMessage(Bundle e){
        if(e==null)return"";try{android.os.Parcelable[] a=e.getParcelableArray(Notification.EXTRA_MESSAGES);if(a==null)return"";for(int i=a.length-1;i>=0;i--){if(!(a[i] instanceof Bundle))continue;Bundle b=(Bundle)a[i];String text=clean(b.getCharSequence("text")),sender=clean(b.getCharSequence("sender"));if(!text.isEmpty())return sender.isEmpty()?text:sender+": "+text;}}catch(Throwable ignored){}return"";
    }
    private static String clean(CharSequence s){return s==null?"":s.toString().replace('\n',' ').replace('\r',' ').replaceAll("\\s+"," ").trim();}
    private static String trim(String s,int n){return s.length()<=n?s:s.substring(0,n);}
}
