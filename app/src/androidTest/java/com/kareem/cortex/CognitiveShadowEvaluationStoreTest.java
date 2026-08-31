package com.kareem.cortex;

import static org.junit.Assert.*;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.ArrayList;

@RunWith(AndroidJUnit4.class)
public final class CognitiveShadowEvaluationStoreTest {

    @Test public void queueBackfillsProvenanceAndPrioritizesDisagreements(){
        TestDb x=open("cognitive-shadow-eval-queue.db");
        try{
            long now=System.currentTimeMillis();
            for(int i=1;i<=4;i++)insertRaw(x.db,i,"com.whatsapp","Sender "+i,"Message "+i,now+i);
            long agreement=shadowRun(x.vault,1,"CONTEXT","","CONTEXT","", "BOTH_CONTEXT",0.90,400,"complete");
            long downgrade=shadowRun(x.vault,2,"ACTION","ACTION","CONTEXT","", "V2_DOWNGRADE",0.70,300,"complete");
            long ignore=shadowRun(x.vault,3,"IGNORE","","CONTEXT","", "IGNORE_DISAGREEMENT",0.80,200,"complete");
            long missed=shadowRun(x.vault,4,"CONTEXT","","DERIVE","ACTION", "V2_FOUND_MISSED_VALUE",0.93,100,"complete");

            assertEquals(0,scalarLong(x.db,"SELECT COUNT(*) FROM source_links WHERE relation='shadow_evaluated'"));
            ArrayList<CognitiveShadowEvaluationStore.EvalCase> q=CognitiveShadowEvaluationStore.queue(x.vault,10);
            assertEquals(4,q.size());
            assertEquals(missed,q.get(0).modelRunId);assertEquals(ignore,q.get(1).modelRunId);assertEquals(downgrade,q.get(2).modelRunId);assertEquals(agreement,q.get(3).modelRunId);
            assertEquals(4,scalarLong(x.db,"SELECT COUNT(*) FROM source_links WHERE relation='shadow_evaluated'"));

            assertTrue(CognitiveShadowEvaluationStore.verdict(x.vault,missed,CognitiveShadowEvaluationStore.V2_BETTER));
            assertFalse(CognitiveShadowEvaluationStore.verdict(x.vault,missed,CognitiveShadowEvaluationStore.BOTH_OK));
            q=CognitiveShadowEvaluationStore.queue(x.vault,10);
            assertEquals(3,q.size());assertNotEquals(missed,q.get(0).modelRunId);
        }catch(Exception e){throw new AssertionError(e);}finally{x.close();}
    }

    @Test public void verdictIsEvaluationOnlyAndNeverChangesProductionAuthority(){
        TestDb x=open("cognitive-shadow-eval-safety.db");
        try{
            long now=System.currentTimeMillis();insertRaw(x.db,1,"com.whatsapp","Ahmed","ممكن تبعتلي الملف؟",now);
            ContentValues d=new ContentValues();d.put("kind","ACTION");d.put("title","Legacy production item");d.put("body","legacy");d.put("state","open");d.put("confidence",0.8);d.put("importance",70);d.put("fingerprint","eval-production-derived");d.put("metadata_json","{\"policy\":\"legacy\"}");d.put("created_at",now);d.put("updated_at",now);x.db.insertOrThrow("derived_items",null,d);
            long runId=shadowRun(x.vault,1,"CONTEXT","","DERIVE","ACTION","V2_FOUND_MISSED_VALUE",0.94,180,"complete");

            long derivedBefore=scalarLong(x.db,"SELECT COUNT(*) FROM derived_items");
            String dispositionBefore=scalarString(x.db,"SELECT disposition FROM raw_signals WHERE id=1");
            long relevanceBefore=scalarLong(x.db,"SELECT COUNT(*) FROM relevance_evaluations");

            assertTrue(CognitiveShadowEvaluationStore.verdict(x.vault,runId,CognitiveShadowEvaluationStore.V2_BETTER));
            assertEquals(derivedBefore,scalarLong(x.db,"SELECT COUNT(*) FROM derived_items"));
            assertEquals(dispositionBefore,scalarString(x.db,"SELECT disposition FROM raw_signals WHERE id=1"));
            assertEquals(relevanceBefore,scalarLong(x.db,"SELECT COUNT(*) FROM relevance_evaluations"));
            assertEquals(1,scalarLong(x.db,"SELECT COUNT(*) FROM feedback_events WHERE target_type='model_run' AND target_id="+runId+" AND event_type='SHADOW_V2_BETTER' AND policy_version='cognitive_shadow_eval_001'"));
        }catch(Exception e){throw new AssertionError(e);}finally{x.close();}
    }

