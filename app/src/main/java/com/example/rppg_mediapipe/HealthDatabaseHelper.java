package com.example.rppg_mediapipe;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

public class HealthDatabaseHelper extends SQLiteOpenHelper {
    private static final String DB_NAME = "face_health_agent.db";
    private static final int DB_VERSION = 1;
    public static final String TABLE = "health_record";

    public HealthDatabaseHelper(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE + " ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "user_id TEXT,"
                + "date TEXT,"
                + "heart_rate REAL,"
                + "respiratory_rate REAL,"
                + "hrv REAL,"
                + "stress_score REAL,"
                + "fatigue_score REAL,"
                + "video_quality TEXT,"
                + "sleep_hours REAL,"
                + "stress_level TEXT,"
                + "is_late_sleep INTEGER,"
                + "is_coffee INTEGER,"
                + "is_after_exercise INTEGER,"
                + "symptoms TEXT,"
                + "user_note TEXT,"
                + "agent_report TEXT,"
                + "created_at TEXT"
                + ")");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS " + TABLE);
        onCreate(db);
    }

    public long insertRecord(HealthRecord record) {
        SQLiteDatabase db = getWritableDatabase();
        long id = db.insert(TABLE, null, toValues(record));
        record.id = id;
        return id;
    }

    public void updateAgentReport(long id, String report) {
        ContentValues values = new ContentValues();
        values.put("agent_report", report);
        getWritableDatabase().update(TABLE, values, "id=?", new String[]{String.valueOf(id)});
    }

    public HealthRecord getLatestRecord() {
        List<HealthRecord> records = query("SELECT * FROM " + TABLE + " ORDER BY created_at DESC, id DESC LIMIT 1", null);
        return records.isEmpty() ? null : records.get(0);
    }

    public HealthRecord getRecordById(long id) {
        List<HealthRecord> records = query("SELECT * FROM " + TABLE + " WHERE id=?", new String[]{String.valueOf(id)});
        return records.isEmpty() ? null : records.get(0);
    }

    public HealthRecord getTodayRecord() {
        List<HealthRecord> records = query("SELECT * FROM " + TABLE + " WHERE date=? ORDER BY created_at DESC, id DESC LIMIT 1",
                new String[]{DateUtils.today()});
        return records.isEmpty() ? null : records.get(0);
    }

    public List<HealthRecord> getRecentRecords(int limit) {
        return query(
                "SELECT * FROM " + TABLE
                        + " WHERE id IN (SELECT MAX(id) FROM " + TABLE + " GROUP BY date)"
                        + " ORDER BY date DESC, created_at DESC, id DESC LIMIT " + limit,
                null
        );
    }

    public void clearAll() {
        getWritableDatabase().delete(TABLE, null, null);
    }

    private List<HealthRecord> query(String sql, String[] args) {
        List<HealthRecord> records = new ArrayList<>();
        Cursor cursor = getReadableDatabase().rawQuery(sql, args);
        try {
            while (cursor.moveToNext()) {
                records.add(fromCursor(cursor));
            }
        } finally {
            cursor.close();
        }
        return records;
    }

    private ContentValues toValues(HealthRecord record) {
        ContentValues values = new ContentValues();
        values.put("user_id", record.userId);
        values.put("date", record.date);
        values.put("heart_rate", record.heartRate);
        values.put("respiratory_rate", record.respiratoryRate);
        values.put("hrv", record.hrv);
        values.put("stress_score", record.stressScore);
        values.put("fatigue_score", record.fatigueScore);
        values.put("video_quality", record.videoQuality);
        values.put("sleep_hours", record.sleepHours);
        values.put("stress_level", record.stressLevel);
        values.put("is_late_sleep", record.lateSleep ? 1 : 0);
        values.put("is_coffee", record.coffee ? 1 : 0);
        values.put("is_after_exercise", record.afterExercise ? 1 : 0);
        values.put("symptoms", record.symptoms);
        values.put("user_note", record.userNote);
        values.put("agent_report", record.agentReport);
        values.put("created_at", record.createdAt);
        return values;
    }

    private HealthRecord fromCursor(Cursor cursor) {
        HealthRecord record = new HealthRecord();
        record.id = cursor.getLong(cursor.getColumnIndexOrThrow("id"));
        record.userId = cursor.getString(cursor.getColumnIndexOrThrow("user_id"));
        record.date = cursor.getString(cursor.getColumnIndexOrThrow("date"));
        record.heartRate = cursor.getDouble(cursor.getColumnIndexOrThrow("heart_rate"));
        record.respiratoryRate = cursor.getDouble(cursor.getColumnIndexOrThrow("respiratory_rate"));
        record.hrv = cursor.getDouble(cursor.getColumnIndexOrThrow("hrv"));
        record.stressScore = cursor.getDouble(cursor.getColumnIndexOrThrow("stress_score"));
        record.fatigueScore = cursor.getDouble(cursor.getColumnIndexOrThrow("fatigue_score"));
        record.videoQuality = cursor.getString(cursor.getColumnIndexOrThrow("video_quality"));
        record.sleepHours = cursor.getDouble(cursor.getColumnIndexOrThrow("sleep_hours"));
        record.stressLevel = cursor.getString(cursor.getColumnIndexOrThrow("stress_level"));
        record.lateSleep = cursor.getInt(cursor.getColumnIndexOrThrow("is_late_sleep")) == 1;
        record.coffee = cursor.getInt(cursor.getColumnIndexOrThrow("is_coffee")) == 1;
        record.afterExercise = cursor.getInt(cursor.getColumnIndexOrThrow("is_after_exercise")) == 1;
        record.symptoms = cursor.getString(cursor.getColumnIndexOrThrow("symptoms"));
        record.userNote = cursor.getString(cursor.getColumnIndexOrThrow("user_note"));
        record.agentReport = cursor.getString(cursor.getColumnIndexOrThrow("agent_report"));
        record.createdAt = cursor.getString(cursor.getColumnIndexOrThrow("created_at"));
        return record;
    }
}
