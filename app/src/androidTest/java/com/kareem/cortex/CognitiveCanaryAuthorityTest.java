package com.kareem.cortex;

import static org.junit.Assert.*;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;

@RunWith(AndroidJUnit4.class)
public final class CognitiveCanaryAuthorityTest {

    @Test public void derivePersistsAtomicAuthorityAndCompleteProvenance(){
        TestDb x=open("cognitive-canary-derive.db");
        try{
            long signal=insertRaw(x.sql,"com.whatsapp","Ahmed","ممكن تبعتلي الملف النهائي؟","CONTEXT","deterministic_fast_gate");
            CognitiveItem item=new CognitiveItem(CognitiveKind.ACTION,"Send Ahmed the final file",88,82,"Ahmed",null,true,false,false);
            CognitiveResult result=new CognitiveResult(CognitiveDisposition.DERIVE,0.93,"Clear request requiring user action",Collections.singletonList(item));
            LocalBrainRun run=new LocalBrainRun(result,"",250,20,200,30,120f,true);

            CognitiveStore.CanaryApply applied=CognitiveStore.applyCanaryAuthority(x.vault,signal,0,result,run,250,"input-hash",CognitiveAdjudicatorV2.CANARY_POLICY);
            assertTrue(applied.modelRunId>0);assertEquals(1,applied.derivedIds.size());
            assertEquals("DERIVED",scalarString(x.sql,"SELECT cognitive_state FROM raw_signals WHERE id="+signal));
            assertEquals(CognitiveAdjudicatorV2.CANARY_POLICY,scalarString(x.sql,"SELECT cognitive_version FROM raw_signals WHERE id="+signal));
            assertEquals("ACTION",scalarString(x.sql,"SELECT disposition FROM raw_signals WHERE id="+signal));
            assertEquals("cognitive_v2_canary",scalarString(x.sql,"SELECT route FROM model_runs WHERE id="+applied.modelRunId));
            assertEquals(applied.modelRunId,scalarLong(x.sql,"SELECT model_run_id FROM derived_items WHERE id="+applied.derivedIds.get(0)));
            assertEquals(88,scalarLong(x.sql,"SELECT importance FROM derived_items WHERE id="+applied.derivedIds.get(0)));
            assertEquals(82,scalarLong(x.sql,"SELECT urgency FROM derived_items WHERE id="+applied.derivedIds.get(0)));
            assertEquals(1,scalarLong(x.sql,"SELECT requires_user_action FROM derived_items WHERE id="+applied.derivedIds.get(0)));
            assertEquals(0,scalarLong(x.sql,"SELECT COUNT(*) FROM derived_items WHERE metadata_json LIKE '%cognitive_v2_canary%' AND model_run_id=0"));
            assertTrue(scalarLong(x.sql,"SELECT COUNT(*) FROM source_links WHERE from_type='model_run' AND from_id="+applied.modelRunId+" AND to_type='derived'")>0);
            assertFalse(RawSignalStore.shouldEnqueueLegacyModel(x.vault,signal));
        }finally{x.close();}
    }

    @Test public void contextAuthorityCreatesNoDerivedItem(){
        TestDb x=open("cognitive-canary-context.db");
        try{
            long signal=insertRaw(x.sql,"com.whatsapp","Ahmed","شكراً","CONTEXT","deterministic_fast_gate");
            CognitiveResult result=new CognitiveResult(CognitiveDisposition.CONTEXT,0.91,"Acknowledgement only",Collections.emptyList());
            LocalBrainRun run=new LocalBrainRun(result,"",180,0,160,18,100f,true);
            long before=scalarLong(x.sql,"SELECT COUNT(*) FROM derived_items");
            CognitiveStore.CanaryApply applied=CognitiveStore.applyCanaryAuthority(x.vault,signal,0,result,run,180,"input-hash",CognitiveAdjudicatorV2.CANARY_POLICY);
            assertTrue(applied.modelRunId>0);assertEquals(before,scalarLong(x.sql,"SELECT COUNT(*) FROM derived_items"));
            assertEquals("CONTEXT_ONLY",scalarString(x.sql,"SELECT cognitive_state FROM raw_signals WHERE id="+signal));
            assertEquals("CONTEXT",scalarString(x.sql,"SELECT disposition FROM raw_signals WHERE id="+signal));
            assertFalse(RawSignalStore.shouldEnqueueLegacyModel(x.vault,signal));
        }finally{x.close();}
    }

