package com.kareem.cortex;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;

import org.json.JSONObject;

/** Temporary/raw signal layer. Exactly one production authority route owns every captured signal. */
public final class RawSignalStore {
    private static final String FAST_POLICY="relevance_fast_004";
    private static final String LEGACY_COGNITIVE_VERSION="legacy-cognitive-003";
    private RawSignalStore(){}

    public static void ensure(VaultDb db){CognitiveStore.ensure(db);}
    public static long capture(VaultDb db,MasterRelevanceFilter.Signal signal){return captureInternal(null,db,signal);}
    public static long capture(Context context,VaultDb db,MasterRelevanceFilter.Signal signal){return captureInternal(context,db,signal);}

    private static long captureInternal(Context context,VaultDb db,MasterRelevanceFilter.Signal signal){
        ensure(db);cleanup(db);
        String contentHash=Fingerprint.text(signal.text());
        String fp=Fingerprint.text(signal.kind+"|"+signal.source+"|"+signal.title+"|"+signal.body+"|"+(signal.occurredAt/60000));
        long existing=find(db,fp);if(existing>0)return existing;
        MasterRelevanceFilter.Decision fast=fastDecision(signal);long now=System.currentTimeMillis(),retention=retentionUntil(now,fast.disposition);

        ContentValues v=new ContentValues();v.put("kind",signal.kind);v.put("source",signal.source);v.put("title",signal.title);v.put("body",signal.body);v.put("metadata_json",signal.metadataJson);v.put("fingerprint",fp);v.put("content_hash",contentHash);v.put("state","filtered");v.put("disposition",fast.disposition.name());v.put("importance",fast.importance);v.put("confidence",fast.confidence);v.put("policy_version",FAST_POLICY);v.put("filter_engine","deterministic_fast_gate");v.put("reason",fast.reason);v.put("occurred_at",signal.occurredAt>0?signal.occurredAt:now);v.put("retention_until",retention);v.put("created_at",now);v.put("updated_at",now);v.put("cognitive_state","LEGACY_UNRESOLVED");v.put("cognitive_version",LEGACY_COGNITIVE_VERSION);v.put("final_reason","Awaiting cognitive authority route");v.put("cognitive_updated_at",now);
        long signalId=db.getWritableDatabase().insert("raw_signals",null,v);if(signalId<=0){DiagnosticsLog.warn(db,"RawSignalStore","capture_insert","failed","RAW_SIGNAL_INSERT",0,0,0,0,0,null);return signalId;}

        long threadId=SignalThreadStore.attach(db,signalId,signal);boolean hardNoise=fast.disposition==MasterRelevanceFilter.Disposition.IGNORE;
        CognitiveAuthorityRouter.Decision routing=CognitiveAuthorityRouter.routeDetailed(context,threadId,signal.source,senderHint(signal),hardNoise);
        if(routing.route==CognitiveAuthorityRouter.Route.HARD_GATE){markHardGate(db,signalId,fast);return signalId;}
        if(routing.route==CognitiveAuthorityRouter.Route.LEGACY){
            LegacyCognitiveFallback.runLegacyAuthority(context,db,signalId,threadId);
            if(context!=null)try{CognitiveAdjudicatorV2.enqueueShadow(context,signalId,threadId);}catch(Throwable ignored){}
            return signalId;
        }

        final CognitiveAuthorityMode mode=routing.route==CognitiveAuthorityRouter.Route.V2_PRIMARY?CognitiveAuthorityMode.V2_PRIMARY:CognitiveAuthorityMode.CANARY;
        final String policy=mode==CognitiveAuthorityMode.V2_PRIMARY?CognitiveAdjudicatorV2.PRIMARY_POLICY:CognitiveAdjudicatorV2.CANARY_POLICY;
        final String queuedReason=mode==CognitiveAuthorityMode.V2_PRIMARY?"V2 primary queued for local authority":"V2 canary queued for local authority";

        if(!CognitiveStore.updateRawCognitiveState(db,signalId,"LOCAL_QUEUED",policy,queuedReason)){
            LegacyCognitiveFallback.fallback(context,db,signalId,threadId,V2FailureReason.STATE_TRANSITION_FAILED,mode,false,true);
            return signalId;
        }

        final Context app=context==null?null:context.getApplicationContext();
        CognitiveAdjudicatorV2.enqueueAuthoritative(
                app,signalId,threadId,mode,routing.reason.name(),routing.bucket,
                new CognitiveAdjudicatorV2.AuthorityCallback(){
                    @Override public void accepted(CognitiveResult result,long modelRunId){/* CognitiveDecisionApplier already committed authority. */}
                    @Override public void fallback(String reason){
                        V2FailureReason why=V2FailureReason.from(reason);
                        if(why==V2FailureReason.SUPERSEDED){
                            VaultDb x=new VaultDb(app);
                            try{CognitiveStore.updateRawCognitiveState(x,signalId,"SUPERSEDED",policy,"Newer signal superseded V2 generation");}
                            finally{try{x.close();}catch(Throwable ignored){}}
                            return;
                        }
                        LegacyCognitiveFallback.fallback(app,signalId,threadId,why);
                    }
                }
        );
        return signalId;
    }

