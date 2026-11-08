package cn.csuft.schedulelite;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.view.Gravity;
import android.widget.TextView;

/** Shared palette and drawable builders so both screens stay visually consistent. */
final class Ui {
    static final int GREEN = Color.rgb(15, 122, 87);
    static final int GREEN_DEEP = Color.rgb(8, 82, 59);
    static final int GREEN_BRIGHT = Color.rgb(32, 163, 117);
    static final int GREEN_TINT = Color.rgb(229, 246, 239);
    static final int BG = Color.rgb(243, 246, 245);
    static final int CARD_LINE = Color.rgb(233, 238, 236);
    static final int GRID_LINE = Color.rgb(236, 240, 244);
    static final int INK = Color.rgb(28, 43, 57);
    static final int INK_SOFT = Color.rgb(99, 117, 134);
    static final int INK_FAINT = Color.rgb(148, 161, 174);
    static final int DANGER = Color.rgb(196, 62, 56);
    static final int WHITE_ON_GREEN = Color.WHITE;
    static final int WHITE_SOFT = Color.argb(224, 255, 255, 255);
    static final int GLASS = Color.argb(56, 255, 255, 255);
    static final int GLASS_STRONG = Color.argb(88, 255, 255, 255);

    /** Pastel card fill, accent stripe and readable text color per course. */
    private static final int[] COURSE_BG = {
            Color.rgb(227, 241, 254), Color.rgb(230, 246, 232), Color.rgb(255, 242, 222),
            Color.rgb(243, 231, 251), Color.rgb(255, 233, 235), Color.rgb(222, 246, 250)
    };
    private static final int[] COURSE_ACCENT = {
            Color.rgb(64, 149, 232), Color.rgb(71, 171, 96), Color.rgb(240, 150, 40),
            Color.rgb(155, 93, 200), Color.rgb(229, 92, 96), Color.rgb(38, 174, 196)
    };

    private Ui() { }

    static int courseIndex(String name) {
        return Math.abs((name == null ? "" : name).hashCode() % COURSE_BG.length);
    }

    static int courseAccent(String name) { return COURSE_ACCENT[courseIndex(name)]; }
    static int courseTint(String name) { return COURSE_BG[courseIndex(name)]; }

    static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    static TextView text(Context context, CharSequence value, float size, int color, boolean bold) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    static GradientDrawable rounded(Context context, int fill, float radius, int stroke) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(fill);
        background.setCornerRadius(dp(context, radius));
        if (stroke != Color.TRANSPARENT) background.setStroke(Math.max(1, dp(context, 0.6f)), stroke);
        return background;
    }

    static GradientDrawable circle(int fill) {
        GradientDrawable background = new GradientDrawable();
        background.setShape(GradientDrawable.OVAL);
        background.setColor(fill);
        return background;
    }

    static GradientDrawable gradient(int start, int end) {
        return new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{start, end});
    }

    /** Pastel rounded card with a colored stripe on the left edge (week grid course block). */
    static Drawable courseCard(Context context, String name) {
        return striped(context, rounded(context, courseTint(name), 9, Color.TRANSPARENT), courseAccent(name), 4, 2.5f);
    }

    /** White rounded card with a colored stripe on the left edge (daily course card). */
    static Drawable whiteAccentCard(Context context, String name) {
        return striped(context, rounded(context, Color.WHITE, 18, CARD_LINE), courseAccent(name), 5, 3);
    }

    private static Drawable striped(Context context, GradientDrawable base, int accent, float stripeWidth, float stripeRadius) {
        GradientDrawable stripe = new GradientDrawable();
        stripe.setColor(accent);
        float radius = dp(context, stripeRadius);
        stripe.setCornerRadii(new float[]{radius, radius, 0, 0, 0, 0, radius, radius});
        LayerDrawable layers = new LayerDrawable(new Drawable[]{base, stripe});
        layers.setLayerWidth(1, dp(context, stripeWidth));
        layers.setLayerGravity(1, Gravity.START | Gravity.FILL_VERTICAL);
        return layers;
    }
}
