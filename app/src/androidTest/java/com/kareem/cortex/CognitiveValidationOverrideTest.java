package com.kareem.cortex;

import static org.junit.Assert.*;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Set;

@RunWith(AndroidJUnit4.class)
public final class CognitiveValidationOverrideTest {
    private static final String PREFS="cortex_cognitive_flags";
    private static final String OVERRIDE="cognitive_v2_validation_override";
    private static final String THREADS="cognitive_v2_validation_threads";

    @Test public void routingOrderKeepsHardGateAndKillSwitchAboveDebugOverride(){
        Context c=context();
        FlagSnapshot old=new FlagSnapshot(c);
        try{
            CognitiveFeatureFlags.setAuthorityCanaryEnabled(c,true);
            CognitiveFeatureFlags.setCanaryPercent(c,0);
            CognitiveFeatureFlags.setValidationOverride(c,true,"141");

            CognitiveAuthorityRouter.Decision hard=CognitiveAuthorityRouter.routeInternal(
                    c,141,"com.whatsapp","Ahmed",true,true
            );
            assertEquals(CognitiveAuthorityRouter.Route.HARD_GATE,hard.route);
            assertEquals(CognitiveAuthorityRouter.RoutingReason.HARD_NOISE,hard.reason);

            CognitiveFeatureFlags.setAuthorityCanaryEnabled(c,false);
            CognitiveAuthorityRouter.Decision killed=CognitiveAuthorityRouter.routeInternal(
                    c,141,"com.whatsapp","Ahmed",false,true
            );
            assertEquals(CognitiveAuthorityRouter.Route.LEGACY,killed.route);
            assertEquals(CognitiveAuthorityRouter.RoutingReason.CANARY_DISABLED,killed.reason);

            CognitiveFeatureFlags.setAuthorityCanaryEnabled(c,true);
            CognitiveAuthorityRouter.Decision forced=CognitiveAuthorityRouter.routeDetailed(
                    c,141,"com.whatsapp","Ahmed",false
            );
            assertEquals(CognitiveAuthorityRouter.Route.V2_CANARY,forced.route);
            assertEquals(CognitiveAuthorityRouter.RoutingReason.VALIDATION_OVERRIDE,forced.reason);
            assertEquals(CognitiveAuthorityRouter.stableBucket("thread:141"),forced.bucket);

            CognitiveAuthorityRouter.Decision notAllowlisted=CognitiveAuthorityRouter.routeInternal(
                    c,142,"com.whatsapp","Ahmed",false,true
            );
            assertEquals(CognitiveAuthorityRouter.Route.LEGACY,notAllowlisted.route);
            assertEquals(CognitiveAuthorityRouter.RoutingReason.HASH_LEGACY,notAllowlisted.reason);

            CognitiveFeatureFlags.setValidationOverride(c,false,"141");
            CognitiveAuthorityRouter.Decision disabled=CognitiveAuthorityRouter.routeInternal(
                    c,141,"com.whatsapp","Ahmed",false,true
            );
            assertEquals(CognitiveAuthorityRouter.Route.LEGACY,disabled.route);
            assertEquals(CognitiveAuthorityRouter.RoutingReason.HASH_LEGACY,disabled.reason);

            CognitiveFeatureFlags.setValidationOverride(c,true,"141");
            CognitiveAuthorityRouter.Decision invalidThread=CognitiveAuthorityRouter.routeInternal(
                    c,0,"com.whatsapp","Ahmed",false,true
            );
            assertEquals(CognitiveAuthorityRouter.Route.LEGACY,invalidThread.route);
            assertEquals(CognitiveAuthorityRouter.RoutingReason.HASH_LEGACY,invalidThread.reason);

            CognitiveAuthorityRouter.Decision simulatedRelease=CognitiveAuthorityRouter.routeInternal(
                    c,141,"com.whatsapp","Ahmed",false,false
            );
            assertEquals(CognitiveAuthorityRouter.Route.LEGACY,simulatedRelease.route);
            assertEquals(CognitiveAuthorityRouter.RoutingReason.HASH_LEGACY,simulatedRelease.reason);
        }finally{
            old.restore(c);
        }
    }

