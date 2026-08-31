package com.kareem.cortex;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.*;
import java.util.regex.*;

/** Deterministic evidence-grounded cognitive adjudicator for the V5 shared packet. */
public final class CognitiveStudentReasoner {
    private CognitiveStudentReasoner(){}

    public static JSONObject decide(JSONObject packet){
        try{
            JSONObject out=new JSONObject().put("schema_version",CognitiveDecisionContract.VERSION);
            JSONArray decisions=new JSONArray();JSONObject state=packet==null?null:packet.optJSONObject("current_state");
            List<JSONObject> evidence=objects(packet==null?null:packet.optJSONArray("new_evidence"));
            long now=packet==null?System.currentTimeMillis():packet.optLong("generated_at",System.currentTimeMillis());

            emitEvidenceOnlyEvents(decisions,evidence,now);

            List<JSONObject> situations=collectSituations(state);boolean[] used=new boolean[situations.size()];
            for(int i=0;i<situations.size();i++){
                if(used[i])continue;JSONObject base=situations.get(i);List<JSONObject> group=new ArrayList<>();group.add(base);used[i]=true;
                for(int j=i+1;j<situations.size();j++)if(!used[j]&&sameSituation(base,situations.get(j))){group.add(situations.get(j));used[j]=true;}
                adjudicateGroup(decisions,group,evidence,now);
            }

            if(decisions.length()==0&&!evidence.isEmpty()){
                JSONObject e=evidence.get(0);decisions.put(decision("STORE","",new JSONArray().put(e.optString("ref")),.45,
                        "Recent evidence exists but no reliable current situation can yet be formed from it.",new JSONObject().put("state","unreconciled"),null));
            }
            out.put("summary",summary(decisions));out.put("decisions",decisions);return out;
        }catch(Exception e){throw new IllegalStateException("Cannot adjudicate Cortex cognitive packet",e);}
    }

    private static void emitEvidenceOnlyEvents(JSONArray out,List<JSONObject> evidence,long now)throws Exception{
        HashSet<String> consumed=new HashSet<>();
        for(JSONObject e:evidence){
            if(isSecurityApproval(e)){
                JSONArray refs=relatedRefs(e,evidence,"security");for(int i=0;i<refs.length();i++)consumed.add(refs.optString(i));
                out.put(decision("ASK_USER","",refs,.95,"A recent sign-in approval request is security-sensitive and requires identity verification before any action.",new JSONObject().put("kind","SECURITY_APPROVAL").put("state","pending_verification"),"Confirm whether you initiated this sign-in. Approve only if it was you; otherwise deny and investigate."));
            }
        }
        for(JSONObject e:evidence){
            if(consumed.contains(e.optString("ref")))continue;String z=norm(oneEvidenceText(e));
            if(isFinancial(z)&&isFailureOrLimit(z)){
                JSONArray refs=relatedRefs(e,evidence,"financial");
                out.put(decision("SURFACE_NOW","",refs,.84,"Fresh financial evidence shows a failed payment, insufficient funds, or an over-limit state that may require remediation.",financialState(refs,evidence),"Show the affected account/card, the latest known amount/status, and the smallest concrete remediation step."));
            }else if(isExplicitReminderRequest(z)){
                JSONArray refs=new JSONArray().put(e.optString("ref"));long target=TemporalResolver.resolveForAttention(oneEvidenceText(e),e.optLong("occurred_at",now));
                out.put(decision("PROPOSE_ACTION","",refs,.91,"The evidence contains an explicit reminder request with a grounded time expression.",new JSONObject().put("kind","REMINDER").put("target_at",target),"Create the requested reminder using the stated reminder time and event context."));
            }
        }
    }

