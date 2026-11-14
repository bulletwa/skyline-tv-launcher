package com.tvlauncher;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.util.DisplayMetrics;
import android.view.View;

/**
 * Frosted glass background: the blurred wallpaper slice behind the view, a dark tint, a light top
 * sheen and a 1.2dp rim that is bright at the top and fades down the sides. Use as a view background.
 */
final class GlassDrawable extends Drawable {
    private final Glass glass = Glass.get();
    private final float radius, rimWidth;
    private final int tint;
    private Bitmap backdrop; // dialogs supply their own (home screen behind them); null = plain wallpaper
    private Bitmap shaderSrc;
    private BitmapShader shader;
    private Shader sheen, rim;
    private final Matrix m = new Matrix();
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final int[] loc = new int[2];

    /** @param tintAlpha 0..255 black tint over the blurred wallpaper (~128 for panels). */
    GlassDrawable(float radiusPx, float rimPx, int tintAlpha) {
        radius = radiusPx;
        rimWidth = rimPx;
        tint = tintAlpha;
        p.setFilterBitmap(true);
        glass.register(this);
    }

    void setBackdrop(Bitmap b) {
        backdrop = b;
        invalidateSelf();
    }

    @Override
    protected void onBoundsChange(android.graphics.Rect b) {
        float h = b.height();
        sheen = new LinearGradient(0, b.top, 0, b.top + h * 0.5f, 0x30FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP);
        rim = new LinearGradient(0, b.top, 0, b.bottom,
                new int[]{0xA0FFFFFF, 0x30FFFFFF, 0x14FFFFFF}, new float[]{0f, 0.4f, 1f}, Shader.TileMode.CLAMP);
    }

    @Override
    public void draw(Canvas c) {
        android.graphics.Rect b = getBounds();
        if (b.isEmpty()) return;
        r.set(b);
        Bitmap src = backdrop != null ? backdrop : glass.blurred();
        View v = getCallback() instanceof View ? (View) getCallback() : null;
        if (src != null && v != null) {
            if (src != shaderSrc) {
                shaderSrc = src;
                shader = new BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            }
            DisplayMetrics dm = v.getResources().getDisplayMetrics();
            v.getLocationOnScreen(loc);
            // Map the small bitmap onto the whole screen, then shift so this view's window shows through.
            m.setScale(dm.widthPixels / (float) src.getWidth(), dm.heightPixels / (float) src.getHeight());
            m.postTranslate(-loc[0] + b.left, -loc[1] + b.top);
            shader.setLocalMatrix(m);
            p.setStyle(Paint.Style.FILL);
            p.setColor(Color.BLACK); // opaque first: a translucent leftover colour would scale the shader
            p.setShader(shader);
            c.drawRoundRect(r, radius, radius, p);
        }
        p.setShader(null);
        p.setStyle(Paint.Style.FILL);
        p.setColor(Color.argb(tint, 0, 0, 0));
        c.drawRoundRect(r, radius, radius, p);

        p.setColor(Color.BLACK);
        p.setShader(sheen);
        c.drawRoundRect(r, radius, radius, p);

        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(rimWidth);
        p.setShader(rim);
        float half = rimWidth / 2f;
        r.inset(half, half);
        c.drawRoundRect(r, radius - half, radius - half, p);
        p.setShader(null);
    }

    @Override
    public void getOutline(Outline o) {
        o.setRoundRect(getBounds(), radius);
    }

    @Override public void setAlpha(int alpha) {}
    @Override public void setColorFilter(ColorFilter cf) {}
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
