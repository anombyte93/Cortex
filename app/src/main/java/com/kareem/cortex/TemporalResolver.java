package com.kareem.cortex;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.regex.*;

/** Resolves relative due expressions against the memory capture time. */
public final class TemporalResolver {
    private static final String DATE="yyyy-MM-dd", DATE_TIME="yyyy-MM-dd HH:mm";
    private TemporalResolver(){}

    public static void ensure(VaultDb db){
        SQLiteDatabase s=db.getWritableDatabase();
        s.execSQL("CREATE TABLE IF NOT EXISTS action_temporal(action_id INTEGER PRIMARY KEY,item_id INTEGER NOT NULL,raw_expression TEXT,resolved_at INTEGER DEFAULT 0,has_time INTEGER DEFAULT 0,timezone TEXT,resolved_at_created INTEGER NOT NULL)");
        s.execSQL("CREATE INDEX IF NOT EXISTS idx_action_temporal_item ON action_temporal(item_id)");
    }

    public static void backfill(VaultDb db,int limit){
        ensure(db);Cursor c=db.getReadableDatabase().rawQuery("SELECT DISTINCT a.item_id FROM actions a LEFT JOIN action_temporal t ON t.action_id=a.id WHERE a.status='open' AND a.due_text IS NOT NULL AND TRIM(a.due_text)<>'' AND (t.action_id IS NULL OR a.due_text LIKE '%بكر%' OR lower(a.due_text) LIKE '%tomorrow%' OR lower(a.due_text) LIKE '%today%') ORDER BY a.id DESC LIMIT ?",new String[]{String.valueOf(Math.max(1,limit))});ArrayList<Long> ids=new ArrayList<>();while(c.moveToNext())ids.add(c.getLong(0));c.close();for(long id:ids)afterAnalysis(db,id);
    }

    public static void afterAnalysis(VaultDb db,long itemId){
        ensure(db);KnowledgeItem k=db.getById(itemId);if(k==null)return;
        SQLiteDatabase s=db.getWritableDatabase();
        Cursor c=s.query("actions",new String[]{"id","due_text"},"item_id=?",new String[]{String.valueOf(itemId)},null,null,"id ASC");
        while(c.moveToNext()){
            long actionId=c.getLong(0);String raw=nz(c.getString(1)).trim();if(raw.isEmpty())continue;
            Cursor old=s.query("action_temporal",new String[]{"raw_expression"},"action_id=?",new String[]{String.valueOf(actionId)},null,null,null,"1");String original=old.moveToFirst()?nz(old.getString(0)):raw;old.close();
            if(parseCanonical(raw)!=null&&!original.equals(raw))continue;
            Resolved r=resolve(original,k.createdAt);if(r==null)continue;
            ContentValues t=new ContentValues();t.put("action_id",actionId);t.put("item_id",itemId);t.put("raw_expression",original);t.put("resolved_at",r.when);t.put("has_time",r.hasTime?1:0);t.put("timezone",TimeZone.getDefault().getID());t.put("resolved_at_created",System.currentTimeMillis());s.insertWithOnConflict("action_temporal",null,t,SQLiteDatabase.CONFLICT_REPLACE);
            ContentValues a=new ContentValues();a.put("due_text",canonical(r.when,r.hasTime));s.update("actions",a,"id=?",new String[]{String.valueOf(actionId)});
        }
        c.close();
    }

    /** Resolve a natural-language expression for attention ranking. Returns 0 if no date can be grounded. */
    public static long resolveForAttention(String expression,long anchorMs){Resolved r=resolve(nz(expression),anchorMs>0?anchorMs:System.currentTimeMillis());return r==null?0:r.when;}

    public static String displayStored(String stored){
        String s=nz(stored).trim();if(s.isEmpty())return "";
        Date d=parseCanonical(s);if(d==null)return s;
        boolean hasTime=s.length()>10;Calendar now=Calendar.getInstance(),target=Calendar.getInstance();target.setTime(d);
        long diffDays=dayNumber(target)-dayNumber(now);String label;
        if(diffDays==0)label="Today";else if(diffDays==1)label="Tomorrow";else if(diffDays==-1)label="Yesterday";else label=new SimpleDateFormat("EEE, dd MMM",Locale.getDefault()).format(d);
        if(hasTime)label+=" • "+new SimpleDateFormat("HH:mm",Locale.getDefault()).format(d);
        return label;
    }

    public static String rawForAction(VaultDb db,long actionId){ensure(db);Cursor c=db.getReadableDatabase().query("action_temporal",new String[]{"raw_expression"},"action_id=?",new String[]{String.valueOf(actionId)},null,null,null,"1");String x=c.moveToFirst()?nz(c.getString(0)):"";c.close();return x;}

