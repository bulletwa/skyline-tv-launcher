package com.tvlauncher;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Every dialog is one of these: a glass panel (24dp corners) over a 0.35 dim. Focus inside a dialog is a
 * soft white fill + 2dp outline on rows and a solid white pill on buttons. Never scale or add
 * elevation on focus here: it gets clipped, and a shadow under translucent fills shows as a dark fringe.
 */
final class GlassDialog extends Dialog {
    private final Activity act;
    private final float d;
    private final int widthDp;
    private final GlassDrawable bg;
    private final LinearLayout body;
    private final LinearLayout footer;
    private final TextView titleView;
    private final BoundedScrollView scroll;
    private final Panel panel;
    /** The dialog on screen now, for the debug state dump. */
    static GlassDialog top;
    /** How many are showing (should never be more than one). */
    static int open;
    private int anchorLeft = -1, anchorTop;
    private final java.util.List<Runnable> closed = new java.util.ArrayList<>();

    GlassDialog(Activity a, int widthDp) {
        super(a, android.R.style.Theme_Translucent_NoTitleBar);
        act = a;
        setOnDismissListener(di -> {
            if (top == this) top = null;
            open = Math.max(0, open - 1);
            for (Runnable r : new java.util.ArrayList<>(closed)) r.run();
        });
        // The TV keyboard covers half the screen: only show it when OK is pressed on a text field.
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
                | WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN);
        d = a.getResources().getDisplayMetrics().density;
        this.widthDp = widthDp;
        bg = new GlassDrawable(24 * d, 1.2f * d, 128);