    @Test public void metricsMeasureMissRecoveryFalseDeriveNoiseReliabilityAndLatency(){
        TestDb x=open("cognitive-shadow-eval-metrics.db");
        try{
            long now=System.currentTimeMillis();for(int i=1;i<=4;i++)insertRaw(x.db,i,"source","T"+i,"B"+i,now+i);
            long miss=shadowRun(x.vault,1,"CONTEXT","","DERIVE","ACTION","V2_FOUND_MISSED_VALUE",0.93,100,"complete");
            long noise=shadowRun(x.vault,2,"IGNORE","","CONTEXT","","IGNORE_DISAGREEMENT",0.82,300,"complete");
            shadowRun(x.vault,3,"CONTEXT","","REVIEW","","DIFFERENT",0.40,50,"failed");
            shadowRun(x.vault,4,"IGNORE","","IGNORE","","BOTH_IGNORE",0,0,"skipped");
            assertTrue(CognitiveShadowEvaluationStore.verdict(x.vault,miss,CognitiveShadowEvaluationStore.V2_BETTER));
            assertTrue(CognitiveShadowEvaluationStore.verdict(x.vault,noise,CognitiveShadowEvaluationStore.LEGACY_BETTER));

            CognitiveShadowEvaluationStore.Metrics m=CognitiveShadowEvaluationStore.metrics(x.vault);
            assertEquals(4,m.totalRuns);assertEquals(2,m.successfulRuns);assertEquals(1,m.failedRuns);assertEquals(1,m.skippedRuns);
            assertEquals(2,m.labeled);assertEquals(2,m.labeledDisagreements);
            assertEquals(1,m.labeledMisses);assertEquals(1,m.approvedMisses);assertEquals(1.0,m.missRecoveryPrecision,0.0001);
            assertEquals(1,m.labeledV2Derives);assertEquals(0,m.badV2Derives);assertEquals(0.0,m.falseDeriveRate,0.0001);
            assertEquals(1,m.labeledIgnoreDisagreements);assertEquals(1,m.badNoiseRevivals);assertEquals(1.0,m.noiseRevivalRate,0.0001);
            assertEquals(2.0/3.0,m.validOutputRate,0.0001);
            assertEquals(100,m.p50LatencyMs);assertEquals(300,m.p95LatencyMs);assertEquals(200.0,m.avgLatencyMs,0.001);
            assertTrue(m.noProductionMutation);assertEquals(0,m.shadowDerivedMutations);
        }catch(Exception e){throw new AssertionError(e);}finally{x.close();}
    }

    @Test public void skipRemovesCaseWithoutCountingAsHumanQualityVerdict(){
        TestDb x=open("cognitive-shadow-eval-skip.db");
        try{
            long now=System.currentTimeMillis();insertRaw(x.db,1,"source","Title","Preview too short",now);
            long run=shadowRun(x.vault,1,"CONTEXT","","DERIVE","CONTENT","V2_FOUND_MISSED_VALUE",0.8,200,"complete");
            assertTrue(CognitiveShadowEvaluationStore.verdict(x.vault,run,CognitiveShadowEvaluationStore.SKIP));
            assertTrue(CognitiveShadowEvaluationStore.queue(x.vault,5).isEmpty());
            CognitiveShadowEvaluationStore.Metrics m=CognitiveShadowEvaluationStore.metrics(x.vault);
            assertEquals(0,m.labeled);assertEquals(1,m.skippedLabels);assertEquals(0,m.labeledDisagreements);
        }catch(Exception e){throw new AssertionError(e);}finally{x.close();}
    }

    @Test public void promotionGateSeparatesCollectionPromptRuntimeAndSafety(){
        CognitiveShadowEvaluationStore.Metrics m=goodMetrics();
        m.successfulRuns=149;
        assertEquals(CognitiveShadowEvaluationStore.PromotionStatus.COLLECTING,CognitiveShadowEvaluationStore.promotionStatus(m));

        m=goodMetrics();
        assertEquals(CognitiveShadowEvaluationStore.PromotionStatus.READY,CognitiveShadowEvaluationStore.promotionStatus(m));
        assertEquals("READY_FOR_AUTHORITATIVE_CANARY",CognitiveShadowEvaluationStore.promotionReason(m));

        m=goodMetrics();m.missRecoveryPrecision=0.80;
        assertEquals(CognitiveShadowEvaluationStore.PromotionStatus.NEEDS_TUNING,CognitiveShadowEvaluationStore.promotionStatus(m));
        assertEquals("PROMPT_TUNING",CognitiveShadowEvaluationStore.promotionReason(m));

        m=goodMetrics();m.p95LatencyMs=3000;
        assertEquals(CognitiveShadowEvaluationStore.PromotionStatus.NEEDS_TUNING,CognitiveShadowEvaluationStore.promotionStatus(m));
        assertEquals("RUNTIME_TUNING",CognitiveShadowEvaluationStore.promotionReason(m));

        m=goodMetrics();m.falseDeriveRate=0.16;
        assertEquals(CognitiveShadowEvaluationStore.PromotionStatus.UNSAFE,CognitiveShadowEvaluationStore.promotionStatus(m));
        assertEquals("FALSE_DERIVE_SAFETY",CognitiveShadowEvaluationStore.promotionReason(m));

        m=goodMetrics();m.noProductionMutation=false;m.shadowDerivedMutations=1;
        assertEquals(CognitiveShadowEvaluationStore.PromotionStatus.UNSAFE,CognitiveShadowEvaluationStore.promotionStatus(m));
        assertEquals("SHADOW_MUTATED_PRODUCTION",CognitiveShadowEvaluationStore.promotionReason(m));
    }

