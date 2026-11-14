package com.tvlauncher;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/**
 * Album art for a wide card: the cover itself as a rounded square on the left, over a soft backdrop made from
 * the same cover (a tiny copy scaled up, which looks blurred and is almost free).
 */
final class NowPlayingArt extends Drawable {
    private final Bitmap art, tiny;
    private final BitmapShader shader;
    private final Matrix m = new Matrix();
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();

    NowPlayingArt(Bitmap art) {
        this.art = art;
        tiny = Bitmap.createScaledBitmap(art, 16, 16, true);
        shader = new BitmapShader(art, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        p.setFilterBitmap(true);
    }

    @Override
    public void draw(Canvas c) {
        android.graphics.Rect b = getBounds();
        // backdrop: the tiny copy stretched over the whole card, dimmed
        p.setShader(null);
        p.setColor(Color.BLACK);
        c.drawBitmap(tiny, null, b, p);
        p.setColor(Color.argb(110, 0, 0, 0));
        c.drawRect(b, p);
        // the cover, top-left, leaving the bottom for the text
        float side = b.height() * 0.5f, left = b.height() * 0.1f, top = b.height() * 0.1f;
        m.setScale(side / art.getWidth(), side / art.getHeight());
        m.postTranslate(left, top);
        shader.setLocalMatrix(m);
        p.setColor(Color.BLACK);
        p.setShader(shader);
        r.set(left, top, left + side, top + side);
        c.drawRoundRect(r, side * 0.08f, side * 0.08f, p);
        p.setShader(null);
    }

    @Override public void setAlpha(int alpha) {}
    @Override public void setColorFilter(ColorFilter cf) {}
    @Override public int getOpacity() { return PixelFormat.OPAQUE; }
}
