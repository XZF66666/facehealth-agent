package com.example.rppg_mediapipe;

public class HealthRecord {
    public long id;
    public String userId = "local_user_001";
    public String date;
    public double heartRate;
    public double respiratoryRate;
    public double hrv;
    public double stressScore;
    public double fatigueScore;
    public String videoQuality = "合格";
    public double sleepHours;
    public String stressLevel = "中";
    public boolean lateSleep;
    public boolean coffee;
    public boolean afterExercise;
    public String symptoms = "";
    public String userNote = "";
    public String agentReport = "";
    public String createdAt;

    public static HealthRecord fromHealthData(HealthData data) {
        HealthRecord record = new HealthRecord();
        record.date = DateUtils.today();
        record.createdAt = DateUtils.now();
        record.heartRate = data.getHeartRate();
        record.respiratoryRate = data.getRespiratoryRate();
        record.hrv = data.getHrvMillis();
        record.stressScore = data.getStressScore();
        record.fatigueScore = data.getFatigueScore();
        record.videoQuality = data.getVideoQuality();
        return record;
    }
}