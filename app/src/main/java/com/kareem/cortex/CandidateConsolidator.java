package com.kareem.cortex;

import java.text.SimpleDateFormat;
import java.util.*;
import java.util.regex.*;

/**
 * Presentation-time consolidation of durable derived candidates into canonical events.
 * Stored relevance remains untouched; this layer only prevents duplicate evidence from
 * consuming multiple attention slots and assigns a more useful cognitive presentation kind.
 */
public final class CandidateConsolidator {
    private static final Pattern URL=Pattern.compile("https?://\\S+",Pattern.CASE_INSENSITIVE);
    private static final Pattern REF=Pattern.compile("\\[(?:\\d+|[^\\]]{0,12})\\]");
    private static final Pattern MONEY=Pattern.compile("(?i)(?:egp|جنيه|جم)?\\s*([0-9]{2,}(?:[.,][0-9]{1,2})?)");
    private static final Pattern BUNDLE_INDEX=Pattern.compile("(?:^|\\s)\\[\\d+\\](?:\\s|$)");
    private CandidateConsolidator(){}

    public static String effectiveKind(PrimeBriefStore.Item x){
        if(x==null)return "CONTEXT";
        String stored=n(x.kind).toUpperCase(Locale.ROOT),raw=x.title+" "+x.body,t=norm(raw),src=norm(x.source);
        // Old builds could persist notification digests as DECISION. Keep the evidence, but never
        // let a bundled app summary impersonate a human decision on Today/Ask.
        if(legacyNotificationBundle(raw,src,stored))return "CONTEXT";
        // Likewise, an automated account/info message cannot become a user ACTION unless the
        // evidence contains an explicit user obligation. This catches legacy/model over-promotion
        // without deleting the underlying evidence.
        if(automatedInformationalAction(raw,src,stored))return "CONTEXT";
        if(alert(t,src))return "ALERT";
        if(reminder(t))return "REMINDER";
        if(change(t,src,stored))return "CHANGE";
        return stored.isEmpty()?"CONTEXT":stored;
    }

    /**
     * Semantic display title for legacy rows whose stored suffix no longer matches the effective
     * cognitive kind. Example: "CIB · Decision" correctly renders as "CIB · Alert" after
     * reclassification, instead of visually lying about what the event is.
     */
    public static String presentationTitle(PrimeBriefStore.Item x){
        if(x==null)return "";
        String title=n(x.title),stored=n(x.kind).toUpperCase(Locale.ROOT),effective=effectiveKind(x);
        if(title.isEmpty()||stored.isEmpty()||effective.equals(stored))return title;
        String storedLabel=friendlyKind(stored);
        if(storedLabel.isEmpty())return title;
        Pattern suffix=Pattern.compile("(?i)\\s*[·|-]\\s*"+Pattern.quote(storedLabel)+"\\s*$");
        Matcher m=suffix.matcher(title);
        if(!m.find())return title;
        if("CONTEXT".equals(effective))return title.substring(0,m.start()).trim();
        return title.substring(0,m.start()).trim()+" · "+friendlyKind(effective);
    }

    /** Visible for regression tests and maintenance diagnostics. */
    static boolean legacyNotificationBundle(String raw,String source,String storedKind){
        if(!"DECISION".equals(n(storedKind).toUpperCase(Locale.ROOT)))return false;
        String t=norm(raw),src=norm(source);
        if(financial(t)||isCommunicationSource(src))return false;
        boolean indexed=BUNDLE_INDEX.matcher(raw==null?"":raw).find();
        boolean summary=has(t,"new messages","new message","notifications","notification summary","you created a new","new activity","updates from","رسائل جديده","رسائل جديدة","اشعارات","إشعارات");
        boolean appFeed=has(src,"youtube","google","instagram","facebook","systemui","notification")&&!has(src,"gmail","outlook");
        return summary&&(indexed||appFeed);
    }

