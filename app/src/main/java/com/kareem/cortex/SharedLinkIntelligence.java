package com.kareem.cortex;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;

/** Turns shared public HTTPS URLs into useful Cortex knowledge with explicit state. */
public final class SharedLinkIntelligence {
    private SharedLinkIntelligence(){}
    private static final Pattern URL=Pattern.compile("https?://[^\\s]+",Pattern.CASE_INSENSITIVE);
    private static final int MAX_REDIRECTS=6;

    public static String firstUrl(String text){if(text==null)return"";Matcher m=URL.matcher(text);return m.find()?trimUrl(m.group()):"";}
    public static boolean containsUrl(String text){return !firstUrl(text).isEmpty();}

    public static void enrichAsync(Context ctx,VaultDb db,long itemId,String sharedText){final String url=firstUrl(sharedText);if(itemId<=0||url.isEmpty())return;Context app=ctx.getApplicationContext();new Thread(()->{try{enrich(app,db,itemId,url,sharedText);}catch(Throwable ignored){}},"cortex-link-intel").start();}
    public static void reprocessPendingAsync(Context ctx){Context app=ctx.getApplicationContext();new Thread(()->{VaultDb v=null;try{v=new VaultDb(app);reprocessPending(app,v,20);}catch(Throwable ignored){}finally{if(v!=null)try{v.close();}catch(Throwable ignored){}}},"cortex-link-backfill").start();}

    public static int reprocessPending(Context ctx,VaultDb vault,int limit){ArrayList<long[]> ids=new ArrayList<>();ArrayList<String> texts=new ArrayList<>();Cursor c=vault.getReadableDatabase().rawQuery("SELECT id,raw_text,summary,metadata_json FROM knowledge_items WHERE source='android_share' AND (type='LINK' OR raw_text LIKE 'http%' OR summary LIKE 'http%') ORDER BY updated_at DESC LIMIT ?",new String[]{String.valueOf(Math.max(1,limit*4))});try{while(c.moveToNext()){long id=c.getLong(0);String raw=n(c.getString(1)),sum=n(c.getString(2)),meta=n(c.getString(3));String text=!firstUrl(raw).isEmpty()?raw:sum;String url=firstUrl(text);if(url.isEmpty())continue;String state="";try{state=new JSONObject(meta.isEmpty()?"{}":meta).optString("link_content_state","");}catch(Throwable ignored){}boolean urlOnly=n(raw).equals(url)||n(sum).equals(url)||n(raw).isEmpty();if("understood".equals(state)&&!urlOnly)continue;ids.add(new long[]{id});texts.add(text);if(ids.size()>=limit)break;}}finally{c.close();}int done=0;for(int i=0;i<ids.size();i++){try{String text=texts.get(i);enrich(ctx,vault,ids.get(i)[0],firstUrl(text),text);done++;}catch(Throwable ignored){}}return done;}

    static void enrich(Context ctx,VaultDb vault,long itemId,String url,String sharedText)throws Exception{SQLiteDatabase db=vault.getWritableDatabase();long now=System.currentTimeMillis();markPending(db,itemId,url,now);Fetch f;try{f=fetch(url);}catch(Throwable t){markFailed(db,itemId,url,now,t.getClass().getSimpleName()+": "+n(t.getMessage()));return;}JSONObject meta=new JSONObject();meta.put("shared_url",url).put("link_intelligence",true).put("fetched_at",now).put("http_status",f.status).put("final_url",f.finalUrl).put("content_type",f.contentType);String source=host(f.finalUrl.isEmpty()?url:f.finalUrl);String title=clean(f.title);if(title.isEmpty())title=source.isEmpty()?"Shared link":source;String body=clean(f.text);if(body.length()<80){meta.put("link_content_state","fetch_failed").put("failure_reason","no_readable_content").put("extraction_chars",body.length());ContentValues v=new ContentValues();v.put("type","LINK");v.put("title",title);v.put("summary","Shared link from "+(source.isEmpty()?"the web":source)+". Cortex could not extract enough readable content yet; the URL is preserved for retry.");v.put("category","Links & Research");v.put("tags","link,shared,web,pending_content");v.put("metadata_json",meta.toString());v.put("status","pending");v.put("analysis_error","link_content_unavailable");v.put("updated_at",now);db.update("knowledge_items",v,"id=?",new String[]{String.valueOf(itemId)});return;}String summary=summarize(body,title,source),useful=usefulTakeaway(body,title,source);meta.put("useful_takeaway",useful).put("extraction_chars",body.length()).put("link_content_state","understood");ContentValues v=new ContentValues();v.put("type","LINK");v.put("title",title);v.put("extracted_text",clip(body,7000));v.put("summary",summary);v.put("category","Links & Research");v.put("tags","link,shared,web,understood");v.put("metadata_json",meta.toString());v.put("status","analyzed");v.put("analysis_error","");v.put("updated_at",now);db.update("knowledge_items",v,"id=?",new String[]{String.valueOf(itemId)});try{SemanticIndex.indexItem(vault,itemId);}catch(Throwable ignored){}}

