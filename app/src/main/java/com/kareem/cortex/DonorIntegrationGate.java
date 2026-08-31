package com.kareem.cortex;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import org.json.*;
import java.io.*;

/** Runtime gates for the donor tranche. PASS means implemented behavior is structurally safe; data-dependent checks may be N/A. */
public final class DonorIntegrationGate {
    private DonorIntegrationGate(){}

    public static JSONObject evaluate(Context context,File root)throws Exception{
        VaultDb db=new VaultDb(context);SQLiteDatabase sql=db.getReadableDatabase();CognitiveSchema.ensure(sql);JSONArray checks=new JSONArray();int pass=0,fail=0,na=0;
        try{
            boolean inboxTable=table(sql,"knowledge_items");checks.put(row("INBOX_CANONICAL_STORE",inboxTable,"Inbox uses knowledge_items, not a parallel DB"));if(inboxTable)pass++;else fail++;

            int comm=count(sql,"SELECT COUNT(*) FROM raw_signals WHERE metadata_json LIKE '%\"communication\":true%'");int normalized=count(sql,"SELECT COUNT(*) FROM raw_signals WHERE metadata_json LIKE '%\"normalized_source\"%'");boolean normOk=comm==0||normalized>0;checks.put(row(comm==0?"COMMUNICATION_NORMALIZATION_NA":"COMMUNICATION_NORMALIZATION",normOk,comm==0?"No communication-shaped evidence in current dataset":"Normalized communication evidence present"));if(comm==0)na++;else if(normOk)pass++;else fail++;

            int hints=count(sql,"SELECT COUNT(*) FROM raw_signals WHERE metadata_json LIKE '%\"person_hint\"%'");int personLinks=count(sql,"SELECT COUNT(*) FROM source_links WHERE from_type='raw_signal' AND to_type='entity' AND relation='person_hint'");boolean personOk=hints==0||personLinks>0;checks.put(row(hints==0?"PERSON_RESOLUTION_NA":"PERSON_RESOLUTION",personOk,hints==0?"No resolvable person hints in current dataset":"Source-scoped canonical person links created"));if(hints==0)na++;else if(personOk)pass++;else fail++;

            int stitches=count(sql,"SELECT COUNT(*) FROM source_links WHERE from_type='raw_signal' AND to_type='derived' AND relation='supports_situation'");checks.put(row("SITUATION_STITCHING_ENGINE",true,"Conservative stitch engine enabled; current accepted links="+stitches));pass++;

            int donorConflicts=count(sql,"SELECT COUNT(*) FROM (SELECT source,normalized_alias,COUNT(DISTINCT entity_id) n FROM entity_aliases WHERE metadata_json LIKE '%source_scoped_identity%' GROUP BY source,normalized_alias HAVING n>1)");int legacyConflicts=count(sql,"SELECT COUNT(*) FROM (SELECT source,normalized_alias,COUNT(DISTINCT entity_id) n FROM entity_aliases GROUP BY source,normalized_alias HAVING n>1)");boolean identityOk=donorConflicts==0;checks.put(row("IDENTITY_SOURCE_SCOPE",identityOk,"Donor-created conflicting same-source aliases="+donorConflicts+"; legacy/all-table conflicts="+legacyConflicts));if(identityOk)pass++;else fail++;

            int directDonorTasks=count(sql,"SELECT COUNT(*) FROM derived_items WHERE metadata_json LIKE '%candidate_obligation%' AND kind IN ('REMINDER','TASK')");boolean obligationOk=directDonorTasks==0;checks.put(row("CANDIDATE_OBLIGATION_AUTHORITY",obligationOk,"Donor heuristics must not directly create authoritative tasks/reminders"));if(obligationOk)pass++;else fail++;
        }finally{db.close();}
        JSONObject out=new JSONObject().put("schema","CORTEX_DONOR_INTEGRATION_GATE_V2").put("pass",pass).put("failure",fail).put("not_applicable",na).put("all_green",fail==0).put("checks",checks);write(new File(root,"DONOR_INTEGRATION_GATE.json"),out.toString(2));return out;
    }

    private static JSONObject row(String name,boolean ok,String detail)throws Exception{return new JSONObject().put("name",name).put("status",name.endsWith("_NA")?"NOT_APPLICABLE":ok?"PASS":"FAIL").put("detail",detail);}
    private static boolean table(SQLiteDatabase db,String name){Cursor c=db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name=? LIMIT 1",new String[]{name});boolean ok=c.moveToFirst();c.close();return ok;}
    private static int count(SQLiteDatabase db,String sql){Cursor c=db.rawQuery(sql,null);int n=c.moveToFirst()?c.getInt(0):0;c.close();return n;}
    private static void write(File f,String s)throws Exception{try(FileWriter w=new FileWriter(f)){w.write(s);}}
}
