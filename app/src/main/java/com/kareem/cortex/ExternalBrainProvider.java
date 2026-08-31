package com.kareem.cortex;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.SystemClock;
import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Provider-agnostic cloud route for Brain.
 * OpenRouter/Ox Alpha is primary when configured; Gemini remains fallback and vision-safe compatibility.
 * Never invoked from Your Data mode.
 */
public final class ExternalBrainProvider {
    private static final String OPENROUTER_ENDPOINT="https://openrouter.ai/api/v1/chat/completions";
    private ExternalBrainProvider(){}

    public static final class Result {
        public final String text,rawResponse,model,provider;
        public final long durationMs;
        Result(String t,String raw,long ms,String m,String p){text=t;rawResponse=raw;durationMs=ms;model=m;provider=p;}
    }

    public static final class HealthReport {
        public final boolean configured,ok;
        public final String provider,model,status,error,responsePreview;
        public final int httpCode;
        public final long latencyMs,checkedAt;
        HealthReport(boolean configured,boolean ok,String provider,String model,String status,String error,String preview,int code,long latency){
            this.configured=configured;this.ok=ok;this.provider=provider;this.model=model;this.status=status;this.error=error;this.responsePreview=preview;this.httpCode=code;this.latencyMs=latency;this.checkedAt=System.currentTimeMillis();
        }
        public String human(){
            StringBuilder b=new StringBuilder();
            b.append("Provider: ").append(provider).append('\n').append("Model: ").append(model).append('\n').append("Configured: ").append(configured?"YES":"NO").append('\n').append("Request: ").append(ok?"PASS":"FAIL");
            if(httpCode>0)b.append(" • HTTP ").append(httpCode);if(latencyMs>0)b.append(" • ").append(latencyMs).append(" ms");
            if(!empty(status))b.append("\nStatus: ").append(status);if(!empty(error))b.append("\nError: ").append(error);if(!empty(responsePreview))b.append("\nResponse: ").append(responsePreview);return b.toString();
        }
    }

    public static final class ProviderException extends IOException {
        public final int httpCode;public final String model,provider;public final long latencyMs;
        ProviderException(String message,int code,String model,String provider,long latency){super(message);this.httpCode=code;this.model=model;this.provider=provider;this.latencyMs=latency;}
        public boolean rateLimited(){return httpCode==429;}public boolean retryable(){return httpCode==408||httpCode==429||httpCode>=500;}
    }

    public static String activeProviderId(Context context){return OpenRouterKeyStore.has(context)?"openrouter":(GeminiKeyStore.has(context)?"gemini":"openrouter");}
    public static String activeModel(Context context){return OpenRouterKeyStore.has(context)?OpenRouterModelConfig.generationModel(context):GeminiModelConfig.generationModel(context);}
    public static boolean configured(Context context){return OpenRouterKeyStore.has(context)||GeminiKeyStore.has(context);}
    public static String configurationHint(Context context){return configured(context)?activeProviderId(context)+" · "+activeModel(context):"Configure OpenRouter in Settings";}

    public static Result ask(Context context,String question,GroundedAnswer grounded,boolean combined)throws Exception{return ask(context,question,grounded,combined,null,"");}
    public static Result ask(Context context,String question,GroundedAnswer grounded,boolean combined,KnowledgeItem focal)throws Exception{return ask(context,question,grounded,combined,focal,"");}

    /** OpenRouter is primary. If it fails and Gemini is configured, Gemini is the provider fallback. */
    public static Result ask(Context context,String question,GroundedAnswer grounded,boolean combined,KnowledgeItem focal,String phoneContext)throws Exception{
        if(OpenRouterKeyStore.has(context)){
            try{return askOpenRouter(context,question,grounded,combined,focal,phoneContext);}catch(Throwable primary){
                if(GeminiKeyStore.has(context))try{return askGemini(context,question,grounded,combined,focal,phoneContext);}catch(Throwable ignored){}
                if(primary instanceof Exception)throw (Exception)primary;throw new IOException(primary);
            }
        }
        if(GeminiKeyStore.has(context))return askGemini(context,question,grounded,combined,focal,phoneContext);
        throw new IllegalStateException("No external Brain provider configured. Add an OpenRouter API key in Settings.");
    }

