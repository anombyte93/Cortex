package com.kareem.cortex;

import android.database.Cursor;
import org.json.JSONObject;

/** Personal calibration over an already-valid attention candidate. */
public final class AttentionLearning {
    public static final String VERSION="attention_learning_004";
    private AttentionLearning(){}

    public static AttentionEngine.Decision apply(VaultDb db,PrimeBriefStore.Item item,AttentionEngine.Decision d){
        if(db==null||item==null||d==null||item.id<=0)return d;CognitiveStore.ensure(db);int positive=0,negative=0,snooze=0,acted=0,evalUp=0,evalDown=0;long snoozedUntil=0,dismissedAt=0,actedAt=0;
        Cursor c=db.getReadableDatabase().query("feedback_events",new String[]{"event_type","value_json","created_at"},"target_type='derived' AND target_id=?",new String[]{String.valueOf(item.id)},null,null,"created_at DESC","60");
        while(c.moveToNext()){
            String e=n(c.getString(0)),value=c.getString(1)==null?"":c.getString(1);long at=c.getLong(2);
            if("attention_opened".equals(e)||"confirm".equals(e))positive++;
            else if("attention_acted".equals(e)||"resolved_by_user".equals(e)||"complete".equals(e)){acted++;actedAt=Math.max(actedAt,at);}
            else if("attention_snoozed".equals(e)){snooze++;try{snoozedUntil=Math.max(snoozedUntil,new JSONObject(value).optLong("until_ms",0));}catch(Exception ignored){}}
            else if("attention_dismissed".equals(e)||"not_important".equals(e)||"dismiss".equals(e)||"ignore_similar".equals(e)){negative++;dismissedAt=Math.max(dismissedAt,at);}
            else if("attention_eval".equals(e)){try{String verdict=n(new JSONObject(value).optString("verdict",""));if("too_low".equals(verdict)||"should_be_now".equals(verdict))evalUp++;else if("too_high".equals(verdict)||"should_be_quiet".equals(verdict))evalDown++;}catch(Exception ignored){}}
        }c.close();
        long now=System.currentTimeMillis();
        if(snoozedUntil>now)return new AttentionEngine.Decision(0,AttentionEngine.Band.QUIET,"Snoozed until "+android.text.format.DateFormat.format("dd MMM · HH:mm",snoozedUntil)+".",d.urgency,d.consequence,d.responsibility,d.temporalPressure,d.openLoopPressure,d.novelty,d.confidence);
        if(dismissedAt>=item.updatedAt)return new AttentionEngine.Decision(0,AttentionEngine.Band.QUIET,"You marked this version as not important. New evidence can surface it again.",d.urgency,d.consequence,d.responsibility,d.temporalPressure,d.openLoopPressure,d.novelty,d.confidence);
        // Acting suppresses only the version that was actually acted on. If newer evidence updates
        // the same durable obligation, it is eligible for fresh attention instead of staying muted forever.
        if(actedAt>=item.updatedAt)return new AttentionEngine.Decision(Math.min(24,d.score),AttentionEngine.Band.QUIET,"You already acted on this version, so Cortex is suppressing it unless new evidence reopens the loop.",d.urgency,d.consequence,d.responsibility,d.temporalPressure,d.openLoopPressure,d.novelty,d.confidence);
        int historicalActed=actedAt>0&&actedAt<item.updatedAt?1:0;
        int delta=Math.min(8,positive*2)-Math.min(18,negative*6)-Math.min(10,snooze*4)+Math.min(18,evalUp*6)-Math.min(18,evalDown*6)-historicalActed*2;if(delta==0)return d;int score=Math.max(0,Math.min(100,d.score+delta));return new AttentionEngine.Decision(score,band(score),learningReason(d.whyNow,positive,negative,snooze,evalUp,evalDown,historicalActed>0),d.urgency,d.consequence,d.responsibility,d.temporalPressure,d.openLoopPressure,d.novelty,d.confidence);
    }

    public static void record(VaultDb db,long derivedId,String behavior){
        if(db==null||derivedId<=0)return;String e=n(behavior);if(!("opened".equals(e)||"acted".equals(e)||"dismissed".equals(e)))return;try{CognitiveStore.feedback(db,"derived",derivedId,"attention_"+e,new JSONObject().put("behavior",e).toString(),VERSION);}catch(Exception ignored){}
    }

    public static void snooze(VaultDb db,long derivedId,long untilMs){
        if(db==null||derivedId<=0||untilMs<=System.currentTimeMillis())return;try{CognitiveStore.feedback(db,"derived",derivedId,"attention_snoozed",new JSONObject().put("behavior","snoozed").put("until_ms",untilMs).toString(),VERSION);}catch(Exception ignored){}
    }

    private static String learningReason(String original,int positive,int negative,int snooze,int evalUp,int evalDown,boolean actedBefore){if(evalDown>0)return original+" Your evaluation says Cortex has been ranking this too high.";if(evalUp>0)return original+" Your evaluation says Cortex has been ranking this too low.";if(negative>0)return original+" Personal feedback has reduced its attention priority.";if(snooze>0)return original+" You previously snoozed this, so Cortex is surfacing it more cautiously.";if(actedBefore)return original+" You acted on an earlier version; newer evidence has reopened this loop.";if(positive>0)return original+" Your prior interactions suggest this is useful to surface.";return original;}
    private static AttentionEngine.Band band(int s){return s>=72?AttentionEngine.Band.NOW:s>=54?AttentionEngine.Band.LATER:s>=36?AttentionEngine.Band.WATCHING:AttentionEngine.Band.QUIET;}
    private static String n(String s){return s==null?"":s.trim().toLowerCase();}
}
