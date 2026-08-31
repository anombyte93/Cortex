package com.kareem.cortex;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.nio.*;
import java.util.*;

public final class SemanticIndex {
    private static final EmbeddingProvider PROVIDER=new LocalSemanticEmbedder();
    /**
     * Repeated Ask queries used to re-read and decode the entire embedding corpus every time.
     * Keep only that immutable/read-only representation warm. Query embedding, lexical search,
     * lifecycle policy, ranking and answer construction still execute independently per request.
     *
     * Weak keys avoid pinning a VaultDb for process lifetime. Every indexItem mutation invalidates
     * the snapshot before the next search, so this is a performance cache rather than a semantic
     * shortcut and cannot intentionally change which evidence is eligible.
     */
    private static final Map<VaultDb,CorpusSnapshot> CORPUS_CACHE=Collections.synchronizedMap(new WeakHashMap<VaultDb,CorpusSnapshot>());
    private SemanticIndex(){}

    public static void indexItem(VaultDb db,long itemId){KnowledgeItem item=db.getById(itemId);if(item==null)return;String body=combined(item);ArrayList<String> chunks=chunk(body,720,110);SQLiteDatabase sql=db.getWritableDatabase();long now=System.currentTimeMillis();sql.beginTransaction();try{sql.execSQL("DELETE FROM embeddings WHERE chunk_id IN (SELECT id FROM memory_chunks WHERE item_id=?)",new Object[]{itemId});sql.delete("memory_chunks","item_id=?",new String[]{String.valueOf(itemId)});int i=0;for(String text:chunks){ContentValues c=new ContentValues();c.put("item_id",itemId);c.put("chunk_index",i++);c.put("chunk_text",text);c.put("created_at",now);long chunkId=sql.insert("memory_chunks",null,c);if(chunkId<0)continue;float[] vector=PROVIDER.embed(text);ContentValues e=new ContentValues();e.put("chunk_id",chunkId);e.put("provider",PROVIDER.name());e.put("version",PROVIDER.version());e.put("dims",PROVIDER.dimensions());e.put("vector",encode(vector));e.put("updated_at",now);sql.insert("embeddings",null,e);}sql.setTransactionSuccessful();}finally{sql.endTransaction();invalidateCorpus(db);}}
    public static int ensureIndexed(VaultDb db,int max){if(max<=0)return 0;int done=0;Cursor c=db.getReadableDatabase().rawQuery("SELECT k.id FROM knowledge_items k LEFT JOIN memory_chunks m ON m.item_id=k.id WHERE m.id IS NULL AND k.status='analyzed' GROUP BY k.id ORDER BY k.updated_at DESC LIMIT ?",new String[]{String.valueOf(max)});ArrayList<Long> ids=new ArrayList<>();while(c.moveToNext())ids.add(c.getLong(0));c.close();for(long id:ids){indexItem(db,id);done++;}return done;}

    public static ArrayList<SemanticHit> search(VaultDb db,String query,int limit){return searchInternal(db,query,limit,false,false);}
    public static ArrayList<SemanticHit> searchForAsk(VaultDb db,String query,int limit){return searchInternal(db,query,limit,true,true);}
    /** Raw Ask retrieval for V4 situation evidence attachment after truth reconciliation already happened. */
    static ArrayList<SemanticHit> searchForAskRaw(VaultDb db,String query,int limit){return searchInternal(db,query,limit,true,false);}

    private static ArrayList<SemanticHit> searchInternal(VaultDb db,String query,int limit,boolean askPolicy,boolean lifecyclePolicy){
        String q=query==null?"":query.trim();if(q.isEmpty())return new ArrayList<>();ensureIndexed(db,8);
        float[] qv=PROVIDER.embed(q);HashMap<Long,Double> semantic=new HashMap<>();HashMap<Long,String> snippets=new HashMap<>();CorpusSnapshot corpus=corpus(db);for(CorpusRow row:corpus.rows){long itemId=row.itemId;double s=cos(qv,row.vector);if(s>semantic.getOrDefault(itemId,-1.0)){semantic.put(itemId,s);snippets.put(itemId,bestSnippet(row.chunk,q));}}ArrayList<KnowledgeItem> lexical=db.lexicalSearch(q,120);HashMap<Long,Double> lex=new HashMap<>();for(int i=0;i<lexical.size();i++){double rank=1.0-(i/(double)Math.max(1,lexical.size()));lex.put(lexical.get(i).id,0.12+0.13*rank);}HashSet<Long> ids=new HashSet<>(semantic.keySet());ids.addAll(lex.keySet());ArrayList<SemanticHit> out=new ArrayList<>();long now=System.currentTimeMillis();for(long id:ids){KnowledgeItem k=db.getById(id);if(k==null||(askPolicy&&!AskSourcePolicy.allowSemantic(k,q))||(lifecyclePolicy&&!SituationTruthResolver.allowAskMemory(db,k)))continue;double sem=Math.max(0,semantic.getOrDefault(id,0.0));double lexicalBoost=lex.getOrDefault(id,0.0);double ageDays=Math.max(0,(now-k.createdAt)/86400000.0);double recency=0.05*Math.exp(-ageDays/120.0);double score=sem*0.78+lexicalBoost+recency;if(score<0.08)continue;String sn=snippets.get(id);if(sn==null||sn.isEmpty())sn=bestSnippet(combined(k),q);out.add(new SemanticHit(k,score,sn));}out.sort((a,b)->Double.compare(b.score,a.score));if(out.size()>limit)return new ArrayList<>(out.subList(0,limit));return out;}

