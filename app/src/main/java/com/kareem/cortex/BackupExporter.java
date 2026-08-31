package com.kareem.cortex;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;

/** Portable backup exporter with bounded memory and one consistent Vault snapshot. */
public final class BackupExporter {
    private BackupExporter(){}
    private static final class Attachment {final long id;final File file;Attachment(long i,File f){id=i;file=f;}}

    public static int write(Context ctx,VaultDb db,Uri dest) throws Exception {
        File work=new File(ctx.getCacheDir(),"cortex_export_"+System.currentTimeMillis());if(!work.mkdirs()&&!work.isDirectory())throw new IOException("Could not create backup workspace");
        File jsonlFile=new File(work,"memories.jsonl"),dbSnapshot=new File(work,"cortex.db");ArrayList<Attachment> attachments=new ArrayList<>();int count=0;SQLiteDatabase sql=db.getWritableDatabase();boolean tx=false;
        try{
            Cursor cp=sql.rawQuery("PRAGMA wal_checkpoint(FULL)",null);try{if(cp.moveToFirst()){} }finally{cp.close();}
            sql.beginTransaction();tx=true;
            copy(ctx.getDatabasePath("cortex.db"),dbSnapshot);
            try(BufferedWriter jsonl=new BufferedWriter(new OutputStreamWriter(new FileOutputStream(jsonlFile),StandardCharsets.UTF_8),64*1024);Cursor c=sql.rawQuery("SELECT id,type,source,title,raw_text,extracted_text,summary,category,tags,attachment_path,status,metadata_json,created_at,updated_at FROM knowledge_items ORDER BY created_at ASC",null)){
                while(c.moveToNext()){
                    long id=c.getLong(0);String path=nz(c.getString(9)),zipPath="";File f=path.isEmpty()?null:new File(path);if(f!=null&&f.exists()&&f.isFile()){zipPath="attachments/"+id+"_"+safe(f.getName());attachments.add(new Attachment(id,f));}
                    JSONObject o=new JSONObject();o.put("id",id);o.put("type",nz(c.getString(1)));o.put("source",nz(c.getString(2)));o.put("title",nz(c.getString(3)));o.put("raw_text",nz(c.getString(4)));o.put("extracted_text",nz(c.getString(5)));o.put("summary",nz(c.getString(6)));o.put("category",nz(c.getString(7)));o.put("tags",nz(c.getString(8)));o.put("attachment_in_backup",zipPath);o.put("status",nz(c.getString(10)));o.put("metadata_json",nz(c.getString(11)));o.put("created_at",c.getLong(12));o.put("updated_at",c.getLong(13));jsonl.write(o.toString());jsonl.newLine();count++;
                }
            }
            sql.setTransactionSuccessful();
        }finally{if(tx)try{sql.endTransaction();}catch(Throwable ignored){}}

        HashSet<String> addedPaths=new HashSet<>();OutputStream opened=null;try{
            opened=ctx.getContentResolver().openOutputStream(dest,"w");if(opened==null)throw new IOException("Could not open backup destination");
            try(OutputStream raw=opened;ZipOutputStream z=new ZipOutputStream(new BufferedOutputStream(raw))){opened=null;putText(z,"README.txt","Cortex portable backup\nContains a Vault snapshot, memories.jsonl and local attachments.\nKeep this archive private: it may contain personal data.\n");if(dbSnapshot.exists())putFile(z,"database/cortex.db",dbSnapshot);for(Attachment a:attachments){String path=a.file.getAbsolutePath();if(!addedPaths.add(path))continue;putFile(z,"attachments/"+a.id+"_"+safe(a.file.getName()),a.file);}putFile(z,"memories.jsonl",jsonlFile);JSONObject manifest=new JSONObject();manifest.put("format","CORTEX_BACKUP_V1");manifest.put("exported_at",System.currentTimeMillis());manifest.put("memory_count",count);manifest.put("attachment_count",addedPaths.size());putText(z,"manifest.json",manifest.toString(2));}
            return count;
        }finally{if(opened!=null)try{opened.close();}catch(Throwable ignored){}delete(work);}
    }
    private static void putText(ZipOutputStream z,String name,String text)throws Exception{z.putNextEntry(new ZipEntry(name));z.write(text.getBytes(StandardCharsets.UTF_8));z.closeEntry();}
    private static void putFile(ZipOutputStream z,String name,File f)throws Exception{z.putNextEntry(new ZipEntry(name));try(InputStream in=new BufferedInputStream(new FileInputStream(f))){byte[] b=new byte[64*1024];int n;while((n=in.read(b))!=-1)z.write(b,0,n);}z.closeEntry();}
    private static void copy(File a,File b)throws IOException{if(a==null||!a.exists())return;try(InputStream in=new BufferedInputStream(new FileInputStream(a));OutputStream out=new BufferedOutputStream(new FileOutputStream(b))){byte[] x=new byte[64*1024];for(int n;(n=in.read(x))!=-1;)out.write(x,0,n);}}
    private static void delete(File f){if(f==null||!f.exists())return;if(f.isDirectory()){File[] xs=f.listFiles();if(xs!=null)for(File x:xs)delete(x);}f.delete();}
    private static String safe(String s){String x=nz(s).replaceAll("[^A-Za-z0-9._-]","_");return x.length()>100?x.substring(x.length()-100):x;}private static String nz(String s){return s==null?"":s;}
}
