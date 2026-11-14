package com.tvlauncher;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/** Spotlight art for "your favourite app": its banner on a backdrop tinted by the banner's own colour. */
final class AppArtDrawable extends Drawable {
    private final Bitmap banner;
    private final int base;
    private final BitmapShader shader;
    private final Matrix m = new Matrix();
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private LinearGradient grad;
    private int gw, gh;

    AppArtDrawable(Bitmap banner) {
        this.banner = banner;
        shader = new BitmapShader(banner, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        // average a few pixels of the banner for the backdrop colour
        int w = banner.getWidth(), h = banner.getHeight(), rr = 0, gg = 0, bb = 0, n = 0;
        int[][] pts = {{w / 8, h / 8}, {w * 7 / 8, h / 8}, {w / 8, h * 7 / 8}, {w * 7 / 8, h * 7 / 8}, {w / 2, h / 2}};
        for (int[] pt : pts) {
            int c = banner.getPixel(pt[0], pt[1]);
            rr += Color.red(c);
            gg += Color.green(c);
            bb += Color.blue(c);
            n++;
        }
        base = SkyModel.rgb(rr / n, gg / n, bb / n);
    }

    @Override
    public void draw(Canvas c) {
        android.graphics.Rect b = getBounds();
        p.setColor(Color.BLACK);
        if (grad == null || gw != b.width() || gh != b.height()) {
            grad = new LinearGradient(0, b.top, 0, b.bottom,
                    0xFF000000 | SkyModel.scale(base, 0.9), 0xFF000000 | SkyModel.scale(base, 0.35), Shader.TileMode.CLAMP);
            gw = b.width();
            gh = b.height();
        }
        p.setShader(grad);
        c.drawRect(b, p);
        float bw = b.width() * 0.5f, bh = bw * banner.getHeight() / banner.getWidth();
        float left = b.exactCenterX() - bw / 2, top = b.height() * 0.1f;
        m.setScale(bw / banner.getWidth(), bh / banner.getHeight());
        m.postTranslate(left, top);
        shader.setLocalMatrix(m);
        p.setColor(Color.BLACK);
        p.setShader(shader);
        r.set(left, top, left + bw, top + bh);
        float rad = b.height() * 0.06f;
        c.drawRoundRect(r, rad, rad, p);
        p.setShader(null);
    }

    @Override public void setAlpha(int alpha) {}
    @Override public void setColorFilter(ColorFilter cf) {}
    @Override public int getOpacity() { return PixelFormat.OPAQUE; }
}