    @Test public void legacyModelGuardSeparatesHardGateCanaryAndLegacy(){
        TestDb x=open("cognitive-canary-guard.db");
        try{
            long canary=insertRaw(x.sql,"com.whatsapp","Ahmed","hello","CONTEXT","deterministic_fast_gate");
            CognitiveStore.updateRawCognitiveState(x.vault,canary,"LOCAL_QUEUED",CognitiveAdjudicatorV2.CANARY_POLICY,"queued");
            assertFalse(RawSignalStore.shouldEnqueueLegacyModel(x.vault,canary));

            long legacy=insertRaw(x.sql,"com.whatsapp","Mona","hello","CONTEXT","deterministic_fast_gate");
            CognitiveStore.updateRawCognitiveState(x.vault,legacy,"CONTEXT_ONLY","legacy-cognitive-003","legacy");
            assertTrue(RawSignalStore.shouldEnqueueLegacyModel(x.vault,legacy));

            long noise=insertRaw(x.sql,"android.systemui","Battery","85%","IGNORE","deterministic_fast_gate");
            CognitiveStore.updateRawCognitiveState(x.vault,noise,"IGNORED_NOISE","legacy-cognitive-003","hard gate");
            assertFalse(RawSignalStore.shouldEnqueueLegacyModel(x.vault,noise));
        }finally{x.close();}
    }

    @Test public void v7FieldsCarryEventAndContentWithoutSchemaChanges(){
        TestDb x=open("cognitive-canary-taxonomy.db");
        try{
            long signal=insertRaw(x.sql,"com.whatsapp","Ahmed","voice message","CONTEXT","deterministic_fast_gate");
            CognitiveItem content=new CognitiveItem(CognitiveKind.CONTENT,"Ahmed sent a voice message",70,30,"Ahmed",null,false,false,true);
            CognitiveItem event=new CognitiveItem(CognitiveKind.EVENT,"Dentist tomorrow at 4 PM",90,80,"",System.currentTimeMillis()+3600000,false,false,false);
            CognitiveResult result=new CognitiveResult(CognitiveDisposition.DERIVE,0.94,"Two useful items",Arrays.asList(content,event));
            LocalBrainRun run=new LocalBrainRun(result,"",300,0,250,35,110f,false);
            CognitiveStore.CanaryApply applied=CognitiveStore.applyCanaryAuthority(x.vault,signal,0,result,run,300,"input-hash",CognitiveAdjudicatorV2.CANARY_POLICY);
            assertEquals(2,applied.derivedIds.size());assertEquals(1,scalarLong(x.sql,"SELECT COUNT(*) FROM derived_items WHERE kind='CONTENT' AND requires_content_extraction=1"));assertEquals(1,scalarLong(x.sql,"SELECT COUNT(*) FROM derived_items WHERE kind='EVENT' AND due_at>0"));
            assertEquals("EVENT",scalarString(x.sql,"SELECT disposition FROM raw_signals WHERE id="+signal));
        }finally{x.close();}
    }

