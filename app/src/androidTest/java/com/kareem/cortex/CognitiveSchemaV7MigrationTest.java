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

@RunWith(AndroidJUnit4.class)
public final class CognitiveSchemaV7MigrationTest {

    @Test public void migratesV6DataWithoutLossAndBackfillsOnlyProvableState() {
        assertEquals(7, CognitiveSchema.DB_VERSION);
        assertEquals("cognitive_004", CognitiveSchema.REVISION);

        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File file = new File(context.getCacheDir(), "cognitive-schema-v7-migration.db");
        deleteDb(file);
        SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(file, null);
        try {
            createV6Fixture(db);
            seedV6Fixture(db);

            assertEquals(100, count(db, "raw_signals"));
            assertEquals(20, count(db, "derived_items"));
            assertEquals(10, count(db, "source_links"));
            assertEquals(5, count(db, "model_runs"));

            CognitiveSchema.ensure(db);

            // Data-loss audit.
            assertEquals(100, count(db, "raw_signals"));
            assertEquals(20, count(db, "derived_items"));
            assertEquals(10, count(db, "source_links"));
            assertEquals(5, count(db, "model_runs"));

            assertEquals("cognitive_004", scalarString(db,
                    "SELECT value FROM schema_meta WHERE key='cognitive_schema'"));
            assertEquals(0, scalarLong(db,
                    "SELECT COUNT(*) FROM raw_signals WHERE cognitive_state IS NULL OR TRIM(cognitive_state)=''"));

            // Migration is conservative: only evidence we can prove is classified.
            assertEquals(20, stateCount(db, "DERIVED"));
            assertEquals(30, stateCount(db, "IGNORED_NOISE"));
            assertEquals(10, stateCount(db, "REVIEW_REQUIRED"));
            assertEquals(20, stateCount(db, "CONTEXT_ONLY"));
            assertEquals(20, stateCount(db, "LEGACY_UNRESOLVED"));

            assertEquals(100, scalarLong(db,
                    "SELECT COUNT(*) FROM raw_signals WHERE cognitive_version='legacy-cognitive-003'"));
            assertEquals(0, scalarLong(db,
                    "SELECT COUNT(*) FROM raw_signals WHERE final_reason IS NULL OR TRIM(final_reason)=''"));
            assertEquals(0, scalarLong(db,
                    "SELECT COUNT(*) FROM raw_signals WHERE COALESCE(cognitive_updated_at,0)<=0"));

            assertEquals(0, scalarLong(db,
                    "SELECT COUNT(*) FROM derived_items WHERE UPPER(kind)='ACTION' AND requires_user_action<>1"));
            assertEquals(0, scalarLong(db,
                    "SELECT COUNT(*) FROM derived_items WHERE UPPER(kind)='WAITING' AND requires_follow_up<>1"));
            assertEquals(0, scalarLong(db,
                    "SELECT COUNT(*) FROM derived_items WHERE ABS(priority_score-COALESCE(importance,0))>0.0001"));

            // V7 must not fabricate information absent from legacy evidence.
            assertEquals(0, scalarLong(db,
                    "SELECT COUNT(*) FROM derived_items WHERE urgency<>0 OR due_at<>0 OR TRIM(person_key)<>'' OR requires_content_extraction<>0 OR model_run_id<>0"));

            assertTrue(hasIndex(db, "idx_raw_cognitive_state"));
            assertTrue(hasIndex(db, "idx_derived_priority"));
            assertTrue(hasIndex(db, "idx_derived_due"));
            assertTrue(hasIndex(db, "idx_derived_model_run"));
            assertTrue(hasIndex(db, "idx_derived_content_queue"));
        } finally {
            db.close();
            deleteDb(file);
        }
    }

