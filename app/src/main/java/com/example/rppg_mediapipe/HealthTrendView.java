package com.example.rppg_mediapipe;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class HealthTrendView extends View {
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private List<Float> values = new ArrayList<>();

    public HealthTrendView(Context context) {
        super(context);
        init();
    }

    public HealthTrendView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        linePaint.setColor(Color.rgb(92, 108, 255));
        linePaint.setStrokeWidth(dp(4));
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeCap(Paint.Cap.ROUND);
        linePaint.setStrokeJoin(Paint.Join.ROUND);

        gridPaint.setColor(Color.argb(70, 130, 139, 170));
        gridPaint.setStrokeWidth(dp(1));

        dotPaint.setColor(Color.WHITE);
        dotPaint.setStyle(Paint.Style.FILL);

        textPaint.setColor(Color.rgb(100, 112, 125));
        textPaint.setTextSize(dp(11));
    }

    public void setRecords(List<HealthRecord> records) {
        values = new ArrayList<>();
        if (records != null) {
            List<HealthRecord> ordered = new ArrayList<>(records);
            Collections.reverse(ordered);
            for (HealthRecord record : ordered) {
                values.add((float) record.heartRate);
            }
        }
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int width = getWidth();
        int height = getHeight();
        float left = dp(12);
        float right = width - dp(12);
        float top = dp(16);
        float bottom = height - dp(28);

        for (int i = 0; i < 3; i++) {
            float y = top + (bottom - top) * i / 2f;
            canvas.drawLine(left, y, right, y, gridPaint);
        }

        List<Float> drawValues = values.isEmpty() ? sampleValues() : values;
        float min = 52f;
        float max = 104f;
        Path line = new Path();
        Path fill = new Path();
        for (int i = 0; i < drawValues.size(); i++) {
            float x = drawValues.size() == 1 ? (left + right) / 2f : left + (right - left) * i / (drawValues.size() - 1f);
            float normalized = (drawValues.get(i) - min) / (max - min);
            float y = bottom - Math.max(0f, Math.min(1f, normalized)) * (bottom - top);
            if (i == 0) {
                line.moveTo(x, y);
                fill.moveTo(x, bottom);
                fill.lineTo(x, y);
            } else {
                line.lineTo(x, y);
                fill.lineTo(x, y);
            }
            canvas.drawCircle(x, y, dp(5), dotPaint);
            canvas.drawCircle(x, y, dp(3), linePaint);
        }
        fill.lineTo(right, bottom);
        fill.close();
        fillPaint.setShader(new LinearGradient(0, top, 0, bottom,
                Color.argb(90, 92, 108, 255), Color.argb(5, 92, 108, 255), Shader.TileMode.CLAMP));
        canvas.drawPath(fill, fillPaint);
        canvas.drawPath(line, linePaint);

        String label = values.isEmpty() ? "等待检测后生成趋势" : "近 7 天心率趋势";
        canvas.drawText(label, left, height - dp(9), textPaint);
    }

    private List<Float> sampleValues() {
        List<Float> sample = new ArrayList<>();
        sample.add(72f);
        sample.add(76f);
        sample.add(74f);
        sample.add(82f);
        sample.add(78f);
        sample.add(75f);
        sample.add(79f);
        return sample;
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}