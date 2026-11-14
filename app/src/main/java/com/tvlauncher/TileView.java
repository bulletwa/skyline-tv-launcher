package com.tvlauncher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.text.TextUtils;
import android.view.View;

/** App banner used in the dock (scale + defocus, set by the carousel) and the All Apps grid (ring + label). */
final class TileView extends View {
    final boolean grid;
    private final float d;
    final float bw, bh;
    private final float radius;
    private int cellW;
    private Bitmap bmp;
    private BitmapShader shader;
    private String label = "";
    private final Matrix m = new Matrix();
    private final RectF rect = new RectF();
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final android.text.TextPaint text = new android.text.TextPaint(Paint.ANTI_ALIAS_FLAG);
    private String drawLabel = ""; // the label cut to the tile's width, computed when the label or width changes

    TileView(Context c, boolean grid) {
        super(c);
        this.grid = grid;
        d = c.getResources().getDisplayMetrics().density;
        bw = (grid ? 192 : 180) * d;
        bh = (grid ? 108 : 101) * d;
        radius = 16 * d;
        setFocusable(true);
        setClickable(true);
        setLongClickable(true);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(2.2f * d);
        ring.setColor(Color.WHITE);
        blurPaint.setFilterBitmap(true); // smooth stretch of the tiny blurred copy
        text.setTypeface(Fonts.get(c, 400));
        text.setTextSize(13 * d);
        text.setColor(Color.argb(210, 255, 255, 255));
        text.setTextAlign(Paint.Align.CENTER);
    }

    void setCellWidth(int px) {
        cellW = px;
    }

    void setLabel(String l) {
        label = l;
        relabel();
        invalidate();
    }

    private void relabel() {
        if (!grid || getWidth() == 0) {
            drawLabel = label;
            return;
        }
        drawLabel = TextUtils.ellipsize(label, text, getWidth() - 16 * d, TextUtils.TruncateAt.END).toString();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        relabel();
    }

    void setBitmap(Bitmap b) {
        bmp = b;
        shader = b == null ? null : new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        blurShader = null;
        if (b != null && !grid) {
            // A tiny blurred copy, stretched back up: a convincing "out of focus" banner for almost no cost.
            Bitmap small = Bitmap.createScaledBitmap(b, blurW, blurH, true).copy(Bitmap.Config.ARGB_8888, true);
            Glass.blur(small, 2, 2);
            blurShader = new BitmapShader(small, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        }
        invalidate();
    }

    private static final int blurW = 32, blurH = 18;
    private BitmapShader blurShader;
    private float defocus;
    private final Paint blurPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    /** 0 = sharp .. 1 = fully out of focus (dock apps away from the centre). */
    private android.animation.ObjectAnimator jiggle;

    /** Edit-mode wiggle, like an app being rearranged. */
    void startJiggle() {
        if (jiggle != null) return;
        setRotation(-1.8f);
        jiggle = android.animation.ObjectAnimator.ofFloat(this, View.ROTATION, -1.8f, 1.8f);
        jiggle.setDuration(120);
        jiggle.setRepeatCount(android.animation.ValueAnimator.INFINITE);
        jiggle.setRepeatMode(android.animation.ValueAnimator.REVERSE);
        jiggle.start();
    }

    void stopJiggle() {
        if (jiggle != null) jiggle.cancel();
        jiggle = null;
        setRotation(0f);
    }

    void setDefocus(float v) {
        if (Math.abs(v - defocus) < 0.01f) return;
        defocus = v;
        invalidate();
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = grid ? cellW : (int) Math.ceil(bw);
        int h = (int) Math.ceil(bh + (grid ? 40 * d : 0));
        setMeasuredDimension(w, h);
    }

    boolean hasBitmap() { return bmp != null; }

    /** True when the label had to be cut with an ellipsis (the label is drawn on a canvas, so the tree cannot see it). */
    boolean labelCut() { return !drawLabel.equals(label); }

    String lookReport() {
        return String.format(java.util.Locale.ENGLISH, "s=%.2f a=%.2f blur=%.2f", getScaleX(), getAlpha(), defocus);
    }

    @Override
    protected void onFocusChanged(boolean gained, int direction, android.graphics.Rect prev) {
        super.onFocusChanged(gained, direction, prev);
        // Dock apps are scaled and faded by the dock itself (centre = sharp, sides fall off); only grid apps zoom here.
        if (grid) {
            float s = gained ? 1.06f : 1f;
            animate().scaleX(s).scaleY(s).setDuration(160).start();
        }
        invalidate();
    }

    /** The picture never overlaps itself, so fading it needs no offscreen layer (much cheaper on this TV). */
    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }

    @Override
    protected void onDraw(Canvas c) {
        float left = (getWidth() - bw) / 2f;
        float top = grid ? 6 * d : 0;
        rect.set(left, top, left + bw, top + bh);
        boolean f = isFocused();
        if (shader != null) {
            m.setTranslate(left, top);
            shader.setLocalMatrix(m);
            fill.setColor(Color.BLACK); // opaque: a leftover translucent colour would scale the shader
            fill.setShader(shader);
            c.drawRoundRect(rect, radius, radius, fill);
            fill.setShader(null);
            if (defocus > 0.02f && blurShader != null) {
                // The sharp banner is underneath; the blurred copy fades in over it as the app leaves the centre.
                m.setScale(bw / blurW, bh / blurH);
                m.postTranslate(left, top);
                blurShader.setLocalMatrix(m);
                blurPaint.setColor(Color.argb((int) (255 * Math.min(1f, defocus)), 0, 0, 0));
                blurPaint.setShader(blurShader);
                c.drawRoundRect(rect, radius, radius, blurPaint);
                blurPaint.setShader(null);
            }
        } else {
            fill.setColor(Color.argb(40, 255, 255, 255));
            c.drawRoundRect(rect, radius, radius, fill);
        }
        if (grid) {
            if (f) {
                ring.setAlpha(255);
                c.drawRoundRect(rect, radius, radius, ring);
            }
            c.drawText(drawLabel, getWidth() / 2f, top + bh + 24 * d, text);
        }
    }
}