    private static void adjudicateGroup(JSONArray out,List<JSONObject> group,List<JSONObject> evidence,long now)throws Exception{
        JSONObject rep=representative(group);String target=rep.optString("ref");List<JSONObject> bound=bindEvidence(group,evidence);JSONArray refs=refs(bound);
        String situationText=groupText(group),all=situationText+" "+evidenceText(bound),lower=norm(all);
        JSONObject resolution=newestResolution(bound);boolean resolved=resolution!=null;
        boolean duplicate=group.size()>1,financial=isFinancial(lower),medical=isMedical(lower),waiting=containsKind(group,"WAITING"),decisionKind=containsKind(group,"DECISION");
        long anchor=rep.optLong("created_at",0);if(anchor<=0)anchor=rep.optLong("updated_at",now);
        long targetAt=TemporalResolver.resolveForAttention(all,anchor);
        boolean future=targetAt>now+6L*3600000L,near=targetAt>0&&targetAt<=now+6L*3600000L,unknownTime=targetAt<=0;
        boolean preparationNow=needsPreparationNow(lower);

        JSONObject proposed=new JSONObject().put("kind",rep.optString("kind")).put("state",resolved?"resolved_or_superseded":rep.optString("state")).put("merged_refs",refsOfSituations(group)).put("evidence_bound",refs.length());

        if(resolved){
            out.put(decision("UPDATE_SITUATION",target,new JSONArray().put(resolution.optString("ref")),.95,"Newer supporting evidence explicitly indicates that this previously open situation completed, was set up, resolved, or was superseded.",proposed,"Mark the prior obligation resolved/superseded and retain the completion evidence as current truth."));return;
        }

        if(duplicate)out.put(decision("LINK",target,refs,.90,"Multiple live rows describe the same thread or strongly overlapping situation and should be represented as one evolving state.",proposed,"Merge these rows into one canonical situation while preserving event history."));

        if(refs.length()==0){
            out.put(decision("STORE",target,refs,.40,"The derived situation is not sufficiently supported by semantically related evidence in this packet, so Cortex should not interrupt the user based on classification alone.",proposed,"Re-bind the situation to supporting evidence before surfacing or asking the user."));return;
        }

        if(financial){
            JSONObject delta=financialDelta(bound);for(String k:jsonKeys(delta))proposed.put(k,delta.opt(k));proposed.put("domain","financial");
            out.put(decision("SURFACE_NOW",target,refs,.90,"The same financial situation has fresh supporting evidence and remains unresolved; the latest state should replace older amounts/status rather than creating duplicate alerts.",proposed,"Show one consolidated financial situation using the newest status/amount and a single remediation step."));return;
        }

        if(medical){
            proposed.put("domain","medical").put("target_at",targetAt);
            if(future&&!preparationNow){out.put(decision("WATCH",target,refs,.88,"This medical follow-up is grounded but clearly future-dated, so it should remain scheduled instead of being promoted to immediate attention.",proposed,"Keep it scheduled and surface it when the preparation/action window approaches."));}
            else if(near||preparationNow){out.put(decision("SURFACE_NOW",target,refs,.89,"This medical follow-up is due soon or has a preparation step that is useful now.",proposed,"Surface the exact next preparation/follow-up step, then resolve the situation after completion."));}
            else if(unknownTime){out.put(decision("WATCH",target,refs,.68,"The medical situation is real, but its time cannot be grounded reliably enough for a NOW decision.",proposed,"Verify or normalize the appointment/follow-up time before promoting it to immediate attention."));}
            return;
        }

        if(waiting){out.put(decision("WATCH",target,refs,.82,"The situation is supported by evidence but depends on an external change rather than a user action right now.",proposed,"Check whether the dependency changed and follow up only if it remains unresolved."));}
        else if(decisionKind){out.put(decision("ASK_USER",target,refs,.80,"A genuinely unresolved decision remains after evidence and lifecycle reconciliation.",proposed,"Ask the smallest question needed to resolve the decision, then update its lifecycle state."));}
        else{out.put(decision("STORE",target,refs,.68,"The situation is grounded but does not currently justify interrupting the user.",proposed,"Keep it in context and re-evaluate when new evidence changes its state."));}
    }

