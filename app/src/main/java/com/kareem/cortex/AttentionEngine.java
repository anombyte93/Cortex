package com.kareem.cortex;

/** Dynamic, explainable attention ranking over already-relevant candidates. */
public final class AttentionEngine {
    private AttentionEngine(){}
    public enum Band { NOW, LATER, WATCHING, QUIET }
    public static final class Decision {
        public final int score;public final Band band;public final String whyNow;
        public final double urgency,consequence,responsibility,temporalPressure,openLoopPressure,novelty,confidence;
        Decision(int score,Band band,String whyNow,double urgency,double consequence,double responsibility,double temporalPressure,double openLoopPressure,double novelty,double confidence){this.score=score;this.band=band;this.whyNow=whyNow;this.urgency=urgency;this.consequence=consequence;this.responsibility=responsibility;this.temporalPressure=temporalPressure;this.openLoopPressure=openLoopPressure;this.novelty=novelty;this.confidence=confidence;}
    }
    public static Decision evaluate(PrimeBriefStore.Item x,long now){
        if(x==null)return quiet("No usable attention candidate");
        String kind=CandidateConsolidator.effectiveKind(x),text=norm(x.title+" "+x.body);long age=Math.max(0,now-x.updatedAt);double hours=age/3600000.0;
        long target=TemporalResolver.resolveForAttention(x.title+" "+x.body,x.updatedAt>0?x.updatedAt:now);double horizon=target>0?(target-now)/3600000.0:Double.NaN;
        double urgency=baseUrgency(kind,text,horizon),consequence=clamp(x.importance/100.0),responsibility=responsibility(kind,text),temporal=temporalPressure(text,hours,horizon),loop=openLoopPressure(kind,hours),novelty=novelty(hours),certainty=clamp(x.confidence);
        double raw=0.20*urgency+0.17*consequence+0.18*responsibility+0.18*temporal+0.14*loop+0.07*novelty+0.06*certainty;
        if(certainty<0.55)raw-=0.10;if(kind.equals("DECISION")&&certainty>=0.75)raw+=0.04;if((kind.equals("ACTION")||kind.equals("REMINDER"))&&responsibility>=0.80)raw+=0.06;if(kind.equals("WAITING")&&temporal>=0.70)raw+=0.06;if(kind.equals("ALERT")&&consequence>=0.65)raw+=0.04;
        int score=(int)Math.round(clamp(raw)*100.0);
        // Explicit future dates constrain NOW. A Saturday event should not consume Thursday-morning NOW budget.
        if(!Double.isNaN(horizon)){if(horizon>72)score=Math.min(score,53);else if(horizon>36)score=Math.min(score,67);else if(horizon>24)score=Math.min(score,71);}
        Band band=band(score);return new Decision(score,band,reason(kind,text,hours,horizon,urgency,responsibility,temporal,loop),urgency,consequence,responsibility,temporal,loop,novelty,certainty);
    }
    private static double baseUrgency(String kind,String t,double horizon){if(!Double.isNaN(horizon)){if(horizon<=6)return 1.0;if(horizon<=24)return .92;if(horizon<=36)return .78;if(horizon<=72)return .55;if(horizon<=168)return .34;return .20;}if(has(t,"urgent","asap","today","tonight","now","immediately","ضروري","النهارده","النهاردة","اليوم","دلوقتي","حالاً","حالا"))return .95;if(kind.equals("ACTION")||kind.equals("REMINDER"))return .66;if(kind.equals("WAITING"))return .52;if(kind.equals("DECISION"))return .58;if(kind.equals("ALERT"))return .62;if(kind.equals("CHANGE"))return .46;return .32;}
    private static double responsibility(String kind,String t){if(kind.equals("ACTION")||kind.equals("REMINDER")){if(has(t,"need you","please send","please review","please confirm","محتاج منك","ابعتلي","ابعت لي","راجع","أكد","اكد"))return .95;return .80;}if(kind.equals("WAITING"))return .28;if(kind.equals("DECISION"))return .58;if(kind.equals("ALERT"))return .42;if(kind.equals("CHANGE"))return .24;return .25;}
    private static double temporalPressure(String t,double ageHours,double horizon){if(!Double.isNaN(horizon)){if(horizon<=0)return 1.0;if(horizon<=6)return 1.0;if(horizon<=24)return .94;if(horizon<=36)return .80;if(horizon<=72)return .56;if(horizon<=168)return .35;return .20;}if(has(t,"overdue","late","متأخر","متاخر","فات معاده","فات موعده"))return 1.0;if(ageHours<4)return .36;if(ageHours<24)return .44;if(ageHours<72)return .58;if(ageHours<168)return .68;return .48;}
    private static double openLoopPressure(String kind,double ageHours){if(!(kind.equals("ACTION")||kind.equals("REMINDER")||kind.equals("WAITING")||kind.equals("DECISION")||kind.equals("ALERT")))return .18;if(ageHours<6)return .35;if(ageHours<24)return .48;if(ageHours<72)return .66;if(ageHours<168)return .78;return .58;}
    private static double novelty(double ageHours){if(ageHours<2)return .90;if(ageHours<8)return .72;if(ageHours<24)return .55;if(ageHours<72)return .38;return .18;}
    private static String reason(String kind,String t,double ageHours,double horizon,double urgency,double responsibility,double temporal,double loop){
        if(!Double.isNaN(horizon)){if(horizon<=0)return "The stated time has arrived or passed, so this unresolved item deserves attention now.";if(horizon<=6)return "The stated time is within the next few hours.";if(horizon<=24)return (kind.equals("ACTION")||kind.equals("REMINDER"))&&responsibility>=.8?"You appear responsible for this and its stated time is within 24 hours.":"Its stated time is within 24 hours.";if(horizon<=36)return "Its stated time is tomorrow/within about a day, so it should stay visible but not dominate early.";if(horizon<=72)return "This is scheduled more than a day away, so it belongs in Later rather than Now.";return "This has a grounded future time, but it is not close enough to consume current attention.";}
        if(kind.equals("ALERT"))return "This is an alert/event that may matter, but no immediate user decision is assumed without stronger evidence.";
        if(kind.equals("CHANGE"))return "Something relevant changed; Cortex is surfacing the change without assuming that you must act on it.";
        if((kind.equals("ACTION")||kind.equals("REMINDER"))&&responsibility>=.80&&temporal>=.70)return "You appear responsible for this and its timing is becoming important.";if(kind.equals("WAITING")&&temporal>=.70)return "You are waiting on something unresolved and the follow-up window is getting closer.";if(kind.equals("DECISION")&&temporal>=.65)return "An unresolved decision is becoming time-sensitive.";if(loop>=.70)return "This open loop has remained unresolved long enough to deserve another look.";if(ageHours<4)return "This is new, but Cortex has not seen enough pressure yet to treat it as urgent.";if(urgency<.45)return "Relevant context, but there is weak evidence that it needs action now.";return "Relevant and unresolved, but no precise current-time trigger was grounded.";
    }
    private static Band band(int s){return s>=72?Band.NOW:s>=54?Band.LATER:s>=36?Band.WATCHING:Band.QUIET;}private static Decision quiet(String why){return new Decision(0,Band.QUIET,why,0,0,0,0,0,0,0);}private static String norm(String s){return MasterRelevanceFilter.ruleNorm(s==null?"":s);}private static boolean has(String s,String...xs){for(String x:xs)if(s.contains(norm(x)))return true;return false;}private static double clamp(double x){return Math.max(0,Math.min(1,x));}
}