    public static HealthReport healthCheck(Context context){
        if(OpenRouterKeyStore.has(context))return healthOpenRouter(context);
        if(GeminiKeyStore.has(context))return healthGemini(context);
        return new HealthReport(false,false,"openrouter",OpenRouterModelConfig.generationModel(context),"API key missing","OpenRouter API key not configured","",0,0);
    }

    private static Result askOpenRouter(Context context,String question,GroundedAnswer grounded,boolean combined,KnowledgeItem focal,String phoneContext)throws Exception{
        String key=OpenRouterKeyStore.get(context);if(key.isEmpty())throw new IllegalStateException("OpenRouter API key not configured");
        String model=OpenRouterModelConfig.generationModel(context);
        KnowledgeItem requestFocal=combined?focal:null;
        GroundedAnswer requestGrounded=grounded;
        String requestPhone=combined?clip(phoneContext,6000):"";
        String prompt=combined?combinedPrompt(question,requestGrounded,requestFocal,requestPhone):externalPrompt(question);

        Object userContent=prompt;
        if(requestFocal!=null&&isImage(requestFocal)){
            byte[] jpeg=prepareImage(requestFocal);
            if(jpeg.length>0){
                JSONArray parts=new JSONArray();
                parts.put(new JSONObject().put("type","text").put("text",prompt));
                parts.put(new JSONObject().put("type","image_url").put("image_url",new JSONObject().put("url","data:image/jpeg;base64,"+Base64.encodeToString(jpeg,Base64.NO_WRAP))));
                userContent=parts;
            }
        }
        JSONArray messages=new JSONArray();
        messages.put(new JSONObject().put("role","system").put("content","You are Cortex Brain. Be useful, direct, context-aware, and preserve Egyptian Arabic/English code-switching naturally. Never reveal chain-of-thought."));
        messages.put(new JSONObject().put("role","user").put("content",userContent));
        JSONObject req=new JSONObject().put("model",model).put("messages",messages).put("max_tokens",1800);
        return requestOpenRouter(key,model,req);
    }

    private static HealthReport healthOpenRouter(Context context){
        String model=OpenRouterModelConfig.generationModel(context),key=OpenRouterKeyStore.get(context);
        if(key.isEmpty())return new HealthReport(false,false,"openrouter",model,"API key missing","OpenRouter API key not configured","",0,0);
        long started=SystemClock.elapsedRealtime();HttpURLConnection c=null;
        try{
            JSONArray messages=new JSONArray().put(new JSONObject().put("role","user").put("content","Cortex external-model health check. Reply with exactly: CORTEX_OK"));
            JSONObject req=new JSONObject().put("model",model).put("messages",messages).put("max_tokens",256);
            if(OpenRouterModelConfig.isOxAlpha(context))req.put("reasoning",new JSONObject().put("effort","low").put("exclude",true));
            c=openOpenRouter(key);write(c,req);int code=c.getResponseCode();String body=read(code>=200&&code<300?c.getInputStream():c.getErrorStream());long ms=SystemClock.elapsedRealtime()-started;
            String text="";if(code>=200&&code<300)try{text=extractOpenRouterText(new JSONObject(body)).trim();}catch(Exception ignored){}
            boolean ok=code>=200&&code<300&&!text.isEmpty();
            return new HealthReport(true,ok,"openrouter",model,ok?"Provider and configured model responded successfully":statusFor(code),ok?"":compact(body),clip(text,160),code,ms);
        }catch(Throwable e){return new HealthReport(true,false,"openrouter",model,"Network/provider request failed",e.getClass().getSimpleName()+": "+safe(e.getMessage()),"",0,SystemClock.elapsedRealtime()-started);}finally{if(c!=null)try{c.disconnect();}catch(Throwable ignored){}}
    }

