package com.kareem.cortex;

import android.content.Context;
import androidx.work.*;
import java.util.concurrent.TimeUnit;

/** Coalesces attention refinement work; never blocks UI and avoids provider bursts. */
public final class AttentionAiScheduler {
    private static final String UNIQUE="cortex-attention-ai";
    private AttentionAiScheduler(){}
    private static Constraints constraints(){return new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresBatteryNotLow(true).build();}
    public static void kick(Context context){
        if(context==null||!ExternalBrainProvider.configured(context))return;Context app=context.getApplicationContext();
        OneTimeWorkRequest w=new OneTimeWorkRequest.Builder(AttentionAiWorker.class).setConstraints(constraints()).addTag(UNIQUE).build();
        WorkManager.getInstance(app).enqueueUniqueWork(UNIQUE,ExistingWorkPolicy.KEEP,w);
    }
    static void continueChain(Context context){
        if(context==null||!ExternalBrainProvider.configured(context))return;Context app=context.getApplicationContext();
        OneTimeWorkRequest w=new OneTimeWorkRequest.Builder(AttentionAiWorker.class).setConstraints(constraints()).setInitialDelay(90,TimeUnit.SECONDS).addTag(UNIQUE).build();
        WorkManager.getInstance(app).enqueueUniqueWork(UNIQUE,ExistingWorkPolicy.APPEND_OR_REPLACE,w);
    }
}