    private static List<JSONObject> bindEvidence(List<JSONObject> group,List<JSONObject> evidence){
        ArrayList<Scored> scored=new ArrayList<>();String gt=canonical(groupText(group));Set<Long> threads=new HashSet<>(),anchors=new HashSet<>();
        String domain=domain(gt);
        for(JSONObject s:group){long t=s.optLong("thread_id",0);if(t>0)threads.add(t);long a=s.optLong("anchor_signal_id",0);if(a>0)anchors.add(a);}
        for(JSONObject e:evidence){String et=canonical(oneEvidenceText(e));double lexical=tokenOverlap(gt,et);long t=e.optLong("thread_id",0),id=e.optLong("local_id",0);boolean exactThread=t>0&&threads.contains(t),exactAnchor=id>0&&anchors.contains(id);boolean compatible=domain.isEmpty()||domain.equals(domain(et));
            if(!compatible&&!exactThread&&!exactAnchor)continue;double score=(exactThread?1.5:0)+(exactAnchor?1.8:0)+lexical;
            if(exactThread||exactAnchor||lexical>=.55)scored.add(new Scored(e,score));}
        scored.sort((a,b)->Double.compare(b.score,a.score));ArrayList<JSONObject> out=new ArrayList<>();for(Scored s:scored){out.add(s.o);if(out.size()>=8)break;}return out;
    }

    private static JSONObject newestResolution(List<JSONObject> bound){JSONObject best=null;long time=Long.MIN_VALUE;for(JSONObject e:bound)if(isResolutionEvidence(e)){long t=Math.max(e.optLong("occurred_at",0),e.optLong("updated_at",0));if(t>time){time=t;best=e;}}return best;}
    private static boolean sameSituation(JSONObject a,JSONObject b){long ta=a.optLong("thread_id",0),tb=b.optLong("thread_id",0);if(ta>0&&tb>0&&ta==tb)return true;String aa=canonical(a.optString("semantic_key")+" "+a.optString("title")+" "+a.optString("body")),bb=canonical(b.optString("semantic_key")+" "+b.optString("title")+" "+b.optString("body"));return domain(aa).equals(domain(bb))&&tokenOverlap(aa,bb)>=.58;}

    private static JSONObject financialDelta(List<JSONObject> bound)throws Exception{JSONObject o=new JSONObject();ArrayList<AmountPoint> xs=new ArrayList<>();for(JSONObject e:bound){String z=oneEvidenceText(e);Matcher m=Pattern.compile("(?i)(?:EGP|LE|جنيه)?\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)").matcher(z);while(m.find())try{double v=Double.parseDouble(m.group(1).replace(",",""));if(v>=1)xs.add(new AmountPoint(v,Math.max(e.optLong("occurred_at",0),e.optLong("updated_at",0))));}catch(Exception ignored){}}
        xs.sort((a,b)->Long.compare(b.t,a.t));if(!xs.isEmpty())o.put("latest_amount",xs.get(0).v);if(xs.size()>1){o.put("previous_amount",xs.get(1).v);o.put("amount_changed",Math.abs(xs.get(0).v-xs.get(1).v)>.005);}return o;}
    private static JSONObject financialState(JSONArray refs,List<JSONObject> evidence)throws Exception{ArrayList<JSONObject> xs=new ArrayList<>();HashSet<String> wanted=new HashSet<>();for(int i=0;i<refs.length();i++)wanted.add(refs.optString(i));for(JSONObject e:evidence)if(wanted.contains(e.optString("ref")))xs.add(e);JSONObject o=financialDelta(xs);o.put("domain","financial").put("state","unresolved");return o;}