    @Test public void staleThreadResultRollsBackWholeAuthorityTransaction(){
        TestDb x=open("cognitive-canary-superseded.db");
        try{
            long oldSignal=insertRaw(x.sql,"com.whatsapp","Ahmed","هبعتلك الملف","CONTEXT","deterministic_fast_gate");
            long threadId=42L;ContentValues oldThread=new ContentValues();oldThread.put("thread_id",threadId);oldThread.put("occurred_at",System.currentTimeMillis()-2000);x.sql.update("raw_signals",oldThread,"id=?",new String[]{String.valueOf(oldSignal)});
            long newerSignal=insertRaw(x.sql,"com.whatsapp","Ahmed","خلاص بعته","CONTEXT","deterministic_fast_gate");ContentValues newThread=new ContentValues();newThread.put("thread_id",threadId);newThread.put("occurred_at",System.currentTimeMillis());x.sql.update("raw_signals",newThread,"id=?",new String[]{String.valueOf(newerSignal)});

            CognitiveItem item=new CognitiveItem(CognitiveKind.WAITING,"Wait for the file",80,60,"Ahmed",null,false,true,false);
            CognitiveResult result=new CognitiveResult(CognitiveDisposition.DERIVE,0.94,"Old message looked like waiting",Collections.singletonList(item));
            LocalBrainRun run=new LocalBrainRun(result,"",260,0,220,25,95f,false);

            try{
                CognitiveStore.applyCanaryAuthority(x.vault,oldSignal,threadId,result,run,260,"stale-input",CognitiveAdjudicatorV2.CANARY_POLICY);
                fail("Expected stale canary result to be rejected");
            }catch(IllegalStateException expected){assertEquals("CANARY_SUPERSEDED",expected.getMessage());}

            assertEquals(0,scalarLong(x.sql,"SELECT COUNT(*) FROM model_runs WHERE route='cognitive_v2_canary'"));
            assertEquals(0,scalarLong(x.sql,"SELECT COUNT(*) FROM derived_items WHERE metadata_json LIKE '%\"authority\":\"CANARY\"%'"));
            assertEquals(0,scalarLong(x.sql,"SELECT COUNT(*) FROM source_links WHERE metadata_json LIKE '%\"authority\":\"CANARY\"%'"));
            assertEquals("CONTEXT",scalarString(x.sql,"SELECT disposition FROM raw_signals WHERE id="+oldSignal));
        }finally{x.close();}
    }

    private static long insertRaw(SQLiteDatabase db,String source,String title,String body,String disposition,String engine){
        long now=System.currentTimeMillis();ContentValues v=new ContentValues();v.put("kind","notification");v.put("source",source);v.put("title",title);v.put("body",body);v.put("metadata_json","{}");v.put("fingerprint",Fingerprint.text(source+"|"+title+"|"+body+"|"+now+"|"+Math.random()));v.put("state","filtered");v.put("disposition",disposition);v.put("importance",0);v.put("reason","");v.put("occurred_at",now);v.put("retention_until",0);v.put("created_at",now);v.put("updated_at",now);v.put("confidence",0.8);v.put("filter_engine",engine);v.put("cognitive_state","LEGACY_UNRESOLVED");v.put("cognitive_version","legacy-cognitive-003");v.put("cognitive_updated_at",now);return db.insertOrThrow("raw_signals",null,v);
    }

    private static TestDb open(String name){
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();File file=new File(context.getCacheDir(),name);delete(file);SQLiteDatabase sql=SQLiteDatabase.openOrCreateDatabase(file,null);CognitiveSchema.ensure(sql);TestVault vault=new TestVault(context,sql);return new TestDb(file,vault,sql);
    }
    private static long scalarLong(SQLiteDatabase db,String sql){Cursor c=db.rawQuery(sql,null);try{return c.moveToFirst()?c.getLong(0):0;}finally{c.close();}}
    private static String scalarString(SQLiteDatabase db,String sql){Cursor c=db.rawQuery(sql,null);try{return c.moveToFirst()&&!c.isNull(0)?c.getString(0):"";}finally{c.close();}}
    private static void delete(File file){if(file.exists())file.delete();new File(file.getPath()+"-wal").delete();new File(file.getPath()+"-shm").delete();new File(file.getPath()+"-journal").delete();}

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
