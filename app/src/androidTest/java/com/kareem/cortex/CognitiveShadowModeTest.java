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
import java.util.Collections;

@RunWith(AndroidJUnit4.class)
public final class CognitiveShadowModeTest {

    @Test public void signalFamilyClassifierIsContextOnlyHint(){
        assertEquals(SignalFamily.SYSTEM,SignalFamilyClassifier.classify("notification","android system","Battery","Charging 84%"));
        assertEquals(SignalFamily.COMMUNICATION,SignalFamilyClassifier.classify("notification","com.whatsapp","Ahmed","ممكن تبعتلي الملف؟"));
        assertEquals(SignalFamily.CONTENT,SignalFamilyClassifier.classify("notification","com.whatsapp","Ahmed","Ahmed sent a voice message"));
        assertEquals(SignalFamily.CONTENT,SignalFamilyClassifier.classify("notification","instagram","Sara","Sara sent you a reel"));
        assertEquals(SignalFamily.EVENT,SignalFamilyClassifier.classify("notification","calendar","Dentist","Tomorrow 4 PM"));
        assertEquals(SignalFamily.DELIVERY,SignalFamilyClassifier.classify("notification","amazon","Order","Your package is arriving today"));
    }

    @Test public void comparatorHighlightsLegacyContextMiss(){
        LegacyCognitiveSnapshot legacy=new LegacyCognitiveSnapshot("CONTEXT","",0.75,"legacy");
        CognitiveItem item=new CognitiveItem(CognitiveKind.CONTENT,"Ahmed sent a voice note.",45,20,"Ahmed",null,false,false,true);
        CognitiveResult v2=new CognitiveResult(CognitiveDisposition.DERIVE,0.92,"Shared content",Collections.singletonList(item));
        JSONObject o=CognitiveShadowComparator.compare(41,legacy,v2);
        assertEquals("V2_FOUND_MISSED_VALUE",o.optString("comparison"));
        assertEquals(41,o.optLong("signal_id"));
        assertFalse(o.toString().contains("voice note payload raw body"));
    }

    @Test public void derivedAgreementRequiresMatchingSemanticKind(){
        LegacyCognitiveSnapshot legacy=new LegacyCognitiveSnapshot("ACTION","ACTION",0.88,"legacy_model");
        CognitiveResult same=new CognitiveResult(CognitiveDisposition.DERIVE,0.9,"",Collections.singletonList(
                new CognitiveItem(CognitiveKind.ACTION,"Send the file.",70,50,"",null,true,false,false)));
        CognitiveResult different=new CognitiveResult(CognitiveDisposition.DERIVE,0.9,"",Collections.singletonList(
                new CognitiveItem(CognitiveKind.WAITING,"Wait for the file.",70,50,"",null,false,true,false)));
        assertEquals("BOTH_DERIVE",CognitiveShadowComparator.comparison(legacy,same));
        assertEquals("DERIVED_KIND_DISAGREEMENT",CognitiveShadowComparator.comparison(legacy,different));
    }

    @Test public void inputFactoryUsesBoundedThreadContextAndRedactsSecrets(){
        TestDb x=open("cognitive-shadow-input.db");
        try{
            long now=System.currentTimeMillis();
            insertRaw(x.db,1,"notification","com.whatsapp","Auth","Auth\nYour OTP is 123456",now-2000,7,"CONTEXT","{\"sender\":\"Auth\"}");
            insertRaw(x.db,2,"notification","com.whatsapp","Mona","Mona\nهبعتلك النسخة المعدلة بكرة",now,7,"CONTEXT","{\"sender\":\"Mona\"}");
            CognitiveInput input=CognitiveInputFactory.load(x.vault,2);
            assertNotNull(input);assertEquals(SignalFamily.COMMUNICATION,input.family);assertEquals("Mona",input.sender);assertEquals("هبعتلك النسخة المعدلة بكرة",input.latestText);
            assertEquals(2,input.recentContext.size());assertTrue(input.recentContext.get(0).sensitiveRedacted);assertFalse(input.recentContext.get(1).sensitiveRedacted);
            String prompt=CognitivePromptBuilder.build(input);
            assertTrue(prompt.contains("[SENSITIVE CONTENT REDACTED]"));assertFalse(prompt.contains("123456"));
        }catch(Exception e){throw new AssertionError(e);}finally{x.close();}
    }

