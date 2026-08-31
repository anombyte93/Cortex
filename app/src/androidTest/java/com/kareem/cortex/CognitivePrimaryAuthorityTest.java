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
import java.util.Collections;

@RunWith(AndroidJUnit4.class)
public final class CognitivePrimaryAuthorityTest {
    private static final String PREFS="cortex_cognitive_flags";
    private static final String MODE="cognitive_authority_mode";
    private static final String CANARY_PERCENT="cognitive_v2_canary_percent";

    @Test public void defaultIsV2PrimaryAndKillSwitchFallsBackToLegacy(){
        Context c=context();FlagSnapshot old=new FlagSnapshot(c);
        try{
            SharedPreferences p=c.getSharedPreferences(PREFS,Context.MODE_PRIVATE);
            p.edit().remove(MODE).remove(CANARY_PERCENT).commit();
            assertEquals(CognitiveAuthorityMode.V2_PRIMARY,CognitiveFeatureFlags.authorityMode(c));
            assertEquals(5,CognitiveFeatureFlags.canaryPercent(c));

            CognitiveFeatureFlags.setAuthorityCanaryEnabled(c,true);
            CognitiveAuthorityRouter.Decision hard=CognitiveAuthorityRouter.routeDetailed(c,812,"com.whatsapp","Ahmed",true);
            assertEquals(CognitiveAuthorityRouter.Route.HARD_GATE,hard.route);
            assertEquals(CognitiveAuthorityRouter.RoutingReason.HARD_NOISE,hard.reason);

            CognitiveAuthorityRouter.Decision primary=CognitiveAuthorityRouter.routeDetailed(c,812,"com.whatsapp","Ahmed",false);
            assertEquals(CognitiveAuthorityRouter.Route.V2_PRIMARY,primary.route);
            assertEquals(CognitiveAuthorityRouter.RoutingReason.PRIMARY,primary.reason);

            CognitiveFeatureFlags.setAuthorityCanaryEnabled(c,false);
            CognitiveAuthorityRouter.Decision disabled=CognitiveAuthorityRouter.routeDetailed(c,812,"com.whatsapp","Ahmed",false);
            assertEquals(CognitiveAuthorityRouter.Route.LEGACY,disabled.route);
            assertEquals(CognitiveAuthorityRouter.RoutingReason.CANARY_DISABLED,disabled.reason);
        }finally{old.restore(c);}
    }

    @Test public void policyUsesPoint78AndSoftensModelIgnore(){
        CognitiveItem action=action("Send the file");
        CognitiveDecisionApplier.Validation deriveAccepted=CognitiveDecisionApplier.validate(new CognitiveResult(CognitiveDisposition.DERIVE,0.78,"",Collections.singletonList(action)));
        assertTrue(deriveAccepted.accepted);
        CognitiveDecisionApplier.Validation deriveLow=CognitiveDecisionApplier.validate(new CognitiveResult(CognitiveDisposition.DERIVE,0.779,"",Collections.singletonList(action)));
        assertFalse(deriveLow.accepted);assertEquals(V2FailureReason.LOW_CONFIDENCE,deriveLow.failureReason);

        assertTrue(CognitiveDecisionApplier.validate(new CognitiveResult(CognitiveDisposition.CONTEXT,0.78,"",Collections.emptyList())).accepted);
        CognitiveDecisionApplier.Validation contextLow=CognitiveDecisionApplier.validate(new CognitiveResult(CognitiveDisposition.CONTEXT,0.55,"",Collections.emptyList()));
        assertFalse(contextLow.accepted);assertEquals(V2FailureReason.LOW_CONFIDENCE,contextLow.failureReason);

        CognitiveDecisionApplier.Validation review=CognitiveDecisionApplier.validate(new CognitiveResult(CognitiveDisposition.REVIEW,0.99,"",Collections.emptyList()));
        assertFalse(review.accepted);assertEquals(V2FailureReason.REVIEW_REQUIRED,review.failureReason);

        CognitiveDecisionApplier.Validation ignore=CognitiveDecisionApplier.validate(new CognitiveResult(CognitiveDisposition.IGNORE,0.20,"model says ignore",Collections.emptyList()));
        assertTrue(ignore.accepted);assertEquals(CognitiveDisposition.CONTEXT,ignore.effectiveResult.disposition);assertEquals("MODEL_IGNORE_SOFTENED",ignore.effectiveResult.reason);
    }