    /** Explicit hook for tests/maintenance that intentionally replace or restore index state. */
    static void invalidateCorpus(VaultDb db){if(db!=null)CORPUS_CACHE.remove(db);}
    private static CorpusSnapshot corpus(VaultDb db){CorpusSnapshot hit=CORPUS_CACHE.get(db);if(hit!=null)return hit;synchronized(CORPUS_CACHE){hit=CORPUS_CACHE.get(db);if(hit!=null)return hit;ArrayList<CorpusRow> rows=new ArrayList<>();Cursor c=db.getReadableDatabase().rawQuery("SELECT m.item_id,m.chunk_text,e.vector FROM memory_chunks m JOIN embeddings e ON e.chunk_id=m.id WHERE e.provider=? AND e.version=?",new String[]{PROVIDER.name(),PROVIDER.version()});try{while(c.moveToNext())rows.add(new CorpusRow(c.getLong(0),c.getString(1),decode(c.getBlob(2),PROVIDER.dimensions())));}finally{c.close();}hit=new CorpusSnapshot(rows);CORPUS_CACHE.put(db,hit);return hit;}}

    public static ArrayList<SemanticHit> related(VaultDb db,KnowledgeItem item,int limit){String seed=combined(item);if(seed.length()>1400)seed=seed.substring(0,1400);ArrayList<SemanticHit> all=search(db,seed,limit+3);ArrayList<SemanticHit> out=new ArrayList<>();for(SemanticHit h:all)if(h.item.id!=item.id){out.add(h);if(out.size()>=limit)break;}return out;}
    static float[] itemVector(KnowledgeItem item){String seed=combined(item);if(seed.length()>1800)seed=seed.substring(0,1800);return PROVIDER.embed(seed);}static double similarity(float[] a,float[] b){return cos(a,b);}public static String providerLabel(){return PROVIDER.name()+" v"+PROVIDER.version()+" • "+PROVIDER.dimensions()+"D";}
    private static String combined(KnowledgeItem k){return nz(k.title)+"\n"+nz(k.summary)+"\n"+nz(k.extractedText)+"\n"+nz(k.rawText)+"\nCategory: "+nz(k.category)+"\nTags: "+nz(k.tags);}private static String nz(String s){return s==null?"":s;}
    static ArrayList<String> chunk(String text,int size,int overlap){ArrayList<String> out=new ArrayList<>();String s=text==null?"":text.trim();if(s.isEmpty())return out;int p=0;while(p<s.length()){int end=Math.min(s.length(),p+size);if(end<s.length()){int cut=-1;for(int i=end;i>p+size/2;i--){char ch=s.charAt(i-1);if(ch=='\n'||ch=='.'||ch=='!'||ch=='?'||ch=='؟'){cut=i;break;}}if(cut>0)end=cut;}String x=s.substring(p,end).trim();if(!x.isEmpty())out.add(x);if(end>=s.length())break;p=Math.max(p+1,end-overlap);}return out;}
    private static byte[] encode(float[] v){ByteBuffer b=ByteBuffer.allocate(v.length*4).order(ByteOrder.LITTLE_ENDIAN);for(float x:v)b.putFloat(x);return b.array();}private static float[] decode(byte[] data,int dims){float[] v=new float[dims];if(data==null)return v;ByteBuffer b=ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);for(int i=0;i<dims&&b.remaining()>=4;i++)v[i]=b.getFloat();return v;}private static double cos(float[] a,float[] b){double dot=0,aa=0,bb=0;for(int i=0;i<Math.min(a.length,b.length);i++){dot+=a[i]*b[i];aa+=a[i]*a[i];bb+=b[i]*b[i];}return aa==0||bb==0?0:dot/Math.sqrt(aa*bb);}
    private static String bestSnippet(String text,String q){String s=text==null?"":text.replaceAll("\\s+"," ").trim();if(s.length()<=220)return s;String n=LocalSemanticEmbedder.norm(s),qn=LocalSemanticEmbedder.norm(q);int at=-1;for(String w:qn.split(" "))if(w.length()>2){at=n.indexOf(w);if(at>=0)break;}if(at<0)return s.substring(0,220)+"…";int start=Math.max(0,Math.min(s.length()-1,at)-70),end=Math.min(s.length(),start+220);return(start>0?"…":"")+s.substring(start,end)+(end<s.length()?"…":"");}

    private static final class CorpusRow{final long itemId;final String chunk;final float[] vector;CorpusRow(long id,String text,float[] v){itemId=id;chunk=text==null?"":text;vector=v;}}
    private static final class CorpusSnapshot{final ArrayList<CorpusRow> rows;CorpusSnapshot(ArrayList<CorpusRow> r){rows=r;}}
}
