package com.example.rppg_mediapipe;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.BufferedSource;

public class AgentApiClient {
    private static final String TAG = "AgentApiClient";
    private static final String AUTH_PREFS = "face_health_agent_auth";
    private static final String KEY_ACCESS_TOKEN = "access_token";
    private static final String KEY_AUTH_USER_ID = "auth_user_id";
    private static final Object AUTH_LOCK = new Object();
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int ANALYZE_TIMEOUT_MS = 120_000;
    private static final int WEEKLY_REPORT_TIMEOUT_MS = 120_000;
    private static final int CHAT_TIMEOUT_MS = 120_000;
    private static final MediaType JSON_MEDIA_TYPE =
            MediaType.get("application/json; charset=utf-8");
    private static final OkHttpClient CHAT_HTTP_CLIENT = new OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(CHAT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .callTimeout(CHAT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build();
    private final SharedPreferences authPreferences;

    public AgentApiClient(Context context) {
        authPreferences = context.getApplicationContext()
                .getSharedPreferences(AUTH_PREFS, Context.MODE_PRIVATE);
    }

    public interface ChatStreamListener {
        void onMeta(String conversationId);
        void onDelta(String text);
        void onDone();
        void onError(String code, String message);
    }

    public String analyzeToday(HealthRecord record, WeeklySummary summary) throws Exception {
        syncHealthRecords(java.util.Collections.singletonList(record));
        JSONObject request = new JSONObject();
        request.put("user_id", authenticatedUserId());
        request.put("user_message", record.userNote);
        request.put("today_metrics", metricsJson(record));
        request.put("weekly_summary", summaryJson(summary));

        JSONObject context = new JSONObject();
        context.put("sleep_hours", record.sleepHours);
        context.put("stress_level", record.stressLevel);
        context.put("is_late_sleep", record.lateSleep);
        context.put("is_coffee", record.coffee);
        context.put("is_after_exercise", record.afterExercise);
        context.put("symptoms", new JSONArray(record.symptoms == null || record.symptoms.isEmpty()
                ? new String[]{} : record.symptoms.split(",")));
        request.put("user_context", context);

        JSONObject response = post("/api/agent/analyze", request, ANALYZE_TIMEOUT_MS);
        return "【今日状态】\n" + response.optString("today_status", "暂无今日状态") + "\n\n"
                + "【一周趋势】\n" + response.optString("weekly_trend", summary.trendText) + "\n\n"
                + "【可能原因】\n" + response.optString("possible_reason", "暂无") + "\n\n"
                + "【健康建议】\n" + response.optString("suggestion", "暂无") + "\n\n"
                + "【风险提示】\n" + response.optString("risk_warning", "本系统不提供医学诊断。如出现胸痛、胸闷、头晕、出冷汗或持续心悸，请及时就医。");
    }

    public String generateWeeklyReport(List<HealthRecord> records, WeeklySummary summary) throws Exception {
        syncHealthRecords(records);
        JSONObject request = new JSONObject();
        request.put("user_id", authenticatedUserId());
        JSONArray array = new JSONArray();
        for (HealthRecord record : records) {
            JSONObject item = metricsJson(record);
            item.put("date", record.date);
            array.put(item);
        }
        request.put("weekly_records", array);
        request.put("weekly_summary", summaryJson(summary));
        request.put("user_note", records.isEmpty() ? "" : records.get(0).userNote);

        JSONObject response = post("/api/agent/weekly-report", request, WEEKLY_REPORT_TIMEOUT_MS);
        StringBuilder builder = new StringBuilder(response.optString("weekly_report", ""));
        JSONArray findings = response.optJSONArray("key_findings");
        if (findings != null && findings.length() > 0) {
            builder.append("\n\n关键发现：");
            for (int i = 0; i < findings.length(); i++) builder.append("\n- ").append(findings.optString(i));
        }
        JSONArray suggestions = response.optJSONArray("suggestions");
        if (suggestions != null && suggestions.length() > 0) {
            builder.append("\n\n建议：");
            for (int i = 0; i < suggestions.length(); i++) builder.append("\n- ").append(suggestions.optString(i));
        }
        return builder.toString();
    }

    public void streamChat(String message, String clientMessageId, HealthRecord today,
                           List<HealthRecord> recentRecords, WeeklySummary summary,
                           String conversationId,
                           ChatStreamListener listener) throws Exception {
        List<HealthRecord> recordsToSync = new ArrayList<>();
        Set<String> recordIds = new HashSet<>();
        if (recentRecords != null) {
            for (HealthRecord record : recentRecords) {
                String id = clientRecordId(record);
                if (recordIds.add(id)) recordsToSync.add(record);
            }
        }
        if (today != null && recordIds.add(clientRecordId(today))) {
            recordsToSync.add(today);
        }
        syncHealthRecords(recordsToSync);

        JSONObject request = new JSONObject();
        request.put("user_id", authenticatedUserId());
        request.put("message", message);
        request.put("client_message_id", clientMessageId);
        request.put("stream", true);
        if (conversationId != null && !conversationId.isEmpty()) {
            request.put("conversation_id", conversationId);
        }
        request.put("weekly_summary", summaryJson(summary));

        RequestBody body = RequestBody.create(request.toString(), JSON_MEDIA_TYPE);
        okhttp3.Request httpRequest = new okhttp3.Request.Builder()
                .url(ApiConfig.BASE_URL + "/api/agent/chat")
                .post(body)
                .header("Authorization", "Bearer " + accessToken())
                .header("Accept", "text/event-stream")
                .header("Cache-Control", "no-cache")
                .build();

        try (Response response = CHAT_HTTP_CLIENT.newCall(httpRequest).execute()) {
            Log.d(TAG, "SSE connected status=" + response.code());
            if (!response.isSuccessful()) {
                String error = response.body() == null ? "" : response.body().string();
                throw new IllegalStateException("HTTP " + response.code() + ": " + error);
            }
            if (response.body() == null) {
                throw new IllegalStateException("SSE response body is empty");
            }
            BufferedSource source = response.body().source();
            String event = "message";
            StringBuilder data = new StringBuilder();
            String line;
            while ((line = source.readUtf8Line()) != null) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException("Chat stream cancelled");
                }
                if (line.isEmpty()) {
                    boolean terminal = dispatchEvent(event, data.toString(), listener);
                    event = "message";
                    data.setLength(0);
                    if (terminal) return;
                } else if (line.startsWith("event:")) {
                    event = line.substring(6).trim();
                } else if (line.startsWith("data:")) {
                    if (data.length() > 0) data.append('\n');
                    data.append(line.substring(5).trim());
                }
            }
            if (data.length() > 0) dispatchEvent(event, data.toString(), listener);
        }
    }

