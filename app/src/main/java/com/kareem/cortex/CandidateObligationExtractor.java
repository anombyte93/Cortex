package com.kareem.cortex;

import java.util.ArrayList;
import java.util.Locale;

/**
 * Donor-derived open-loop detector. Produces candidates only; it never creates tasks/reminders.
 */
public final class CandidateObligationExtractor {
    private CandidateObligationExtractor(){}

    public static final class Candidate {
        public final String text, kind;
        public final double confidence;
        Candidate(String text,String kind,double confidence){this.text=text;this.kind=kind;this.confidence=confidence;}
    }

    public static ArrayList<Candidate> extract(String text){
        ArrayList<Candidate> out=new ArrayList<>();String clean=clean(text);if(clean.isEmpty())return out;String n=normalize(clean);
        add(out,clean,n,new String[]{"follow up","متابعة","تابع مع","check back"},"follow_up",0.82);
        add(out,clean,n,new String[]{"waiting for","wait for","مستني","مستنى","في انتظار"},"waiting_on",0.84);
        add(out,clean,n,new String[]{"please review","kindly review","review and","راجع","مراجعة"},"review",0.78);
        add(out,clean,n,new String[]{"please send","kindly send","send me","ابعت","أرسل","ارسل"},"send",0.78);
        add(out,clean,n,new String[]{"confirm","confirmation","أكد","تاكيد","تأكيد"},"confirm",0.76);
        add(out,clean,n,new String[]{"deadline","due ","before ","آخر موعد","اخر موعد","قبل يوم"},"deadline",0.80);
        add(out,clean,n,new String[]{"appointment","meeting","موعد","ميعاد","اجتماع"},"appointment",0.70);
        if(clean.indexOf('?')>=0&&out.isEmpty())out.add(new Candidate(trim(clean,280),"reply_or_decision",0.62));
        return dedupe(out);
    }

    private static void add(ArrayList<Candidate> out,String raw,String normalized,String[] needles,String kind,double confidence){for(String w:needles)if(normalized.contains(normalize(w))){out.add(new Candidate(trim(raw,280),kind,confidence));return;}}
    private static ArrayList<Candidate> dedupe(ArrayList<Candidate> in){ArrayList<Candidate> out=new ArrayList<>();for(Candidate c:in){boolean seen=false;for(Candidate x:out)if(x.kind.equals(c.kind)){seen=true;break;}if(!seen)out.add(c);}return out;}
    private static String clean(String s){return s==null?"":s.replace('\n',' ').replace('\r',' ').replaceAll("\\s+"," ").trim();}
    private static String normalize(String s){return clean(s).toLowerCase(Locale.US).replace('أ','ا').replace('إ','ا').replace('آ','ا').replace('ى','ي');}
    private static String trim(String s,int n){return s.length()<=n?s:s.substring(0,n)+"…";}
}