    /** Visible for regression tests. */
    static boolean automatedInformationalAction(String raw,String source,String storedKind){
        if(!"ACTION".equals(n(storedKind).toUpperCase(Locale.ROOT)))return false;
        String t=norm(raw),src=norm(source);
        boolean informational=has(t,
                "important info about","important information about","google account","account notice","account update",
                "newsletter","weekly summary","weekly digest","digest","what's new","whats new","new features",
                "privacy policy","terms of service","welcome to","معلومات مهمه عن","معلومات مهمة عن","تحديث الحساب");
        boolean automatedSource=has(src,"google","notion","youtube","instagram","facebook","system","notification","gmail","mail","outlook");
        boolean explicitObligation=has(t,
                "action required","please send","can you send","could you send","need you to send","please review","can you review","could you review",
                "please confirm","can you confirm","could you confirm","verify your","verify identity","complete your","submit your","reply to","respond to",
                "required to","you must","مطلوب منك","لازم تعمل","محتاج منك","ممكن تبعت","ابعتلي","راجع","أكد","اكد");
        return informational&&!explicitObligation&&(automatedSource||has(t,"google account","newsletter","digest"));
    }

    /** Canonical event identity used for grouping repeated evidence. */
    public static String eventKey(PrimeBriefStore.Item x){
        if(x==null)return "";
        String strong=strongSignature(x);
        if(!strong.isEmpty())return strong;
        String t=canonicalText(x.title+" "+x.body);
        String source=sourceFamily(x.source);
        return effectiveKind(x)+"|"+source+"|"+t;
    }

    public static boolean sameEvent(PrimeBriefStore.Item a,PrimeBriefStore.Item b){
        if(a==null||b==null)return false;
        String ka=effectiveKind(a),kb=effectiveKind(b);if(!ka.equals(kb))return false;

        String strongA=strongSignature(a),strongB=strongSignature(b);
        if(!strongA.isEmpty()&&!strongB.isEmpty())return strongA.equals(strongB);

        String sa=sourceFamily(a.source),sb=sourceFamily(b.source);
        if(!sa.isEmpty()&&!sb.isEmpty()&&!sa.equals(sb))return false;
        String x=canonicalText(a.title+" "+a.body),y=canonicalText(b.title+" "+b.body);
        if(x.equals(y))return true;
        if(x.length()<24||y.length()<24)return false;
        int min=Math.min(x.length(),y.length()),max=Math.max(x.length(),y.length());
        if(min*100/max<72)return false;
        if(x.contains(y)||y.contains(x))return true;
        return tokenOverlap(x,y)>=0.72;
    }

    public static <T> ArrayList<T> consolidate(List<T> rows,ItemAccessor<T> access){
        ArrayList<T> out=new ArrayList<>();
        for(T row:rows){
            PrimeBriefStore.Item item=access.item(row);boolean merged=false;
            for(int i=0;i<out.size();i++){
                T old=out.get(i);PrimeBriefStore.Item oi=access.item(old);
                if(!sameEvent(item,oi))continue;
                if(access.priority(row)>access.priority(old)||(access.priority(row)==access.priority(old)&&item.updatedAt>oi.updatedAt))out.set(i,row);
                merged=true;break;
            }
            if(!merged)out.add(row);
        }
        return out;
    }

    public interface ItemAccessor<T>{PrimeBriefStore.Item item(T x);int priority(T x);}