    public void clearConversation(String conversationId) throws Exception {
        if (conversationId == null || conversationId.isEmpty()) return;
        ensureAuthenticated();
        String encodedId = URLEncoder.encode(conversationId, StandardCharsets.UTF_8.name())
                .replace("+", "%20");
        HttpURLConnection connection = openConnection(
                "/api/agent/chat/" + encodedId, "DELETE", 15_000);
        int code = connection.getResponseCode();
        String response = readResponse(connection, code);
        connection.disconnect();
        if (code < 200 || code >= 300) {
            throw new IllegalStateException("HTTP " + code + ": " + response);
        }
    }

    public void syncHealthRecords(List<HealthRecord> records) throws Exception {
        ensureAuthenticated();
        JSONArray items = new JSONArray();
        if (records != null) {
            for (HealthRecord record : records) {
                if (record == null || record.date == null || record.date.trim().isEmpty()) continue;
                JSONObject item = new JSONObject();
                item.put("client_record_id", clientRecordId(record));
                item.put("date", record.date);
                if (record.createdAt != null && !record.createdAt.isEmpty()) {
                    item.put("created_at", record.createdAt);
                }
                item.put("metrics", metricsJson(record));
                item.put("context", contextJson(record));
                item.put("user_note", record.userNote == null ? "" : record.userNote);
                items.put(item);
            }
        }
        JSONObject payload = new JSONObject();
        payload.put("records", items);
        post("/api/health/records/sync", payload, ANALYZE_TIMEOUT_MS);
    }

    public JSONObject getUserProfile() throws Exception {
        ensureAuthenticated();
        HttpURLConnection connection = openConnection(
                "/api/health/profile", "GET", ANALYZE_TIMEOUT_MS);
        int code = connection.getResponseCode();
        String response = readResponse(connection, code);
        connection.disconnect();
        if (code < 200 || code >= 300) {
            throw new IllegalStateException("HTTP " + code + ": " + response);
        }
        return new JSONObject(response).optJSONObject("profile");
    }

    public JSONObject updateUserProfile(JSONObject profile) throws Exception {
        ensureAuthenticated();
        HttpURLConnection connection = openConnection(
                "/api/health/profile", "PUT", ANALYZE_TIMEOUT_MS);
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        writeBody(connection, profile);
        int code = connection.getResponseCode();
        String response = readResponse(connection, code);
        connection.disconnect();
        if (code < 200 || code >= 300) {
            throw new IllegalStateException("HTTP " + code + ": " + response);
        }
        return new JSONObject(response).optJSONObject("profile");
    }

    private boolean dispatchEvent(String event, String data, ChatStreamListener listener)
            throws Exception {
        if (data.isEmpty()) return false;
        Log.d(TAG, "SSE event=" + event + " dataLength=" + data.length());
        JSONObject payload = new JSONObject(data);
        switch (event) {
            case "meta":
                listener.onMeta(payload.optString("conversation_id"));
                return false;
            case "delta":
                listener.onDelta(payload.optString("text"));
                return false;
            case "done":
                listener.onDone();
                return true;
            case "error":
                listener.onError(payload.optString("code", "STREAM_FAILED"),
                        payload.optString("message", "流式回复中断"));
                return true;
            default:
                return false;
        }
    }