    private static Resolved resolve(String expression,long anchorMs){
        String raw=expression.trim();String n=norm(raw);Calendar cal=Calendar.getInstance();cal.setTimeInMillis(anchorMs>0?anchorMs:System.currentTimeMillis());zeroSeconds(cal);
        boolean dateFound=false;
        if(has(n,"بعد بكرة","بعد بكره","day after tomorrow")){cal.add(Calendar.DAY_OF_YEAR,2);dateFound=true;}
        else if(has(n,"بكرة","بكره","tomorrow")){cal.add(Calendar.DAY_OF_YEAR,1);dateFound=true;}
        else if(has(n,"النهاردة","النهارده","اليوم","today","tonight")){dateFound=true;}
        else if(has(n,"الأسبوع الجاي","الاسبوع الجاي","next week")){cal.add(Calendar.DAY_OF_YEAR,7);dateFound=true;}
        else {Integer weekday=weekday(n);if(weekday!=null){int cur=cal.get(Calendar.DAY_OF_WEEK),delta=(weekday-cur+7)%7;if(delta==0)delta=7;cal.add(Calendar.DAY_OF_YEAR,delta);dateFound=true;}}
        if(!dateFound){Date absolute=parseLooseDate(raw,cal.getTime());if(absolute!=null){Calendar x=Calendar.getInstance();x.setTime(absolute);cal.set(Calendar.YEAR,x.get(Calendar.YEAR));cal.set(Calendar.MONTH,x.get(Calendar.MONTH));cal.set(Calendar.DAY_OF_MONTH,x.get(Calendar.DAY_OF_MONTH));dateFound=true;}}
        if(!dateFound)return null;
        TimeResult tr=time(raw,n);if(tr.hasTime){cal.set(Calendar.HOUR_OF_DAY,tr.hour);cal.set(Calendar.MINUTE,tr.minute);}else{cal.set(Calendar.HOUR_OF_DAY,12);cal.set(Calendar.MINUTE,0);}zeroSeconds(cal);
        return new Resolved(cal.getTimeInMillis(),tr.hasTime);
    }

    private static TimeResult time(String raw,String n){
        if(has(n,"آخر اليوم","اخر اليوم","end of day"))return new TimeResult(19,0,true);
        Matcher m=Pattern.compile("(?i)(?:الساعة\\s*)?([01]?\\d|2[0-3])(?::([0-5]\\d))?\\s*(am|pm|ص|م)?").matcher(raw);
        while(m.find()){
            int h=Integer.parseInt(m.group(1));int min=m.group(2)==null?0:Integer.parseInt(m.group(2));String ap=m.group(3);String around=m.group();
            if(around.length()<=2 && raw.matches(".*\\d[/-]\\d.*"))continue;
            if(ap!=null){if(("pm".equalsIgnoreCase(ap)||"م".equals(ap))&&h<12)h+=12;if(("am".equalsIgnoreCase(ap)||"ص".equals(ap))&&h==12)h=0;}
            else if(has(n,"بالليل","المساء","مساء","evening","tonight")&&h<12)h+=12;
            return new TimeResult(h,min,true);
        }
        return new TimeResult(12,0,false);
    }

    private static Integer weekday(String n){if(has(n,"sunday","الأحد","الاحد"))return Calendar.SUNDAY;if(has(n,"monday","الاثنين","الإثنين","الاتنين"))return Calendar.MONDAY;if(has(n,"tuesday","الثلاثاء"))return Calendar.TUESDAY;if(has(n,"wednesday","الأربعاء","الاربعاء"))return Calendar.WEDNESDAY;if(has(n,"thursday","الخميس"))return Calendar.THURSDAY;if(has(n,"friday","الجمعة"))return Calendar.FRIDAY;if(has(n,"saturday","السبت"))return Calendar.SATURDAY;return null;}
    private static Date parseLooseDate(String s,Date anchor){String[] f={"dd/MM/yyyy","d/M/yyyy","dd-MM-yyyy","d-M-yyyy","yyyy-MM-dd"};for(String x:f)try{SimpleDateFormat d=new SimpleDateFormat(x,Locale.US);d.setLenient(false);Matcher m=Pattern.compile(x.startsWith("yyyy")?"\\d{4}-\\d{1,2}-\\d{1,2}":"\\d{1,2}[/\\-]\\d{1,2}(?:[/\\-]\\d{4})?").matcher(s);if(m.find()){String v=m.group();if(!v.matches(".*\\d{4}.*")&&!x.startsWith("yyyy"))v=v+(x.contains("/")?"/":"-")+new SimpleDateFormat("yyyy",Locale.US).format(anchor);return d.parse(v);}}catch(Exception ignored){}return null;}
    private static Date parseCanonical(String s){for(String f:new String[]{DATE_TIME,DATE})try{SimpleDateFormat d=new SimpleDateFormat(f,Locale.US);d.setLenient(false);return d.parse(s);}catch(Exception ignored){}return null;}
    private static String canonical(long ms,boolean time){return new SimpleDateFormat(time?DATE_TIME:DATE,Locale.US).format(new Date(ms));}
    private static long dayNumber(Calendar c){Calendar x=(Calendar)c.clone();x.set(Calendar.HOUR_OF_DAY,12);x.set(Calendar.MINUTE,0);x.set(Calendar.SECOND,0);x.set(Calendar.MILLISECOND,0);return x.getTimeInMillis()/(24L*60*60*1000);}
    private static void zeroSeconds(Calendar c){c.set(Calendar.SECOND,0);c.set(Calendar.MILLISECOND,0);}private static boolean has(String n,String... xs){for(String x:xs)if(n.contains(norm(x)))return true;return false;}private static String norm(String s){return LocalSemanticEmbedder.norm(nz(s));}private static String nz(String s){return s==null?"":s;}
    private static class Resolved{final long when;final boolean hasTime;Resolved(long w,boolean h){when=w;hasTime=h;}}private static class TimeResult{final int hour,minute;final boolean hasTime;TimeResult(int h,int m,boolean t){hour=h;minute=m;hasTime=t;}}
}