    @Test public void humanShadowRatingWritesFeedbackOnly(){
        TestDb x=open("cognitive-shadow-feedback.db");
        try{
            long now=System.currentTimeMillis();insertRaw(x.db,1,"notification","com.whatsapp","Ahmed","Ahmed\nممكن تبعتلي الملف؟",now,0,"CONTEXT","{}");
            ContentValues d=new ContentValues();d.put("kind","ACTION");d.put("title","Legacy item");d.put("body","legacy");d.put("state","open");d.put("confidence",0.8);d.put("importance",70);d.put("fingerprint","shadow-safety-derived");d.put("metadata_json","{}");d.put("created_at",now);d.put("updated_at",now);assertTrue(x.db.insertOrThrow("derived_items",null,d)>0);
            int derivedBefore=count(x.db,"derived_items");String dispositionBefore=scalarString(x.db,"SELECT disposition FROM raw_signals WHERE id=1");
            JSONObject out=new JSONObject().put("schema","cognitive_shadow_001").put("signal_id",1).put("comparison","V2_FOUND_MISSED_VALUE")
                    .put("legacy",new JSONObject().put("disposition","CONTEXT"))
                    .put("v2",new JSONObject().put("disposition","DERIVE").put("items",new JSONArray().put(new JSONObject().put("kind","ACTION").put("summary","Send Ahmed the file."))));
            long runId=AiJobStore.modelRun(x.vault,0,1,"cognitive_shadow","local",LocalModelManager.MODEL_NAME,"cognitive_v2_shadow","complete","hash",100,0,30,0.9,out.toString(),"");
            CognitiveShadowStore.rate(x.vault,runId,"SHADOW_V2_BETTER");
            assertEquals(derivedBefore,count(x.db,"derived_items"));assertEquals(dispositionBefore,scalarString(x.db,"SELECT disposition FROM raw_signals WHERE id=1"));
            assertEquals(1,scalarLong(x.db,"SELECT COUNT(*) FROM feedback_events WHERE target_type='model_run' AND target_id="+runId+" AND event_type='SHADOW_V2_BETTER'"));
            CognitiveShadowStore.Stats stats=CognitiveShadowStore.stats(x.vault);
            assertEquals(0,stats.shadowDerivedMutations);assertEquals(1,stats.ratedMisses);assertEquals(1,stats.recoveredMisses);assertEquals(100.0,stats.missRecoveryRate(),0.001);
        }catch(Exception e){throw new AssertionError(e);}finally{x.close();}
    }

    @Test public void shadowStatsExposeInvalidContractAndMutationGuard(){
        TestDb x=open("cognitive-shadow-stats.db");
        try{
            JSONObject failed=new JSONObject().put("schema","cognitive_shadow_001").put("signal_id",1).put("outcome","FAILED").put("failure_kind","INVALID_CONTRACT");
            AiJobStore.modelRun(x.vault,0,1,"cognitive_shadow","local",LocalModelManager.MODEL_NAME,"cognitive_v2_shadow","failed","",20,0,0,0,failed.toString(),"invalid");
            CognitiveShadowStore.Stats before=CognitiveShadowStore.stats(x.vault);assertEquals(1,before.invalidContract);assertEquals(100.0,before.invalidJsonRate(),0.001);assertEquals(0,before.shadowDerivedMutations);

            long now=System.currentTimeMillis();ContentValues d=new ContentValues();d.put("kind","ACTION");d.put("title","bad shadow write");d.put("body","");d.put("state","open");d.put("confidence",0.9);d.put("importance",80);d.put("fingerprint","illegal-shadow-derived");d.put("metadata_json","{\"policy\":\"cognitive_v2_shadow_001\"}");d.put("created_at",now);d.put("updated_at",now);x.db.insertOrThrow("derived_items",null,d);
            assertEquals(1,CognitiveShadowStore.stats(x.vault).shadowDerivedMutations);
        }catch(Exception e){throw new AssertionError(e);}finally{x.close();}
    }

