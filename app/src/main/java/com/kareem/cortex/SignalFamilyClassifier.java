package com.kareem.cortex;

import java.util.Locale;

/** Cheap deterministic context hint for Cognitive V2. Never decides user obligations. */
public final class SignalFamilyClassifier {
    private SignalFamilyClassifier(){}

    public static SignalFamily classify(String kind,String source,String title,String body){
        String k=n(kind),s=n(source),text=n(title)+" "+n(body);
        String x=(k+" "+s+" "+text).toLowerCase(Locale.ROOT);
        if(containsAny(x,"calendar","appointment","meeting","reminder","event"))return SignalFamily.EVENT;
        if(containsAny(x,"voice message","voice note","reel","shared a reel","sent you a reel","document","attachment","photo","video"))return SignalFamily.CONTENT;
        if(containsAny(x,"whatsapp","telegram","messenger","messages","sms","gmail","email","mail"))return SignalFamily.COMMUNICATION;
        if(containsAny(x,"instagram","facebook","tiktok","snapchat"))return SignalFamily.SOCIAL;
        if(containsAny(x,"delivery","package","shipment","arriving","delivered"))return SignalFamily.DELIVERY;
        if(containsAny(x,"payment","transaction","purchase","card","bank"))return SignalFamily.TRANSACTION;
        if(containsAny(x,"otp","verification code","security alert","login"))return SignalFamily.SECURITY;
        if(containsAny(x,"android system","battery","charging","now playing","background service"))return SignalFamily.SYSTEM;
        return SignalFamily.UNKNOWN;
    }

    private static boolean containsAny(String value,String... candidates){for(String candidate:candidates)if(value.contains(candidate))return true;return false;}
    private static String n(String s){return s==null?"":s.trim();}
}