        Window w = getWindow();
        w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        w.setDimAmount(0.35f);
        w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);

        panel = new Panel(a);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(bg);
        panel.setClipToOutline(true);
        int pad = dp(24);
        panel.setPadding(pad, dp(22), pad, dp(18));

        titleView = text(22, 300, Color.WHITE);
        titleView.setVisibility(View.GONE);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-1, -2);
        tlp.bottomMargin = dp(10);
        tlp.leftMargin = dp(8);
        panel.addView(titleView, tlp);

        scroll = new BoundedScrollView(a, (int) (a.getResources().getDisplayMetrics().heightPixels * 0.68f));
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        body = new LinearLayout(a);
        body.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(body, new FrameLayout.LayoutParams(-1, -2));
        panel.addView(scroll, new LinearLayout.LayoutParams(-1, -2));

        footer = new LinearLayout(a);
        footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.setGravity(Gravity.END);
        footer.setVisibility(View.GONE);
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(-1, -2);
        flp.topMargin = dp(14);
        panel.addView(footer, flp);
    }

    private int dp(float v) {
        return Math.round(v * d);
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        FrameLayout host = new FrameLayout(act);
        if (anchorLeft >= 0) { // a small menu just above-left of something on screen, with the screen barely dimmed
            android.util.DisplayMetrics dm = act.getResources().getDisplayMetrics();
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(widthDp), -2, Gravity.BOTTOM | Gravity.START);
            lp.leftMargin = Math.max(dp(16), Math.min(anchorLeft, dm.widthPixels - dp(widthDp) - dp(16)));
            lp.bottomMargin = Math.max(dp(8), dm.heightPixels - anchorTop + dp(22));
            panel.setPadding(dp(6), dp(6), dp(6), dp(6));
            getWindow().setDimAmount(0.12f);
            host.addView(panel, lp);
        } else {
            host.addView(panel, new FrameLayout.LayoutParams(dp(widthDp), -2, Gravity.CENTER));
        }
        setContentView(host);
    }

    // ------------------------------------------------------------------ content

    /** Shows this dialog as a small panel just above a screen position, left edges aligned. */
    GlassDialog anchoredAt(int left, int top) {
        anchorLeft = left;
        anchorTop = top;
        return this;
    }

    /** A tighter row, for small menus. */
    View compactRow(String title, final Runnable action) {
        View r = row(title, null, action);
        r.setPadding(dp(10), dp(5), dp(10), dp(5));
        TextView t = (TextView) ((ViewGroup) ((ViewGroup) r).getChildAt(0)).getChildAt(0);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        return r;
    }

    GlassDialog title(String t) {
        titleView.setText(t);
        titleView.setVisibility(View.VISIBLE);
        return this;
    }

    private java.util.function.BooleanSupplier backHandler;

    /** Lets a dialog use Back for something smaller than closing (return true when it did). */
    GlassDialog onBack(java.util.function.BooleanSupplier handler) {
        backHandler = handler;
        return this;
    }

    @Override
    public void onBackPressed() {
        if (backHandler != null && backHandler.getAsBoolean()) return;
        super.onBackPressed();
    }

    /** Runs when the dialog goes away, however it was closed. */
    GlassDialog onClosed(Runnable r) {
        closed.add(r);
        return this;
    }

    GlassDialog section(String caps) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(12);
        lp.bottomMargin = dp(4);
        lp.leftMargin = dp(8);
        body.addView(makeSection(act, caps), lp);
        return this;
    }

    /** Small spaced caps header at 50% white. */
    static TextView makeSection(Context c, String caps) {
        TextView t = text(c, 11, 400, Color.argb(128, 255, 255, 255));
        t.setSingleLine(true);
        t.setLetterSpacing(0.22f);
        t.setText(caps.toUpperCase());
        return t;
    }

    GlassDialog message(String s) {
        TextView t = text(14, 300, Color.argb(153, 255, 255, 255));
        t.setSingleLine(false);
        t.setText(s);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.leftMargin = dp(8);
        lp.rightMargin = dp(8);
        lp.bottomMargin = dp(6);
        body.addView(t, lp);
        return this;
    }

    /** A focusable row; the dialog closes after {@code action} unless keepOpen. */
    View row(String title, String sub, String value, final Runnable action, final boolean keepOpen) {
        View r = makeRow(act, title, sub, value);
        r.setOnClickListener(v -> {
            if (!keepOpen) dismiss();
            if (action != null) action.run();
        });
        body.addView(r, new LinearLayout.LayoutParams(-1, -2));
        return r;
    }

    View row(String title, String sub, final Runnable action) {
        return row(title, sub, null, action, false);
    }

    GlassDialog button(String label, final Runnable action, final boolean keepOpen) {
        TextView b = makeButton(act, label);
        b.setOnClickListener(v -> {
            if (!keepOpen) dismiss();
            if (action != null) action.run();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(40));
        lp.leftMargin = dp(10);
        footer.addView(b, lp);
        footer.setVisibility(View.VISIBLE);
        return this;
    }

    LinearLayout body() {
        return body;
    }

    /** Where the panel is on screen, for the debug dump. */
    android.graphics.Rect bounds() {
        android.graphics.Rect r = new android.graphics.Rect();
        panel.getGlobalVisibleRect(r);
        return r;
    }

    /** One line for the debug dump: the title, then each row's text, with ">" before the focused one. */
    String describe() {
        StringBuilder sb = new StringBuilder(titleView.getVisibility() == View.VISIBLE ? titleView.getText() : "menu");
        sb.append(" [");
        View focus = getCurrentFocus();
        describeInto(body, focus, sb);
        describeInto(footer, focus, sb);
        return sb.append(']').toString();
    }

    private static void describeInto(ViewGroup g, View focus, StringBuilder sb) {
        for (int i = 0; i < g.getChildCount(); i++) {
            View c = g.getChildAt(i);
            if (c.isFocusable()) {
                String t = firstText(c);
                if (t == null) continue;
                if (sb.charAt(sb.length() - 1) != '[') sb.append(" | ");
                sb.append(c == focus ? "> " : "").append(t);
            } else if (c instanceof ViewGroup) {
                describeInto((ViewGroup) c, focus, sb);
            }
        }
    }

    private static String firstText(View v) {
        if (v instanceof TextView) return ((TextView) v).getText().toString();
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                String t = firstText(g.getChildAt(i));
                if (t != null && !t.isEmpty()) return t;
            }
        }
        return null;
    }

    @Override
    public void show() {
        top = this;
        open++;
        super.show();
        View f = body.getChildCount() > 0 ? firstFocusable(body) : null;
        if (f == null && footer.getChildCount() > 0) f = footer.getChildAt(0);
        if (f != null) f.requestFocus();
    }

    private static View firstFocusable(ViewGroup g) {
        for (int i = 0; i < g.getChildCount(); i++) {
            View c = g.getChildAt(i);
            if (c.isFocusable()) return c;
            if (c instanceof ViewGroup) {
                View f = firstFocusable((ViewGroup) c);
                if (f != null) return f;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ shared widgets

    static TextView text(Context c, float sp, int weight, int color) {
        TextView t = new TextView(c);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(Fonts.get(c, weight));
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        return t;
    }

    private TextView text(float sp, int weight, int color) {
        TextView t = text(act, sp, weight, color);
        t.setSingleLine(true);
        return t;
    }

    /** Row: transparent at rest; focused = soft white fill + 2dp white outline. */
    static LinearLayout makeRow(Context c, String title, String sub, String value) {
        float d = c.getResources().getDisplayMetrics().density;
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setFocusable(true);
        row.setClickable(true);
        row.setPadding(Math.round(14 * d), Math.round(11 * d), Math.round(14 * d), Math.round(11 * d));

        GradientDrawable focused = new GradientDrawable();
        focused.setCornerRadius(12 * d);
        focused.setColor(Color.argb(40, 255, 255, 255));
        focused.setStroke(Math.round(2 * d), Color.argb(235, 255, 255, 255));
        GradientDrawable rest = new GradientDrawable();
        rest.setCornerRadius(12 * d);
        rest.setColor(Color.TRANSPARENT);
        StateListDrawable sl = new StateListDrawable();
        sl.addState(new int[]{android.R.attr.state_focused}, focused);
        sl.addState(new int[]{}, rest);
        row.setBackground(sl);

        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView t = text(c, 17, 400, Color.WHITE);
        t.setText(title);
        col.addView(t);
        if (sub != null && !sub.isEmpty()) {
            TextView s = text(c, 13, 300, Color.argb(153, 255, 255, 255));
            s.setText(sub);
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-2, -2);
            slp.topMargin = Math.round(3 * d);
            col.addView(s, slp);
        }
        row.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));
        if (value != null) {
            TextView v = text(c, 15, 300, Color.argb(153, 255, 255, 255));
            v.setText(value);
            LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(-2, -2);
            vlp.leftMargin = Math.round(16 * d);
            row.addView(v, vlp);
        }
        return row;
    }

    /** Button/chip: translucent pill at rest; focused = solid white with dark text. */
    static TextView makeButton(Context c, String label) {
        float d = c.getResources().getDisplayMetrics().density;
        TextView b = text(c, 15, 400, Color.WHITE);
        b.setText(label);
        b.setGravity(Gravity.CENTER);
        b.setFocusable(true);
        b.setClickable(true);
        b.setPadding(Math.round(20 * d), 0, Math.round(20 * d), 0);
        GradientDrawable focused = new GradientDrawable();
        focused.setCornerRadius(100 * d);
        focused.setColor(Color.WHITE);
        GradientDrawable rest = new GradientDrawable();
        rest.setCornerRadius(100 * d);
        rest.setColor(Color.argb(36, 255, 255, 255));
        rest.setStroke(Math.max(1, Math.round(d)), Color.argb(64, 255, 255, 255));
        StateListDrawable sl = new StateListDrawable();
        sl.addState(new int[]{android.R.attr.state_focused}, focused);
        sl.addState(new int[]{}, rest);
        b.setBackground(sl);
        b.setTextColor(new ColorStateList(
                new int[][]{{android.R.attr.state_focused}, {}},
                new int[]{Color.rgb(24, 24, 28), Color.WHITE}));
        return b;
    }

    /**
     * Tab / category chip. Focused = solid white with dark text (like buttons); the selected chip keeps a
     * stronger translucent fill so you can tell which one is active when focus is elsewhere.
     */
    static TextView makeChip(Context c, String label, boolean selected) {
        float d = c.getResources().getDisplayMetrics().density;
        TextView b = text(c, 14, selected ? 500 : 400, Color.WHITE);
        b.setText(label);
        b.setGravity(Gravity.CENTER);
        b.setFocusable(true);
        b.setClickable(true);
        b.setPadding(Math.round(16 * d), 0, Math.round(16 * d), 0);
        GradientDrawable focused = new GradientDrawable();
        focused.setCornerRadius(100 * d);
        focused.setColor(Color.WHITE);
        GradientDrawable rest = new GradientDrawable();
        rest.setCornerRadius(100 * d);
        rest.setColor(Color.argb(selected ? 90 : 28, 255, 255, 255));
        rest.setStroke(Math.max(1, Math.round(d)), Color.argb(selected ? 150 : 56, 255, 255, 255));
        StateListDrawable sl = new StateListDrawable();
        sl.addState(new int[]{android.R.attr.state_focused}, focused);
        sl.addState(new int[]{}, rest);
        b.setBackground(sl);
        b.setTextColor(new ColorStateList(
                new int[][]{{android.R.attr.state_focused}, {}},
                new int[]{Color.rgb(24, 24, 28), Color.WHITE}));
        return b;
    }

    /** Single-line text field in the same language as rows: soft fill at rest, white outline when focused. */
    static android.widget.EditText makeInput(Context c, String hint) {
        float d = c.getResources().getDisplayMetrics().density;
        android.widget.EditText e = new android.widget.EditText(c);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        e.setTypeface(Fonts.get(c, 400));
        e.setTextColor(Color.WHITE);
        e.setHint(hint);
        e.setHintTextColor(Color.argb(128, 255, 255, 255));
        e.setSingleLine(true);
        e.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
        e.setIncludeFontPadding(false);
        e.setPadding(Math.round(14 * d), Math.round(11 * d), Math.round(14 * d), Math.round(11 * d));
        GradientDrawable focused = new GradientDrawable();
        focused.setCornerRadius(12 * d);
        focused.setColor(Color.argb(40, 255, 255, 255));
        focused.setStroke(Math.round(2 * d), Color.argb(235, 255, 255, 255));
        GradientDrawable rest = new GradientDrawable();
        rest.setCornerRadius(12 * d);
        rest.setColor(Color.argb(26, 255, 255, 255));
        rest.setStroke(Math.max(1, Math.round(d)), Color.argb(56, 255, 255, 255));
        StateListDrawable sl = new StateListDrawable();
        sl.addState(new int[]{android.R.attr.state_focused}, focused);
        sl.addState(new int[]{}, rest);
        e.setBackground(sl);
        e.setHighlightColor(Color.argb(90, 255, 255, 255));
        return e;
    }

    /** Lets callers recolour a row's trailing value (e.g. "Following"). */
    static void setRowValue(View row, String value) {
        if (!(row instanceof LinearLayout)) return;
        LinearLayout r = (LinearLayout) row;
        View last = r.getChildAt(r.getChildCount() - 1);
        if (r.getChildCount() >= 2 && last instanceof TextView) ((TextView) last).setText(value);
    }

    // ------------------------------------------------------------------ internals

    /** Captures what is behind the dialog as soon as the panel is attached to its window. */
    private final class Panel extends LinearLayout {
        Panel(Context c) {
            super(c);
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            View root = act.findViewById(android.R.id.content);
            bg.setBackdrop(Glass.get().captureBackdrop(root));
        }
    }

    private static final class BoundedScrollView extends ScrollView {
        private final int max;

        BoundedScrollView(Context c, int maxPx) {
            super(c);
            max = maxPx;
        }

        @Override
        protected void onMeasure(int w, int h) {
            super.onMeasure(w, MeasureSpec.makeMeasureSpec(max, MeasureSpec.AT_MOST));
        }
    }
}
