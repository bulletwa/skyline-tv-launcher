package com.tvlauncher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/** "No picture" tile: the channel's colour with its initials. Never a stock photo. */
final class TileDrawable extends Drawable {
    private final int color;
    private final String initials;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private android.graphics.LinearGradient grad;
    private int gw, gh;

    TileDrawable(Context c, int rgb, String initials) {
        color = rgb;
        this.initials = initials;
        text.setTypeface(Fonts.get(c, 600));
        text.setTextAlign(Paint.Align.CENTER);
        text.setColor(Color.argb(70, 255, 255, 255));
    }

    @Override
    public void draw(Canvas c) {
        android.graphics.Rect b = getBounds();
        fill.setColor(Color.BLACK);
        if (grad == null || gw != b.width() || gh != b.height()) {
            grad = new LinearGradient(b.left, b.top, b.right, b.bottom,
                    0xFF000000 | SkyModel.mix(color, 0xFFFFFF, 0.08), 0xFF000000 | SkyModel.scale(color, 0.45),
                    Shader.TileMode.CLAMP);
            gw = b.width();
            gh = b.height();
        }
        fill.setShader(grad);
        c.drawRect(b, fill);
        text.setTextSize(b.height() * 0.5f);
        c.drawText(initials, b.exactCenterX(), b.exactCenterY() - b.height() * 0.06f + text.getTextSize() * 0.35f, text);
    }

    @Override public void setAlpha(int alpha) {}
    @Override public void setColorFilter(ColorFilter cf) {}
    @Override public int getOpacity() { return PixelFormat.OPAQUE; }
}