    @Test public void readyCacheIsScopedPerDatabasePath() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File first = new File(context.getCacheDir(), "cognitive-schema-v7-ready-a.db");
        File second = new File(context.getCacheDir(), "cognitive-schema-v7-ready-b.db");
        deleteDb(first);
        deleteDb(second);
        SQLiteDatabase a = SQLiteDatabase.openOrCreateDatabase(first, null);
        SQLiteDatabase b = SQLiteDatabase.openOrCreateDatabase(second, null);
        try {
            CognitiveSchema.ensure(a);
            CognitiveSchema.ensure(b);
            assertTrue(hasColumn(a, "raw_signals", "cognitive_state"));
            assertTrue(hasColumn(b, "raw_signals", "cognitive_state"));
            assertEquals("cognitive_004", scalarString(a,
                    "SELECT value FROM schema_meta WHERE key='cognitive_schema'"));
            assertEquals("cognitive_004", scalarString(b,
                    "SELECT value FROM schema_meta WHERE key='cognitive_schema'"));
        } finally {
            a.close();
            b.close();
            deleteDb(first);
            deleteDb(second);
        }
    }

    private static void createV6Fixture(SQLiteDatabase db) {
        db.execSQL("PRAGMA user_version=6");
        db.execSQL("CREATE TABLE raw_signals(id INTEGER PRIMARY KEY AUTOINCREMENT,kind TEXT NOT NULL,source TEXT,title TEXT,body TEXT,metadata_json TEXT,fingerprint TEXT UNIQUE,state TEXT DEFAULT 'filtered',disposition TEXT,importance INTEGER DEFAULT 0,reason TEXT,promoted_item_id INTEGER DEFAULT 0,occurred_at INTEGER NOT NULL,retention_until INTEGER DEFAULT 0,created_at INTEGER NOT NULL,updated_at INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE derived_items(id INTEGER PRIMARY KEY AUTOINCREMENT,kind TEXT NOT NULL,title TEXT NOT NULL,body TEXT,state TEXT DEFAULT 'open',confidence REAL DEFAULT 0,importance INTEGER DEFAULT 0,fingerprint TEXT,metadata_json TEXT,created_at INTEGER NOT NULL,updated_at INTEGER NOT NULL,resolved_at INTEGER DEFAULT 0)");
        db.execSQL("CREATE TABLE source_links(id INTEGER PRIMARY KEY AUTOINCREMENT,from_type TEXT NOT NULL,from_id INTEGER NOT NULL,to_type TEXT NOT NULL,to_id INTEGER NOT NULL,relation TEXT NOT NULL,confidence REAL DEFAULT 0,metadata_json TEXT,created_at INTEGER NOT NULL,UNIQUE(from_type,from_id,to_type,to_id,relation))");
        db.execSQL("CREATE TABLE model_runs(id INTEGER PRIMARY KEY AUTOINCREMENT,job_id INTEGER DEFAULT 0,pass_index INTEGER DEFAULT 0,role TEXT,provider TEXT,model TEXT,route TEXT,state TEXT,input_hash TEXT,latency_ms INTEGER DEFAULT 0,tokens_in INTEGER DEFAULT 0,tokens_out INTEGER DEFAULT 0,confidence REAL DEFAULT 0,output_json TEXT,error TEXT,created_at INTEGER NOT NULL)");
    }

    private static void seedV6Fixture(SQLiteDatabase db) {
        long now = System.currentTimeMillis();
        for (int i = 0; i < 100; i++) {
            ContentValues v = new ContentValues();
            v.put("kind", "NOTIFICATION");
            v.put("source", "fixture");
            v.put("title", "Signal " + i);
            v.put("body", "body " + i);
            v.put("fingerprint", "v6-signal-" + i);
            v.put("state", "filtered");
            v.put("importance", i % 100);
            if (i < 20) {
                v.put("disposition", "CONTEXT");
                v.put("promoted_item_id", i + 1L);
            } else if (i < 50) {
                v.put("disposition", "IGNORE");
            } else if (i < 60) {
                v.put("disposition", "REVIEW");
            } else if (i < 80) {
                v.put("disposition", "CONTEXT");
            } else if ((i & 1) == 0) {
                v.put("disposition", "ACTION");
            }
            v.put("occurred_at", now - i * 1000L);
            v.put("created_at", now - i * 1000L);
            v.put("updated_at", now - i * 500L);
            assertTrue(db.insertOrThrow("raw_signals", null, v) > 0);
        }

        for (int i = 0; i < 20; i++) {
            ContentValues v = new ContentValues();
            String kind = i < 8 ? "ACTION" : (i < 14 ? "WAITING" : "DECISION");
            v.put("kind", kind);
            v.put("title", kind + " " + i);
            v.put("body", "legacy item");
            v.put("state", "open");
            v.put("confidence", 0.8);
            v.put("importance", 40 + i);
            v.put("fingerprint", "v6-derived-" + i);
            v.put("metadata_json", "{}");
            v.put("created_at", now - i * 1000L);
            v.put("updated_at", now - i * 500L);
            assertTrue(db.insertOrThrow("derived_items", null, v) > 0);
        }

        for (int i = 0; i < 10; i++) {
            ContentValues v = new ContentValues();
            v.put("from_type", "signal");
            v.put("from_id", i + 1L);
            v.put("to_type", "derived");
            v.put("to_id", i + 1L);
            v.put("relation", "SUPPORTS");
            v.put("confidence", 1.0);
            v.put("created_at", now);
            assertTrue(db.insertOrThrow("source_links", null, v) > 0);
        }

        for (int i = 0; i < 5; i++) {
            ContentValues v = new ContentValues();
            v.put("job_id", i + 1L);
            v.put("pass_index", 0);
            v.put("role", "legacy");
            v.put("provider", "fixture");
            v.put("model", "fixture-model");
            v.put("route", "fixture");
            v.put("state", "completed");
            v.put("confidence", 0.7);
            v.put("created_at", now);
            assertTrue(db.insertOrThrow("model_runs", null, v) > 0);
        }
    }

    private static long count(SQLiteDatabase db, String table) {
        return scalarLong(db, "SELECT COUNT(*) FROM " + table);
    }

    private static long stateCount(SQLiteDatabase db, String state) {
        Cursor c = db.rawQuery("SELECT COUNT(*) FROM raw_signals WHERE cognitive_state=?", new String[]{state});
        try {
            return c.moveToFirst() ? c.getLong(0) : 0;
        } finally {
            c.close();
        }
    }

    private static long scalarLong(SQLiteDatabase db, String sql) {
        Cursor c = db.rawQuery(sql, null);
        try {
            return c.moveToFirst() ? c.getLong(0) : 0;
        } finally {
            c.close();
        }
    }

    private static String scalarString(SQLiteDatabase db, String sql) {
        Cursor c = db.rawQuery(sql, null);
        try {
            return c.moveToFirst() && !c.isNull(0) ? c.getString(0) : "";
        } finally {
            c.close();
        }
    }

    private static boolean hasColumn(SQLiteDatabase db, String table, String column) {
        Cursor c = db.rawQuery("PRAGMA table_info(" + table + ")", null);
        try {
            int name = c.getColumnIndex("name");
            while (c.moveToNext()) {
                if (name >= 0 && column.equals(c.getString(name))) return true;
            }
            return false;
        } finally {
            c.close();
        }
    }

    private static boolean hasIndex(SQLiteDatabase db, String index) {
        Cursor c = db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='index' AND name=?", new String[]{index});
        try {
            return c.moveToFirst();
        } finally {
            c.close();
        }
    }

    private static void deleteDb(File file) {
        if (file.exists()) file.delete();
        File wal = new File(file.getPath() + "-wal");
        File shm = new File(file.getPath() + "-shm");
        File journal = new File(file.getPath() + "-journal");
        if (wal.exists()) wal.delete();
        if (shm.exists()) shm.delete();
        if (journal.exists()) journal.delete();
    }
}
