package com.example.rppg_mediapipe;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

public class RingGaugeView extends View {
    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arcPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int value = 0;
    private String label = "指数";
    private int color = Color.rgb(92, 108, 255);

    public RingGaugeView(Context context) {
        super(context);
        init();
    }

    public RingGaugeView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        trackPaint.setColor(Color.argb(60, 130, 139, 170));
        trackPaint.setStrokeWidth(dp(9));
        trackPaint.setStyle(Paint.Style.STROKE);
        trackPaint.setStrokeCap(Paint.Cap.ROUND);

        arcPaint.setStrokeWidth(dp(9));
        arcPaint.setStyle(Paint.Style.STROKE);
        arcPaint.setStrokeCap(Paint.Cap.ROUND);

        textPaint.setColor(Color.rgb(24, 33, 43));
        textPaint.setTextSize(dp(22));
        textPaint.setFakeBoldText(true);
        textPaint.setTextAlign(Paint.Align.CENTER);

        labelPaint.setColor(Color.rgb(100, 112, 125));
        labelPaint.setTextSize(dp(11));
        labelPaint.setTextAlign(Paint.Align.CENTER);
    }

    public void setGauge(int value, String label, int color) {
        this.value = Math.max(0, Math.min(100, value));
        this.label = label;
        this.color = color;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float size = Math.min(getWidth(), getHeight()) - dp(16);
        float left = (getWidth() - size) / 2f;
        float top = dp(8);
        RectF rect = new RectF(left, top, left + size, top + size);
        arcPaint.setColor(color);
        canvas.drawArc(rect, 145, 250, false, trackPaint);
        canvas.drawArc(rect, 145, 250 * value / 100f, false, arcPaint);
        canvas.drawText(String.valueOf(value), getWidth() / 2f, top + size / 2f + dp(7), textPaint);
        canvas.drawText(label, getWidth() / 2f, top + size + dp(18), labelPaint);
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}