    @Test public void hardNoiseCreatesNoModelRunEvenInPrimaryMode(){
        Context c=context();FlagSnapshot old=new FlagSnapshot(c);TestDb x=open("cognitive-primary-hard-noise.db");
        try{
            CognitiveFeatureFlags.setAuthorityCanaryEnabled(c,true);CognitiveFeatureFlags.setAuthorityMode(c,CognitiveAuthorityMode.V2_PRIMARY);
            MasterRelevanceFilter.Signal signal=new MasterRelevanceFilter.Signal("notification","com.android.systemui","Charging","Charging 85% until full","{}",System.currentTimeMillis(),true);
            long id=RawSignalStore.capture(c,x.vault,signal);assertTrue(id>0);
            assertEquals("IGNORED_NOISE",scalarString(x.sql,"SELECT cognitive_state FROM raw_signals WHERE id="+id));
            assertEquals(0,scalarLong(x.sql,"SELECT COUNT(*) FROM model_runs"));
        }finally{x.close();old.restore(c);}
    }

    @Test public void primaryApplyPersistsActionContextAndIgnoreSafetyProvenance(){
        TestDb x=open("cognitive-primary-apply.db");
        try{
            long threadAction=812L;
            long actionSignal=insertRaw(x.sql,threadAction,"com.whatsapp","Ahmed","Please send the final file.",System.currentTimeMillis(),"LOCAL_RUNNING",CognitiveAdjudicatorV2.PRIMARY_POLICY,"CONTEXT");
            CognitiveResult actionResult=new CognitiveResult(CognitiveDisposition.DERIVE,0.93,"Clear request",Collections.singletonList(action("Send Ahmed the final file")));
            CognitiveDecisionApplier.ApplyResult actionApplied=CognitiveDecisionApplier.apply(x.vault,actionSignal,threadAction,actionResult,run(actionResult),220,"input-action",CognitiveAuthorityMode.V2_PRIMARY,CognitiveAdjudicatorV2.PRIMARY_POLICY,"PRIMARY",39,1);
            assertTrue(actionApplied.modelRunId>0);
            assertEquals("DERIVED",scalarString(x.sql,"SELECT cognitive_state FROM raw_signals WHERE id="+actionSignal));
            assertEquals(CognitiveAdjudicatorV2.PRIMARY_POLICY,scalarString(x.sql,"SELECT cognitive_version FROM raw_signals WHERE id="+actionSignal));
            assertTrue(scalarString(x.sql,"SELECT final_reason FROM raw_signals WHERE id="+actionSignal).contains("V2 primary accepted"));
            assertEquals("cognitive_v2_primary",scalarString(x.sql,"SELECT route FROM model_runs WHERE id="+actionApplied.modelRunId));
            JSONObject actionOut=new JSONObject(scalarString(x.sql,"SELECT output_json FROM model_runs WHERE id="+actionApplied.modelRunId));
            assertEquals("V2_PRIMARY",actionOut.getString("authority_mode"));assertEquals("PRIMARY",actionOut.getString("routing_reason"));
            JSONObject actionLink=new JSONObject(scalarString(x.sql,"SELECT metadata_json FROM source_links WHERE from_type='model_run' AND from_id="+actionApplied.modelRunId+" AND relation='authoritative_evaluated' LIMIT 1"));
            assertEquals("cognitive_v2_primary",actionLink.getString("route"));assertEquals("V2_PRIMARY",actionLink.getString("authority_mode"));

            long contextSignal=insertRaw(x.sql,813,"com.example","Update","FYI only",System.currentTimeMillis()+10,"LOCAL_RUNNING",CognitiveAdjudicatorV2.PRIMARY_POLICY,"CONTEXT");
            CognitiveResult context=new CognitiveResult(CognitiveDisposition.CONTEXT,0.82,"No action",Collections.emptyList());
            CognitiveDecisionApplier.apply(x.vault,contextSignal,813,context,run(context),180,"input-context",CognitiveAuthorityMode.V2_PRIMARY,CognitiveAdjudicatorV2.PRIMARY_POLICY,"PRIMARY",55,1);
            assertEquals("CONTEXT_ONLY",scalarString(x.sql,"SELECT cognitive_state FROM raw_signals WHERE id="+contextSignal));

            long ignoreSignal=insertRaw(x.sql,814,"com.example","Meaningful","A meaningful message the model ignored",System.currentTimeMillis()+20,"LOCAL_RUNNING",CognitiveAdjudicatorV2.PRIMARY_POLICY,"CONTEXT");
            CognitiveResult rawIgnore=new CognitiveResult(CognitiveDisposition.IGNORE,0.22,"model says ignore",Collections.emptyList());
            CognitiveResult softened=CognitiveDecisionApplier.validate(rawIgnore).effectiveResult;
            CognitiveDecisionApplier.apply(x.vault,ignoreSignal,814,softened,run(softened),175,"input-ignore",CognitiveAuthorityMode.V2_PRIMARY,CognitiveAdjudicatorV2.PRIMARY_POLICY,"PRIMARY",61,1);
            assertEquals("CONTEXT_ONLY",scalarString(x.sql,"SELECT cognitive_state FROM raw_signals WHERE id="+ignoreSignal));
            assertNotEquals("IGNORED_NOISE",scalarString(x.sql,"SELECT cognitive_state FROM raw_signals WHERE id="+ignoreSignal));
            assertTrue(scalarString(x.sql,"SELECT final_reason FROM raw_signals WHERE id="+ignoreSignal).contains("MODEL_IGNORE_SOFTENED"));
        }catch(Exception e){throw new AssertionError(e);}finally{x.close();}
    }