    private static CognitiveShadowEvaluationStore.Metrics goodMetrics(){
        CognitiveShadowEvaluationStore.Metrics m=new CognitiveShadowEvaluationStore.Metrics();
        m.successfulRuns=150;m.labeledDisagreements=60;m.validOutputRate=0.995;m.missRecoveryPrecision=0.90;m.falseDeriveRate=0.05;m.noiseRevivalRate=0.01;m.p50LatencyMs=600;m.p95LatencyMs=1800;m.noProductionMutation=true;
        m.approvedAction=1;m.approvedWaiting=1;m.approvedEvent=1;m.approvedContent=1;
        return m;
    }

    private static long shadowRun(VaultDb db,long signalId,String legacyDisposition,String legacyCandidate,String v2Disposition,String v2Kind,String comparison,double confidence,long latency,String state)throws Exception{
        JSONObject legacy=new JSONObject().put("disposition",legacyDisposition).put("candidate_kind",legacyCandidate).put("confidence",0.8).put("engine","legacy");
        JSONObject v2=new JSONObject().put("disposition",v2Disposition).put("confidence",confidence).put("reason","test");JSONArray items=new JSONArray();if(!v2Kind.isEmpty())items.put(new JSONObject().put("kind",v2Kind).put("summary","Summary "+signalId).put("importance",70).put("urgency",50));v2.put("items",items);
        JSONObject root=new JSONObject().put("schema","cognitive_shadow_001").put("signal_id",signalId).put("legacy",legacy).put("v2",v2).put("comparison",comparison).put("outcome",state.toUpperCase());
        return AiJobStore.modelRun(db,0,1,"cognitive_shadow","local",LocalModelManager.MODEL_NAME,"cognitive_v2_shadow",state,"hash-"+signalId,latency,0,20,confidence,root.toString(),"failed".equals(state)?"test failure":"");
    }

    private static void insertRaw(SQLiteDatabase db,long id,String source,String title,String body,long now){
        ContentValues v=new ContentValues();v.put("id",id);v.put("kind","notification");v.put("source",source);v.put("title",title);v.put("body",body);v.put("metadata_json","{}");v.put("fingerprint","eval-raw-"+id);v.put("state","filtered");v.put("disposition","CONTEXT");v.put("importance",0);v.put("reason","");v.put("occurred_at",now);v.put("retention_until",0);v.put("created_at",now);v.put("updated_at",now);v.put("confidence",0.8);v.put("filter_engine","deterministic_fast_gate");db.insertOrThrow("raw_signals",null,v);
    }

    private static TestDb open(String name){
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();File file=new File(context.getCacheDir(),name);delete(file);SQLiteDatabase raw=SQLiteDatabase.openOrCreateDatabase(file,null);CognitiveSchema.ensure(raw);raw.close();
        VaultDb vault=new VaultDb(context){@Override public SQLiteDatabase getWritableDatabase(){return SQLiteDatabase.openDatabase(file.getPath(),null,SQLiteDatabase.OPEN_READWRITE);}@Override public SQLiteDatabase getReadableDatabase(){return getWritableDatabase();}};
        SQLiteDatabase db=SQLiteDatabase.openDatabase(file.getPath(),null,SQLiteDatabase.OPEN_READWRITE);return new TestDb(file,vault,db);
    }
    private static long scalarLong(SQLiteDatabase db,String sql){Cursor c=db.rawQuery(sql,null);try{return c.moveToFirst()?c.getLong(0):0;}finally{c.close();}}
    private static String scalarString(SQLiteDatabase db,String sql){Cursor c=db.rawQuery(sql,null);try{return c.moveToFirst()&&!c.isNull(0)?c.getString(0):"";}finally{c.close();}}
    private static void delete(File file){if(file.exists())file.delete();new File(file.getPath()+"-wal").delete();new File(file.getPath()+"-shm").delete();new File(file.getPath()+"-journal").delete();}

    private static final class TestDb{
        final File file;final VaultDb vault;final SQLiteDatabase db;TestDb(File file,VaultDb vault,SQLiteDatabase db){this.file=file;this.vault=vault;this.db=db;}
        void close(){try{db.close();}catch(Throwable ignored){}try{vault.close();}catch(Throwable ignored){}delete(file);}
    }
}
