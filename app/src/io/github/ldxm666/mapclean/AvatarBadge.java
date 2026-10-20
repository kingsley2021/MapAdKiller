package io.github.ldxm666.mapclean;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

/**
 * Circular avatar badge for the sheet header.
 *
 * <p>Two modes:
 * <ul>
 *   <li><b>Icon</b> -- the module's own launcher drawable, cropped to a circle. This is the
 *       real product mark, so it is preferred whenever the drawable resolves.</li>
 *   <li><b>Monogram</b> -- a gradient disc with the author's initials. Used when the
 *       drawable is missing, which happens in slim builds that drop the launcher surface;
 *       the header must never render an empty hole.</li>
 * </ul>
 *
 * <p>Both modes get the same treatment: a soft ring and a hairline highlight, so the badge
 * reads as a deliberate element rather than a pasted image.
 */
public final class AvatarBadge extends View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final android.graphics.drawable.Drawable icon;
    private final String monogram;
    private final float ring;

    private int ringColor = 0x33000000;
    private int gradientTop = 0xFF3D82FF;
    private int gradientBottom = 0xFF0A5CE0;

    public AvatarBadge(Context c, String fallbackMonogram) {
        this(c, fallbackMonogram, 0);
    }

    /**
     * @param drawableResource id of the author avatar, or 0 to use the module's launcher icon
     */
    public AvatarBadge(Context c, String fallbackMonogram, int drawableResource) {
        super(c);
        this.monogram = fallbackMonogram == null ? "M" : fallbackMonogram;
        this.ring = UiKit.dpf(c, 1.4f);
        this.icon = resolveIcon(c, drawableResource);
    }

    /**
     * Find the avatar, in decreasing order of preference:
     * <ol>
     *   <li>a drawable id, when one was resolved;</li>
     *   <li>the embedded JPEG in {@link AvatarAsset} -- this is the path actually used, and
     *       the only one that cannot fail, because it needs nothing beyond this class being
     *       loaded;</li>
     *   <li>the module's {@code res/drawable}, for builds where the resources are reachable.</li>
     * </ol>
     *
     * <p><b>Deliberately not</b> {@code host.getApplicationInfo().loadIcon()}: that returns
     * the <i>host's</i> icon, so the header would quietly display 高德's logo instead of the
     * author's picture. A missing avatar is better than the wrong one.
     */
    private static android.graphics.drawable.Drawable resolveIcon(Context c, int drawableResource) {
        if (drawableResource != 0) {
            try {
                android.graphics.drawable.Drawable d = c.getDrawable(drawableResource);
                if (d != null) return d;
            } catch (Throwable ignored) { }
            try {
                android.graphics.drawable.Drawable d =
                        c.getResources().getDrawable(drawableResource, c.getTheme());
                if (d != null) return d;
            } catch (Throwable ignored) { }
        }
        // Embedded data: the only path that cannot fail, since it needs nothing beyond this
        // class having been loaded.
        android.graphics.drawable.Drawable embedded = AvatarAsset.drawable(c);
        if (embedded != null) return embedded;
        return ModuleResources.drawable(c, "avatar_author");
    }

    public void setRingColor(int value) { ringColor = value; invalidate(); }

    public void setGradient(int top, int bottom) {
        gradientTop = top;
        gradientBottom = bottom;
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        float w = getWidth(), h = getHeight();
        float size = Math.min(w, h);
        if (size <= 0f) return;
        float cx = w / 2f, cy = h / 2f;
        float r = size / 2f - ring;

        if (icon != null) {
            // Clip to the disc and draw the icon scaled to cover it.
            canvas.save();
            pathReset(cx, cy, r);
            canvas.clipPath(circle);
            float iw = icon.getIntrinsicWidth(), ih = icon.getIntrinsicHeight();
            if (iw <= 0 || ih <= 0) { iw = size; ih = size; }
            float scale = Math.max(size / iw, size / ih);
            float dw = iw * scale, dh = ih * scale;
            icon.setBounds(Math.round(cx - dw / 2f), Math.round(cy - dh / 2f),
                    Math.round(cx + dw / 2f), Math.round(cy + dh / 2f));
            icon.draw(canvas);
            canvas.restore();
        } else {
            paint.setStyle(Paint.Style.FILL);
            paint.setShader(new LinearGradient(0f, cy - r, 0f, cy + r,
                    gradientTop, gradientBottom, Shader.TileMode.CLAMP));
            canvas.drawCircle(cx, cy, r, paint);
            paint.setShader(null);

            paint.setColor(0xFFFFFFFF);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            paint.setTextSize(size * 0.42f);
            Paint.FontMetrics fm = paint.getFontMetrics();
            canvas.drawText(monogram, cx, cy - (fm.ascent + fm.descent) / 2f, paint);
        }

        // Ring, then a light arc on top so the badge has a lit edge.
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(ring);
        paint.setColor(ringColor);
        canvas.drawCircle(cx, cy, r + ring / 2f, paint);
        paint.setColor(0x59FFFFFF);
        canvas.drawArc(rectFor(cx, cy, r + ring / 2f), 200f, 140f, false, paint);
        paint.setStyle(Paint.Style.FILL);
    }

    private final android.graphics.Path circle = new android.graphics.Path();

    /** Set once in the constructor so {@code onDraw} allocates nothing. */
    private void pathReset(float cx, float cy, float r) {
        circle.reset();
        circle.addCircle(cx, cy, r, android.graphics.Path.Direction.CW);
    }

    private RectF rectFor(float cx, float cy, float r) {
        rect.set(cx - r, cy - r, cx + r, cy + r);
        return rect;
    }
}
