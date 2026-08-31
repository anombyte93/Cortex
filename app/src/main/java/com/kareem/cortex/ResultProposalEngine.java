package com.kareem.cortex;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Handler;
import android.os.Looper;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Universal micro-level proposal fabric.
 * Every result surface can submit the exact result it is showing. Requests are cached by result
 * fingerprint and automatically batched so ten visible cards do not become ten model calls.
 * Suggestions are always model-generated; when no eligible model is available, no synthetic
 * fallback suggestion is invented.
 */
public final class ResultProposalEngine {
    private ResultProposalEngine(){}

    public static final class Target {
        public final String surface,resultKey,title,text,sourceType;
        public final long sourceItemId;
        public final boolean cloudAllowed;
        public Target(String surface,String resultKey,String title,String text,long sourceItemId,String sourceType,boolean cloudAllowed){
            this.surface=n(surface);this.resultKey=n(resultKey);this.title=n(title);this.text=n(text);this.sourceItemId=Math.max(0,sourceItemId);this.sourceType=n(sourceType);this.cloudAllowed=cloudAllowed;
        }
        String fingerprint(){return Fingerprint.text("proposal-v2|"+surface+"|"+resultKey+"|"+sourceItemId+"|"+title+"|"+text);}
    }

    public static final class Proposal {
        public final String id,title,why,execution,prompt,actionType;
        public final double confidence;
        public final JSONObject payload;
        public final JSONArray missing;
        Proposal(String id,String title,String why,String execution,String prompt,String actionType,double confidence,JSONObject payload,JSONArray missing){
            this.id=n(id);this.title=n(title);this.why=n(why);this.execution=n(execution);this.prompt=n(prompt);this.actionType=n(actionType);this.confidence=Math.max(0,Math.min(1,confidence));this.payload=payload==null?new JSONObject():payload;this.missing=missing==null?new JSONArray():missing;
        }
        public boolean isAction(){return "ACTION".equals(execution);}
    }

    public interface Callback { void done(ArrayList<Proposal> proposals,String provider,String error); }

