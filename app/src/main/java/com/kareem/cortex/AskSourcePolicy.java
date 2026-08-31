package com.kareem.cortex;

import java.util.Locale;

/** Filters evidence that is technically searchable but cognitively unsafe/noisy for Ask. */
public final class AskSourcePolicy {
    private AskSourcePolicy(){}

    public static boolean allowSemantic(KnowledgeItem item,String query){
        if(item==null)return false;
        if(isSelfUiScreenshot(item)&&!queryExplicitlyAboutCortexUi(query))return false;
        if(isLowSignalSystemContext(item)&&queryNeedsCognition(query))return false;
        if(isOcrGarbage(item)&&!queryExplicitlyAboutArtifactText(query))return false;
        return true;
    }

    public static boolean isSelfUiScreenshot(KnowledgeItem k){
        if(k==null||!("SCREENSHOT".equals(k.type)||"IMAGE".equals(k.type)))return false;
        String t=(n(k.title)+"\n"+n(k.extractedText)+"\n"+n(k.summary)).toLowerCase(Locale.ROOT);if(!t.contains("cortex"))return false;int markers=0;
        if(t.contains("ask cortex"))markers++;if(t.contains("try asking"))markers++;if(t.contains("home"))markers++;if(t.contains("focus"))markers++;if(t.contains("vault"))markers++;if(t.contains("settings"))markers++;if(t.contains("local qwen"))markers++;if(t.contains("needs you")||t.contains("needs attention"))markers++;if(t.contains("capture")&&t.contains("ask"))markers++;return markers>=2;
    }

    private static boolean isLowSignalSystemContext(KnowledgeItem k){
        String s=LocalSemanticEmbedder.norm(n(k.title)+" "+n(k.summary)+" "+n(k.extractedText)+" "+n(k.rawText));
        if("CONTACT".equals(k.type)&&"contacts_sync".equals(k.source))return true;
        return has(s,"screenshot saved","response ready","download complete","battery","charging","play store","installing","package installer","system ui","systemui")&&!has(s,"declined","payment","sign-in","sign in","authenticator","missed call","appointment","reminder");
    }

    private static boolean isOcrGarbage(KnowledgeItem k){
        if(!("SCREENSHOT".equals(k.type)||"IMAGE".equals(k.type)))return false;String x=n(k.extractedText);if(x.length()<30)return false;int letters=0,weird=0;for(int i=0;i<x.length();i++){char c=x.charAt(i);if(Character.isLetterOrDigit(c))letters++;else if(!Character.isWhitespace(c)&&".,:;!?؟-_/@#%()+[]{}'\"".indexOf(c)<0)weird++;}double ratio=letters/(double)Math.max(1,x.length());return ratio<.38||weird>x.length()*.18;
    }

    private static boolean queryNeedsCognition(String query){String q=LocalSemanticEmbedder.norm(n(query));return has(q,"attention","needs my attention","what matters","ongoing","situation","episode","open","project","deadline","appointment","reminder","action","decision","noise","context","محتاج","مهم","المواقف","المشاريع","المواعيد","قرار");}
    private static boolean queryExplicitlyAboutArtifactText(String query){String q=LocalSemanticEmbedder.norm(n(query));return has(q,"ocr","screenshot text","read screenshot","what does this screenshot say","extract text","نص الصورة","اقرأ الصورة","سكرين شوت");}
    private static boolean queryExplicitlyAboutCortexUi(String query){String q=LocalSemanticEmbedder.norm(n(query));return has(q,"cortex","ask cortex","cortex ui","interface","screen","screenshot","vault","focus","واجهة","شاشة","سكرين شوت","كورتكس");}
    private static boolean has(String t,String...xs){for(String x:xs)if(t.contains(LocalSemanticEmbedder.norm(x)))return true;return false;}
    private static String n(String s){return s==null?"":s;}
}