    private static void markPending(SQLiteDatabase db,long id,String url,long now){try{JSONObject m=new JSONObject().put("shared_url",url).put("link_intelligence",true).put("link_content_state","pending_content").put("last_attempt_at",now);ContentValues v=new ContentValues();v.put("type","LINK");v.put("metadata_json",m.toString());v.put("status","pending");v.put("analysis_error","");v.put("updated_at",now);db.update("knowledge_items",v,"id=?",new String[]{String.valueOf(id)});}catch(Throwable ignored){}}
    private static void markFailed(SQLiteDatabase db,long id,String url,long now,String reason){try{JSONObject m=new JSONObject().put("shared_url",url).put("link_intelligence",true).put("link_content_state","fetch_failed").put("failure_reason",reason).put("last_attempt_at",now);ContentValues v=new ContentValues();v.put("type","LINK");v.put("metadata_json",m.toString());v.put("status","pending");v.put("analysis_error",reason);v.put("tags","link,shared,web,pending_content");v.put("updated_at",now);db.update("knowledge_items",v,"id=?",new String[]{String.valueOf(id)});}catch(Throwable ignored){}}

    /** Fetch only ordinary public HTTPS pages. Every redirect is revalidated before connection. */
    private static Fetch fetch(String input)throws Exception{
        URL u=validatedPublicHttps(input);
        for(int redirects=0;redirects<=MAX_REDIRECTS;redirects++){
            HttpURLConnection c=(HttpURLConnection)u.openConnection();c.setInstanceFollowRedirects(false);c.setConnectTimeout(9000);c.setReadTimeout(12000);c.setRequestProperty("User-Agent","Mozilla/5.0 CortexLink/1.2");c.setRequestProperty("Accept","text/html,application/xhtml+xml,text/plain;q=0.8,*/*;q=0.5");c.setRequestProperty("Accept-Encoding","identity");
            int status=c.getResponseCode();
            if(status==301||status==302||status==303||status==307||status==308){String location=c.getHeaderField("Location");c.disconnect();if(location==null||location.trim().isEmpty())throw new IOException("Redirect without Location");if(redirects>=MAX_REDIRECTS)throw new IOException("Too many redirects");u=validatedPublicHttps(new URL(u,location).toString());continue;}
            String ct=n(c.getContentType());if(!(ct.isEmpty()||ct.toLowerCase(Locale.ROOT).contains("text/")||ct.toLowerCase(Locale.ROOT).contains("html")||ct.toLowerCase(Locale.ROOT).contains("xhtml"))){c.disconnect();throw new IOException("Unsupported web content type");}
            InputStream raw=status>=200&&status<400?c.getInputStream():c.getErrorStream();String html=raw==null?"":read(raw,512000);String finalUrl=u.toString();c.disconnect();String title=match(html,"(?is)<title[^>]*>(.*?)</title>");if(title.isEmpty())title=meta(html,"og:title");return new Fetch(status,ct,finalUrl,decode(title),htmlToText(html));
        }
        throw new IOException("Redirect limit exceeded");
    }