    private static Result requestOpenRouter(String key,String model,JSONObject req)throws Exception{
        long started=SystemClock.elapsedRealtime();HttpURLConnection c=openOpenRouter(key);write(c,req);int code=c.getResponseCode();String body=read(code>=200&&code<300?c.getInputStream():c.getErrorStream());c.disconnect();long ms=SystemClock.elapsedRealtime()-started;
        if(code<200||code>=300)throw new ProviderException("OpenRouter Brain HTTP "+code+" ["+model+"]: "+compact(body),code,model,"openrouter",ms);
        String text=cleanModelText(extractOpenRouterText(new JSONObject(body)));
        if(text.isEmpty())throw new ProviderException("OpenRouter returned an empty answer ["+model+"]",200,model,"openrouter",ms);
        return new Result(text,body,ms,model,"openrouter");
    }

    private static HttpURLConnection openOpenRouter(String key)throws Exception{
        HttpURLConnection c=(HttpURLConnection)new URL(OPENROUTER_ENDPOINT).openConnection();c.setRequestMethod("POST");c.setDoOutput(true);c.setConnectTimeout(20000);c.setReadTimeout(45000);
        c.setRequestProperty("Content-Type","application/json");c.setRequestProperty("Accept","application/json");c.setRequestProperty("Authorization","Bearer "+key);c.setRequestProperty("X-Title","Cortex");return c;
    }

    private static String extractOpenRouterText(JSONObject root){
        JSONArray choices=root.optJSONArray("choices");if(choices==null||choices.length()==0)return"";JSONObject choice=choices.optJSONObject(0);if(choice==null)return"";JSONObject message=choice.optJSONObject("message");if(message==null)return"";Object content=message.opt("content");
        if(content instanceof String)return (String)content;
        if(content instanceof JSONArray){StringBuilder b=new StringBuilder();JSONArray a=(JSONArray)content;for(int i=0;i<a.length();i++){Object part=a.opt(i);if(part instanceof JSONObject){String t=((JSONObject)part).optString("text","");if(!t.isEmpty()){if(b.length()>0)b.append('\n');b.append(t);}}else if(part instanceof String){if(b.length()>0)b.append('\n');b.append((String)part);}}return b.toString();}
        return content==null?"":String.valueOf(content);
    }

    private static Result askGemini(Context context,String question,GroundedAnswer grounded,boolean combined,KnowledgeItem focal,String phoneContext)throws Exception{
        String key=GeminiKeyStore.get(context);if(key.isEmpty())throw new IllegalStateException("Gemini API key not configured");String model=GeminiModelConfig.generationModel(context);
        KnowledgeItem requestFocal=combined?focal:null;GroundedAnswer requestGrounded=grounded;String requestPhone=combined?clip(phoneContext,6000):"";
        String prompt=combined?combinedPrompt(question,requestGrounded,requestFocal,requestPhone):externalPrompt(question);JSONArray parts=new JSONArray().put(new JSONObject().put("text",prompt));
        if(requestFocal!=null&&isImage(requestFocal)){byte[] jpeg=prepareImage(requestFocal);if(jpeg.length>0)parts.put(new JSONObject().put("inlineData",new JSONObject().put("mimeType","image/jpeg").put("data",Base64.encodeToString(jpeg,Base64.NO_WRAP))));}
        return requestGemini(key,model,parts,1200,0.25);
    }

    private static HealthReport healthGemini(Context context){
        String model=GeminiModelConfig.generationModel(context),key=GeminiKeyStore.get(context);if(key.isEmpty())return new HealthReport(false,false,"gemini",model,"API key missing","Gemini API key not configured","",0,0);long started=SystemClock.elapsedRealtime();HttpURLConnection c=null;
        try{JSONArray parts=new JSONArray().put(new JSONObject().put("text","Cortex external-model health check. Reply with exactly: CORTEX_OK"));JSONArray contents=new JSONArray().put(new JSONObject().put("role","user").put("parts",parts));JSONObject cfg=new JSONObject().put("temperature",0).put("maxOutputTokens",24);JSONObject req=new JSONObject().put("contents",contents).put("generationConfig",cfg);c=(HttpURLConnection)new URL(geminiEndpoint(model,key)).openConnection();c.setRequestMethod("POST");c.setDoOutput(true);c.setConnectTimeout(15000);c.setReadTimeout(30000);c.setRequestProperty("Content-Type","application/json");c.setRequestProperty("Accept","application/json");write(c,req);int code=c.getResponseCode();String body=read(code>=200&&code<300?c.getInputStream():c.getErrorStream());long ms=SystemClock.elapsedRealtime()-started;String text="";if(code>=200&&code<300)try{text=extractGeminiText(new JSONObject(body)).trim();}catch(Exception ignored){}boolean ok=code>=200&&code<300&&!text.isEmpty();return new HealthReport(true,ok,"gemini",model,ok?"Provider and configured model responded successfully":statusFor(code),ok?"":compact(body),clip(text,160),code,ms);}catch(Throwable e){return new HealthReport(true,false,"gemini",model,"Network/provider request failed",e.getClass().getSimpleName()+": "+safe(e.getMessage()),"",0,SystemClock.elapsedRealtime()-started);}finally{if(c!=null)try{c.disconnect();}catch(Throwable ignored){}}
    }

