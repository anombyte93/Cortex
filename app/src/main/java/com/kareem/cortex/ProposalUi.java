package com.kareem.cortex;

import android.app.*;
import android.graphics.Color;
import android.view.*;
import android.widget.*;
import org.json.JSONArray;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Compact reusable model-proposal strip mounted directly under one specific result. */
public final class ProposalUi {
    private ProposalUi(){}

    public static void attach(Activity activity,VaultDb db,LinearLayout parent,ResultProposalEngine.Target target){
        if(activity==null||db==null||parent==null||target==null||target.text.trim().isEmpty())return;
        LinearLayout holder=new LinearLayout(activity);holder.setOrientation(LinearLayout.VERTICAL);holder.setPadding(0,CortexUi.dp(activity,8),0,CortexUi.dp(activity,2));parent.addView(holder);
        AtomicInteger generation=new AtomicInteger();startRequest(activity,db,holder,target,generation,false);
    }

    private static void startRequest(Activity activity,VaultDb db,LinearLayout holder,ResultProposalEngine.Target target,AtomicInteger generation,boolean fresh){
        if(activity.isFinishing()||activity.isDestroyed()||holder.getParent()==null)return;final int token=generation.incrementAndGet(),dp=CortexUi.dp(activity,1);holder.removeAllViews();holder.setVisibility(View.VISIBLE);
        LinearLayout waiting=new LinearLayout(activity);waiting.setGravity(Gravity.CENTER_VERTICAL);waiting.addView(CortexUi.glyph(activity,"brain",CortexUi.RED,true),new LinearLayout.LayoutParams(28*dp,28*dp));TextView thinking=CortexUi.plain(activity,fresh?"Retrying useful next moves…":"Thinking of useful next moves…",9,CortexUi.MUTED);thinking.setClickable(false);thinking.setFocusable(false);if(android.os.Build.VERSION.SDK_INT>=19)thinking.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);LinearLayout.LayoutParams tp=new LinearLayout.LayoutParams(0,-2,1);tp.setMargins(7*dp,0,0,0);waiting.addView(thinking,tp);holder.addView(waiting);
        ResultProposalEngine.Callback callback=(proposals,provider,error)->{
            if(token!=generation.get()||activity.isFinishing()||activity.isDestroyed()||holder.getParent()==null)return;holder.removeAllViews();
            if(proposals.isEmpty()){
                if(isTimeout(error)){renderTimeout(activity,db,holder,target,generation,provider);return;}
                if(!error.isEmpty()){TextView unavailable=CortexUi.plain(activity,"Suggestions are waiting for an available model",8,CortexUi.FAINT);unavailable.setClickable(false);unavailable.setFocusable(false);holder.addView(unavailable);if(provider!=null&&!provider.trim().isEmpty()){TextView model=CortexUi.plain(activity,"Last provider: "+provider,7,CortexUi.FAINT);model.setPadding(0,2*dp,0,0);holder.addView(model);}}else holder.setVisibility(View.GONE);return;
            }
            renderProposals(activity,db,holder,target,proposals,provider,dp);
        };
        if(fresh)ResultProposalEngine.requestFresh(activity,target,callback);else ResultProposalEngine.request(activity,target,callback);
    }

    private static void renderTimeout(Activity activity,VaultDb db,LinearLayout holder,ResultProposalEngine.Target target,AtomicInteger generation,String provider){
        int dp=CortexUi.dp(activity,1);TextView title=CortexUi.plain(activity,"Suggestions are taking too long",9,CortexUi.TEXT);CortexUi.medium(title);holder.addView(title);TextView detail=CortexUi.plain(activity,"The model did not finish within 45 seconds. Retry starts a fresh suggestion request; Cortex will not invent fallback actions.",8,CortexUi.FAINT);detail.setPadding(0,4*dp,0,7*dp);detail.setClickable(false);detail.setFocusable(false);holder.addView(detail);
        TextView retry=CortexUi.action(activity,"Retry suggestions",CortexUi.RED,false);retry.setOnClickListener(v->{retry.setEnabled(false);retry.setClickable(false);startRequest(activity,db,holder,target,generation,true);});holder.addView(retry,new LinearLayout.LayoutParams(-1,40*dp));
        if(provider!=null&&!provider.trim().isEmpty()){TextView model=CortexUi.plain(activity,"Last provider: "+provider,7,CortexUi.FAINT);model.setPadding(0,3*dp,0,0);holder.addView(model);}
    }

    private static void renderProposals(Activity activity,VaultDb db,LinearLayout holder,ResultProposalEngine.Target target,ArrayList<ResultProposalEngine.Proposal> proposals,String provider,int dp){
        holder.setVisibility(View.VISIBLE);LinearLayout headRow=new LinearLayout(activity);headRow.setGravity(Gravity.CENTER_VERTICAL);headRow.addView(CortexUi.glyph(activity,"brain",CortexUi.RED,true),new LinearLayout.LayoutParams(30*dp,30*dp));TextView head=CortexUi.plain(activity,"Cortex suggests",9,CortexUi.TEXT);CortexUi.medium(head);LinearLayout.LayoutParams hp=new LinearLayout.LayoutParams(0,-2,1);hp.setMargins(7*dp,0,0,0);headRow.addView(head,hp);holder.addView(headRow);
        HorizontalScrollView scroller=new HorizontalScrollView(activity);scroller.setHorizontalScrollBarEnabled(false);LinearLayout row=new LinearLayout(activity);row.setOrientation(LinearLayout.HORIZONTAL);row.setPadding(0,7*dp,0,2*dp);scroller.addView(row,new HorizontalScrollView.LayoutParams(-2,-2));
        for(ResultProposalEngine.Proposal p:proposals){
            int color=proposalColor(p);TextView chip=CortexUi.chip(activity,p.title,color,false);chip.setSingleLine(true);chip.setEllipsize(android.text.TextUtils.TruncateAt.END);chip.setMaxWidth(290*dp);int wash=Color.argb(9,Color.red(color),Color.green(color),Color.blue(color));CortexUi.pressable(activity,chip,CortexUi.round(activity,wash,Color.argb(68,Color.red(color),Color.green(color),Color.blue(color)),999));
            LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-2,38*dp);if(row.getChildCount()>0)cp.setMargins(7*dp,0,0,0);row.addView(chip,cp);chip.setOnClickListener(v->open(activity,db,target,p));
        }
        holder.addView(scroller,new LinearLayout.LayoutParams(-1,45*dp));if(provider!=null&&!provider.trim().isEmpty()){TextView model=CortexUi.plain(activity,"Suggested by "+provider,7,CortexUi.FAINT);model.setPadding(0,1*dp,0,0);holder.addView(model);}
    }

    private static boolean isTimeout(String error){return error!=null&&error.toLowerCase(Locale.ROOT).contains("timeout");}

    private static int proposalColor(ResultProposalEngine.Proposal p){
        if(p==null)return CortexUi.RED;if("BRAIN_PROMPT".equals(p.execution))return CortexUi.RED;String t=p.actionType==null?"":p.actionType;
        if("WAIT_FOR".equals(t)||"REMINDER".equals(t)||"FOLLOW_UP".equals(t))return CortexUi.ORANGE;
        if("CALENDAR_EVENT".equals(t)||"CALENDAR_RESCHEDULE".equals(t))return CortexUi.YELLOW;
        if("TASK".equals(t)||"PROJECT_LINK".equals(t)||"KNOWLEDGE_NOTE".equals(t))return CortexUi.GREEN;
        if("CALL".equals(t)||"MESSAGE_DRAFT".equals(t)||"EMAIL_DRAFT".equals(t))return CortexUi.RED;
        return CortexUi.YELLOW;
    }

    private static void open(Activity a,VaultDb db,ResultProposalEngine.Target target,ResultProposalEngine.Proposal p){
        if("BRAIN_PROMPT".equals(p.execution)){StringBuilder q=new StringBuilder();if(p.prompt!=null&&!p.prompt.trim().isEmpty())q.append(p.prompt.trim());q.append("\n\nCORTEX RESULT CONTEXT\nSurface: ").append(target.surface).append("\nTitle: ").append(target.title).append("\nResult:\n").append(clip(target.text,1800));CortexActionExecutor.openBrain(a,target.sourceItemId,q.toString());return;}
        if(!"ACTION".equals(p.execution))return;String key=safeKey(target.resultKey)+"_"+safeKey(p.id);long syntheticJob=target.sourceItemId>0?target.sourceItemId:Math.max(1,Math.abs((long)target.fingerprint().hashCode()));JSONArray missing=normalizedMissing(p,target);String status=missing.length()==0?"READY":"NEEDS_DETAILS";BrainActionStore.Action action=new BrainActionStore.Action(0,syntheticJob,key,p.actionType,p.title,status,p.confidence,p.payload,missing,target.sourceItemId,target.sourceType,clip(target.text,420));CortexActionDispatcher.preview(a,db,action);
    }

    private static JSONArray normalizedMissing(ResultProposalEngine.Proposal p,ResultProposalEngine.Target target){LinkedHashSet<String> xs=new LinkedHashSet<>();for(int i=0;i<p.missing.length();i++){String x=p.missing.optString(i,"").trim();if(!x.isEmpty())xs.add(x);}if("PROJECT_LINK".equals(p.actionType)){xs.remove("confirmed project selection");if(target.sourceItemId<=0)xs.add("source capture");}if("OPEN_APP".equals(p.actionType)){String pkg=p.payload.optString("package","").trim();if(pkg.isEmpty())xs.add("exact app package");}JSONArray out=new JSONArray();for(String x:xs)out.put(x);return out;}
    public static boolean cloudAllowedForMemory(Activity a,KnowledgeItem k){if(a==null||k==null)return false;try{return CloudEvidencePolicy.canSend(a,k);}catch(Throwable ignored){return false;}}
    private static String safeKey(String s){String x=s==null?"":s.replaceAll("[^A-Za-z0-9_-]","");return x.isEmpty()?"result":x;}
    private static String clip(String s,int n){String x=s==null?"":s.replaceAll("\\s+"," ").trim();return x.length()>n?x.substring(0,n)+"…":x;}
}