    private static JSONArray relatedRefs(JSONObject seed,List<JSONObject> evidence,String wantedDomain){JSONArray a=new JSONArray();String seedText=canonical(oneEvidenceText(seed));long thread=seed.optLong("thread_id",0);for(JSONObject e:evidence){String et=canonical(oneEvidenceText(e));if(!wantedDomain.equals(domain(et)))continue;if((thread>0&&thread==e.optLong("thread_id",0))||tokenOverlap(seedText,et)>=.50)a.put(e.optString("ref"));if(a.length()>=5)break;}if(a.length()==0)a.put(seed.optString("ref"));return a;}
    private static List<JSONObject> collectSituations(JSONObject state){ArrayList<JSONObject> out=new ArrayList<>();if(state==null)return out;LinkedHashSet<String> seen=new LinkedHashSet<>();for(String name:new String[]{"attention","waiting","decisions","goals","situations"}){JSONArray a=state.optJSONArray(name);if(a==null)continue;for(int i=0;i<a.length();i++){JSONObject x=a.optJSONObject(i);if(x==null)continue;String r=x.optString("ref");if(!r.isEmpty()&&seen.add(r))out.add(x);}}return out;}
    private static JSONObject representative(List<JSONObject> g){JSONObject best=g.get(0);for(JSONObject x:g){int bi=best.optInt("importance",0),xi=x.optInt("importance",0);long bu=best.optLong("updated_at",0),xu=x.optLong("updated_at",0);if(xi>bi||(xi==bi&&xu>bu))best=x;}return best;}
    private static boolean containsKind(List<JSONObject> g,String k){for(JSONObject x:g)if(k.equalsIgnoreCase(x.optString("kind")))return true;return false;}
    private static JSONArray refs(List<JSONObject> xs){JSONArray a=new JSONArray();for(JSONObject x:xs)a.put(x.optString("ref"));return a;}
    private static JSONArray refsOfSituations(List<JSONObject> g){JSONArray a=new JSONArray();for(JSONObject x:g)a.put(x.optString("ref"));return a;}

    private static boolean isResolutionEvidence(JSONObject e){String z=norm(oneEvidenceText(e));return has(z,"you’re all set","you're all set","setup complete","set up successfully","completed successfully","completed","resolved","done","cancelled","canceled","تم بنجاح","تم الاعداد","تم الإعداد","اتعمل","خلص")&&!isSecurityApproval(e);}
    private static boolean isSecurityApproval(JSONObject e){String z=norm(oneEvidenceText(e));return has(z,"approve sign-in","approve sign in","new sign-in request","new sign in request","authenticator","تسجيل دخول","طلب تسجيل دخول")&&has(z,"approve","request","موافقة","طلب");}
    private static boolean isFailureOrLimit(String z){return has(z,"over limit","over-limit","declined","insufficient funds","failed payment","مرفوض","رصيد غير كافي","تجاوز الحد");}
    private static boolean isExplicitReminderRequest(String z){return has(z,"remind me","reminder","فكرني","ذكّرني","ذكرني")&&has(z,"tomorrow","today","saturday","sunday","monday","tuesday","wednesday","thursday","friday","بكرة","بكره","السبت","الأحد","الاحد","الاثنين","الثلاثاء","الأربعاء","الخميس","الجمعة","الساعة");}
    private static boolean needsPreparationNow(String z){return has(z,"pick up result","collect result","bring result","prepare","قبل الموعد","أجيب النتيجة","اجيب النتيجة","استلم النتيجة","جهز","حضّر","حضر");}
    private static boolean isFinancial(String z){return "financial".equals(domain(z));}
    private static boolean isMedical(String z){return "medical".equals(domain(z));}
    private static String domain(String z){String n=norm(z);if(has(n,"card","credit","debit","over limit","declined","transaction","payment","bank","balance","spotify","snapchat","بطاقة","كارت","حد الائتمان","مرفوض","عملية","دفع","بنك","رصيد"))return "financial";if(has(n,"doctor","hospital","appointment","scan","ct","mri","lab","result","medical","عيادة","دكتور","مستشفى","موعد","اشعة","أشعة","تحليل","تحاليل","نتيجة","صدر"))return "medical";if(has(n,"sign-in","sign in","authenticator","security","تسجيل دخول","موافقة"))return "security";if(has(n,"youtube","supervision","family","إشراف","اشراف"))return "family";return "";}