    private JSONObject post(String path, JSONObject body, int readTimeoutMs) throws Exception {
        ensureAuthenticated();
        HttpURLConnection connection = openConnection(path, "POST", readTimeoutMs);
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        writeBody(connection, body);

        int code = connection.getResponseCode();
        String response = readResponse(connection, code);
        connection.disconnect();
        if (code < 200 || code >= 300) {
            throw new IllegalStateException("HTTP " + code + ": " + response);
        }
        return new JSONObject(response);
    }

    private HttpURLConnection openConnection(String path, String method, int readTimeoutMs)
            throws Exception {
        URL url = new URL(ApiConfig.BASE_URL + path);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(readTimeoutMs);
        String token = accessToken();
        if (!token.isEmpty()) {
            connection.setRequestProperty("Authorization", "Bearer " + token);
        }
        return connection;
    }

    private void ensureAuthenticated() throws Exception {
        if (!accessToken().isEmpty()) return;
        synchronized (AUTH_LOCK) {
            if (!accessToken().isEmpty()) return;
            URL url = new URL(ApiConfig.BASE_URL + "/api/auth/anonymous");
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(ANALYZE_TIMEOUT_MS);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            JSONObject request = new JSONObject();
            request.put("device_name", "Android " + Build.MODEL);
            writeBody(connection, request);
            int code = connection.getResponseCode();
            String response = readResponse(connection, code);
            connection.disconnect();
            if (code < 200 || code >= 300) {
                throw new IllegalStateException("设备认证失败 HTTP " + code + ": " + response);
            }
            JSONObject body = new JSONObject(response);
            String token = body.optString("access_token");
            String userId = body.optString("user_id");
            if (token.isEmpty() || userId.isEmpty()) {
                throw new IllegalStateException("设备认证响应缺少令牌");
            }
            authPreferences.edit()
                    .putString(KEY_ACCESS_TOKEN, token)
                    .putString(KEY_AUTH_USER_ID, userId)
                    .commit();
        }
    }

    private String accessToken() {
        return authPreferences.getString(KEY_ACCESS_TOKEN, "");
    }

    private String authenticatedUserId() throws Exception {
        ensureAuthenticated();
        return authPreferences.getString(KEY_AUTH_USER_ID, "local_user_001");
    }

    private String clientRecordId(HealthRecord record) {
        if (record.id > 0) return "android-" + record.id;
        String date = record.date == null ? "unknown-date" : record.date;
        String createdAt = record.createdAt == null ? "" : record.createdAt;
        return "android-" + Integer.toHexString((date + "|" + createdAt).hashCode());
    }

    private void writeBody(HttpURLConnection connection, JSONObject body) throws Exception {
        byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
        try (OutputStream outputStream = connection.getOutputStream()) {
            outputStream.write(payload);
        }
    }

    private String readResponse(HttpURLConnection connection, int code) throws Exception {
        if (code == HttpURLConnection.HTTP_NO_CONTENT) return "";
        if (code >= 400 && connection.getErrorStream() == null) return "";
        BufferedReader reader = new BufferedReader(new InputStreamReader(code >= 200 && code < 300
                ? connection.getInputStream() : connection.getErrorStream(), StandardCharsets.UTF_8));
        StringBuilder response = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) response.append(line);
        reader.close();
        return response.toString();
    }

    private JSONObject metricsJson(HealthRecord record) throws Exception {
        JSONObject metrics = new JSONObject();
        metrics.put("heart_rate", record.heartRate);
        metrics.put("respiratory_rate", record.respiratoryRate);
        metrics.put("hrv", record.hrv);
        metrics.put("stress_score", record.stressScore);
        metrics.put("fatigue_score", record.fatigueScore);
        metrics.put("video_quality", record.videoQuality);
        return metrics;
    }

    private JSONObject contextJson(HealthRecord record) throws Exception {
        JSONObject context = new JSONObject();
        context.put("sleep_hours", record.sleepHours);
        context.put("stress_level", record.stressLevel);
        context.put("is_late_sleep", record.lateSleep);
        context.put("is_coffee", record.coffee);
        context.put("is_after_exercise", record.afterExercise);
        JSONArray symptoms = new JSONArray();
        if (record.symptoms != null && !record.symptoms.trim().isEmpty()) {
            for (String symptom : record.symptoms.split(",")) {
                String value = symptom.trim();
                if (!value.isEmpty()) symptoms.put(value);
            }
        }
        context.put("symptoms", symptoms);
        return context;
    }

    private JSONObject summaryJson(WeeklySummary summary) throws Exception {
        JSONObject json = new JSONObject();
        json.put("avg_heart_rate", summary.avgHeartRate);
        json.put("avg_respiratory_rate", summary.avgRespiratoryRate);
        json.put("avg_hrv", summary.avgHrv);
        json.put("avg_stress_score", summary.avgStressScore);
        json.put("avg_fatigue_score", summary.avgFatigueScore);
        json.put("abnormal_days", summary.abnormalDays);
        json.put("trend", summary.trendText);
        return json;
    }
}
