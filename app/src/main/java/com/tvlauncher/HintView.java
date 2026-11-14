package com.tvlauncher;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;

/**
 * A chevron above "ALL APPS": tells you the apps are one step down. It nudges a few times when asked and then
 * holds still, so it costs nothing while idle. The chevron's whole travel stays clear of the label.
 */
final class HintView extends View {
    private static final float HEIGHT_DP = 34, CHEVRON_TOP_DP = 3, CHEVRON_H_DP = 6, NUDGE_DP = 3, LABEL_BASELINE_FROM_BOTTOM_DP = 5;

    private final float d;
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path chevron = new Path();
    private float nudge; // 0..1
    private ValueAnimator anim;

    HintView(Context c) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);
        line.setStrokeWidth(1.8f * d);
        line.setColor(Color.argb(200, 255, 255, 255));
        text.setTypeface(Fonts.get(c, 400));
        text.setTextSize(10 * d);
        text.setLetterSpacing(0.22f);
        text.setTextAlign(Paint.Align.CENTER);
        text.setColor(Color.argb(150, 255, 255, 255));
    }

    /** Lowest pixel the chevron can reach, measured from the top; the label starts below this. */
    static float chevronBottomDp() {
        return CHEVRON_TOP_DP + CHEVRON_H_DP + NUDGE_DP + 1; // +1 for the stroke
    }

    /** Highest pixel of the label (cap height), measured from the top. */
    static float labelTopDp() {
        return HEIGHT_DP - LABEL_BASELINE_FROM_BOTTOM_DP - 7.5f; // 10sp text: caps are ~7.5dp tall
    }

    @Override
    protected void onMeasure(int w, int h) {
        setMeasuredDimension(MeasureSpec.getSize(w), Math.round(HEIGHT_DP * d));
    }

    /** A few gentle nudges downward. */
    void bounce() {
        if (anim != null) anim.cancel();
        anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(420);
        anim.setRepeatCount(3);
        anim.setRepeatMode(ValueAnimator.REVERSE);
        anim.setInterpolator(new AccelerateDecelerateInterpolator());
        anim.addUpdateListener(a -> {
            nudge = (float) a.getAnimatedValue();
            invalidate();
        });
        anim.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator a) {
                nudge = 0;
                invalidate();
            }
            @Override public void onAnimationCancel(android.animation.Animator a) {
                nudge = 0;
            }
        });
        anim.start();
    }

    void stopBounce() {
        if (anim != null) anim.cancel();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        stopBounce();
    }

    @Override
    protected void onDraw(Canvas c) {
        float cx = getWidth() / 2f, y = CHEVRON_TOP_DP * d + nudge * NUDGE_DP * d;
        chevron.reset();
        chevron.moveTo(cx - 7 * d, y);
        chevron.lineTo(cx, y + CHEVRON_H_DP * d);
        chevron.lineTo(cx + 7 * d, y);
        c.drawPath(chevron, line);
        c.drawText("ALL APPS", cx, getHeight() - LABEL_BASELINE_FROM_BOTTOM_DP * d, text);
    }
}
