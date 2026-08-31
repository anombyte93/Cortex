package com.kareem.cortex;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import org.json.JSONObject;

/** Persists packet/teacher/student/differential history without making model output authoritative. */
public final class CognitiveAdjudicationStore {
    private CognitiveAdjudicationStore(){}

    public static void ensure(SQLiteDatabase db){
        db.execSQL("CREATE TABLE IF NOT EXISTS cognitive_adjudications(id INTEGER PRIMARY KEY AUTOINCREMENT,packet_id TEXT NOT NULL,packet_json TEXT NOT NULL,teacher_json TEXT,student_json TEXT,diff_json TEXT,teacher_valid INTEGER DEFAULT 0,student_valid INTEGER DEFAULT 0,state TEXT DEFAULT 'packet_ready',created_at INTEGER NOT NULL,updated_at INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_cognitive_adjudication_packet ON cognitive_adjudications(packet_id,updated_at DESC)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_cognitive_adjudication_state ON cognitive_adjudications(state,updated_at DESC)");
    }

    public static long savePacket(SQLiteDatabase db,JSONObject packet){
        ensure(db);long now=System.currentTimeMillis();ContentValues v=new ContentValues();
        v.put("packet_id",packet==null?"":packet.optString("packet_id",""));v.put("packet_json",packet==null?"{}":packet.toString());v.put("state","packet_ready");v.put("created_at",now);v.put("updated_at",now);
        return db.insertOrThrow("cognitive_adjudications",null,v);
    }

    public static void saveTeacher(SQLiteDatabase db,long id,String raw,CognitiveDecisionContract.Validation validation){saveSide(db,id,"teacher",raw,validation);}
    public static void saveStudent(SQLiteDatabase db,long id,String raw,CognitiveDecisionContract.Validation validation){saveSide(db,id,"student",raw,validation);}

    private static void saveSide(SQLiteDatabase db,long id,String side,String raw,CognitiveDecisionContract.Validation validation){
        ensure(db);ContentValues v=new ContentValues();v.put(side+"_json",raw==null?"":raw);v.put(side+"_valid",validation!=null&&validation.valid()?1:0);v.put("updated_at",System.currentTimeMillis());v.put("state",side+"_recorded");db.update("cognitive_adjudications",v,"id=?",new String[]{String.valueOf(id)});
    }

    public static void saveDiff(SQLiteDatabase db,long id,JSONObject diff){
        ensure(db);ContentValues v=new ContentValues();v.put("diff_json",diff==null?"{}":diff.toString());v.put("state","diff_ready");v.put("updated_at",System.currentTimeMillis());db.update("cognitive_adjudications",v,"id=?",new String[]{String.valueOf(id)});
    }

    public static JSONObject get(SQLiteDatabase db,long id){
        ensure(db);Cursor c=db.query("cognitive_adjudications",null,"id=?",new String[]{String.valueOf(id)},null,null,null,"1");try{if(!c.moveToFirst())return null;JSONObject o=new JSONObject();String[] cols=c.getColumnNames();for(int i=0;i<cols.length;i++){switch(c.getType(i)){case Cursor.FIELD_TYPE_INTEGER:o.put(cols[i],c.getLong(i));break;case Cursor.FIELD_TYPE_FLOAT:o.put(cols[i],c.getDouble(i));break;case Cursor.FIELD_TYPE_NULL:o.put(cols[i],JSONObject.NULL);break;default:o.put(cols[i],c.getString(i));}}return o;}catch(Exception e){throw new IllegalStateException(e);}finally{c.close();}
    }
}
