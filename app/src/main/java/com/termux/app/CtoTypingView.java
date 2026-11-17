package com.termux.app;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/** Three bouncing dots shown while the CTO engine is composing a reply. */
public final class CtoTypingView extends View {

    private static final int DOT_COUNT = 3;
    private static final int DOT_COLOR = 0xFF8B5CF6;

    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private ValueAnimator mAnimator;

    public CtoTypingView(@NonNull Context context) {
        this(context, null);
    }

    public CtoTypingView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        mPaint.setColor(DOT_COLOR);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int dip = (int) (9 * getResources().getDisplayMetrics().density);
        setMeasuredDimension(dip * DOT_COUNT * 2, dip * 3);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        mAnimator = ValueAnimator.ofFloat(0f, (float) (2 * Math.PI));
        mAnimator.setDuration(900);
        mAnimator.setRepeatCount(ValueAnimator.INFINITE);
        mAnimator.setInterpolator(new LinearInterpolator());
        mAnimator.addUpdateListener(animation -> {
            invalidate();
        });
        mAnimator.start();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (mAnimator != null)
            mAnimator.cancel();
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        int dip = (int) (9 * getResources().getDisplayMetrics().density);
        int radius = dip / 2;
        float phase = mAnimator == null ? 0f : (float) mAnimator.getAnimatedValue();
        for (int i = 0; i < DOT_COUNT; i++) {
            float offset = (float) Math.sin(phase + i * (Math.PI / 3f));
            float alpha = 0.35f + 0.45f * (0.5f + 0.5f * offset);
            mPaint.setAlpha((int) (255 * alpha));
            float x = dip + i * dip * 2 + radius;
            float y = dip * 1.5f + offset * dip * 0.5f;
            canvas.drawCircle(x, y, radius, mPaint);
        }
    }
}
