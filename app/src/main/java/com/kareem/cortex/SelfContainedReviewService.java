package com.kareem.cortex;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.*;
import org.json.JSONObject;

/** Keeps the long phone-only review alive independently of the Activity lifecycle. */
public final class SelfContainedReviewService extends Service {
    static final String PREF="cortex_phone_review";
    static final String CHANNEL="cortex_phone_review";
    static final int NOTIFICATION_ID=7319;
    private static volatile boolean running=false;
    private PowerManager.WakeLock wakeLock;

    @Override public void onCreate(){super.onCreate();ensureChannel();}

    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(running){updateNotification(currentStatus());return START_NOT_STICKY;}
        running=true;setState("RUNNING","Starting phone-only review…","");startForeground(NOTIFICATION_ID,notification("Starting phone-only review…"));
        try{PowerManager pm=(PowerManager)getSystemService(POWER_SERVICE);if(pm!=null){wakeLock=pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"Cortex:PhoneOnlyReview");wakeLock.setReferenceCounted(false);wakeLock.acquire(45L*60L*1000L);}}catch(Throwable ignored){}
        new Thread(()->runOnce(startId),"cortex-phone-review-service").start();return START_NOT_STICKY;
    }

    private void runOnce(int startId){
        try{
            progress("[0/6] Reconciling pending shared links");
            VaultDb linkDb=null;try{linkDb=new VaultDb(this);int n=SharedLinkIntelligence.reprocessPending(this,linkDb,20);progress("Link backfill attempted: "+n);}catch(Throwable t){progress("Link backfill unavailable: "+t.getClass().getSimpleName());}finally{if(linkDb!=null)try{linkDb.close();}catch(Throwable ignored){}}
            java.io.File root=SelfContainedReviewRunner.run(this,this::progress);
            progress("[5/6] Strict human-product quality gate");JSONObject strict=StrictReviewGate.evaluate(root);boolean strictGreen=strict.optBoolean("all_green",false);progress(strictGreen?"Strict gate: ALL GREEN":"Strict gate: NOT ALL GREEN — inspect STRICT_REVIEW_GATE.json");
            progress("[6/6] Donor integration quality gate");JSONObject donor=DonorIntegrationGate.evaluate(this,root);boolean donorGreen=donor.optBoolean("all_green",false);progress(donorGreen?"Donor gate: ALL GREEN":"Donor gate: NOT ALL GREEN — inspect DONOR_INTEGRATION_GATE.json");
            boolean allGreen=strictGreen&&donorGreen;
            Uri uri=SelfContainedReviewRunner.publishZip(this,root);String path="Downloads/Cortex/"+root.getName()+".zip";String label=allGreen?"DONE — ALL GREEN":"DONE — QUALITY GAPS REMAIN";setState("DONE",label+"\nSaved to "+path+(uri==null?"":"\nMediaStore: "+uri),path);updateNotification(allGreen?"Review complete — all green":"Review complete — quality gaps remain");
        }catch(Throwable t){setState("FAILED","FAILED\n"+t.getClass().getSimpleName()+": "+String.valueOf(t.getMessage()),"");updateNotification("Review failed — open Cortex for details");}
        finally{running=false;try{if(wakeLock!=null&&wakeLock.isHeld())wakeLock.release();}catch(Throwable ignored){}stopForeground(false);stopSelfResult(startId);}
    }

    private void progress(String s){String old=currentStatus();String next=(old==null||old.isEmpty()?"":old+"\n")+s;setState("RUNNING",next,"");updateNotification(s);}
    static void start(Context c){Intent i=new Intent(c,SelfContainedReviewService.class);if(Build.VERSION.SDK_INT>=26)c.startForegroundService(i);else c.startService(i);}
    static String status(Context c){return c.getSharedPreferences(PREF,MODE_PRIVATE).getString("status","Preparing…");}
    static String phase(Context c){return c.getSharedPreferences(PREF,MODE_PRIVATE).getString("phase","IDLE");}
    static String output(Context c){return c.getSharedPreferences(PREF,MODE_PRIVATE).getString("output","");}
    private String currentStatus(){return status(this);}
    private void setState(String phase,String status,String output){getSharedPreferences(PREF,MODE_PRIVATE).edit().putString("phase",phase).putString("status",status).putString("output",output).putLong("updated_at",System.currentTimeMillis()).apply();}
    private void ensureChannel(){if(Build.VERSION.SDK_INT>=26){NotificationManager nm=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);if(nm!=null)nm.createNotificationChannel(new NotificationChannel(CHANNEL,"Cortex phone-only review",NotificationManager.IMPORTANCE_LOW));}}
    private Notification notification(String text){Intent open=new Intent(this,StableSelfContainedReviewActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_SINGLE_TOP);PendingIntent pi=PendingIntent.getActivity(this,0,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);Notification.Builder b=Build.VERSION.SDK_INT>=26?new Notification.Builder(this,CHANNEL):new Notification.Builder(this);return b.setContentTitle("Cortex self-contained review").setContentText(text).setSmallIcon(android.R.drawable.stat_notify_sync).setOngoing(true).setContentIntent(pi).build();}
    private void updateNotification(String text){try{NotificationManager nm=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);if(nm!=null)nm.notify(NOTIFICATION_ID,notification(text));}catch(Throwable ignored){}}
    @Override public android.os.IBinder onBind(Intent intent){return null;}
}