    private static JSONObject decision(String type,String target,JSONArray refs,double confidence,String reason,JSONObject state,String next)throws Exception{return new JSONObject().put("type",type).put("target_ref",target==null?"":target).put("evidence_refs",refs==null?new JSONArray():refs).put("confidence",Math.max(0,Math.min(1,confidence))).put("reason",reason).put("proposed_state",state==null?new JSONObject():state).put("next_action",next==null?JSONObject.NULL:next);}
    private static String summary(JSONArray d){int now=0,watch=0,ask=0,update=0,link=0,action=0;for(int i=0;i<d.length();i++){JSONObject x=d.optJSONObject(i);String t=x==null?"":x.optString("type");if("SURFACE_NOW".equals(t))now++;else if("WATCH".equals(t))watch++;else if("ASK_USER".equals(t))ask++;else if("UPDATE_SITUATION".equals(t))update++;else if("LINK".equals(t))link++;else if("PROPOSE_ACTION".equals(t))action++;}return "Evidence-grounded reconciliation: "+now+" now, "+watch+" watch, "+ask+" ask, "+update+" lifecycle update, "+link+" link, "+action+" proposed action.";}
    private static List<JSONObject> objects(JSONArray a){ArrayList<JSONObject> out=new ArrayList<>();if(a!=null)for(int i=0;i<a.length();i++){JSONObject x=a.optJSONObject(i);if(x!=null)out.add(x);}return out;}
    private static String groupText(List<JSONObject> g){StringBuilder b=new StringBuilder();for(JSONObject x:g)b.append(' ').append(x.optString("title")).append(' ').append(x.optString("body")).append(' ').append(x.optString("semantic_key")).append(' ').append(x.optString("metadata_json"));return b.toString();}
    private static String evidenceText(List<JSONObject> e){StringBuilder b=new StringBuilder();for(JSONObject x:e)b.append(' ').append(oneEvidenceText(x));return b.toString();}
    private static String oneEvidenceText(JSONObject e){return e.optString("title")+" "+e.optString("body")+" "+e.optString("raw_text")+" "+e.optString("extracted_text")+" "+e.optString("summary")+" "+e.optString("reason")+" "+e.optString("metadata_json");}
    private static String norm(String s){return LocalSemanticEmbedder.norm(s==null?"":s);}
    private static String canonical(String s){String x=norm(s);StringBuilder b=new StringBuilder();for(String w:x.split("[^\\p{L}\\p{Nd}]+")){if(w.length()<2||STOP.contains(w))continue;if(b.length()>0)b.append(' ');b.append(w);}return b.toString();}
    private static double tokenOverlap(String a,String b){if(a==null||b==null||a.isEmpty()||b.isEmpty())return 0;HashSet<String>x=new HashSet<>(Arrays.asList(a.split(" "))),y=new HashSet<>(Arrays.asList(b.split(" ")));x.remove("");y.remove("");if(x.isEmpty()||y.isEmpty())return 0;int inter=0;for(String w:x)if(y.contains(w))inter++;return inter/(double)Math.min(x.size(),y.size());}
    private static boolean has(String z,String...xs){for(String x:xs)if(z.contains(norm(x)))return true;return false;}
    private static List<String> jsonKeys(JSONObject o){ArrayList<String> xs=new ArrayList<>();Iterator<String> it=o.keys();while(it.hasNext())xs.add(it.next());return xs;}
    private static final Set<String> STOP=new HashSet<>(Arrays.asList("the","a","an","to","of","and","or","for","in","on","at","is","are","be","my","your","this","that","من","في","على","الى","إلى","اللي","ده","دي","و","او","أو"));
    private static final class Scored{final JSONObject o;final double score;Scored(JSONObject o,double score){this.o=o;this.score=score;}}
    private static final class AmountPoint{final double v;final long t;AmountPoint(double v,long t){this.v=v;this.t=t;}}
}