    /**
     * Semantic signature for event types where renderings vary a lot. It intentionally uses
     * only strong anchors so unrelated events are not merged accidentally.
     */
    private static String strongSignature(PrimeBriefStore.Item x){
        String kind=effectiveKind(x),text=canonicalText(x.title+" "+x.body),src=sourceFamily(x.source);
        if("ALERT".equals(kind)){
            String bank=bankAnchor(text,src),amount=amountAnchor(text),merchant=merchantAnchor(text),failure=failureAnchor(text);
            if(!bank.isEmpty()&&!amount.isEmpty())return "ALERT|"+bank+"|"+amount+"|"+merchant+"|"+failure;
            // Without an amount, do not merge every future decline from the same merchant.
            // A two-hour bucket collapses duplicate/re-rendered notifications while preserving
            // genuinely separate later attempts.
            if(!bank.isEmpty()&&!merchant.isEmpty()&&!failure.isEmpty())return "ALERT|"+bank+"|"+merchant+"|"+failure+"|"+twoHourBucket(x.updatedAt);
        }
        if("REMINDER".equals(kind)){
            long when=TemporalResolver.resolveForAttention(x.title+" "+x.body,x.updatedAt>0?x.updatedAt:System.currentTimeMillis());
            String time=when>0?new SimpleDateFormat("yyyy-MM-dd-HH",Locale.US).format(new Date(when)):"";
            String place=placeAnchor(text),subject=subjectAnchor(text);
            if(!time.isEmpty()&&!place.isEmpty())return "REMINDER|"+time+"|"+place;
            if(!time.isEmpty()&&!subject.isEmpty())return "REMINDER|"+time+"|"+subject;
        }
        return "";
    }

    private static String twoHourBucket(long when){
        long t=when>0?when:System.currentTimeMillis();long bucket=2L*60L*60L*1000L;return String.valueOf(t/bucket);
    }
    private static String bankAnchor(String t,String src){
        if(has(t,"cib","commercial international bank")||src.contains("cib"))return "cib";
        if(has(t,"nbe","national bank of egypt","البنك الاهلي","البنك الأهلي")||src.contains("nbe"))return "nbe";
        if(has(t,"qnb")||src.contains("qnb"))return "qnb";
        if(has(t,"banque misr","بنك مصر"))return "banque_misr";
        return src.contains("bank")?src:"";
    }
    private static String amountAnchor(String t){
        Matcher m=MONEY.matcher(t);ArrayList<String> nums=new ArrayList<>();while(m.find()){
            String v=m.group(1).replace(',','.');try{double d=Double.parseDouble(v);if(d>=20&&d<10000000)nums.add(trimNumber(d));}catch(Exception ignored){}
        }
        if(nums.isEmpty())return "";
        // Prefer smaller purchase amount over balances/limits when multiple values exist.
        nums.sort((a,b)->Double.compare(parseNum(a),parseNum(b)));return nums.get(0);
    }
    private static String merchantAnchor(String t){
        if(t.contains("spotify"))return "spotify";if(t.contains("google"))return "google";if(t.contains("uber"))return "uber";if(t.contains("amazon"))return "amazon";return "";
    }
    private static String failureAnchor(String t){
        if(has(t,"declined","rejected","refused","failed","رفض","مرفوض","لم تتم"))return "declined";
        if(has(t,"غير كافي","عدم كفايه","عدم كفاية","insufficient"))return "insufficient";
        return "";
    }
    private static String placeAnchor(String t){
        if(has(t,"مستشفى النسائم","مستشفي النسائم","al nasaem","al nasaem hospital","nasaem hospital"))return "nasaem_hospital";
        if(has(t,"مستشفى","مستشفي","hospital"))return importantTokens(t,new String[]{"مستشفى","مستشفي","hospital"});
        return "";
    }
    private static String subjectAnchor(String t){
        if(has(t,"اشعات","اشعة","أشعة","radiology","scan"))return "imaging";
        if(has(t,"شهادة","certificate"))return "certificate";
        if(has(t,"chest ct","ct"))return "ct";
        return importantTokens(t,new String[]{"reminder","فكرني","ذكرني","اعمل"});
    }
    private static String importantTokens(String t,String[] drop){
        Set<String> d=new HashSet<>();for(String x:drop)d.add(norm(x));ArrayList<String> out=new ArrayList<>();for(String x:t.split("\\s+")){if(x.length()<3||d.contains(x)||x.matches("\\d+"))continue;if(stop(x))continue;out.add(x);if(out.size()>=4)break;}return String.join("_",out);
    }
    private static boolean stop(String x){return has(x,"عندي","محتاج","لازم","اعمل","يوم","الساعة","الصبح","مساء","الدخول","الحضور","باسقية","ضروري","this","that","with","from","need","have");}