    private static Result requestGemini(String key,String model,JSONArray parts,int maxTokens,double temperature)throws Exception{
        JSONArray contents=new JSONArray().put(new JSONObject().put("role","user").put("parts",parts));JSONObject cfg=new JSONObject().put("temperature",temperature).put("maxOutputTokens",maxTokens);JSONObject req=new JSONObject().put("contents",contents).put("generationConfig",cfg);long started=SystemClock.elapsedRealtime();HttpURLConnection c=(HttpURLConnection)new URL(geminiEndpoint(model,key)).openConnection();c.setRequestMethod("POST");c.setDoOutput(true);c.setConnectTimeout(20000);c.setReadTimeout(45000);c.setRequestProperty("Content-Type","application/json");c.setRequestProperty("Accept","application/json");write(c,req);int code=c.getResponseCode();String body=read(code>=200&&code<300?c.getInputStream():c.getErrorStream());c.disconnect();long ms=SystemClock.elapsedRealtime()-started;if(code<200||code>=300)throw new ProviderException("Gemini Brain HTTP "+code+" ["+model+"]: "+compact(body),code,model,"gemini",ms);String text=cleanModelText(extractGeminiText(new JSONObject(body)));if(text.isEmpty())throw new ProviderException("Gemini returned an empty answer ["+model+"]",200,model,"gemini",ms);return new Result(text,body,ms,model,"gemini");
    }

    private static String externalPrompt(String q){return "You are Brain, the general AI surface inside Cortex. Answer the user's question using general knowledge only. No private Cortex memory has been supplied in this route. If the answer depends on current live information you cannot verify, say that clearly rather than pretending it is current. Preserve Egyptian Arabic and English code-switching naturally. Be concise, useful, and do not reveal chain-of-thought.\n\nUSER QUESTION:\n"+q;}

    private static String combinedPrompt(String q,GroundedAnswer g,KnowledgeItem focal,String phoneContext){
        StringBuilder s=new StringBuilder();s.append("You are Brain, the AI surface inside Cortex. The user explicitly selected Combined mode. Use the selected private Cortex evidence together with general knowledge. Distinguish evidence from general knowledge. Never invent missing private facts. If a focal image is attached, inspect the IMAGE ITSELF as primary visual evidence; OCR is supporting evidence and may be wrong. Recent phone context, when supplied, is ephemeral situational context rather than durable memory. Preserve Egyptian Arabic and English code-switching naturally. Do not reveal chain-of-thought.\n\nUSER QUESTION:\n").append(q).append("\n\n");
        if(focal!=null){s.append("FOCAL CORTEX CAPTURE [THIS]:\nType: ").append(safe(focal.type)).append("\nTitle: ").append(safe(focal.title)).append("\n");String body=!empty(focal.summary)?focal.summary:(!empty(focal.extractedText)?focal.extractedText:focal.rawText);if(!empty(body))s.append("Supporting evidence: ").append(clip(body,2400)).append("\n");s.append("\n");}
        if(!empty(phoneContext))s.append("RECENT PHONE CONTEXT [EPHEMERAL]:\n").append(phoneContext).append("\n\n");
        s.append("SELECTED CORTEX EVIDENCE:\n");int n=g==null?0:Math.min(12,g.sources.size()),perSource=n<=0?0:Math.min(1800,Math.max(600,10000/n));
        for(int i=0;i<n;i++){KnowledgeItem k=g.sources.get(i).item;String body=!empty(k.summary)?k.summary:(!empty(k.extractedText)?k.extractedText:k.rawText);body=safe(body).replace('\u0000',' ').trim();if(body.length()>perSource)body=body.substring(0,perSource)+"…";s.append("[M").append(i+1).append("] ").append(empty(k.title)?"Memory":k.title).append("\n").append(body).append("\n\n");}
        if(n==0)s.append("(No additional relevant private evidence was found.)\n\n");s.append("Answer the question directly. Cite [M1], [M2], etc when using retrieved private evidence. Refer to the focal capture as 'this capture' when relevant. Treat phone context as recent situational evidence and do not turn it into a permanent fact. Suggest executable next steps when useful, but do not claim an external action was executed unless it actually was.");return s.toString();
    }

