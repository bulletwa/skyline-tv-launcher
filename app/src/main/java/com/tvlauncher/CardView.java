package com.tvlauncher;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.text.TextUtils;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Home-screen card: 16dp corners, 1.6dp white 20% border, picture filling the card with a bottom dark
 * gradient for the text. Pictures crossfade between two ImageViews; focus zooms the card.
 */
final class CardView extends FrameLayout {
    private final float d;
    private final ImageView imgA, imgB;
    private final LinearLayout textCol;
    private final View dot;
    private final TextView accent, title, subtitle, hint;
    private final DotsView dots;
    private final LinearLayout footer;
    private View extra;

    CardView(Context c) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
        setFocusable(true);
        setClickable(true);
        setBackgroundColor(Color.rgb(16, 20, 30));
        setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), 16 * d);
            }
        });
        setClipToOutline(true);

        imgA = image();
        imgB = image();
        imgB.setAlpha(0f);
        addView(imgA, new LayoutParams(-1, -1));
        addView(imgB, new LayoutParams(-1, -1));

        View shade = new View(c);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x00000000, 0x10000000, 0xB4000000, 0xF0000000}));
        addView(shade, new LayoutParams(-1, -1));

        textCol = new LinearLayout(c);
        textCol.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(20 * d);
        textCol.setPadding(pad, 0, pad, Math.round(16 * d));
        addView(textCol, new LayoutParams(-1, -2, Gravity.BOTTOM));

        LinearLayout accentRow = new LinearLayout(c);
        accentRow.setOrientation(LinearLayout.HORIZONTAL);
        accentRow.setGravity(Gravity.CENTER_VERTICAL);
        dot = new View(c);
        GradientDrawable dg = new GradientDrawable();
        dg.setShape(GradientDrawable.OVAL);
        dg.setColor(Color.rgb(230, 40, 40));
        dot.setBackground(dg);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(Math.round(7 * d), Math.round(7 * d));
        dlp.rightMargin = Math.round(8 * d);
        accentRow.addView(dot, dlp);
        accent = text(11, 500, Color.argb(220, 255, 255, 255));
        accent.setLetterSpacing(0.2f);
        accent.setEllipsize(TextUtils.TruncateAt.END); // long place names must end in "…", not be cut mid-letter
        accentRow.addView(accent, new LinearLayout.LayoutParams(0, -2, 1f));
        textCol.addView(accentRow);

        title = text(22, 400, Color.WHITE);
        title.setSingleLine(false);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setLineSpacing(0, 0.98f);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-1, -2);
        tlp.topMargin = Math.round(6 * d);
        textCol.addView(title, tlp);

        subtitle = text(14, 300, Color.argb(184, 255, 255, 255));
        subtitle.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, -2);
        slp.topMargin = Math.round(4 * d);
        textCol.addView(subtitle, slp);

        footer = new LinearLayout(c);
        footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.setGravity(Gravity.CENTER_VERTICAL);
        dots = new DotsView(c);
        footer.addView(dots);
        View spacer = new View(c);
        footer.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));
        hint = text(12, 300, Color.argb(150, 255, 255, 255));
        hint.setEllipsize(TextUtils.TruncateAt.END);
        hint.setMaxWidth(Math.round(240 * d));
        footer.addView(hint);
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(-1, -2);
        flp.topMargin = Math.round(10 * d);
        textCol.addView(footer, flp);
        footer.setVisibility(GONE);

        // Border sits above everything; brighter while focused.
        GradientDrawable rest = new GradientDrawable();
        rest.setCornerRadius(16 * d);
        rest.setStroke(Math.round(1.6f * d), Color.argb(51, 255, 255, 255));
        GradientDrawable focus = new GradientDrawable();
        focus.setCornerRadius(16 * d);
        focus.setStroke(Math.round(2.4f * d), Color.argb(235, 255, 255, 255));
        StateListDrawable border = new StateListDrawable();
        border.addState(new int[]{android.R.attr.state_focused}, focus);
        border.addState(new int[]{}, rest);
        setForeground(border);
    }

    private ImageView image() {
        ImageView v = new ImageView(getContext());
        v.setScaleType(ImageView.ScaleType.CENTER_CROP);
        return v;
    }

    private TextView text(float sp, int weight, int color) {
        TextView t = new TextView(getContext());
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(Fonts.get(getContext(), weight));
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        t.setSingleLine(true);
        return t;
    }

    @Override
    protected void onFocusChanged(boolean gained, int direction, android.graphics.Rect prev) {
        super.onFocusChanged(gained, direction, prev);
        float s = gained ? 1.03f : 1f;
        animate().scaleX(s).scaleY(s).setDuration(160).start();
    }

    // ------------------------------------------------------------------ content

    /** @param dotColor 0 hides the dot; @param textColor accent text colour. */
    void setAccent(String s, int dotColor, int textColor) {
        accent.setText(s);
        accent.setTextColor(textColor);
        dot.setVisibility(dotColor == 0 ? GONE : VISIBLE);
        if (dotColor != 0) ((GradientDrawable) dot.getBackground()).setColor(dotColor);
    }

    /** Debug dump: what the picture is, whether it is on, and the zoom; the texts are in the tree. */
    String debug() {
        android.graphics.drawable.Drawable a = imgA.getDrawable(), b = imgB.getDrawable();
        return String.format(java.util.Locale.ENGLISH, "scale=%.2f img=%s%s dot=%s footer=%s extra=%s",
                getScaleX(), a == null ? "none" : a.getClass().getSimpleName(),
                b != null && imgB.getAlpha() > 0.01f ? "+fading" : "", dot.getVisibility() == VISIBLE,
                footer.getVisibility() == VISIBLE, extra != null);
    }

    void setTitle(String s) {
        title.setText(s);
    }

    /** Big single-line numbers (temperature) or the normal two-line headline. */
    void setTitleStyle(float sp, int weight, int maxLines) {
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        title.setTypeface(Fonts.get(getContext(), weight));
        title.setMaxLines(maxLines);
        title.setSingleLine(maxLines == 1);
    }

    void setSubtitle(String s) {
        subtitle.setText(s);
        subtitle.setVisibility(s == null || s.isEmpty() ? GONE : VISIBLE);
    }

    /** Puts a custom view (the weather week) between the subtitle and the footer; null removes it. */
    void setExtra(View v) {
        if (extra == v) return;
        if (extra != null) textCol.removeView(extra);
        extra = v;
        if (v != null) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = Math.round(10 * d);
            textCol.addView(v, textCol.indexOfChild(footer), lp);
        }
    }

    void setFooter(int dotCount, int selected, String hintText) {
        footer.setVisibility(dotCount <= 0 && hintText == null ? GONE : VISIBLE);
        dots.set(dotCount, selected);
        dots.setVisibility(dotCount <= 0 ? GONE : VISIBLE);
        hint.setText(hintText == null ? "" : hintText);
    }

    /** Fades the text out, applies the change and fades it back in. */
    void swapText(final Runnable change, boolean animate) {
        textCol.animate().cancel();
        if (!animate) {
            change.run();
            textCol.setAlpha(1f);
            return;
        }
        textCol.animate().alpha(0f).setDuration(160).withEndAction(new Runnable() {
            @Override public void run() {
                change.run();
                textCol.animate().alpha(1f).setDuration(260).start();
            }
        }).start();
    }

    /** Crossfades to a new picture (or sets it directly). */
    void setImage(final Drawable d, boolean animate) {
        imgB.animate().cancel();
        if (imgB.getAlpha() > 0f) { // a fade was interrupted: commit what was fading in
            imgA.setImageDrawable(imgB.getDrawable());
            imgB.setAlpha(0f);
        }
        if (!animate || imgA.getDrawable() == null) {
            imgA.setImageDrawable(d);
            return;
        }
        imgB.setImageDrawable(d);
        imgB.animate().alpha(1f).setDuration(700).withEndAction(new Runnable() {
            @Override public void run() {
                imgA.setImageDrawable(d);
                imgB.setAlpha(0f);
            }
        }).start();
    }
}
