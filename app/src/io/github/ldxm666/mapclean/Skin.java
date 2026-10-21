package io.github.ldxm666.mapclean;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Design tokens + component factory for the module settings screen.
 *
 * <p>Visual language: soft "liquid glass" — a tinted aurora backdrop, frosted translucent
 * plates with a bright specular rim, one accent colour, and a strict spacing/type scale.
 * Implemented with plain framework API because this module compiles with {@code javac}
 * against {@code android.jar} — no AndroidX, no Compose, no XML.
 */
final class Skin {

    static final int S1 = 4;
    static final int S2 = 8;
    static final int S3 = 12;
    static final int S4 = 16;
    static final int S5 = 20;
    static final int S6 = 24;
    static final int S7 = 32;

    static final int R_CARD = 28;
    static final int R_INNER = 14;
    static final int R_CONTROL = 12;
    static final int R_PILL = 999;

    static final float T_DISPLAY = 30f;
    static final float T_TITLE = 18f;
    static final float T_ROW = 15f;
    static final float T_BODY = 13.5f;
    static final float T_CAPTION = 12f;
    static final float T_LABEL = 11f;

    static final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    static final Typeface REGULAR = Typeface.create("sans-serif", Typeface.NORMAL);
    static final Typeface BOLD = Typeface.create("sans-serif", Typeface.BOLD);

    final boolean dark;
    final int bg;
    final int surface;
    final int surface2;
    final int rimHi;
    final int rimLo;
    final int stroke;
    final int divider;
    final int text;
    final int text2;
    final int text3;
    final int accent;
    final int accentSoft;
    final int onAccent;
    final int success;
    final int warn;
    final int danger;
    final int chipBg;
    final int navBg;

    private final Context context;
    private Bitmap frostTile;

    Skin(Context context, boolean dark) {
        this.context = context;
        this.dark = dark;
        if (dark) {
            bg = 0xFF0A0B0E;
            surface = 0xFF15171C;
            surface2 = 0x991B2130;
            rimHi = 0x66FFFFFF;
            rimLo = 0x0AFFFFFF;
            stroke = 0xFF262A32;
            divider = 0x1FFFFFFF;
            text = 0xFFF4F6FB;
            text2 = 0xFFA7B0C4;
            text3 = 0xFF6F7A90;
            accent = 0xFF7FA8FF;
            accentSoft = 0x337FA8FF;
            onAccent = 0xFF06101F;
            success = 0xFF34D399;
            warn = 0xFFFBBF24;
            danger = 0xFFF87171;
            chipBg = 0x33FFFFFF;
            navBg = 0xE6121720;
        } else {
            bg = 0xFFFFFFFF;
            surface = 0xFFFFFFFF;
            surface2 = 0xFFF5F6F9;
            rimHi = 0xFFFFFFFF;
            rimLo = 0xFFFFFFFF;
            stroke = 0xFFE8EBF0;
            divider = 0xFFF0F2F5;
            text = 0xFF0F1420;
            text2 = 0xFF5D6675;
            text3 = 0xFF98A1B0;
            accent = 0xFF2F6BFF;
            accentSoft = 0xFFEDF2FF;
            onAccent = 0xFFFFFFFF;
            success = 0xFF12A150;
            warn = 0xFFC77700;
            danger = 0xFFD92D20;
            chipBg = 0xFFF3F5F8;
            navBg = 0xFFFFFFFF;
        }
    }

    /** Base tint painted over the blurred backdrop inside every glass pane. */
    int glassTint() {
        return dark ? 0xFF15171C : 0xFFF3F4F8;
    }