    /** Caller guard used only by LegacyCognitiveFallback before it starts the old local adjudicator. */
    public static boolean shouldEnqueueLegacyModel(VaultDb db,long signalId){
        if(db==null||signalId<=0)return false;ensure(db);Cursor c=db.getReadableDatabase().query("raw_signals",new String[]{"cognitive_state","cognitive_version","disposition","filter_engine"},"id=?",new String[]{String.valueOf(signalId)},null,null,null,"1");
        if(!c.moveToFirst()){c.close();return false;}String state=n(c.getString(0)),version=n(c.getString(1)),disposition=n(c.getString(2)),engine=n(c.getString(3));c.close();
        if("IGNORE".equalsIgnoreCase(disposition)&&"deterministic_fast_gate".equalsIgnoreCase(engine))return false;
        if(CognitiveAdjudicatorV2.CANARY_POLICY.equals(version)||CognitiveAdjudicatorV2.PRIMARY_POLICY.equals(version))return "LEGACY_UNRESOLVED".equals(state)||"LEGACY_FALLBACK_RUNNING".equals(state);
        return true;
    }

    /** Existing Legacy production authority pipeline. Only LegacyCognitiveFallback calls it in runtime paths. */
    static MasterRelevanceFilter.Decision runLegacyPipeline(VaultDb db,long signalId,long threadId,MasterRelevanceFilter.Signal signal,MasterRelevanceFilter.Decision fast){
        MasterRelevanceFilter.Decision authority=fast;boolean threadAuthority=false;
        if(threadId>0){MasterRelevanceFilter.Decision threaded=ThreadRelevanceEngine.onSignal(db,threadId,signalId);if(threaded!=null){authority=threaded;threadAuthority=true;}}
        if(authority.durable()&&(!threadAuthority||RelevanceDecisionStatusStore.isApplied(db,signalId)))promote(db,signalId,threadId,signal,authority,!threadAuthority);
        return authority;
    }

