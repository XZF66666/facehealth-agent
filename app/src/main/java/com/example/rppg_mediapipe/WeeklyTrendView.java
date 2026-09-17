package com.example.rppg_mediapipe;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class WeeklyTrendView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final List<HealthRecord> records = new ArrayList<>();
    private final int[] colors = {
            Color.rgb(92, 108, 255), Color.rgb(37, 99, 235),
            Color.rgb(36, 185, 154), Color.rgb(190, 18, 60)
    };
    private final String[] labels = {"心率", "HRV", "压力", "疲劳"};
    private final String[] units = {"次/分", "ms", "/100", "/100"};

    public WeeklyTrendView(Context context, AttributeSet attrs) {
        super(context, attrs);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
    }

    public void setRecords(List<HealthRecord> source) {
        records.clear();
        if (source != null) records.addAll(source);
        Collections.reverse(records);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (records.isEmpty()) {
            paint.setColor(Color.rgb(100, 112, 125));
            paint.setTextSize(sp(14));
            paint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText("完成检测后，这里会出现真实趋势曲线", getWidth() / 2f, getHeight() / 2f, paint);
            return;
        }
        float panelHeight = getHeight() / 4f;
        for (int metric = 0; metric < 4; metric++) {
            drawMetric(canvas, metric, metric * panelHeight, panelHeight);
        }
    }

    private void drawMetric(Canvas canvas, int metric, float top, float height) {
        float left = dp(46);
        float right = getWidth() - dp(14);
        float chartTop = top + dp(28);
        float chartBottom = top + height - dp(metric == 3 ? 22 : 12);

        paint.setTextAlign(Paint.Align.LEFT);
        paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        paint.setTextSize(sp(13));
        paint.setColor(colors[metric]);
        canvas.drawText(labels[metric], dp(2), top + dp(18), paint);

        double latest = value(records.get(records.size() - 1), metric);
        paint.setTextAlign(Paint.Align.RIGHT);
        paint.setTextSize(sp(12));
        canvas.drawText(Math.round(latest) + " " + units[metric], right, top + dp(18), paint);
        paint.setTypeface(android.graphics.Typeface.DEFAULT);

        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;
        for (HealthRecord record : records) {
            double value = value(record, metric);
            min = Math.min(min, value);
            max = Math.max(max, value);
        }
        if (metric >= 2) {
            min = 0;
            max = 100;
        } else {
            double padding = Math.max(3, (max - min) * 0.25);
            min -= padding;
            max += padding;
        }
        if (max <= min) max = min + 1;

        paint.setColor(Color.rgb(226, 230, 240));
        paint.setStrokeWidth(dp(1));
        canvas.drawLine(left, chartBottom, right, chartBottom, paint);

        Path path = new Path();
        for (int i = 0; i < records.size(); i++) {
            float x = records.size() == 1 ? (left + right) / 2f
                    : left + (right - left) * i / (records.size() - 1f);
            float y = chartBottom - (float) ((value(records.get(i), metric) - min) / (max - min)) * (chartBottom - chartTop);
            if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
        }
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(2.5f));
        paint.setColor(colors[metric]);
        canvas.drawPath(path, paint);
        paint.setStyle(Paint.Style.FILL);
        for (int i = 0; i < records.size(); i++) {
            float x = records.size() == 1 ? (left + right) / 2f
                    : left + (right - left) * i / (records.size() - 1f);
            float y = chartBottom - (float) ((value(records.get(i), metric) - min) / (max - min)) * (chartBottom - chartTop);
            canvas.drawCircle(x, y, dp(3), paint);
        }

        if (metric == 3) {
            paint.setColor(Color.rgb(100, 112, 125));
            paint.setTextSize(sp(10));
            paint.setTextAlign(Paint.Align.LEFT);
            canvas.drawText(shortDate(records.get(0).date), left, top + height - dp(4), paint);
            paint.setTextAlign(Paint.Align.RIGHT);
            canvas.drawText(shortDate(records.get(records.size() - 1).date), right, top + height - dp(4), paint);
        }
    }

    private double value(HealthRecord record, int metric) {
        if (metric == 0) return record.heartRate;
        if (metric == 1) return record.hrv;
        if (metric == 2) return record.stressScore;
        return record.fatigueScore;
    }

    private String shortDate(String date) {
        if (date == null) return "";
        return date.length() >= 10 ? date.substring(5) : date;
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    private float sp(float value) {
        return value * getResources().getDisplayMetrics().scaledDensity;
    }
}