    private static byte[] prepareImage(KnowledgeItem focal){Bitmap b=null;try{File f=new File(safe(focal.attachmentPath));b=SafeImageDecoder.decode(f,1800,3_000_000L);if(b==null)return new byte[0];ByteArrayOutputStream out=new ByteArrayOutputStream();b.compress(Bitmap.CompressFormat.JPEG,86,out);byte[] bytes=out.toByteArray();if(bytes.length<=3_500_000)return bytes;out.reset();b.compress(Bitmap.CompressFormat.JPEG,72,out);return out.size()<=4_500_000?out.toByteArray():new byte[0];}catch(Throwable e){return new byte[0];}finally{if(b!=null)try{b.recycle();}catch(Throwable ignored){}}}
    private static boolean isImage(KnowledgeItem k){return k!=null&&("IMAGE".equals(k.type)||"SCREENSHOT".equals(k.type));}
    private static String geminiEndpoint(String model,String key)throws Exception{return "https://generativelanguage.googleapis.com/v1beta/models/"+model+":generateContent?key="+java.net.URLEncoder.encode(key,"UTF-8");}
    private static String extractGeminiText(JSONObject root){JSONArray cs=root.optJSONArray("candidates");if(cs==null||cs.length()==0)return"";JSONObject c=cs.optJSONObject(0);if(c==null)return"";JSONObject content=c.optJSONObject("content");if(content==null)return"";JSONArray parts=content.optJSONArray("parts");if(parts==null)return"";StringBuilder b=new StringBuilder();for(int i=0;i<parts.length();i++){JSONObject p=parts.optJSONObject(i);if(p==null)continue;String t=p.optString("text","");if(!t.isEmpty()){if(b.length()>0)b.append('\n');b.append(t);}}return b.toString();}
    private static String cleanModelText(String text){String x=safe(text).trim();return x.replaceAll("(?s)<think>.*?</think>","").replaceAll("^```(?:text|markdown)?\\s*","").replaceAll("```$","").trim();}
    private static void write(HttpURLConnection c,JSONObject req)throws Exception{try(OutputStream out=c.getOutputStream()){out.write(req.toString().getBytes(StandardCharsets.UTF_8));}}
    private static String read(InputStream in)throws Exception{if(in==null)return"";try(InputStream x=in;ByteArrayOutputStream b=new ByteArrayOutputStream()){byte[] buf=new byte[8192];for(int n;(n=x.read(buf))!=-1;)b.write(buf,0,n);return b.toString("UTF-8");}}
    private static String statusFor(int code){if(code==400)return"Provider rejected the request/model configuration";if(code==401||code==403)return"Authentication/permission failed";if(code==404)return"Configured model was not found";if(code==429)return"Provider quota/rate limit reached";if(code>=500)return"Provider service error";return"Provider returned HTTP "+code;}
    private static String compact(String s){String x=safe(s).replaceAll("\\s+"," ").trim();return x.length()>500?x.substring(0,500)+"…":x;}
    private static String clip(String s,int n){String x=safe(s);return x.length()<=n?x:x.substring(0,n)+"…";}
    private static String safe(String s){return s==null?"":s;}private static boolean empty(String s){return s==null||s.trim().isEmpty();}
}