    private static final long MODEL_TIMEOUT_MS=45_000L;
    private static final Handler MAIN=new Handler(Looper.getMainLooper());
    // Cache/batching bookkeeping must never be held hostage by a slow network/model call.
    private static final ScheduledExecutorService QUEUE=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"cortex-proposal-queue");t.setPriority(Thread.NORM_PRIORITY-1);return t;});
    // A retry gets a genuinely fresh execution lane instead of sitting behind the timed-out request.
    private static final ExecutorService MODELS=Executors.newFixedThreadPool(3,r->{Thread t=new Thread(r,"cortex-proposal-model");t.setPriority(Thread.NORM_PRIORITY-1);return t;});
    private static final Object LOCAL_MODEL_LOCK=new Object();
    private static final LinkedHashMap<String,Pending> PENDING=new LinkedHashMap<>();
    private static boolean flushScheduled=false;
    private static final Set<String> ACTION_TYPES=new HashSet<>(Arrays.asList(
            "TASK","REMINDER","CALENDAR_EVENT","CALENDAR_RESCHEDULE","CALL","MESSAGE_DRAFT","EMAIL_DRAFT",
            "PROJECT_LINK","FOLLOW_UP","WAIT_FOR","KNOWLEDGE_NOTE","WEB_SEARCH","OPEN_APP"));

    private static final class Pending {
        final Context app;final Target target;final String fingerprint;final ArrayList<Callback> callbacks=new ArrayList<>();
        Pending(Context app,Target target,String fingerprint,Callback cb){this.app=app;this.target=target;this.fingerprint=fingerprint;if(cb!=null)callbacks.add(cb);}
    }
    private static final class Cached {final ArrayList<Proposal> proposals;final String provider;Cached(ArrayList<Proposal> p,String provider){proposals=p;this.provider=provider;}}
    private static final class ModelOutput {final String raw,provider;ModelOutput(String r,String p){raw=n(r);provider=n(p);}}

    public static void request(Context context,Target target,Callback callback){enqueue(context,target,callback,true);}
    /** Explicit retry path: skip cache and do not join an older same-fingerprint request. */
    public static void requestFresh(Context context,Target target,Callback callback){enqueue(context,target,callback,false);}

    private static void enqueue(Context context,Target target,Callback callback,boolean useCache){
        if(context==null||target==null||target.text.trim().isEmpty()){deliver(callback,new ArrayList<>(),"","empty result");return;}
        Context app=context.getApplicationContext();String fp=target.fingerprint();
        QUEUE.execute(()->{
            if(useCache){
                VaultDb db=null;
                try{db=new VaultDb(app);ensure(db);Cached cached=load(db,fp);if(cached!=null){deliver(callback,cached.proposals,cached.provider,"");return;}}
                catch(Throwable ignored){}finally{if(db!=null)try{db.close();}catch(Throwable ignored){}}
                Pending existing=PENDING.get(fp);if(existing!=null){if(callback!=null)existing.callbacks.add(callback);return;}
                PENDING.put(fp,new Pending(app,target,fp,callback));
            }else{
                PENDING.put(fp+"#fresh-"+System.nanoTime(),new Pending(app,target,fp,callback));
            }
            if(!flushScheduled){flushScheduled=true;QUEUE.schedule(ResultProposalEngine::flush,180,TimeUnit.MILLISECONDS);}
        });
    }

    private static void flush(){
        flushScheduled=false;if(PENDING.isEmpty())return;ArrayList<Pending> all=new ArrayList<>(PENDING.values());PENDING.clear();
        ArrayList<Pending> cloud=new ArrayList<>(),local=new ArrayList<>();for(Pending p:all)(p.target.cloudAllowed?cloud:local).add(p);
        processInChunks(cloud,4,true);processInChunks(local,4,false);
    }

    private static void processInChunks(ArrayList<Pending> xs,int size,boolean cloudAllowed){
        for(int start=0;start<xs.size();start+=size){int end=Math.min(xs.size(),start+size);ArrayList<Pending> batch=new ArrayList<>(xs.subList(start,end));startBatch(batch,cloudAllowed);}
    }

    private static void startBatch(ArrayList<Pending> batch,boolean cloudAllowed){
        if(batch.isEmpty())return;Context app=batch.get(0).app;String prompt=prompt(batch),hint=providerHint(app,cloudAllowed);AtomicBoolean completed=new AtomicBoolean(false);AtomicReference<ScheduledFuture<?>> timeoutRef=new AtomicReference<>();
        final Future<?> future;
        try{
            future=MODELS.submit(()->{
                ModelOutput mo=null;String error="";
                try{mo=runModel(app,prompt,cloudAllowed);}catch(Throwable e){error=e.getClass().getSimpleName()+(e.getMessage()==null?"":": "+e.getMessage());}
                if(!completed.compareAndSet(false,true))return;ScheduledFuture<?> timeout=timeoutRef.get();if(timeout!=null)timeout.cancel(false);ModelOutput result=mo;String failure=error;QUEUE.execute(()->finishBatch(batch,result,hint,failure));
            });
        }catch(RejectedExecutionException e){finishBatch(batch,null,hint,"proposal executor unavailable");return;}
        ScheduledFuture<?> timeout=QUEUE.schedule(()->{
            if(!completed.compareAndSet(false,true))return;future.cancel(true);finishBatch(batch,null,hint,"timeout: model did not finish within 45 seconds");
        },MODEL_TIMEOUT_MS,TimeUnit.MILLISECONDS);timeoutRef.set(timeout);if(completed.get())timeout.cancel(false);
    }

    private static void finishBatch(ArrayList<Pending> batch,ModelOutput mo,String providerHint,String error){
        Map<String,ArrayList<Proposal>> parsed=mo==null?Collections.emptyMap():parse(mo.raw,batch.size());
        VaultDb db=null;try{db=new VaultDb(batch.get(0).app);ensure(db);}catch(Throwable ignored){}
        for(int i=0;i<batch.size();i++){
            Pending p=batch.get(i);ArrayList<Proposal> proposals=parsed.get("R"+(i+1));if(proposals==null)proposals=new ArrayList<>();
            if(mo!=null&&db!=null)try{save(db,p.fingerprint,p.target,proposals,mo.provider);}catch(Throwable ignored){}
            String provider=mo==null?n(providerHint):mo.provider;for(Callback cb:p.callbacks)deliver(cb,proposals,provider,error);
        }
        if(db!=null)try{db.close();}catch(Throwable ignored){}
    }

    private static ModelOutput runModel(Context app,String prompt,boolean cloudAllowed)throws Exception{
        Throwable cloudError=null;
        if(cloudAllowed&&ExternalBrainProvider.configured(app)){
            try{
                GroundedAnswer empty=new GroundedAnswer(prompt,"",0,new ArrayList<SemanticHit>(),new ArrayList<String>(),new ArrayList<String>());
                ExternalBrainProvider.Result r=ExternalBrainProvider.ask(app,prompt,empty,false,null,"");
                return new ModelOutput(r.text,r.provider+" · "+r.model);
            }catch(Throwable e){cloudError=e;}
        }
        if(LocalLlmRuntime.ready(app)&&LocalModelManager.verified(app)){
            synchronized(LOCAL_MODEL_LOCK){
                LocalLlmBridge.CompletionResult r=LocalLlmBridge.completeCached(LocalModelManager.modelFile(app).getAbsolutePath(),prompt,
                        "You are Cortex Proposal Router. Return only the requested JSON. Suggest useful next moves specific to each result. Never invent private facts. /no_think",1200);
                return new ModelOutput(r.getText(),"local-qwen");
            }
        }
        if(cloudError instanceof Exception)throw (Exception)cloudError;
        if(cloudAllowed&&!ExternalBrainProvider.configured(app))throw new IllegalStateException("No external proposal model configured and local model is not ready");
        throw new IllegalStateException("Local proposal model is not ready for this private result");
    }

    private static String providerHint(Context app,boolean cloudAllowed){
        try{if(cloudAllowed&&ExternalBrainProvider.configured(app))return ExternalBrainProvider.activeProviderId(app)+" · "+ExternalBrainProvider.activeModel(app);if(LocalLlmRuntime.ready(app)&&LocalModelManager.verified(app))return"local-qwen";}catch(Throwable ignored){}return"";
    }

    private static String prompt(ArrayList<Pending> batch){
        StringBuilder b=new StringBuilder();
        b.append("CORTEX_MICRO_PROPOSALS_V2\nReturn ONLY one valid JSON object, no markdown fences.\n")
         .append("For EACH result below, think about that exact result and propose 1-3 genuinely useful next moves. These must be content-specific, not a fixed toolbar. Do not suggest generic Export/Share/Copy unless the content itself makes that unusually useful. Zero proposals is allowed when there is no meaningful next move.\n")
         .append("Good proposal examples include: turn an obligation into a task/follow-up; study or learn a topic; compare options; extract decisions/questions/checklists; connect to a person/project; research a product/topic; prepare questions for a meeting/doctor; track something; create a calendar/reminder only when timing is actually present.\n")
         .append("Never invent dates, times, people, projects, phone numbers, emails, calendar IDs, package IDs, diagnoses, prices, or other missing facts. If an executable action lacks required data, include missing_fields rather than guessing.\n")
         .append("Execution must be either BRAIN_PROMPT or ACTION. BRAIN_PROMPT means the proposal continues reasoning/study/analysis in Cortex Brain and requires a concrete next_prompt. ACTION uses the action schema below.\n")
         .append("Schema: {\"schema_version\":2,\"results\":[{\"result_ref\":\"R1\",\"proposals\":[{\"id\":\"p1\",\"title\":\"short user-facing suggestion\",\"why\":\"brief reason\",\"confidence\":0.0,\"execution\":\"BRAIN_PROMPT|ACTION\",\"next_prompt\":\"...\",\"action\":{\"type\":\"TASK|REMINDER|CALENDAR_EVENT|CALENDAR_RESCHEDULE|CALL|MESSAGE_DRAFT|EMAIL_DRAFT|PROJECT_LINK|FOLLOW_UP|WAIT_FOR|KNOWLEDGE_NOTE|WEB_SEARCH|OPEN_APP\",\"payload\":{},\"missing_fields\":[]}}]}]}\n\n");
        for(int i=0;i<batch.size();i++){Target t=batch.get(i).target;b.append("RESULT R").append(i+1).append("\nSurface: ").append(clip(t.surface,80)).append("\nType: ").append(clip(t.sourceType,80)).append("\nTitle: ").append(clip(t.title,180)).append("\nContent:\n").append(clip(t.text,1100)).append("\n\n");}
        return b.toString();
    }

    private static Map<String,ArrayList<Proposal>> parse(String raw,int expected){
        LinkedHashMap<String,ArrayList<Proposal>> out=new LinkedHashMap<>();JSONObject root=parseObject(raw);if(root==null)return out;JSONArray results=root.optJSONArray("results");if(results==null)return out;
        for(int i=0;i<results.length();i++){
            JSONObject r=results.optJSONObject(i);if(r==null)continue;String ref=n(r.optString("result_ref","")).trim().toUpperCase(Locale.ROOT);if(!ref.matches("R[1-9][0-9]*"))continue;JSONArray ps=r.optJSONArray("proposals");ArrayList<Proposal> list=new ArrayList<>();
            if(ps!=null)for(int j=0;j<ps.length()&&list.size()<3;j++){
                JSONObject p=ps.optJSONObject(j);if(p==null)continue;String title=n(p.optString("title","")).trim();if(title.isEmpty())continue;String execution=n(p.optString("execution","")).trim().toUpperCase(Locale.ROOT);if(!"BRAIN_PROMPT".equals(execution)&&!"ACTION".equals(execution))continue;
                String id=n(p.optString("id","p"+(j+1))).replaceAll("[^A-Za-z0-9_-]","");String why=n(p.optString("why","")).trim();double confidence=p.optDouble("confidence",0.68);String next=n(p.optString("next_prompt","")).trim();String actionType="";JSONObject payload=new JSONObject();JSONArray missing=new JSONArray();
                if("BRAIN_PROMPT".equals(execution)){if(next.isEmpty())continue;}
                else{
                    JSONObject a=p.optJSONObject("action");if(a==null)continue;actionType=n(a.optString("type","")).trim().toUpperCase(Locale.ROOT);if(!ACTION_TYPES.contains(actionType))continue;JSONObject supplied=a.optJSONObject("payload");if(supplied!=null)payload=supplied;missing=validatedMissing(actionType,payload,a.optJSONArray("missing_fields"));
                }
                list.add(new Proposal(id.isEmpty()?"p"+(j+1):id,title,why,execution,next,actionType,confidence,payload,missing));
            }
            out.put(ref,list);
        }
        return out;
    }

    private static JSONArray validatedMissing(String type,JSONObject p,JSONArray modelMissing){
        LinkedHashSet<String> m=new LinkedHashSet<>();if(modelMissing!=null)for(int i=0;i<modelMissing.length();i++){String x=n(modelMissing.optString(i,"")).trim();if(!x.isEmpty())m.add(x);}
        if("CALENDAR_EVENT".equals(type)){need(p,m,"event_title","title");if(!has(p,"start_time")&&!(has(p,"date")&&has(p,"time")))m.add("date/time");}
        else if("CALENDAR_RESCHEDULE".equals(type)){m.add("existing calendar event");if(!has(p,"new_start_time")&&!(has(p,"new_date")&&has(p,"new_time")))m.add("new date/time");}
        else if("REMINDER".equals(type)){if(!has(p,"trigger_time")&&!has(p,"due_at")&&!(has(p,"due_date")&&has(p,"due_time")))m.add("trigger date/time");}
        else if("CALL".equals(type)){if(!has(p,"phone_number"))m.add("phone number");}
        else if("MESSAGE_DRAFT".equals(type)){if(!has(p,"phone_number")&&!has(p,"recipient"))m.add("recipient");if(!has(p,"body")&&!has(p,"message"))m.add("message");}
        else if("EMAIL_DRAFT".equals(type)){if(!has(p,"to"))m.add("recipient email");}
        else if("PROJECT_LINK".equals(type)){m.add("confirmed project selection");}
        else if("WEB_SEARCH".equals(type)){if(!has(p,"query"))m.add("search query");}
        else if("OPEN_APP".equals(type)){if(!has(p,"package")&&!has(p,"app_name"))m.add("app");}
        JSONArray a=new JSONArray();for(String x:m)a.put(x);return a;
    }
    private static void need(JSONObject p,Set<String> m,String... alternatives){for(String x:alternatives)if(has(p,x))return;m.add(alternatives.length==0?"required detail":alternatives[0]);}
    private static boolean has(JSONObject p,String k){Object x=p.opt(k);return x!=null&&x!=JSONObject.NULL&&!String.valueOf(x).trim().isEmpty();}

    private static void ensure(VaultDb db){SQLiteDatabase s=db.getWritableDatabase();s.execSQL("CREATE TABLE IF NOT EXISTS result_proposals(result_fingerprint TEXT PRIMARY KEY,surface TEXT NOT NULL,result_key TEXT NOT NULL,source_item_id INTEGER DEFAULT 0,source_type TEXT DEFAULT '',title TEXT DEFAULT '',proposals_json TEXT NOT NULL DEFAULT '[]',provider TEXT DEFAULT '',generated_at INTEGER NOT NULL)");s.execSQL("CREATE INDEX IF NOT EXISTS idx_result_proposals_source ON result_proposals(source_item_id,generated_at)");}
    private static Cached load(VaultDb db,String fp){Cursor c=db.getReadableDatabase().query("result_proposals",new String[]{"proposals_json","provider"},"result_fingerprint=?",new String[]{fp},null,null,null,"1");if(!c.moveToFirst()){c.close();return null;}String json=c.getString(0),provider=c.getString(1);c.close();return new Cached(decode(json),n(provider));}
    private static void save(VaultDb db,String fp,Target t,ArrayList<Proposal> proposals,String provider){android.content.ContentValues v=new android.content.ContentValues();v.put("result_fingerprint",fp);v.put("surface",t.surface);v.put("result_key",t.resultKey);v.put("source_item_id",t.sourceItemId);v.put("source_type",t.sourceType);v.put("title",t.title);v.put("proposals_json",encode(proposals).toString());v.put("provider",provider);v.put("generated_at",System.currentTimeMillis());db.getWritableDatabase().insertWithOnConflict("result_proposals",null,v,SQLiteDatabase.CONFLICT_REPLACE);}

    private static JSONArray encode(ArrayList<Proposal> xs){JSONArray a=new JSONArray();for(Proposal p:xs){JSONObject o=new JSONObject();try{o.put("id",p.id).put("title",p.title).put("why",p.why).put("confidence",p.confidence).put("execution",p.execution).put("next_prompt",p.prompt).put("action_type",p.actionType).put("payload",p.payload).put("missing",p.missing);}catch(Exception ignored){}a.put(o);}return a;}
    private static ArrayList<Proposal> decode(String json){ArrayList<Proposal> out=new ArrayList<>();try{JSONArray a=new JSONArray(n(json));for(int i=0;i<a.length();i++){JSONObject o=a.optJSONObject(i);if(o==null)continue;out.add(new Proposal(o.optString("id",""),o.optString("title",""),o.optString("why",""),o.optString("execution",""),o.optString("next_prompt",""),o.optString("action_type",""),o.optDouble("confidence",0.68),o.optJSONObject("payload"),o.optJSONArray("missing")));}}catch(Exception ignored){}return out;}
    private static JSONObject parseObject(String raw){String x=n(raw).trim();if(x.startsWith("```")){int nl=x.indexOf('\n');if(nl>=0)x=x.substring(nl+1);int end=x.lastIndexOf("```");if(end>=0)x=x.substring(0,end);}int a=x.indexOf('{'),b=x.lastIndexOf('}');if(a<0||b<=a)return null;try{return new JSONObject(x.substring(a,b+1));}catch(Exception e){return null;}}
    private static void deliver(Callback cb,ArrayList<Proposal> p,String provider,String error){if(cb==null)return;ArrayList<Proposal> copy=new ArrayList<>(p);MAIN.post(()->{try{cb.done(copy,n(provider),n(error));}catch(Throwable ignored){}});}
    private static String clip(String s,int max){String x=n(s).replaceAll("\\s+"," ").trim();return x.length()>max?x.substring(0,max)+"…":x;}
    private static String n(String s){return s==null?"":s;}
}
