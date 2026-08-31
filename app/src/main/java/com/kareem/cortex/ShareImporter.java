package com.kareem.cortex;

import android.content.*;
import android.database.Cursor;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Parcelable;
import android.provider.OpenableColumns;
import org.json.JSONObject;
import java.io.*;
import java.util.*;

public class ShareImporter {
    private static final long MAX_IMAGE_BYTES=80L*1024L*1024L;
    private static final long MAX_AUDIO_BYTES=750L*1024L*1024L;
    private static final long MAX_FILE_BYTES=750L*1024L*1024L;
    private final Context ctx;private final VaultDb db;public ShareImporter(Context c,VaultDb d){ctx=c;db=d;}

    /** Import exactly one ACTION_SEND payload and return the real knowledge item id, including an existing duplicate. */
    public long importSingle(Intent i){if(i==null||!Intent.ACTION_SEND.equals(i.getAction()))return 0;String mime=i.getType();Uri u=readUri(i);if(mime!=null&&mime.startsWith("image/")&&u!=null)return saveImage(u,mime);if(mime!=null&&mime.startsWith("audio/")&&u!=null)return saveAudio(u,mime);if(u!=null)return saveFile(u,mime);String text=i.getStringExtra(Intent.EXTRA_TEXT);return text!=null&&!text.trim().isEmpty()?saveText(text,mime):0;}
    public int importIntent(Intent i){if(i==null)return 0;String action=i.getAction(),mime=i.getType();int n=0;if(Intent.ACTION_SEND.equals(action)){if(importSingle(i)>0)n++;}else if(Intent.ACTION_SEND_MULTIPLE.equals(action)){ArrayList<Uri> us=i.getParcelableArrayListExtra(Intent.EXTRA_STREAM);if(us!=null)for(Uri u:us){long id=(mime!=null&&mime.startsWith("image/"))?saveImage(u,mime):(mime!=null&&mime.startsWith("audio/"))?saveAudio(u,mime):saveFile(u,mime);if(id>0)n++;}}return n;}
    public long importAudio(Uri uri,String mime){if(uri==null)return 0;return saveAudio(uri,mime==null?"audio/*":mime);}
    private Uri readUri(Intent i){Parcelable p=i.getParcelableExtra(Intent.EXTRA_STREAM);return p instanceof Uri?(Uri)p:null;}

    private long saveText(String text,String mime){String cat=AutoClassifier.category(text,mime);boolean link=SharedLinkIntelligence.containsUrl(text);String url=SharedLinkIntelligence.firstUrl(text);String title=link?initialLinkTitle(url):AutoClassifier.title(text,mime);String tags=AutoClassifier.tags(text,cat)+(link?",link,shared,web,pending_content":"");String meta="{}";try{if(link)meta=new JSONObject().put("shared_url",url).put("link_intelligence",true).put("link_content_state","pending_content").put("imported_at",System.currentTimeMillis()).toString();}catch(Exception ignored){}long id=existingOrNew(db.insert(link?"LINK":"TEXT","android_share",title,text,link?"Links & Research":cat,tags,"",Fingerprint.text(text),meta));if(link&&id>0)SharedLinkIntelligence.enrichAsync(ctx,db,id,text);return id;}
    private String initialLinkTitle(String url){try{String host=new java.net.URL(url).getHost().replaceFirst("^www\\.","");return host.isEmpty()?"Shared link":"Shared from "+host;}catch(Exception e){return"Shared link";}}
    private long saveImage(Uri uri,String mime){String name=displayName(uri,"image");String local=copyBounded(uri,name,"imports",MAX_IMAGE_BYTES);if(local.isEmpty())return 0;String cat=AutoClassifier.category("",mime);String meta=imageMeta(local,mime,name);long id=db.insert("SCREENSHOT","android_share",name,"",cat,"screenshot,image",local,Fingerprint.file(local),meta);if(id<0){new File(local).delete();return-id;}return id;}
    private long saveAudio(Uri uri,String mime){String name=displayName(uri,"audio");String local=copyBounded(uri,name,"audio",MAX_AUDIO_BYTES);if(local.isEmpty())return 0;long id=db.insert("AUDIO","audio_import",name,"","Voice & Audio","voice,audio,transcript,imported",local,Fingerprint.file(local),fileMeta(local,mime,name));if(id<0){new File(local).delete();return-id;}return id;}
    private long saveFile(Uri uri,String mime){String name=displayName(uri,"file");String local=copyBounded(uri,name,"imports",MAX_FILE_BYTES);if(local.isEmpty())return 0;long id=db.insert("FILE","android_share",name,name,"Files","file,attachment",local,Fingerprint.file(local),fileMeta(local,mime,name));if(id<0){new File(local).delete();return-id;}return id;}
    private long existingOrNew(long id){return id<0?-id:id;}
    private String imageMeta(String path,String mime,String name){try{BitmapFactory.Options o=new BitmapFactory.Options();o.inJustDecodeBounds=true;BitmapFactory.decodeFile(path,o);JSONObject j=new JSONObject(fileMeta(path,mime,name));j.put("width",o.outWidth);j.put("height",o.outHeight);return j.toString();}catch(Exception e){return"{}";}}
    private String fileMeta(String path,String mime,String name){try{JSONObject j=new JSONObject();j.put("mime",mime==null?"":mime);j.put("name",name);j.put("bytes",new File(path).length());j.put("imported_at",System.currentTimeMillis());return j.toString();}catch(Exception e){return"{}";}}
    private String displayName(Uri u,String fallback){try(Cursor c=ctx.getContentResolver().query(u,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){if(c!=null&&c.moveToFirst()){String x=c.getString(0);if(x!=null&&!x.trim().isEmpty())return x;}}catch(Exception ignored){}return fallback+"_"+System.currentTimeMillis();}

    private String copyBounded(Uri uri,String name,String folder,long maxBytes){File out=null;try{File dir=new File(ctx.getFilesDir(),folder);if(!dir.exists()&&!dir.mkdirs())return"";String safe=name.replaceAll("[^A-Za-z0-9._-]","_");if(safe.length()>80)safe=safe.substring(safe.length()-80);out=new File(dir,UUID.randomUUID()+"_"+safe);long known=querySize(uri);if(known>maxBytes)return"";long total=0;try(InputStream in=ctx.getContentResolver().openInputStream(uri);OutputStream os=new BufferedOutputStream(new FileOutputStream(out))){if(in==null)throw new IOException("Cannot open shared content");byte[] b=new byte[64*1024];int r;while((r=in.read(b))!=-1){total+=r;if(total>maxBytes)throw new IOException("Shared content exceeds safe import limit");os.write(b,0,r);}}return out.getAbsolutePath();}catch(Exception e){if(out!=null)try{out.delete();}catch(Throwable ignored){}return"";}}
    private long querySize(Uri u){try(Cursor c=ctx.getContentResolver().query(u,new String[]{OpenableColumns.SIZE},null,null,null)){if(c!=null&&c.moveToFirst()&&!c.isNull(0))return c.getLong(0);}catch(Throwable ignored){}return-1;}
}