    @Test public void compatibilityCapturePathDoesNotScheduleShadow(){
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();boolean old=CognitiveFeatureFlags.shadowEnabled(context);CognitiveFeatureFlags.setShadowEnabled(context,true);
        TestDb x=open("cognitive-shadow-compat.db");
        try{
            long now=System.currentTimeMillis();MasterRelevanceFilter.Signal s=new MasterRelevanceFilter.Signal("notification","android system","Battery","Charging 84%","{}",now,false);
            long id=RawSignalStore.capture(x.vault,s);assertTrue(id>0);assertEquals(0,scalarLong(x.db,"SELECT COUNT(*) FROM model_runs WHERE role='cognitive_shadow'"));
        }finally{CognitiveFeatureFlags.setShadowEnabled(context,old);x.close();}
    }

    private static TestDb open(String name){
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();File file=new File(context.getCacheDir(),name);delete(file);SQLiteDatabase raw=SQLiteDatabase.openOrCreateDatabase(file,null);CognitiveSchema.ensure(raw);raw.close();
        VaultDb vault=new VaultDb(context){@Override public SQLiteDatabase getWritableDatabase(){return SQLiteDatabase.openDatabase(file.getPath(),null,SQLiteDatabase.OPEN_READWRITE);}@Override public SQLiteDatabase getReadableDatabase(){return getWritableDatabase();}};
        SQLiteDatabase db=SQLiteDatabase.openDatabase(file.getPath(),null,SQLiteDatabase.OPEN_READWRITE);return new TestDb(file,vault,db);
    }

    private static void insertRaw(SQLiteDatabase db,long id,String kind,String source,String title,String body,long occurred,long thread,String disposition,String metadata){
        ContentValues v=new ContentValues();v.put("id",id);v.put("kind",kind);v.put("source",source);v.put("title",title);v.put("body",body);v.put("metadata_json",metadata);v.put("fingerprint","shadow-raw-"+id);v.put("state","filtered");v.put("disposition",disposition);v.put("importance",0);v.put("reason","");v.put("occurred_at",occurred);v.put("retention_until",0);v.put("created_at",occurred);v.put("updated_at",occurred);v.put("thread_id",thread);v.put("confidence",0.8);v.put("filter_engine","deterministic_fast_gate");db.insertOrThrow("raw_signals",null,v);
    }
    private static int count(SQLiteDatabase db,String table){return(int)scalarLong(db,"SELECT COUNT(*) FROM "+table);}
    private static long scalarLong(SQLiteDatabase db,String sql){Cursor c=db.rawQuery(sql,null);try{return c.moveToFirst()?c.getLong(0):0;}finally{c.close();}}
    private static String scalarString(SQLiteDatabase db,String sql){Cursor c=db.rawQuery(sql,null);try{return c.moveToFirst()&&!c.isNull(0)?c.getString(0):"";}finally{c.close();}}
    private static void delete(File file){if(file.exists())file.delete();new File(file.getPath()+"-wal").delete();new File(file.getPath()+"-shm").delete();new File(file.getPath()+"-journal").delete();}

    private static final class TestDb{
        final File file;final VaultDb vault;final SQLiteDatabase db;TestDb(File file,VaultDb vault,SQLiteDatabase db){this.file=file;this.vault=vault;this.db=db;}
        void close(){try{db.close();}catch(Throwable ignored){}try{vault.close();}catch(Throwable ignored){}delete(file);}
    }
}