    @Test public void malformedCsvIsIgnoredDeduplicatedAndCappedAtFifty(){
        Context c=context();
        FlagSnapshot old=new FlagSnapshot(c);
        try{
            CognitiveFeatureFlags.setValidationOverride(
                    c,true," ,abc,-1,0,141,141, 208x, 208,9223372036854775808,319, "
            );
            Set<Long> parsed=CognitiveFeatureFlags.validationThreadIds(c);
            assertEquals(3,parsed.size());
            assertTrue(parsed.contains(141L));
            assertTrue(parsed.contains(208L));
            assertTrue(parsed.contains(319L));
            assertFalse(parsed.contains(0L));
            assertTrue(CognitiveFeatureFlags.validationOverrideEnabled(c));

            ArrayList<String> values=new ArrayList<>();
            for(int i=1;i<=60;i++)values.add(String.valueOf(i));
            CognitiveFeatureFlags.setValidationOverride(c,true,join(values));
            Set<Long> capped=CognitiveFeatureFlags.validationThreadIds(c);
            assertEquals(50,capped.size());
            assertTrue(capped.contains(1L));
            assertTrue(capped.contains(50L));
            assertFalse(capped.contains(51L));

            CognitiveFeatureFlags.setValidationOverride(c,true,"bad,-5,0");
            assertTrue(CognitiveFeatureFlags.validationThreadIds(c).isEmpty());
            assertFalse(CognitiveFeatureFlags.validationOverrideEnabled(c));
        }finally{
            old.restore(c);
        }
    }

    @Test public void forcedSuccessfulApplyPersistsRoutingProvenanceAtomically() throws Exception{
        Context c=context();
        FlagSnapshot old=new FlagSnapshot(c);
        TestDb x=open("cognitive-validation-override.db");
        try{
            final long threadId=141L;
            CognitiveFeatureFlags.setAuthorityCanaryEnabled(c,true);
            CognitiveFeatureFlags.setCanaryPercent(c,0);
            CognitiveFeatureFlags.setValidationOverride(c,true,String.valueOf(threadId));

            CognitiveAuthorityRouter.Decision routing=CognitiveAuthorityRouter.routeDetailed(
                    c,threadId,"com.whatsapp","Ahmed",false
            );
            assertEquals(CognitiveAuthorityRouter.Route.V2_CANARY,routing.route);
            assertEquals(CognitiveAuthorityRouter.RoutingReason.VALIDATION_OVERRIDE,routing.reason);

            long signal=insertRaw(x.sql,"com.whatsapp","Ahmed","Please send me the final PDF.");
            ContentValues thread=new ContentValues();
            thread.put("thread_id",threadId);
            x.sql.update("raw_signals",thread,"id=?",new String[]{String.valueOf(signal)});

            CognitiveItem item=new CognitiveItem(
                    CognitiveKind.ACTION,"Send Ahmed the final PDF",88,80,"Ahmed",null,
                    true,false,false
            );
            CognitiveResult result=new CognitiveResult(
                    CognitiveDisposition.DERIVE,0.93,"Clear user action",
                    Collections.singletonList(item)
            );
            LocalBrainRun run=new LocalBrainRun(result,"",220,0,200,9,15f,true);

            CognitiveStore.CanaryApply applied=CognitiveStore.applyCanaryAuthority(
                    x.vault,signal,threadId,result,run,220,"input-hash",
                    CognitiveAdjudicatorV2.CANARY_POLICY,
                    routing.reason.name(),routing.bucket
            );
            assertTrue(applied.modelRunId>0);

            JSONObject output=new JSONObject(scalarString(
                    x.sql,"SELECT output_json FROM model_runs WHERE id="+applied.modelRunId
            ));
            assertEquals("VALIDATION_OVERRIDE",output.getString("routing_reason"));
            assertEquals(routing.bucket,output.getInt("routing_bucket"));

            JSONObject linkMeta=new JSONObject(scalarString(
                    x.sql,
                    "SELECT metadata_json FROM source_links WHERE from_type='model_run' AND from_id="
                            +applied.modelRunId
                            +" AND to_type='raw_signal' AND to_id="+signal
                            +" AND relation='authoritative_evaluated' LIMIT 1"
            ));
            assertEquals(CognitiveAdjudicatorV2.CANARY_POLICY,linkMeta.getString("policy"));
            assertEquals("VALIDATION_OVERRIDE",linkMeta.getString("routing_reason"));
            assertEquals(routing.bucket,linkMeta.getInt("routing_bucket"));

            String finalReason=scalarString(x.sql,"SELECT final_reason FROM raw_signals WHERE id="+signal);
            assertTrue(finalReason.contains("via VALIDATION_OVERRIDE"));
        }finally{
            x.close();
            old.restore(c);
        }
    }