    /** Soft veil behind a page title so dark type stays legible over the photo. */
    Drawable headerScrim() {
        return new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{dark ? 0xE60A0B0E : 0xE6FFFFFF, dark ? 0x000A0B0E : 0x00FFFFFF});
    }

    GlassDrawable glassFor(View host, float radiusDp) {
        return new GlassDrawable(host, dp(radiusDp), glassTint(), dark);
    }

    GlassDrawable glassFor(View host, float radiusDp, int tint) {
        return new GlassDrawable(host, dp(radiusDp), tint, dark);
    }

    int dp(float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    /** Pure white canvas — the requested backdrop. */
    Drawable aurora() {
        return solid(bg, 0);
    }

    TextView display(String value) {
        return text(value, T_DISPLAY, BOLD, text);
    }

    TextView title(String value) {
        return text(value, T_TITLE, BOLD, text);
    }

    TextView rowTitle(String value) {
        return text(value, T_ROW, MEDIUM, text);
    }

    TextView body(String value) {
        return text(value, T_BODY, REGULAR, text2);
    }

    TextView caption(String value) {
        return text(value, T_CAPTION, REGULAR, text2);
    }

    TextView footnote(String value) {
        return text(value, T_LABEL, REGULAR, text3);
    }

    TextView text(String value, float sp, Typeface face, int color) {
        TextView v = new TextView(context);
        v.setText(value);
        v.setTextSize(sp);
        v.setTypeface(face);
        v.setTextColor(color);
        v.setIncludeFontPadding(false);
        return v;
    }

    LinearLayout card() {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(S4), dp(S4), dp(S4), dp(S4));
        card.setBackground(glassFor(card, R_CARD));
        UiKit.clipRounded(card, dp(R_CARD));
        shadow(card, 7f);
        return card;
    }

    LinearLayout listCard() {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(S1), dp(S2), dp(S1), dp(S2));
        card.setBackground(glassFor(card, R_CARD));
        UiKit.clipRounded(card, dp(R_CARD));
        shadow(card, 7f);
        return card;
    }

    Drawable glass(float radiusDp) {
        return border(surface, stroke, radiusDp);
    }

    /** White card tinted with the accent — the one block that leads the eye. */
    Drawable tintedGlass(int tintColor, float radiusDp) {
        int fill = UiKit.blend(surface, tintColor, dark ? 0.16f : 0.06f);
        int line = UiKit.blend(stroke, tintColor, dark ? 0.22f : 0.10f);
        return border(fill, line, radiusDp);
    }

    GradientDrawable border(int fill, int line, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setColor(fill);
        g.setCornerRadius(dp(radiusDp));
        g.setStroke(Math.max(1, dp(1)), line);
        return g;
    }

    GradientDrawable solid(int fill, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setColor(fill);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    Drawable pressable(int fill, float radiusDp) {
        return UiKit.ripple(dark ? 0x33FFFFFF : 0x14000000, solid(fill, radiusDp), dp(radiusDp));
    }

    Drawable pressableBorder(int fill, int line, float radiusDp) {
        return UiKit.ripple(dark ? 0x33FFFFFF : 0x14000000, border(fill, line, radiusDp), dp(radiusDp));
    }

    View hairline() {
        View v = new View(context);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1)));
        v.setLayoutParams(lp);
        v.setBackgroundColor(divider);
        return v;
    }

    LinearLayout row() {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(56));
        row.setPadding(dp(S2), dp(S2), dp(S2), dp(S2));
        row.setBackground(UiKit.rippleBare(dark ? 0x22FFFFFF : 0x0F000000, dp(R_INNER)));
        row.setClickable(true);
        return row;
    }

    LinearLayout rowText(String title, String caption) {
        LinearLayout col = new LinearLayout(context);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(rowTitle(title));
        if (caption != null && caption.length() > 0) {
            TextView c = caption(caption);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(2);
            col.addView(c, lp);
        }
        return col;
    }

    TextView chip(String value, int fill, int color) {
        TextView t = text(value, T_LABEL + 0.5f, MEDIUM, color);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(8), dp(4), dp(8), dp(4));
        t.setBackground(solid(fill, R_PILL));
        return t;
    }

    TextView section(String value) {
        TextView t = text(value, T_LABEL + 1f, MEDIUM, text3);
        t.setLetterSpacing(0.06f);
        t.setPadding(dp(4), 0, 0, 0);
        return t;
    }

    void addSection(LinearLayout parent, String label) {
        TextView t = section(label);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(S6);
        lp.bottomMargin = dp(S2);
        parent.addView(t, lp);
    }

    TextView primaryButton(String label) {
        TextView b = text(label, T_ROW, MEDIUM, onAccent);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(S4), dp(13), dp(S4), dp(13));
        b.setBackground(UiKit.ripple(0x33FFFFFF, accentPlate(), dp(R_INNER)));
        b.setClickable(true);
        UiKit.pressable(b, 0.98f);
        return b;
    }

    private Drawable accentPlate() {
        int top = UiKit.blend(accent, 0xFFFFFFFF, dark ? 0.06f : 0.18f);
        int bottom = UiKit.blend(accent, 0xFF000000, dark ? 0.10f : 0.06f);
        return UiKit.gradient(top, bottom, dp(R_INNER));
    }

    TextView secondaryButton(String label) {
        TextView b = text(label, T_ROW, MEDIUM, text);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(S4), dp(13), dp(S4), dp(13));
        b.setBackground(UiKit.ripple(dark ? 0x33FFFFFF : 0x14000000,
                glassFor(b, R_INNER), dp(R_INNER)));
        b.setClickable(true);
        UiKit.pressable(b, 0.98f);
        return b;
    }

    TextView textButton(String label) {
        TextView b = text(label, T_CAPTION + 0.5f, MEDIUM, accent);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(S3), dp(6), dp(S3), dp(6));
        b.setBackground(UiKit.rippleBare(dark ? 0x22FFFFFF : 0x12000000, dp(R_PILL)));
        b.setClickable(true);
        return b;
    }

    LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    void addWithTop(LinearLayout parent, View child, int topDp) {
        LinearLayout.LayoutParams lp = matchWrap();
        lp.topMargin = dp(topDp);
        parent.addView(child, lp);
    }

    /**
     * Soft, colour-tinted elevation.
     *
     * <p>Framework shadows are neutral grey and read as dirt over a coloured backdrop.
     * API 28+ lets us tint spot and ambient shadows, so the plate looks lit rather than
     * dirty; older versions simply get a lower elevation.
     */
    void shadow(View v, float elevationDp) {
        v.setElevation(dp(elevationDp));
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            v.setOutlineSpotShadowColor(dark ? 0x80000000 : 0x26000000);
            v.setOutlineAmbientShadowColor(dark ? 0x40000000 : 0x1A000000);
        }
    }

    /** 1 px rim stroked with a corner-to-corner gradient: lit top-left, quiet bottom-right. */
    private static final class Rim extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float radius;
        private final float stroke;
        private final int hi;
        private final int lo;

        Rim(float radius, float stroke, int hi, int lo) {
            this.radius = radius;
            this.stroke = stroke;
            this.hi = hi;
            this.lo = lo;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(stroke);
        }

        @Override public void draw(Canvas canvas) {
            android.graphics.Rect b = getBounds();
            if (b.width() <= 0 || b.height() <= 0) return;
            paint.setShader(new LinearGradient(b.left, b.top, b.right, b.bottom,
                    hi, lo, Shader.TileMode.CLAMP));
            float inset = stroke / 2f;
            canvas.drawRoundRect(new android.graphics.RectF(
                    b.left + inset, b.top + inset, b.right - inset, b.bottom - inset),
                    radius - inset, radius - inset, paint);
            paint.setShader(null);
        }

        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }

        @Override public void setColorFilter(ColorFilter colorFilter) {
            paint.setColorFilter(colorFilter);
        }

        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    private static final class Aurora extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint basePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int[] base;
        private final int[] colors;
        private final float[][] blobs;

        Aurora(int[] base, int[] colors, float[][] blobs) {
            this.base = base;
            this.colors = colors;
            this.blobs = blobs;
            basePaint.setStyle(Paint.Style.FILL);
        }

        @Override public void draw(Canvas canvas) {
            int w = getBounds().width();
            int h = getBounds().height();
            if (w <= 0 || h <= 0) return;
            basePaint.setShader(new LinearGradient(getBounds().left, getBounds().top,
                    getBounds().right, getBounds().bottom, base, new float[]{0f, 0.52f, 1f},
                    Shader.TileMode.CLAMP));
            canvas.drawRect(getBounds(), basePaint);
            float max = Math.max(w, h);
            for (int i = 0; i < colors.length && i < blobs.length; i++) {
                float cx = getBounds().left + w * blobs[i][0];
                float cy = getBounds().top + h * blobs[i][1];
                float radius = max * blobs[i][2];
                paint.setShader(new RadialGradient(cx, cy, radius,
                        new int[]{colors[i], colors[i] & 0x00FFFFFF}, null,
                        Shader.TileMode.CLAMP));
                canvas.drawRect(getBounds(), paint);
            }
            paint.setShader(null);
        }

        @Override public void setAlpha(int alpha) { basePaint.setAlpha(alpha); }

        @Override public void setColorFilter(ColorFilter colorFilter) {
            basePaint.setColorFilter(colorFilter);
        }

        @Override public int getOpacity() { return PixelFormat.OPAQUE; }
    }
}