    private static URL validatedPublicHttps(String input)throws Exception{
        URL u=new URL(input);if(!"https".equalsIgnoreCase(u.getProtocol()))throw new IOException("Only public HTTPS links are fetched automatically");String host=n(u.getHost()).trim();if(host.isEmpty()||"localhost".equalsIgnoreCase(host)||host.endsWith(".localhost")||host.endsWith(".local"))throw new IOException("Local/private hosts are not eligible for automatic fetch");InetAddress[] addrs=InetAddress.getAllByName(host);if(addrs.length==0)throw new IOException("Host did not resolve");for(InetAddress a:addrs)if(!publicAddress(a))throw new IOException("Local/private network address blocked");return u;
    }
    private static boolean publicAddress(InetAddress a){if(a==null||a.isAnyLocalAddress()||a.isLoopbackAddress()||a.isLinkLocalAddress()||a.isSiteLocalAddress()||a.isMulticastAddress())return false;byte[] b=a.getAddress();if(b.length==16){int first=b[0]&0xff;if((first&0xfe)==0xfc)return false;boolean mapped=true;for(int i=0;i<10;i++)if(b[i]!=0)mapped=false;if(mapped&&b[10]==(byte)0xff&&b[11]==(byte)0xff){byte[] v4=Arrays.copyOfRange(b,12,16);try{return publicAddress(InetAddress.getByAddress(v4));}catch(Exception e){return false;}}}return true;}

    private static String meta(String h,String prop){Matcher m=Pattern.compile("(?is)<meta[^>]+(?:property|name)=[\"']"+Pattern.quote(prop)+"[\"'][^>]+content=[\"'](.*?)[\"'][^>]*>").matcher(h);return m.find()?m.group(1):"";}
    private static String htmlToText(String h){if(h==null)return"";String x=h.replaceAll("(?is)<script.*?</script>"," ").replaceAll("(?is)<style.*?</style>"," ").replaceAll("(?is)<noscript.*?</noscript>"," ").replaceAll("(?is)<svg.*?</svg>"," ").replaceAll("(?is)<br\\s*/?>","\n").replaceAll("(?is)</(?:p|div|li|h[1-6]|article|section)>","\n").replaceAll("(?is)<[^>]+>"," ");x=decode(x).replace('\u00a0',' ').replaceAll("[ \\t\\x0B\\f\\r]+"," ").replaceAll("\\n\\s*\\n+","\n").trim();return x;}
    private static String summarize(String body,String title,String source){List<String> s=sentences(body);StringBuilder b=new StringBuilder();for(String x:s){if(x.length()<35)continue;if(b.length()>0)b.append(' ');b.append(x);if(b.length()>520)break;}String core=clip(b.toString(),560);if(core.isEmpty())core=clip(body,560);return (source.isEmpty()?"":source+" — ")+(!title.isEmpty()?title+". ":"")+core;}
    private static String usefulTakeaway(String body,String title,String source){List<String> s=sentences(body);for(String x:s){String z=x.toLowerCase(Locale.ROOT);if(x.length()>=45&&(z.contains("how ")||z.contains("why ")||z.contains("should ")||z.contains("can ")||z.contains("important")||z.contains("best ")||z.contains("guide")||z.contains("طريقة")||z.contains("مهم")||z.contains("نصيحة")||z.contains("يمكن")))return clip(x,360);}return s.isEmpty()?"Review the source if it is relevant to an active situation, person, or project.":clip(s.get(0),360);}
    private static List<String> sentences(String x){ArrayList<String> o=new ArrayList<>();if(x==null)return o;for(String s:x.split("(?<=[.!?؟])\\s+|\\n+")){String z=clean(s);if(z.length()>20)o.add(z);}return o;}
    private static String read(InputStream in,int max)throws Exception{try(InputStream src=in;ByteArrayOutputStream b=new ByteArrayOutputStream()){byte[] x=new byte[8192];int n,total=0;while((n=src.read(x))>0&&total<max){int w=Math.min(n,max-total);b.write(x,0,w);total+=w;}return new String(b.toByteArray(),StandardCharsets.UTF_8);}}
    private static String decode(String x){return n(x).replace("&amp;","&").replace("&quot;","\"").replace("&#39;","'").replace("&lt;","<").replace("&gt;",">").replaceAll("&#(\\d+);","");}
    private static String match(String s,String re){Matcher m=Pattern.compile(re).matcher(n(s));return m.find()?m.group(1):"";}
    private static String host(String u){try{return new URL(u).getHost().replaceFirst("^www\\.","");}catch(Exception e){return"";}}
    private static String trimUrl(String u){return n(u).replaceAll("[),.;!?]+$","");}
    private static String clean(String s){return n(s).replaceAll("\\s+"," ").trim();}
    private static String clip(String s,int max){String x=n(s);return x.length()<=max?x:x.substring(0,max)+"…";}
    private static String n(String s){return s==null?"":s;}
    static final class Fetch{final int status;final String contentType,finalUrl,title,text;Fetch(int s,String c,String f,String t,String x){status=s;contentType=c;finalUrl=f;title=t;text=x;}}
}
