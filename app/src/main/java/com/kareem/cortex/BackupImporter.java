package com.kareem.cortex;

import android.content.*;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import org.json.JSONObject;
import java.io.*;
import java.util.*;
import java.util.zip.*;

/** Backup restore with non-mutating validation before any live Vault write. */
public final class BackupImporter {
    private static final long MAX_ENTRY=256L*1024L*1024L,MAX_TOTAL=2L*1024L*1024L*1024L;
    private static final int MAX_ENTRIES=20000;
    private BackupImporter(){}

    public static final class Inspection {
        public final boolean valid;public final int entries,memories,attachments;public final long bytes;public final String detail;
        Inspection(boolean v,int e,int m,int a,long b,String d){valid=v;entries=e;memories=m;attachments=a;bytes=b;detail=d;}
        public String human(){return(valid?"Valid Cortex backup":"Invalid backup")+"\nMemories: "+memories+"\nAttachments: "+attachments+"\nArchive entries: "+entries+"\nUncompressed bytes checked: "+bytes+(detail.isEmpty()?"":"\n"+detail);}
    }

    /** Read-only preflight. Does not extract or write the Vault. */
    public static Inspection inspect(Context ctx,Uri src){
        int entries=0,memories=0,attachments=0;long total=0;boolean hasMemories=false;InputStream opened=null;
        try{
            opened=ctx.getContentResolver().openInputStream(src);if(opened==null)return new Inspection(false,0,0,0,0,"Could not open backup");
            try(InputStream raw=opened;ZipInputStream z=new ZipInputStream(new BufferedInputStream(raw))){opened=null;ZipEntry e;byte[] buf=new byte[16384];
                while((e=z.getNextEntry())!=null){
                    if(++entries>MAX_ENTRIES)return new Inspection(false,entries,memories,attachments,total,"Too many archive entries");String name=validatedName(e.getName());if(e.isDirectory()){z.closeEntry();continue;}
                    long entryBytes=0;boolean memoriesEntry="memories.jsonl".equals(name);int lineCount=0;boolean hadData=false,lastWasNewline=true;
                    for(int n;(n=z.read(buf))!=-1;){entryBytes+=n;total+=n;if(entryBytes>MAX_ENTRY||total>MAX_TOTAL)return new Inspection(false,entries,memories,attachments,total,"Backup exceeds safe extraction limits");if(memoriesEntry){for(int i=0;i<n;i++){byte b=buf[i];if(b=='\n'){if(hadData)lineCount++;hadData=false;lastWasNewline=true;}else if(b!='\r'&&!Character.isWhitespace((char)(b&0xff))){hadData=true;lastWasNewline=false;}}}}
                    if(memoriesEntry){hasMemories=true;if(hadData&&!lastWasNewline)lineCount++;memories=lineCount;}else if(name.startsWith("attachments/"))attachments++;z.closeEntry();
                }
            }
            if(!hasMemories)return new Inspection(false,entries,0,attachments,total,"memories.jsonl is missing");return new Inspection(true,entries,memories,attachments,total,"Preflight only; live Vault has not been changed.");
        }catch(Throwable e){return new Inspection(false,entries,memories,attachments,total,e.getClass().getSimpleName()+": "+safe(e.getMessage()));}
        finally{if(opened!=null)try{opened.close();}catch(Throwable ignored){}}
    }