    private static boolean financial(String t){return has(t,"transaction","payment","purchase","card","بطاق","معامله","المعامله","عمليه","عملية","خصم","رصيد","credit limit","الحد الائتماني","egp");}
    private static boolean alert(String t,String src){
        boolean failure=has(t,"declined","rejected","refused","failed","رفض","مرفوض","لم تتم","غير كافي","عدم كفايه","عدم كفاية","تجاوزتم");
        boolean security=has(t,"security alert","unusual activity","new login","تسجيل دخول جديد","تنبيه امان");
        return security||(financial(t)&&failure)||(src.contains("bank")&&failure);
    }
    private static boolean reminder(String t){return has(t,"reminder","remind me","remember to","فكرني","ذكرني","اعمل تذكير","اعمل reminder");}
    private static boolean change(String t,String src,String stored){
        if(!("DECISION".equals(stored)||"MEMORY".equals(stored)))return false;
        // A bank/provider rejection is an external alert/change of state, never a decision the user made.
        if(financial(t)&&failureAnchor(t).length()>0)return false;
        if(has(t,"status changed","changed to","became","تم التغيير","اتغير","تغيرت الحاله","تغيرت الحالة"))return true;
        return !src.isEmpty()&&!isCommunicationSource(src)&&has(t,"approved","rejected","تمت الموافقه","تمت الموافقة","تم الرفض");
    }
    private static boolean isCommunicationSource(String src){return has(src,"whatsapp","telegram","messenger","signal","sms","messages","gmail","outlook","mail");}
    private static String canonicalText(String s){
        String x=s==null?"":s;x=URL.matcher(x).replaceAll(" ");x=REF.matcher(x).replaceAll(" ");x=norm(x);
        x=x.replaceAll("(?i)^(cib|nbe|qnb|banque misr|bank)\\s*[:·-]\\s*"," ").replaceAll("(?i)\\s+(cib|nbe|qnb)\\s*[:·-]?\\s*$"," ");
        return x.replaceAll("\\s+"," ").trim();
    }
    private static String sourceFamily(String s){String x=norm(s);if(x.contains("cib"))return "cib";if(x.contains("whatsapp"))return "whatsapp";if(x.contains("gmail")||x.contains("mail"))return "mail";if(x.contains("sms")||x.contains("message"))return "message";return x.length()>48?x.substring(0,48):x;}
    private static double tokenOverlap(String a,String b){Set<String>x=tokens(a),y=tokens(b);if(x.isEmpty()||y.isEmpty())return 0;Set<String>i=new HashSet<>(x);i.retainAll(y);Set<String>u=new HashSet<>(x);u.addAll(y);return u.isEmpty()?0:(double)i.size()/u.size();}
    private static Set<String> tokens(String s){HashSet<String>r=new HashSet<>();for(String x:s.split("\\s+"))if(x.length()>1)r.add(x);return r;}
    private static String friendlyKind(String kind){String k=n(kind).toUpperCase(Locale.ROOT);if(k.isEmpty())return "";if("ACTION".equals(k))return "Action";if("WAITING".equals(k))return "Waiting";if("DECISION".equals(k))return "Decision";if("ALERT".equals(k))return "Alert";if("CHANGE".equals(k))return "Change";if("REMINDER".equals(k))return "Reminder";if("CONTEXT".equals(k))return "Context";return k.substring(0,1)+k.substring(1).toLowerCase(Locale.ROOT);}
    private static boolean has(String s,String...xs){for(String x:xs)if(s.contains(norm(x)))return true;return false;}
    private static String norm(String s){return MasterRelevanceFilter.ruleNorm(s==null?"":s);}
    private static String n(String s){return s==null?"":s.trim();}
    private static double parseNum(String s){try{return Double.parseDouble(s);}catch(Exception e){return Double.MAX_VALUE;}}
    private static String trimNumber(double d){if(Math.rint(d)==d)return String.valueOf((long)d);return String.format(Locale.US,"%.2f",d);}
}