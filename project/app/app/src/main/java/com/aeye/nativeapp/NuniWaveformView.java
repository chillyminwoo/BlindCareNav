package com.aeye.nativeapp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

public class NuniWaveformView extends View {
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint wavePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint scanPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private String state = "idle";
    private long startedAt = SystemClock.uptimeMillis();

    public NuniWaveformView(Context context) {
        super(context);
        init();
    }

    public NuniWaveformView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        setLayerType(LAYER_TYPE_SOFTWARE, null);

        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(1.2f);
        ringPaint.setColor(Color.argb(38, 255, 215, 0));

        wavePaint.setStyle(Paint.Style.STROKE);
        wavePaint.setStrokeCap(Paint.Cap.ROUND);
        wavePaint.setStrokeWidth(2.4f);
        wavePaint.setColor(Color.rgb(255, 215, 0));

        fillPaint.setStyle(Paint.Style.FILL);
        scanPaint.setStyle(Paint.Style.STROKE);
        scanPaint.setStrokeWidth(1.5f);
    }

    public void setState(String nextState) {
        state = nextState == null ? "idle" : nextState;
        startedAt = SystemClock.uptimeMillis();
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        float width = getWidth();
        float height = getHeight();
        float centerX = width / 2f;
        float centerY = height / 2f;
        float baseRadius = Math.min(width, height) * 0.22f;
        float glowRadius = baseRadius + dpLike(60f);
        float elapsed = (SystemClock.uptimeMillis() - startedAt) / 1000f;

        float glowAlpha = "idle".equals(state) ? 0.04f : "listening".equals(state) ? 0.12f : 0.08f;
        fillPaint.setShader(new RadialGradient(
                centerX,
                centerY,
                glowRadius + dpLike(20f),
                new int[]{
                        Color.argb(Math.round(glowAlpha * 255), 255, 215, 0),
                        Color.TRANSPARENT
                },
                new float[]{0f, 1f},
                Shader.TileMode.CLAMP
        ));
        canvas.drawCircle(centerX, centerY, glowRadius + dpLike(20f), fillPaint);
        fillPaint.setShader(null);

        int barCount = 64;
        float angleStep = 360f / barCount;

        for (int index = 0; index < barCount; index += 1) {
            float angle = (float) Math.toRadians(index * angleStep - 90f);
            float amplitude = getAmplitude(index, elapsed, barCount);
            float inner = baseRadius;
            float outer = baseRadius + amplitude;
            float startX = centerX + (float) Math.cos(angle) * inner;
            float startY = centerY + (float) Math.sin(angle) * inner;
            float endX = centerX + (float) Math.cos(angle) * outer;
            float endY = centerY + (float) Math.sin(angle) * outer;

            float opacity = "idle".equals(state) ? 0.50f : 1.0f;
            wavePaint.setStrokeWidth("listening".equals(state) ? 2.5f : 2.0f);
            wavePaint.setShader(new LinearGradient(
                    startX,
                    startY,
                    endX,
                    endY,
                    new int[]{
                            Color.argb(Math.round(opacity * 76), 255, 215, 0),
                            Color.argb(Math.round(opacity * 255), 255, 215, 0),
                            Color.argb(Math.round(opacity * 255), 255, 255, 180)
                    },
                    new float[]{0f, 0.6f, 1f},
                    Shader.TileMode.CLAMP
            ));
            wavePaint.setShadowLayer("listening".equals(state) ? 12f : 6f, 0f, 0f, Color.rgb(255, 215, 0));
            canvas.drawLine(startX, startY, endX, endY, wavePaint);
        }

        wavePaint.setShader(null);
        wavePaint.clearShadowLayer();

        fillPaint.setShader(new RadialGradient(
                centerX,
                centerY,
                baseRadius * 0.6f,
                new int[]{
                        Color.argb(38, 255, 215, 0),
                        Color.argb(13, 255, 215, 0),
                        Color.TRANSPARENT
                },
                new float[]{0f, 0.5f, 1f},
                Shader.TileMode.CLAMP
        ));
        canvas.drawCircle(centerX, centerY, baseRadius * 0.6f, fillPaint);
        fillPaint.setShader(null);

        canvas.drawCircle(centerX, centerY, baseRadius, ringPaint);

        if ("processing".equals(state)) {
            float scanAngle = elapsed * 3f;
            float endX = centerX + (float) Math.cos(scanAngle) * (baseRadius + dpLike(50f));
            float endY = centerY + (float) Math.sin(scanAngle) * (baseRadius + dpLike(50f));
            scanPaint.setShader(new LinearGradient(
                    centerX,
                    centerY,
                    endX,
                    endY,
                    new int[]{Color.TRANSPARENT, Color.argb(128, 255, 215, 0)},
                    new float[]{0f, 1f},
                    Shader.TileMode.CLAMP
            ));
            scanPaint.setShadowLayer(8f, 0f, 0f, Color.rgb(255, 215, 0));
            canvas.drawLine(centerX, centerY, endX, endY, scanPaint);
            scanPaint.setShader(null);
            scanPaint.clearShadowLayer();
        }

        postInvalidateDelayed(48L);
    }

    private float getAmplitude(int index, float elapsed, int barCount) {
        float angle = (index / (float) barCount) * (float) Math.PI * 2f;

        if ("idle".equals(state)) {
            return dpLike(4f + (float) Math.sin(elapsed * 0.8f + angle * 2f) * 3f);
        }

        if ("processing".equals(state)) {
            float pulse = Math.abs((float) Math.sin(elapsed * 4f + (index / (float) barCount) * Math.PI));
            return dpLike(10f + pulse * 30f + (float) Math.sin(elapsed * 7f + angle) * 8f);
        }

        if ("navigating".equals(state)) {
            return dpLike(15f
                    + (float) Math.sin(elapsed * 2f + angle * 2f) * 12f
                    + (float) Math.sin(elapsed * 3.3f + angle) * 8f);
        }

        return dpLike(20f
                + (float) Math.sin(elapsed * 3.5f + angle * 3f) * 18f
                + (float) Math.sin(elapsed * 5.1f + angle * 1.5f) * 12f
                + (float) Math.sin(elapsed * 2.2f + angle * 5f) * 8f);
    }

    private float dpLike(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