    static void syncLegacyCognitiveState(VaultDb db,long signalId,String fallbackReason){syncLegacyCognitiveState(db,signalId,"V2 canary",fallbackReason);}
    static void syncLegacyCognitiveState(VaultDb db,long signalId,String fallbackPrefix,String fallbackReason){
        Cursor c=db.getReadableDatabase().query("raw_signals",new String[]{"disposition","state","promoted_item_id"},"id=?",new String[]{String.valueOf(signalId)},null,null,null,"1");if(!c.moveToFirst()){c.close();return;}String disposition=n(c.getString(0)),state=n(c.getString(1));long promoted=c.getLong(2);c.close();String cognitive="LEGACY_UNRESOLVED";
        if(promoted>0||state.startsWith("derived")||"ACTION".equalsIgnoreCase(disposition)||"WAITING".equalsIgnoreCase(disposition)||"DECISION".equalsIgnoreCase(disposition))cognitive="DERIVED";
        else if("IGNORE".equalsIgnoreCase(disposition))cognitive="IGNORED_NOISE";
        else if("REVIEW".equalsIgnoreCase(disposition))cognitive="REVIEW_REQUIRED";
        else if("CONTEXT".equalsIgnoreCase(disposition))cognitive="CONTEXT_ONLY";
        String legacy="Legacy authority: "+(disposition.isEmpty()?"unresolved":disposition);
        String reason=empty(fallbackReason)?legacy:(empty(fallbackPrefix)?"V2":fallbackPrefix)+" fallback ("+fallbackReason+") -> "+legacy;
        CognitiveStore.updateRawCognitiveState(db,signalId,cognitive,LEGACY_COGNITIVE_VERSION,reason);
    }

    private static void markHardGate(VaultDb db,long signalId,MasterRelevanceFilter.Decision fast){CognitiveStore.updateRawCognitiveState(db,signalId,"IGNORED_NOISE",LEGACY_COGNITIVE_VERSION,"Hard deterministic noise gate: "+n(fast.reason));}
    private static MasterRelevanceFilter.Decision fastDecision(MasterRelevanceFilter.Signal s){if(s!=null&&"screen_context".equalsIgnoreCase(s.kind))return new MasterRelevanceFilter.Decision(MasterRelevanceFilter.Disposition.CONTEXT,38,"explicit screen evidence; short-lived context until the user asks or promotes it","",0.94);return MasterRelevanceFilter.evaluateFast(s);}

    private static long promote(VaultDb db,long signalId,long threadId,MasterRelevanceFilter.Signal s,MasterRelevanceFilter.Decision d,boolean createDerived){
        try{
            JSONObject meta=new JSONObject();meta.put("raw_signal_id",signalId);if(threadId>0)meta.put("thread_id",threadId);meta.put("source",s.source);meta.put("occurred_at",s.occurredAt);meta.put("relevance_disposition",d.disposition.name());meta.put("importance",d.importance);meta.put("filter_reason",d.reason);meta.put("policy_version",createDerived?FAST_POLICY:"thread_authority");if(!s.metadataJson.isEmpty())meta.put("source_metadata",new JSONObject(s.metadataJson));
            String title=s.title.isEmpty()?friendlyTitle(s):s.title,tags="signal,"+s.kind.toLowerCase()+",importance_"+d.importance;long inserted=db.insert(typeFor(s),s.source,title,s.body,categoryFor(s,d),tags,"",Fingerprint.text("promoted-signal|"+signalId),meta.toString());long itemId=inserted<0?-inserted:inserted;
            if(itemId>0){ContentValues u=new ContentValues();u.put("promoted_item_id",itemId);u.put("state","promoted");u.put("retention_until",0);u.put("updated_at",System.currentTimeMillis());db.getWritableDatabase().update("raw_signals",u,"id=?",new String[]{String.valueOf(signalId)});CognitiveStore.link(db,"raw_signal",signalId,"memory",itemId,"promoted_to",1.0,"{\"policy\":\""+(createDerived?FAST_POLICY:"thread_authority")+"\"}");if(threadId>0)CognitiveStore.link(db,"memory",itemId,"thread",threadId,"from_thread",1.0,"");
                if(createDerived&&(d.disposition==MasterRelevanceFilter.Disposition.ACTION||d.disposition==MasterRelevanceFilter.Disposition.WAITING||d.disposition==MasterRelevanceFilter.Disposition.DECISION)){long derived=CognitiveStore.addDerived(db,d.disposition.name(),title,s.body,"open",d.confidence,d.importance,Fingerprint.text("derived|"+d.disposition.name()+"|"+signalId),meta.toString());if(derived>0){CognitiveStore.setDerivedRouting(db,derived,s.source,threadId,signalId,d.disposition.name());CognitiveStore.link(db,"raw_signal",signalId,"derived",derived,"supports",1.0,"");CognitiveStore.link(db,"derived",derived,"memory",itemId,"grounded_by",1.0,"");if(threadId>0)CognitiveStore.link(db,"derived",derived,"thread",threadId,"derived_from_thread",1.0,"");}}
            }
            return itemId;
        }catch(Throwable e){DiagnosticsLog.error(db,"RawSignalStore","promote",e,"RAW_SIGNAL_PROMOTE",0,threadId,signalId,0,0,null);return 0;}
    }