    @Test public void staleThreadGenerationCannotCommit(){
        TestDb x=open("cognitive-primary-stale.db");
        try{
            long thread=900L,now=System.currentTimeMillis();
            long a=insertRaw(x.sql,thread,"com.whatsapp","Ahmed","First update",now,"LOCAL_RUNNING",CognitiveAdjudicatorV2.PRIMARY_POLICY,"CONTEXT");
            long b=insertRaw(x.sql,thread,"com.whatsapp","Ahmed","Second update",now+1,"LOCAL_QUEUED",CognitiveAdjudicatorV2.PRIMARY_POLICY,"CONTEXT");
            CognitiveResult result=new CognitiveResult(CognitiveDisposition.DERIVE,0.92,"",Collections.singletonList(action("Act on first update")));
            try{CognitiveDecisionApplier.apply(x.vault,a,thread,result,run(result),200,"stale",CognitiveAuthorityMode.V2_PRIMARY,CognitiveAdjudicatorV2.PRIMARY_POLICY,"PRIMARY",80,1);fail("stale generation must not commit");}
            catch(IllegalStateException expected){assertTrue(String.valueOf(expected.getMessage()).contains("STALE_GENERATION"));}
            assertEquals(0,scalarLong(x.sql,"SELECT COUNT(*) FROM model_runs WHERE CAST(json_extract(output_json,'$.signal_id') AS INTEGER)="+a));
            assertEquals("LOCAL_RUNNING",scalarString(x.sql,"SELECT cognitive_state FROM raw_signals WHERE id="+a));
            assertEquals("LOCAL_QUEUED",scalarString(x.sql,"SELECT cognitive_state FROM raw_signals WHERE id="+b));
        }finally{x.close();}
    }

    @Test public void staleLocalRunningRecoversThroughExplicitPrimaryFallback(){
        Context c=context();TestDb x=open("cognitive-primary-recovery.db");
        try{
            long old=System.currentTimeMillis()-61_000L;
            long signal=insertRaw(x.sql,0,"com.example","FYI","Ordinary context",old,"LOCAL_RUNNING",CognitiveAdjudicatorV2.PRIMARY_POLICY,"CONTEXT");
            ContentValues u=new ContentValues();u.put("cognitive_updated_at",old);u.put("updated_at",old);x.sql.update("raw_signals",u,"id=?",new String[]{String.valueOf(signal)});
            assertEquals(1,CognitiveRecoverySweep.run(c,x.vault,false));
            assertEquals("CONTEXT_ONLY",scalarString(x.sql,"SELECT cognitive_state FROM raw_signals WHERE id="+signal));
            assertEquals("legacy-cognitive-003",scalarString(x.sql,"SELECT cognitive_version FROM raw_signals WHERE id="+signal));
            assertTrue(scalarString(x.sql,"SELECT final_reason FROM raw_signals WHERE id="+signal).contains("V2 primary fallback (MODEL_FAILED) -> Legacy authority: CONTEXT"));
            assertEquals(0,scalarLong(x.sql,"SELECT COUNT(*) FROM model_runs WHERE role='cognitive_authority'"));
            assertEquals(0,CognitiveRecoverySweep.run(c,x.vault,false));
        }finally{x.close();}
    }

