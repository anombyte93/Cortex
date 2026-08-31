package com.kareem.cortex;

import java.util.*;

/** Semantic, truth-preserving presentation helpers for Focus/Inbox rows. */
public final class InboxPresentation {
    private static final HashSet<String> GENERIC_TITLES=new HashSet<>(Arrays.asList(
            "memory","voice","voice note","recording","audio","audio note","screenshot","image","photo","file","document","shared item","imported file","notification","capture","quick capture"));
    private InboxPresentation(){}

    /** Prefer the actual obligation/meaning over transport-shaped labels such as Voice or Memory. */
    public static String title(KnowledgeItem item,ArrayList<String> openActions){
        if(openActions!=null&&!openActions.isEmpty()){
            ArrayList<String> parts=actionParts(openActions);
            if(!parts.isEmpty())return concise(parts.get(0),76);
        }
        String original=item==null?"":stripTransportPrefix(nz(item.title));
        if(!genericTitle(original))return concise(original,76);
        if(item!=null){
            String semantic=firstUseful(item.summary,item.extractedText,item.rawText);
            if(!semantic.isEmpty())return concise(firstSentence(semantic),76);
        }
        return fallback(item);
    }

    public static ArrayList<String> actionParts(ArrayList<String> raw){
        ArrayList<String> out=new ArrayList<>();
        if(raw==null)return out;
        for(String source:raw){
            String s=stripDue(nz(source));
            if(s.isEmpty())continue;
            s=s.replaceAll("\\s+[•|]\\s+",". ");
            s=s.replaceAll("(?i)\\s+(?=and then\\b|then\\b|after that\\b|if (?:he|she|they|it)\\b)",". ");
            s=s.replaceAll("\\s+(?=ولو\\b|وبعدها\\b|وبعدين\\b|بعدها\\b|لو ما\\b|لو م[ا]?\\s)",". ");
            String[] clauses=s.split("(?<=[.!؟])\\s+");
            for(String c:clauses){String x=cleanup(c);if(x.length()<3)continue;if(!containsNear(out,x))out.add(concise(x,130));}
        }
        if(out.size()>4)return new ArrayList<>(out.subList(0,4));return out;
    }

    public static String due(ArrayList<String> raw){
        if(raw==null)return "";
        for(String s:raw){int i=nz(s).toLowerCase(Locale.US).lastIndexOf("due:");if(i>=0){String d=nz(s).substring(i+4).replace("•","").trim();if(!d.isEmpty())return TemporalResolver.displayStored(d);}}
        return "";
    }

    public static String preview(KnowledgeItem k,ArrayList<String> actions){return preview(k,actions,"");}
    public static String preview(KnowledgeItem k,ArrayList<String> actions,String visibleTitle){
        if(k==null)return "";
        String p=firstUseful(k.summary,k.extractedText,k.rawText);p=cleanup(p);
        if(p.isEmpty())return "";
        if(sameMeaning(p,visibleTitle)){
            String alternate=firstDifferent(visibleTitle,k.extractedText,k.rawText);
            p=cleanup(alternate);
            if(p.isEmpty()||sameMeaning(p,visibleTitle))return "";
        }
        if(actions!=null&&!actions.isEmpty()){int stop=sentenceEnd(p);if(stop>25&&stop<170)p=p.substring(0,stop+1);return concise(p,170);}return concise(p,300);
    }

    /** This is a state label, not a model-confidence claim. */
    public static String stateLabel(KnowledgeItem item,String bucket,boolean pinned,boolean reviewed,ArrayList<String> actions){
        String status=item==null?"":nz(item.status).toLowerCase(Locale.ROOT);
        if(status.equals("failed_retryable")||status.equals("analysis_failed"))return"Analysis incomplete";
        if(status.contains("processing")||status.contains("analyz")||status.contains("queued")||status.contains("transcrib"))return"Understanding";
        if(actions!=null&&!actions.isEmpty())return"Open action";
        String b=nz(bucket);
        if("Waiting".equals(b))return"Waiting";
        if("Needs attention".equals(b))return"Needs attention";
        if(pinned)return"Pinned";
        if(!reviewed)return"Unreviewed";
        return"Context";
    }

    /** Attention score expresses evidence/priority strength; never relabel it as probability. */
    public static String signalLabel(KnowledgeItem item,int score,ArrayList<String> actions){
        String status=item==null?"":nz(item.status).toLowerCase(Locale.ROOT);
        if(status.equals("failed_retryable")||status.equals("analysis_failed"))return"needs retry";
        if(actions!=null&&!actions.isEmpty())return score>=70?"strong signal":"grounded signal";
        if(score>=100)return"urgent signal";
        if(score>=70)return"strong signal";
        if(score>=45)return"moderate signal";
        return"weak signal";
    }

    public static boolean sameMeaning(String a,String b){
        String x=norm(a),y=norm(b);if(x.isEmpty()||y.isEmpty())return false;
        return x.equals(y)||x.startsWith(y)||y.startsWith(x)||(x.length()>20&&y.length()>20&&(x.contains(y)||y.contains(x)));
    }

    private static String stripDue(String s){int i=s.toLowerCase(Locale.US).lastIndexOf("due:");if(i>=0){int bullet=s.lastIndexOf('•',i);s=s.substring(0,bullet>=0?bullet:i);}return cleanup(s);}
    private static String stripTransportPrefix(String s){String x=cleanup(s);x=x.replaceFirst("(?i)^(?:voice|audio|recording|memory|capture|screenshot|image|file)\\s*[:·-]\\s*","").trim();return x;}
    private static boolean genericTitle(String s){String x=norm(s);return x.isEmpty()||GENERIC_TITLES.contains(x)||x.matches("(?i)(?:voice|audio|recording|memory|capture|screenshot|image|file)(?: note)?(?: \\d+)?");}
    private static String firstUseful(String...xs){for(String x:xs){String c=cleanup(nz(x));if(!c.isEmpty()&&!genericTitle(c))return c;}return"";}
    private static String firstDifferent(String visible,String...xs){for(String x:xs){String c=cleanup(nz(x));if(!c.isEmpty()&&!sameMeaning(c,visible))return c;}return"";}
    private static String firstSentence(String s){String x=cleanup(s);int stop=sentenceEnd(x);if(stop>=12&&stop<120)return x.substring(0,stop+1);return x;}
    private static int sentenceEnd(String s){int best=-1;for(char c:new char[]{'.','؟','!','\n'}){int i=s.indexOf(c);if(i>=0&&(best<0||i<best))best=i;}return best;}
    private static String fallback(KnowledgeItem item){String t=item==null?"":nz(item.type).toUpperCase(Locale.ROOT);if("AUDIO".equals(t))return"Voice note";if("FILE".equals(t))return"File";if("SCREENSHOT".equals(t)||"IMAGE".equals(t))return"Image";return"Memory";}
    private static String cleanup(String s){return nz(s).replace('\n',' ').replace('\r',' ').replaceAll("\\s+"," ").replaceAll("^[•.،,\\-]+\\s*","").replaceAll("\\s+[.،,]+$","").trim();}
    private static String concise(String s,int max){String x=cleanup(s);if(x.length()<=max)return x;int cut=x.lastIndexOf(' ',max);if(cut<max/2)cut=max;return x.substring(0,cut).trim()+"…";}
    private static boolean containsNear(ArrayList<String> xs,String x){String n=norm(x);for(String y:xs){String m=norm(y);if(m.equals(n)||m.contains(n)||n.contains(m))return true;}return false;}
    private static String norm(String s){return cleanup(s).toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+"," ").trim();}
    private static String nz(String s){return s==null?"":s;}
}
