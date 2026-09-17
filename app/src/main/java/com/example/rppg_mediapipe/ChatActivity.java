package com.example.rppg_mediapipe;

import android.annotation.SuppressLint;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AlertDialog;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.ref.WeakReference;
import java.util.List;
import java.util.UUID;

public class ChatActivity extends AppCompatActivity {
    private static final String PREFS_NAME = "face_health_agent";
    private static final String KEY_CONVERSATION_ID = "agent_conversation_id";
    private static final String KEY_MESSAGES = "agent_chat_messages";
    private static final String KEY_LAST_QUESTION = "agent_last_question";
    private static final String KEY_LAST_MESSAGE_ID = "agent_last_message_id";
    private static final int MAX_SAVED_MESSAGES = 40;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private LinearLayout messageContainer;
    private EditText input;
    private ScrollView chatScroll;
    private Button sendButton;
    private HealthDatabaseHelper db;
    private TextView loadingBubble;
    private Runnable loadingRunnable;
    private String lastQuestion = "";
    private String lastClientMessageId = "";
    private volatile String conversationId = "";
    private int renderGeneration = 0;
    private boolean requesting;
    private ChatTask currentChatTask;
    private TextView streamingBubble;
    private LinearLayout streamingContent;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_chat);
        applySystemBarInsets();
        db = new HealthDatabaseHelper(this);
        messageContainer = findViewById(R.id.messageContainer);
        input = findViewById(R.id.chatInput);
        chatScroll = findViewById(R.id.chatScroll);
        sendButton = findViewById(R.id.sendButton);
        SharedPreferences preferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        conversationId = preferences.getString(KEY_CONVERSATION_ID, "");
        lastQuestion = preferences.getString(KEY_LAST_QUESTION, "");
        lastClientMessageId = preferences.getString(KEY_LAST_MESSAGE_ID, "");

        if (!restoreMessages()) {
            addAssistantMessage("你好，我会结合你的今日检测和一周趋势提供建议。你可以问我运动、压力、疲劳或恢复方面的问题。", false, false);
        }
        sendButton.setOnClickListener(v -> {
            if (requesting) {
                cancelCurrentRequest();
            } else {
                sendMessage();
            }
        });
        findViewById(R.id.chatBackButton).setOnClickListener(v -> finish());
        findViewById(R.id.clearChatButton).setOnClickListener(v -> confirmClearConversation());
        findViewById(R.id.questionExercise).setOnClickListener(v -> sendSuggestedQuestion("我今天适合运动吗？"));
        findViewById(R.id.questionStress).setOnClickListener(v -> sendSuggestedQuestion("帮我分析一下本周压力趋势"));
        findViewById(R.id.questionRecovery).setOnClickListener(v -> sendSuggestedQuestion("我该如何改善身体恢复状态？"));
        input.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendMessage();
                return true;
            }
            return false;
        });
    }

    private void applySystemBarInsets() {
        View root = findViewById(R.id.chatRoot);
        int initialLeft = root.getPaddingLeft();
        int initialTop = root.getPaddingTop();
        int initialRight = root.getPaddingRight();
        int initialBottom = root.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, windowInsets) -> {
            Insets systemBars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            view.setPadding(
                    initialLeft + systemBars.left,
                    initialTop + systemBars.top,
                    initialRight + systemBars.right,
                    initialBottom + systemBars.bottom
            );
            return windowInsets;
        });
        ViewCompat.requestApplyInsets(root);
    }

    private void sendSuggestedQuestion(String question) {
        if (requesting) return;
        input.setText(question);
        input.setSelection(question.length());
        sendMessage();
    }

    private void sendMessage() {
        String message = input.getText().toString().trim();
        if (message.isEmpty() || requesting) return;
        input.setText("");
        lastQuestion = message;
        lastClientMessageId = UUID.randomUUID().toString();
        saveLastRequest();
        addUserMessage(message);
        requestAssistant(message, lastClientMessageId);
    }

    private void requestAssistant(String message, String clientMessageId) {
        requesting = true;
        sendButton.setEnabled(true);
        sendButton.setText("停止");
        showLoadingBubble();
        currentChatTask = new ChatTask(this, message, clientMessageId, renderGeneration);
        currentChatTask.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    private void clearConversation() {
        String previousConversationId = conversationId;
        renderGeneration++;
        if (currentChatTask != null) currentChatTask.cancel(true);
        currentChatTask = null;
        stopLoadingBubble();
        streamingBubble = null;
        streamingContent = null;
        requesting = false;
        lastQuestion = "";
        lastClientMessageId = "";
        conversationId = "";
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .remove(KEY_CONVERSATION_ID)
                .remove(KEY_MESSAGES)
                .remove(KEY_LAST_QUESTION)
                .remove(KEY_LAST_MESSAGE_ID)
                .apply();
        sendButton.setEnabled(true);
        sendButton.setText("发送");
        messageContainer.removeAllViews();
        addAssistantMessage("会话已清空。你可以重新询问今天的状态或一周趋势。", false, false);
        if (!previousConversationId.isEmpty()) {
            AsyncTask.execute(() -> {
                try {
                    new AgentApiClient(getApplicationContext())
                            .clearConversation(previousConversationId);
                } catch (Exception ignored) {
                    // Local state is already cleared; server cleanup can safely be retried later.
                }
            });
        }
    }

    private void confirmClearConversation() {
        if (requesting) return;
        new AlertDialog.Builder(this)
                .setTitle("清空当前会话？")
                .setMessage("聊天记录和本轮上下文记忆将被删除，此操作无法撤销。")
                .setNegativeButton("取消", null)
                .setPositiveButton("清空", (dialog, which) -> clearConversation())
                .show();
    }

    private void saveConversationId(String value) {
        if (value == null || value.isEmpty()) return;
        conversationId = value;
        SharedPreferences preferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        preferences.edit().putString(KEY_CONVERSATION_ID, value).apply();
    }

    private void saveLastRequest() {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .putString(KEY_LAST_QUESTION, lastQuestion)
                .putString(KEY_LAST_MESSAGE_ID, lastClientMessageId)
                .apply();
    }

    private void addUserMessage(String text) {
        addUserMessage(text, true);
    }

    private void addUserMessage(String text, boolean persist) {
        TextView bubble = createBubble(text, true);
        LinearLayout row = createMessageRow(Gravity.END);
        row.addView(bubble);
        messageContainer.addView(row);
        if (persist) persistMessage("user", text, false, lastClientMessageId);
        scrollToLatest();
    }

    private void addAssistantMessage(String text, boolean showRetry, boolean animate) {
        addAssistantMessage(text, showRetry, animate, true);
    }

    private void addAssistantMessage(String text, boolean showRetry, boolean animate,
                                     boolean persist) {
        int generation = renderGeneration;
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        TextView label = new TextView(this);
        label.setText(getString(R.string.assistant_name));
        label.setTextColor(getColor(R.color.primary));
        label.setTextSize(12);
        label.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        label.setPadding(dp(4), 0, 0, dp(5));
        content.addView(label);

        TextView bubble = createBubble(animate ? "" : text, false);
        content.addView(bubble);
        if (showRetry) {
            addRetryAction(content, "重新发送");
        }

        LinearLayout row = createMessageRow(Gravity.START);
        row.addView(content);
        messageContainer.addView(row);
        if (persist) persistMessage("assistant", text, showRetry, lastClientMessageId);
        if (animate) typeText(bubble, text, generation, 0);
        scrollToLatest();
    }

    private void addRetryAction(LinearLayout content, String status) {
        final String retryQuestion = lastQuestion;
        final String retryMessageId = lastClientMessageId;
        TextView retry = new TextView(this);
        retry.setText(status + "  ·  重新发送");
        retry.setTextColor(getColor(R.color.rose));
        retry.setTextSize(13);
        retry.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        retry.setPadding(dp(8), dp(8), dp(8), dp(2));
        retry.setOnClickListener(v -> {
            if (!requesting && !retryQuestion.isEmpty() && !retryMessageId.isEmpty()) {
                lastQuestion = retryQuestion;
                lastClientMessageId = retryMessageId;
                saveLastRequest();
                requestAssistant(retryQuestion, retryMessageId);
            }
        });
        content.addView(retry);
    }

    private TextView startStreamingAssistantMessage() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        TextView label = new TextView(this);
        label.setText(getString(R.string.assistant_name));
        label.setTextColor(getColor(R.color.primary));
        label.setTextSize(12);
        label.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        label.setPadding(dp(4), 0, 0, dp(5));
        content.addView(label);

        TextView bubble = createBubble("", false);
        content.addView(bubble);
        streamingContent = content;
        LinearLayout row = createMessageRow(Gravity.START);
        row.addView(content);
        messageContainer.addView(row);
        scrollToLatest();
        return bubble;
    }

    private TextView createBubble(String text, boolean user) {
        TextView bubble = new TextView(this);
        bubble.setText(text);
        bubble.setTextColor(getColor(user ? R.color.white : R.color.ink));
        bubble.setTextSize(15);
        bubble.setLineSpacing(dp(3), 1f);
        bubble.setMaxWidth((int) (getResources().getDisplayMetrics().widthPixels * 0.78f));
        bubble.setBackgroundResource(user ? R.drawable.bg_chat_user : R.drawable.bg_chat_assistant_bubble);
        return bubble;
    }

    private LinearLayout createMessageRow(int gravity) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(gravity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(12);
        row.setLayoutParams(params);
        return row;
    }

    private void showLoadingBubble() {
        LinearLayout row = createMessageRow(Gravity.START);
        loadingBubble = createBubble("正在分析你的健康数据", false);
        row.addView(loadingBubble);
        messageContainer.addView(row);
        final int[] dots = {0};
        loadingRunnable = new Runnable() {
            @Override
            public void run() {
                if (loadingBubble == null) return;
                dots[0] = (dots[0] + 1) % 4;
                StringBuilder text = new StringBuilder("正在分析你的健康数据");
                for (int i = 0; i < dots[0]; i++) text.append(".");
                loadingBubble.setText(text.toString());
                uiHandler.postDelayed(this, 420);
            }
        };
        uiHandler.post(loadingRunnable);
        scrollToLatest();
    }

    private void stopLoadingBubble() {
        if (loadingRunnable != null) uiHandler.removeCallbacks(loadingRunnable);
        if (loadingBubble != null && loadingBubble.getParent() != null) {
            View row = (View) loadingBubble.getParent();
            messageContainer.removeView(row);
        }
        loadingBubble = null;
        loadingRunnable = null;
    }

    private void cancelCurrentRequest() {
        renderGeneration++;
        if (currentChatTask != null) currentChatTask.cancel(true);
        currentChatTask = null;
        stopLoadingBubble();
        requesting = false;
        sendButton.setEnabled(true);
        sendButton.setText("发送");
        if (streamingBubble != null && streamingContent != null) {
            String partial = streamingBubble.getText().toString().trim();
            addRetryAction(streamingContent, "已停止");
            if (!partial.isEmpty()) {
                persistMessage("assistant", partial, true, lastClientMessageId);
            }
        } else {
            addAssistantMessage("已停止生成。", true, false);
        }
        streamingBubble = null;
        streamingContent = null;
    }

    private boolean restoreMessages() {
        String saved = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getString(KEY_MESSAGES, "");
        if (saved == null || saved.isEmpty()) return false;
        try {
            JSONArray messages = new JSONArray(saved);
            if (messages.length() == 0) return false;
            for (int i = 0; i < messages.length(); i++) {
                JSONObject item = messages.optJSONObject(i);
                if (item == null) continue;
                String role = item.optString("role");
                String text = item.optString("text");
                String messageId = item.optString("client_message_id");
                if (text.isEmpty()) continue;
                if ("user".equals(role)) {
                    lastQuestion = text;
                    lastClientMessageId = messageId;
                    addUserMessage(text, false);
                } else if ("assistant".equals(role)) {
                    addAssistantMessage(text, item.optBoolean("retry"), false, false);
                }
            }
            saveLastRequest();
            return messageContainer.getChildCount() > 0;
        } catch (Exception ignored) {
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                    .remove(KEY_MESSAGES).apply();
            return false;
        }
    }

    private void persistMessage(String role, String text, boolean retry,
                                String clientMessageId) {
        if (text == null || text.trim().isEmpty()) return;
        SharedPreferences preferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        try {
            JSONArray current = new JSONArray(preferences.getString(KEY_MESSAGES, "[]"));
            JSONObject item = new JSONObject();
            item.put("role", role);
            item.put("text", text);
            item.put("retry", retry);
            item.put("client_message_id", clientMessageId == null ? "" : clientMessageId);
            current.put(item);

            JSONArray limited = new JSONArray();
            int start = Math.max(0, current.length() - MAX_SAVED_MESSAGES);
            for (int i = start; i < current.length(); i++) limited.put(current.get(i));
            preferences.edit().putString(KEY_MESSAGES, limited.toString()).apply();
        } catch (Exception ignored) {
            // A damaged local transcript must not block the live conversation.
        }
    }

    private void typeText(TextView view, String text, int generation, int end) {
        if (generation != renderGeneration || isFinishing()) return;
        int next = Math.min(text.length(), end + 3);
        view.setText(text.substring(0, next));
        scrollToLatest();
        if (next < text.length()) {
            uiHandler.postDelayed(() -> typeText(view, text, generation, next), 16);
        }
    }

    private void scrollToLatest() {
        chatScroll.post(() -> chatScroll.fullScroll(ScrollView.FOCUS_DOWN));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        renderGeneration++;
        if (currentChatTask != null) currentChatTask.cancel(true);
        stopLoadingBubble();
        super.onDestroy();
    }

    @SuppressLint("StaticFieldLeak")
    private static class ChatTask extends AsyncTask<Void, StreamUpdate, ChatResponse> {
        private final WeakReference<ChatActivity> activityReference;
        private final String message;
        private final String clientMessageId;
        private final int conversationVersion;

        ChatTask(ChatActivity activity, String message, String clientMessageId,
                 int conversationVersion) {
            activityReference = new WeakReference<>(activity);
            this.message = message;
            this.clientMessageId = clientMessageId;
            this.conversationVersion = conversationVersion;
        }

        @Override
        protected ChatResponse doInBackground(Void... voids) {
            ChatActivity activity = activityReference.get();
            if (activity == null) return new ChatResponse("", false, false);
            HealthRecord today = activity.db.getTodayRecord();
            List<HealthRecord> recentRecords = activity.db.getRecentRecords(7);
            WeeklySummary summary = WeeklyAnalyzer.analyze(recentRecords);
            if (today == null && recentRecords.isEmpty()) {
                return new ChatResponse("你今天还没有检测数据。建议先完成一次 15 秒本地检测，再向我询问个性化建议。", false, false);
            }
            final boolean[] receivedDelta = {false};
            final boolean[] receivedDone = {false};
            final String[] streamError = {null};
            try {
                new AgentApiClient(activity).streamChat(message, clientMessageId, today,
                        recentRecords, summary, activity.conversationId,
                        new AgentApiClient.ChatStreamListener() {
                            @Override
                            public void onMeta(String id) {
                                publishProgress(StreamUpdate.meta(id));
                            }

                            @Override
                            public void onDelta(String text) {
                                if (isCancelled() || text.isEmpty()) return;
                                receivedDelta[0] = true;
                                publishProgress(StreamUpdate.delta(text));
                            }

                            @Override
                            public void onDone() {
                                receivedDone[0] = true;
                                publishProgress(StreamUpdate.done());
                            }

                            @Override
                            public void onError(String code, String errorMessage) {
                                streamError[0] = errorMessage;
                            }
                        });
                if (streamError[0] != null) {
                    return new ChatResponse("回复中断：" + streamError[0], true, receivedDelta[0]);
                }
                if (!receivedDone[0]) {
                    return new ChatResponse("连接提前结束", true, receivedDelta[0]);
                }
                return new ChatResponse("", false, true);
            } catch (Exception e) {
                if (isCancelled()) return new ChatResponse("", false, true);
                if (receivedDelta[0]) {
                    return new ChatResponse("回复中断，请检查网络后重新发送。", true, true);
                }
                return new ChatResponse("云端助手暂时无法连接，已为你生成本地分析：\n\n"
                        + FallbackReportGenerator.chatReply(message, today, summary), true, false);
            }
        }

        @Override
        protected void onProgressUpdate(StreamUpdate... updates) {
            ChatActivity activity = activityReference.get();
            if (activity == null || activity.isFinishing()
                    || activity.renderGeneration != conversationVersion) return;
            for (StreamUpdate update : updates) {
                if (update.type == StreamUpdate.TYPE_META) {
                    activity.saveConversationId(update.value);
                } else if (update.type == StreamUpdate.TYPE_DELTA) {
                    activity.stopLoadingBubble();
                    if (activity.streamingBubble == null) {
                        activity.streamingBubble = activity.startStreamingAssistantMessage();
                    }
                    activity.streamingBubble.append(update.value);
                    activity.scrollToLatest();
                }
            }
        }

        @Override
        protected void onPostExecute(ChatResponse response) {
            ChatActivity activity = activityReference.get();
            if (activity == null || activity.isFinishing()
                    || activity.renderGeneration != conversationVersion) return;
            activity.stopLoadingBubble();
            activity.requesting = false;
            activity.currentChatTask = null;
            activity.sendButton.setEnabled(true);
            activity.sendButton.setText("发送");
            if (activity.streamingBubble != null && activity.streamingContent != null) {
                String streamedText = activity.streamingBubble.getText().toString();
                if (response.retry) {
                    activity.addRetryAction(activity.streamingContent,
                            response.text.isEmpty() ? "回复中断" : response.text);
                }
                activity.persistMessage("assistant", streamedText, response.retry,
                        clientMessageId);
            } else if (!response.text.isEmpty()) {
                activity.addAssistantMessage(response.text, response.retry, !response.streamed);
            } else {
                activity.addAssistantMessage("后端未返回有效回复，请重新发送。", true, false);
            }
            activity.streamingBubble = null;
            activity.streamingContent = null;
        }
    }

    private static class ChatResponse {
        final String text;
        final boolean retry;
        final boolean streamed;

        ChatResponse(String text, boolean retry, boolean streamed) {
            this.text = text;
            this.retry = retry;
            this.streamed = streamed;
        }
    }

    private static class StreamUpdate {
        static final int TYPE_META = 1;
        static final int TYPE_DELTA = 2;
        static final int TYPE_DONE = 3;
        final int type;
        final String value;

        private StreamUpdate(int type, String value) {
            this.type = type;
            this.value = value;
        }

        static StreamUpdate meta(String value) {
            return new StreamUpdate(TYPE_META, value);
        }

        static StreamUpdate delta(String value) {
            return new StreamUpdate(TYPE_DELTA, value);
        }

        static StreamUpdate done() {
            return new StreamUpdate(TYPE_DONE, "");
        }
    }
}