    private static Context context(){return InstrumentationRegistry.getInstrumentation().getTargetContext().getApplicationContext();}
    private static CognitiveItem action(String summary){return new CognitiveItem(CognitiveKind.ACTION,summary,88,80,"Ahmed",null,true,false,false);}
    private static LocalBrainRun run(CognitiveResult result){return new LocalBrainRun(result,"",220,0,200,9,15f,true);}

    private static long insertRaw(SQLiteDatabase db,long threadId,String source,String title,String body,long at,String cognitiveState,String version,String disposition){
        ContentValues v=new ContentValues();v.put("kind","notification");v.put("source",source);v.put("title",title);v.put("body",body);v.put("metadata_json","{}");v.put("fingerprint",Fingerprint.text(source+"|"+title+"|"+body+"|"+at+"|"+Math.random()));v.put("state","filtered");v.put("disposition",disposition);v.put("importance",28);v.put("reason","test baseline");v.put("occurred_at",at);v.put("retention_until",0);v.put("created_at",at);v.put("updated_at",at);v.put("confidence",0.82);v.put("filter_engine","deterministic_fast_gate");v.put("policy_version","relevance_fast_004");v.put("cognitive_state",cognitiveState);v.put("cognitive_version",version);v.put("cognitive_updated_at",at);v.put("thread_id",threadId);return db.insertOrThrow("raw_signals",null,v);
    }

    private static TestDb open(String name){Context context=context();File file=new File(context.getCacheDir(),name);delete(file);SQLiteDatabase sql=SQLiteDatabase.openOrCreateDatabase(file,null);CognitiveSchema.ensure(sql);return new TestDb(file,new TestVault(context,sql),sql);}
    private static String scalarString(SQLiteDatabase db,String sql){Cursor c=db.rawQuery(sql,null);try{return c.moveToFirst()&&!c.isNull(0)?c.getString(0):"";}finally{c.close();}}
    private static long scalarLong(SQLiteDatabase db,String sql){Cursor c=db.rawQuery(sql,null);try{return c.moveToFirst()?c.getLong(0):0;}finally{c.close();}}
    private static void delete(File file){if(file.exists())file.delete();new File(file.getPath()+"-wal").delete();new File(file.getPath()+"-shm").delete();new File(file.getPath()+"-journal").delete();}

    private static final class FlagSnapshot{
        final boolean modePresent,percentPresent,canaryEnabled;final String modeValue;final int percentValue;
        FlagSnapshot(Context context){SharedPreferences p=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE);modePresent=p.contains(MODE);percentPresent=p.contains(CANARY_PERCENT);modeValue=modePresent?p.getString(MODE,""):"";percentValue=percentPresent?p.getInt(CANARY_PERCENT,5):5;canaryEnabled=CognitiveFeatureFlags.authorityCanaryEnabled(context);}
        void restore(Context context){SharedPreferences p=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE);SharedPreferences.Editor e=p.edit();if(modePresent)e.putString(MODE,modeValue);else e.remove(MODE);if(percentPresent)e.putInt(CANARY_PERCENT,percentValue);else e.remove(CANARY_PERCENT);e.commit();CognitiveFeatureFlags.setAuthorityCanaryEnabled(context,canaryEnabled);}
    }

    private static final class TestVault extends VaultDb{
        private final SQLiteDatabase sql;TestVault(Context context,SQLiteDatabase sql){super(context);this.sql=sql;}
        @Override public SQLiteDatabase getWritableDatabase(){return sql;}
        @Override public SQLiteDatabase getReadableDatabase(){return sql;}
        @Override public synchronized void close(){}
    }
    private static final class TestDb{
        final File file;final TestVault vault;final SQLiteDatabase sql;TestDb(File file,TestVault vault,SQLiteDatabase sql){this.file=file;this.vault=vault;this.sql=sql;}
        void close(){try{sql.close();}catch(Throwable ignored){}delete(file);}
    }
}