    public static long promotedItemId(VaultDb db,long signalId){ensure(db);Cursor c=db.getReadableDatabase().query("raw_signals",new String[]{"promoted_item_id"},"id=?",new String[]{String.valueOf(signalId)},null,null,null,"1");long id=c.moveToFirst()?c.getLong(0):0;c.close();return id;}
    public static long threadId(VaultDb db,long signalId){ensure(db);Cursor c=db.getReadableDatabase().query("raw_signals",new String[]{"thread_id"},"id=?",new String[]{String.valueOf(signalId)},null,null,null,"1");long id=c.moveToFirst()?c.getLong(0):0;c.close();return id;}
    public static void cleanup(VaultDb db){ensure(db);long now=System.currentTimeMillis();String where="promoted_item_id=0 AND retention_until>0 AND retention_until<? AND NOT EXISTS (SELECT 1 FROM source_links l JOIN derived_items d ON d.id=l.to_id WHERE l.from_type='raw_signal' AND l.from_id=raw_signals.id AND l.to_type='derived' AND d.state IN ('pending','open'))";db.getWritableDatabase().delete("raw_signals",where,new String[]{String.valueOf(now)});}
    private static long find(VaultDb db,String fp){Cursor c=db.getReadableDatabase().query("raw_signals",new String[]{"id"},"fingerprint=?",new String[]{fp},null,null,null,"1");long id=c.moveToFirst()?c.getLong(0):0;c.close();return id;}
    private static String senderHint(MasterRelevanceFilter.Signal signal){try{JSONObject o=new JSONObject(n(signal.metadataJson));String sender=n(o.optString("sender",""));if(sender.isEmpty())sender=n(o.optString("person_hint",""));if(!sender.isEmpty())return sender;}catch(Throwable ignored){}return n(signal.title);}
    private static long retentionUntil(long now,MasterRelevanceFilter.Disposition d){if(d==MasterRelevanceFilter.Disposition.IGNORE)return now+6L*60*60*1000;if(d==MasterRelevanceFilter.Disposition.CONTEXT||d==MasterRelevanceFilter.Disposition.REVIEW)return now+7L*24*60*60*1000;return 0;}
    private static String typeFor(MasterRelevanceFilter.Signal s){return "notification".equalsIgnoreCase(s.kind)?"NOTIFICATION":"SIGNAL";}
    private static String categoryFor(MasterRelevanceFilter.Signal s,MasterRelevanceFilter.Decision d){if(d.disposition==MasterRelevanceFilter.Disposition.ACTION)return"Actions";if(d.disposition==MasterRelevanceFilter.Disposition.WAITING)return"Waiting";if(d.disposition==MasterRelevanceFilter.Disposition.DECISION)return"Decisions";return"Memory";}
    private static String friendlyTitle(MasterRelevanceFilter.Signal s){return "notification".equalsIgnoreCase(s.kind)?"Notification":"Signal";}
    private static boolean empty(String s){return s==null||s.trim().isEmpty();}
    private static String n(String s){return s==null?"":s.trim();}
}