    /** Restore is database-atomic. Files copied into live storage are deleted if the DB transaction rolls back. */
    public static int restore(Context ctx,VaultDb db,Uri src)throws Exception{
        Inspection check=inspect(ctx,src);if(!check.valid)throw new IOException("Restore preflight failed: "+check.detail);
        File tmp=new File(ctx.getCacheDir(),"cortex_restore_"+System.currentTimeMillis());if(!tmp.mkdirs()&&!tmp.isDirectory())throw new IOException("Could not create restore workspace");
        File memories=null;HashMap<String,File> attachments=new HashMap<>();long total=0;int entries=0;InputStream opened=null;ArrayList<File> liveCopies=new ArrayList<>();SQLiteDatabase sql=db.getWritableDatabase();boolean began=false,committed=false;
        try{
            opened=ctx.getContentResolver().openInputStream(src);if(opened==null)throw new IOException("Could not open backup");
            try(InputStream raw=opened;ZipInputStream z=new ZipInputStream(new BufferedInputStream(raw))){opened=null;ZipEntry e;byte[] b=new byte[16384];while((e=z.getNextEntry())!=null){if(++entries>MAX_ENTRIES)throw new IOException("Too many archive entries");String name=validatedName(e.getName());if(e.isDirectory()){z.closeEntry();continue;}File out=safeChild(tmp,name);File parent=out.getParentFile();if(parent!=null&&!parent.exists()&&!parent.mkdirs())throw new IOException("Could not create restore directory");long entryBytes=0;try(OutputStream os=new BufferedOutputStream(new FileOutputStream(out))){int n;while((n=z.read(b))!=-1){entryBytes+=n;total+=n;if(entryBytes>MAX_ENTRY||total>MAX_TOTAL)throw new IOException("Backup exceeds safe extraction limits");os.write(b,0,n);}}if("memories.jsonl".equals(name))memories=out;else if(name.startsWith("attachments/"))attachments.put(name,out);z.closeEntry();}}
            if(memories==null||!memories.exists())throw new IOException("Not a Cortex backup: memories.jsonl missing");

            sql.beginTransaction();began=true;int count=0;
            try(BufferedReader r=new BufferedReader(new InputStreamReader(new FileInputStream(memories),"UTF-8"))){String line;while((line=r.readLine())!=null){line=line.trim();if(line.isEmpty())continue;JSONObject o=new JSONObject(line);String raw=o.optString("raw_text",""),ex=o.optString("extracted_text",""),title=o.optString("title","Restored memory"),att=o.optString("attachment_in_backup","");File srcFile=!att.isEmpty()?attachments.get(att):null;String local="";String fp;
                    File planned=null;if(srcFile!=null&&srcFile.exists()){File dir=new File(ctx.getFilesDir(),"restored");if(!dir.exists()&&!dir.mkdirs())throw new IOException("Could not create restored files directory");planned=new File(dir,UUID.randomUUID()+"_"+srcFile.getName());local=planned.getAbsolutePath();fp=Fingerprint.file(srcFile.getAbsolutePath());}else fp=Fingerprint.text((raw.isEmpty()?ex:raw)+"|"+title);
                    long id=db.insert(o.optString("type","TEXT"),"backup_restore",title,raw,o.optString("category","Notes"),o.optString("tags","restored"),local,fp,o.optString("metadata_json","{}"));if(id<0)continue;
                    if(planned!=null){copy(srcFile,planned);liveCopies.add(planned);}
                    ContentValues v=new ContentValues();v.put("extracted_text",ex);v.put("summary",o.optString("summary",""));v.put("status",o.optString("status","analyzed"));v.put("created_at",o.optLong("created_at",System.currentTimeMillis()));v.put("updated_at",o.optLong("updated_at",System.currentTimeMillis()));sql.update("knowledge_items",v,"id=?",new String[]{String.valueOf(id)});count++;
                }}
            sql.setTransactionSuccessful();committed=true;return count;
        }finally{
            if(began)try{sql.endTransaction();}catch(Throwable ignored){}
            if(!committed)for(File f:liveCopies)try{if(f!=null)f.delete();}catch(Throwable ignored){}
            if(opened!=null)try{opened.close();}catch(Throwable ignored){}delete(tmp);
        }
    }

    private static String validatedName(String s)throws IOException{String n=s==null?"":s.replace('\\','/');if(n.isEmpty()||n.startsWith("/")||n.contains("../")||n.equals("..")||n.contains(":/"))throw new IOException("Unsafe backup path: "+n);return n;}
    private static File safeChild(File root,String name)throws IOException{File f=new File(root,name),canonicalRoot=root.getCanonicalFile(),canonical=f.getCanonicalFile();String rp=canonicalRoot.getPath()+File.separator;if(!canonical.getPath().startsWith(rp))throw new IOException("Unsafe backup path");return canonical;}
    private static void copy(File a,File b)throws Exception{try(InputStream in=new BufferedInputStream(new FileInputStream(a));OutputStream out=new BufferedOutputStream(new FileOutputStream(b))){byte[] x=new byte[16384];int n;while((n=in.read(x))!=-1)out.write(x,0,n);}}
    private static void delete(File f){if(f==null||!f.exists())return;if(f.isDirectory()){File[] xs=f.listFiles();if(xs!=null)for(File x:xs)delete(x);}f.delete();}
    private static String safe(String s){return s==null?"":s;}
}
