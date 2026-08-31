package com.kareem.cortex;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.provider.CalendarContract;
import android.view.View;
import android.widget.Toast;
import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * Approval-first execution bridge for structured Brain suggestions.
 * No model output mutates an external system directly: Cortex previews, the user approves,
 * then the owning app gets a draft/edit surface. Local cognitive tasks are created only after approval.
 */
public final class CortexActionDispatcher {
    private CortexActionDispatcher(){}

    public static void preview(Activity a,VaultDb db,BrainActionStore.Action action){
        if(a==null||db==null||action==null)return;
        StringBuilder message=new StringBuilder();
        message.append(friendlyType(action.type)).append("\n").append(action.title);
        if(!action.evidenceExcerpt.isEmpty())message.append("\n\nFrom Cortex evidence:\n").append(action.evidenceExcerpt);
        if(action.missing.length()>0)message.append("\n\nNeeds: ").append(join(action.missing));
        message.append("\n\nNothing will be sent or changed until you confirm the next step.");
        AlertDialog.Builder b=new AlertDialog.Builder(a).setTitle(action.ready()?"Prepare this action?":"This action needs details").setMessage(message.toString()).setNegativeButton("Cancel",null);
        if(action.ready())b.setPositiveButton(primaryLabel(action.type),(d,w)->executeApproved(a,db,action));
        else b.setPositiveButton("Complete details",(d,w)->completeInBrain(a,action));
        b.setNeutralButton("Dismiss suggestion",(d,w)->{BrainActionStore.markStatus(db,action.rowId,"DISMISSED");toast(a,"Suggestion dismissed");});
        View appRoot=a.findViewById(android.R.id.content);final int previousImportance=appRoot==null?View.IMPORTANT_FOR_ACCESSIBILITY_AUTO:appRoot.getImportantForAccessibility();
        AlertDialog dialog=b.create();dialog.setCanceledOnTouchOutside(false);
        dialog.setOnShowListener(d->{if(appRoot!=null)appRoot.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);});
        dialog.setOnDismissListener(d->{if(appRoot!=null)appRoot.setImportantForAccessibility(previousImportance);});
        dialog.show();
    }

    private static void executeApproved(Activity a,VaultDb db,BrainActionStore.Action x){
        try{
            if(localType(x.type)){createLocal(db,x);toast(a,"Added to Cortex");return;}
            if("CALENDAR_EVENT".equals(x.type)||"REMINDER".equals(x.type)){
                long when=parseWhen(x.payload,"CALENDAR_EVENT".equals(x.type)?"start_time":"trigger_time");
                if(when<=0)when=parseWhen(x.payload,"due_at");
                if(when<=0)when=parseDateTime(x.payload.optString("date",x.payload.optString("due_date","")),x.payload.optString("time",x.payload.optString("due_time","")));
                if(when<=0){toast(a,"Cortex still needs an exact date and time");completeInBrain(a,x);return;}
                String title=first(x.payload,"event_title","reminder_text","title");if(title.isEmpty())title=x.title;
                String body=first(x.payload,"description","body");if(body.isEmpty())body=x.evidenceExcerpt;
                if(CortexActionExecutor.calendarDraft(a,title,body,when))BrainActionStore.markStatus(db,x.rowId,"PREPARED");return;
            }
            if("CALENDAR_RESCHEDULE".equals(x.type)){completeInBrain(a,x);return;}
            if("CALL".equals(x.type)){String number=x.payload.optString("phone_number","").trim();if(!number.isEmpty()){a.startActivity(new Intent(Intent.ACTION_DIAL,Uri.parse("tel:"+Uri.encode(number))));BrainActionStore.markStatus(db,x.rowId,"PREPARED");}return;}
            if("MESSAGE_DRAFT".equals(x.type)){String to=first(x.payload,"phone_number","recipient"),body=first(x.payload,"body","message");if(CortexActionExecutor.messageDraft(a,to,body))BrainActionStore.markStatus(db,x.rowId,"PREPARED");return;}
            if("EMAIL_DRAFT".equals(x.type)){if(CortexActionExecutor.emailDraft(a,x.payload.optString("to",""),x.payload.optString("subject",x.title),first(x.payload,"body","message")))BrainActionStore.markStatus(db,x.rowId,"PREPARED");return;}
            if("PROJECT_LINK".equals(x.type)){if(x.sourceItemId>0)CortexActionExecutor.chooseProject(a,db,x.sourceItemId,()->BrainActionStore.markStatus(db,x.rowId,"DONE"));else completeInBrain(a,x);return;}
            if("WEB_SEARCH".equals(x.type)){if(CortexActionExecutor.searchWeb(a,x.payload.optString("query",x.title)))BrainActionStore.markStatus(db,x.rowId,"PREPARED");return;}
            if("OPEN_APP".equals(x.type)){String pkg=x.payload.optString("package","").trim();Intent i=pkg.isEmpty()?null:a.getPackageManager().getLaunchIntentForPackage(pkg);if(i!=null){a.startActivity(i);BrainActionStore.markStatus(db,x.rowId,"PREPARED");}else{toast(a,"Cortex needs the exact app before it can open it");completeInBrain(a,x);}return;}
            completeInBrain(a,x);
        }catch(Throwable e){toast(a,"Action stopped safely: "+safe(e.getMessage()));}
    }

    private static boolean localType(String type){return "TASK".equals(type)||"FOLLOW_UP".equals(type)||"WAIT_FOR".equals(type)||"KNOWLEDGE_NOTE".equals(type);}
    private static void createLocal(VaultDb db,BrainActionStore.Action x)throws Exception{
        String kind="TASK";if("WAIT_FOR".equals(x.type))kind="WAITING";else if("KNOWLEDGE_NOTE".equals(x.type))kind="NOTE";else if("FOLLOW_UP".equals(x.type))kind="FOLLOW_UP";
        JSONObject meta=new JSONObject().put("created_from","brain_action").put("job_id",x.jobId).put("action_key",x.key).put("action_type",x.type).put("source_item_id",x.sourceItemId).put("payload",x.payload);
        String body=first(x.payload,"task_name","body","note","details");if(body.isEmpty())body=x.evidenceExcerpt;
        long id=CognitiveStore.addDerived(db,kind,x.title,body,"open",x.confidence,75,Fingerprint.text("brain-action|"+x.jobId+"|"+x.key),meta.toString());
        if(id<=0)throw new IllegalStateException("Could not create Cortex action");
        if(x.sourceItemId>0)CognitiveStore.linkChecked(db,"memory",x.sourceItemId,"derived",id,"suggested_action",Math.max(.5,x.confidence),new JSONObject().put("job_id",x.jobId).put("action_key",x.key).put("user_confirmed",true).toString());
        CognitiveStore.feedback(db,"derived",id,"created_from_brain_action",new JSONObject().put("job_id",x.jobId).put("action_key",x.key).toString(),"brain_actions_001");
        BrainActionStore.markStatus(db,x.rowId,"DONE");
    }

    private static void completeInBrain(Activity a,BrainActionStore.Action x){String prompt="Complete this Cortex action without inventing missing information. Action: "+x.title+" ["+x.type+"]"+(x.missing.length()>0?". Missing: "+join(x.missing):"")+". Ask only for the minimum information needed, then return an executable action suggestion.";CortexActionExecutor.openBrain(a,x.sourceItemId,prompt);}

    private static long parseWhen(JSONObject p,String key){String s=p.optString(key,"").trim();if(s.isEmpty())return 0;String[] formats={"yyyy-MM-dd'T'HH:mm:ssXXX","yyyy-MM-dd'T'HH:mmXXX","yyyy-MM-dd'T'HH:mm:ss","yyyy-MM-dd'T'HH:mm","yyyy-MM-dd HH:mm"};for(String f:formats)try{SimpleDateFormat d=new SimpleDateFormat(f,Locale.US);d.setLenient(false);return d.parse(s).getTime();}catch(Exception ignored){}return 0;}
    private static long parseDateTime(String date,String time){date=safe(date);time=safe(time);if(date.isEmpty()||time.isEmpty())return 0;try{SimpleDateFormat d=new SimpleDateFormat("yyyy-MM-dd HH:mm",Locale.US);d.setLenient(false);return d.parse(date+" "+time).getTime();}catch(Exception e){return 0;}}
    private static String first(JSONObject p,String... keys){for(String k:keys){String x=p.optString(k,"").trim();if(!x.isEmpty())return x;}return"";}
    private static String friendlyType(String t){return safe(t).replace('_',' ').toLowerCase(Locale.ROOT);}
    private static String primaryLabel(String t){if(localType(t))return"Add to Cortex";if("CALL".equals(t))return"Open dialer";if("WEB_SEARCH".equals(t))return"Search";if("OPEN_APP".equals(t))return"Open app";return"Prepare draft";}
    private static String join(org.json.JSONArray a){ArrayList<String> xs=new ArrayList<>();for(int i=0;i<a.length();i++){String x=a.optString(i,"").trim();if(!x.isEmpty())xs.add(x);}return android.text.TextUtils.join(", ",xs);}
    private static void toast(Activity a,String s){try{Toast.makeText(a,s,Toast.LENGTH_SHORT).show();}catch(Throwable ignored){}}
    private static String safe(String s){return s==null?"":s.trim();}
}
