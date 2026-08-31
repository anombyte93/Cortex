package com.kareem.cortex;

import org.json.JSONArray;
import org.json.JSONObject;

/** Produces privacy-bounded old-vs-V2 telemetry. No raw notification body is copied here. */
public final class CognitiveShadowComparator {
    private CognitiveShadowComparator(){}

    public static JSONObject compare(long signalId,LegacyCognitiveSnapshot legacy,CognitiveResult v2){
        JSONObject o=new JSONObject();
        try{
            o.put("schema","cognitive_shadow_001");o.put("signal_id",signalId);
            JSONObject old=new JSONObject();old.put("disposition",legacy==null?"":legacy.disposition);old.put("candidate_kind",legacy==null?"":legacy.candidateKind);old.put("confidence",legacy==null?0:legacy.confidence);old.put("engine",legacy==null?"":legacy.engine);o.put("legacy",old);
            JSONObject newer=new JSONObject();newer.put("disposition",v2.disposition.name());newer.put("confidence",v2.confidence);newer.put("reason",clip(v2.reason,300));JSONArray items=new JSONArray();
            for(CognitiveItem item:v2.items){JSONObject x=new JSONObject();x.put("kind",item.kind.name());x.put("summary",clip(item.summary,240));x.put("importance",item.importance);x.put("urgency",item.urgency);x.put("requires_user_action",item.requiresUserAction);x.put("requires_follow_up",item.requiresFollowUp);x.put("requires_content_extraction",item.requiresContentExtraction);items.put(x);}newer.put("items",items);o.put("v2",newer);
            o.put("comparison",comparison(legacy==null?new LegacyCognitiveSnapshot("","",0,""):legacy,v2));
        }catch(Throwable ignored){}
        return o;
    }

    static String comparison(LegacyCognitiveSnapshot legacy,CognitiveResult v2){
        String old=upper(legacy.disposition);
        if(isLegacyDerived(old)&&v2.disposition==CognitiveDisposition.DERIVE){
            String expected=legacyDerivedKind(legacy);
            return !expected.isEmpty()&&hasKind(v2,expected)?"BOTH_DERIVE":"DERIVED_KIND_DISAGREEMENT";
        }
        if("CONTEXT".equals(old)&&v2.disposition==CognitiveDisposition.DERIVE)return "V2_FOUND_MISSED_VALUE";
        if("IGNORE".equals(old)&&v2.disposition!=CognitiveDisposition.IGNORE)return "IGNORE_DISAGREEMENT";
        if(isLegacyDerived(old)&&(v2.disposition==CognitiveDisposition.CONTEXT||v2.disposition==CognitiveDisposition.IGNORE))return "V2_DOWNGRADE";
        if("CONTEXT".equals(old)&&v2.disposition==CognitiveDisposition.CONTEXT)return "BOTH_CONTEXT";
        if("IGNORE".equals(old)&&v2.disposition==CognitiveDisposition.IGNORE)return "BOTH_IGNORE";
        return "DIFFERENT";
    }

    private static String legacyDerivedKind(LegacyCognitiveSnapshot legacy){
        String candidate=upper(legacy.candidateKind);if(isLegacyDerived(candidate))return candidate;
        String disposition=upper(legacy.disposition);return isLegacyDerived(disposition)?disposition:"";
    }

    private static boolean hasKind(CognitiveResult result,String kind){
        if(result==null||kind==null||kind.isEmpty())return false;
        for(CognitiveItem item:result.items)if(item!=null&&item.kind!=null&&kind.equals(item.kind.name()))return true;
        return false;
    }

    private static boolean isLegacyDerived(String x){return"ACTION".equals(x)||"WAITING".equals(x)||"DECISION".equals(x);}
    private static String upper(String s){return s==null?"":s.trim().toUpperCase(java.util.Locale.ROOT);}
    private static String clip(String s,int max){String x=s==null?"":s.trim();return x.length()<=max?x:x.substring(0,max);}
}