    private static Context context(){
        return InstrumentationRegistry.getInstrumentation().getTargetContext().getApplicationContext();
    }

    private static String join(ArrayList<String> values){
        StringBuilder out=new StringBuilder();
        for(String value:values){if(out.length()>0)out.append(',');out.append(value);}
        return out.toString();
    }

    private static long insertRaw(SQLiteDatabase db,String source,String title,String body){
        long now=System.currentTimeMillis();
        ContentValues v=new ContentValues();
        v.put("kind","notification");v.put("source",source);v.put("title",title);v.put("body",body);
        v.put("metadata_json","{}");
        v.put("fingerprint",Fingerprint.text(source+"|"+title+"|"+body+"|"+now+"|"+Math.random()));
        v.put("state","filtered");v.put("disposition","CONTEXT");v.put("importance",0);v.put("reason","");
        v.put("occurred_at",now);v.put("retention_until",0);v.put("created_at",now);v.put("updated_at",now);
        v.put("confidence",0.8);v.put("filter_engine","deterministic_fast_gate");
        v.put("cognitive_state","LEGACY_UNRESOLVED");v.put("cognitive_version","legacy-cognitive-003");
        v.put("cognitive_updated_at",now);
        return db.insertOrThrow("raw_signals",null,v);
    }

    private static TestDb open(String name){
        Context context=context();
        File file=new File(context.getCacheDir(),name);
        delete(file);
        SQLiteDatabase sql=SQLiteDatabase.openOrCreateDatabase(file,null);
        CognitiveSchema.ensure(sql);
        TestVault vault=new TestVault(context,sql);
        return new TestDb(file,vault,sql);
    }

    private static String scalarString(SQLiteDatabase db,String sql){
        Cursor c=db.rawQuery(sql,null);
        try{return c.moveToFirst()&&!c.isNull(0)?c.getString(0):"";}finally{c.close();}
    }

    private static void delete(File file){
        if(file.exists())file.delete();
        new File(file.getPath()+"-wal").delete();
        new File(file.getPath()+"-shm").delete();
        new File(file.getPath()+"-journal").delete();
    }

    private static final class FlagSnapshot{
        final boolean canaryEnabled;
        final int canaryPercent;
        final boolean overridePref;
        final String validationThreads;

        FlagSnapshot(Context context){
            canaryEnabled=CognitiveFeatureFlags.authorityCanaryEnabled(context);
            canaryPercent=CognitiveFeatureFlags.canaryPercent(context);
            SharedPreferences p=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE);
            overridePref=p.getBoolean(OVERRIDE,false);
            validationThreads=p.getString(THREADS,"");
        }

        void restore(Context context){
            CognitiveFeatureFlags.setAuthorityCanaryEnabled(context,canaryEnabled);
            CognitiveFeatureFlags.setCanaryPercent(context,canaryPercent);
            CognitiveFeatureFlags.setValidationOverride(context,overridePref,validationThreads);
        }
    }

    private static final class TestVault extends VaultDb{
        private final SQLiteDatabase sql;
        TestVault(Context context,SQLiteDatabase sql){super(context);this.sql=sql;}
        @Override public SQLiteDatabase getWritableDatabase(){return sql;}
        @Override public SQLiteDatabase getReadableDatabase(){return sql;}
        @Override public synchronized void close(){}
    }

    private static final class TestDb{
        final File file;final TestVault vault;final SQLiteDatabase sql;
        TestDb(File file,TestVault vault,SQLiteDatabase sql){this.file=file;this.vault=vault;this.sql=sql;}
        void close(){try{sql.close();}catch(Throwable ignored){}delete(file);}
    }
}
