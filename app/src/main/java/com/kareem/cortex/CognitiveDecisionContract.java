package com.kareem.cortex;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.*;

/** Transport-independent ChatGPT/Cortex cognitive adjudication contract. */
public final class CognitiveDecisionContract {
    public static final int VERSION=5;
    private CognitiveDecisionContract(){}

    public static final Set<String> ALLOWED=Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(
            "IGNORE","STORE","LINK","UPDATE_SITUATION","SURFACE_NOW","WATCH","ASK_USER","PROPOSE_ACTION")));

    public static String teacherPrompt(JSONObject packet){
        return "CORTEX_COGNITIVE_ADJUDICATION_V5\n"
                +"You are the cognitive adjudicator for Cortex. Interpret the supplied local evidence as a coherent world-state. Decide what is useful to Kareem, what evidence belongs together, what changed, what is stale/resolved, what deserves attention now, and what next action should only be proposed. Return ONLY valid JSON. Never invent private facts. Use only E*/S* references present in the packet. PROPOSE_ACTION never means execute.\n"
                +"Output schema: {\"schema_version\":5,\"summary\":\"...\",\"decisions\":[{\"type\":\"IGNORE|STORE|LINK|UPDATE_SITUATION|SURFACE_NOW|WATCH|ASK_USER|PROPOSE_ACTION\",\"target_ref\":\"S1 or empty\",\"evidence_refs\":[\"E1\"],\"confidence\":0.0,\"reason\":\"...\",\"proposed_state\":{},\"next_action\":null}]}\n\nCOGNITIVE_PACKET:\n"
                +(packet==null?"{}":packet.toString());
    }

    public static Validation validate(String raw,Set<String> validRefs){
        ArrayList<String> errors=new ArrayList<>();JSONObject root=parse(raw);if(root==null){errors.add("invalid_json");return new Validation(null,errors);}
        if(root.optInt("schema_version",-1)!=VERSION)errors.add("wrong_schema_version");
        JSONArray ds=root.optJSONArray("decisions");if(ds==null){errors.add("missing_decisions");return new Validation(root,errors);}
        for(int i=0;i<ds.length();i++){
            JSONObject d=ds.optJSONObject(i);if(d==null){errors.add("decision_"+i+"_not_object");continue;}
            String type=n(d.optString("type")).toUpperCase(Locale.ROOT);if(!ALLOWED.contains(type))errors.add("decision_"+i+"_bad_type");
            checkRef(errors,"decision_"+i+"_target",d.optString("target_ref"),validRefs,true);
            JSONArray refs=d.optJSONArray("evidence_refs");if(refs!=null)for(int j=0;j<refs.length();j++)checkRef(errors,"decision_"+i+"_evidence_"+j,refs.optString(j),validRefs,false);
            double c=d.optDouble("confidence",-1);if(c<0||c>1)errors.add("decision_"+i+"_bad_confidence");
            if(n(d.optString("reason")).isEmpty())errors.add("decision_"+i+"_missing_reason");
        }
        return new Validation(root,errors);
    }

    private static void checkRef(List<String> e,String label,String ref,Set<String> valid,boolean emptyOk){String r=n(ref);if(r.isEmpty()&&emptyOk)return;if(r.isEmpty()||valid==null||!valid.contains(r))e.add(label+"_unknown_ref");}
    private static JSONObject parse(String raw){String x=n(raw);if(x.startsWith("```")){int nl=x.indexOf('\n');if(nl>=0)x=x.substring(nl+1);int z=x.lastIndexOf("```");if(z>=0)x=x.substring(0,z);}int a=x.indexOf('{'),b=x.lastIndexOf('}');if(a<0||b<=a)return null;try{return new JSONObject(x.substring(a,b+1));}catch(Exception ignored){return null;}}
    private static String n(String s){return s==null?"":s.trim();}

    public static final class Validation{
        public final JSONObject value;public final List<String> errors;
        Validation(JSONObject value,List<String> errors){this.value=value;this.errors=Collections.unmodifiableList(errors);}
        public boolean valid(){return value!=null&&errors.isEmpty();}
    }
}
