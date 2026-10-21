package io.github.ldxm666.mapclean;

import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.view.View;

/**
 * White-background liquid glass.
 *
 * <p>The reference (Kyant0/AndroidLiquidGlass) builds glass from five layers: vibrancy ->
 * tiny blur -> lens -> a 45 degree specular highlight (white 50 %, additive) -> an inner
 * shadow (black 15 %, radius 24 dp, offset down) -> a thin rim. On a pure white page the
 * first three have nothing to act on, so the character comes from the highlight, the inner
 * shadow and the rim: the pane reads as a polished, slightly cool glass tile that catches
 * the light from the top-left.
 *
 * <p>Layers implemented here, bottom to top:
 * <ol>
 *   <li>cool glass fill (vertical gradient, a touch darker than the page),</li>
 *   <li>45 degree white specular sweep fading out by 55 %,</li>
 *   <li>inner bottom shadow (blurred inset stroke),</li>
 *   <li>1 dp rim: white top-left fading to translucent grey bottom-right.</li>
 * </ol>
 */
final class GlassDrawable extends Drawable {

    private final float radius;
    private final int tint;
    private final boolean dark;
    private final float density;
    private final float stroke;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint inner = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final RectF rect = new RectF();
    private final RectF inset = new RectF();
    private final Path clip = new Path();

    GlassDrawable(View host, float radius, int tint, boolean dark) {
        this.radius = radius;
        this.tint = tint;
        this.dark = dark;
        this.density = host.getResources().getDisplayMetrics().density;
        this.stroke = Math.max(1f, 1.0f * density);
        inner.setStyle(Paint.Style.FILL);
        inner.setColor(0x1A000000);
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(stroke);
    }

    @Override public void draw(Canvas canvas) {
        Rect bounds = getBounds();
        if (bounds.width() <= 0 || bounds.height() <= 0) return;
        rect.set(bounds);
        float r = Math.min(radius, Math.min(rect.width(), rect.height()) / 2f);

        clip.reset();
        clip.addRoundRect(rect, r, r, Path.Direction.CW);
        canvas.save();
        canvas.clipPath(clip);

        // Light: cool white pane. Dark: the pane must stay *darker* than white text --
        // blending toward white here was what turned every card into flat grey in night mode.
        int top = dark ? UiKit.blend(tint, 0xFF252A33, 0.55f) : UiKit.blend(tint, 0xFFFFFFFF, 0.45f);
        int bottom = dark ? UiKit.blend(tint, 0xFF0B0D12, 0.45f) : UiKit.blend(tint, 0xFFE7EBF2, 0.22f);
        fill.setShader(new LinearGradient(0f, rect.top, 0f, rect.bottom,
                new int[]{top, bottom}, null, Shader.TileMode.CLAMP));
        canvas.drawRect(rect, fill);

        sheen.setShader(new LinearGradient(rect.left, rect.top,
                rect.left + rect.width() * 0.85f, rect.top + rect.height() * 0.85f,
                new int[]{dark ? 0x17FFFFFF : 0x73FFFFFF, 0x00FFFFFF}, new float[]{0f, 0.58f},
                Shader.TileMode.CLAMP));
        canvas.drawRect(rect, sheen);

        // Inner shadow, offset down like the reference (0, +24 dp): only the lower part of
        // the pane darkens, which reads as thickness instead of a dirty border all round.
        inner.setShader(new LinearGradient(0f, rect.top, 0f, rect.bottom,
                new int[]{0x00000000, 0x00000000, dark ? 0x66000000 : 0x1A000000},
                new float[]{0f, 0.70f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawRect(rect, inner);

        canvas.restore();

        // Contrast pair: a 1 dp dark rim outside a 0.8 dp white inner rim. On white this is
        // what actually defines the pane's edge (a white-only rim disappears).
        rim.setShader(null);
        rim.setColor(dark ? 0x99000000 : 0x1A0B1220);
        rim.setStrokeWidth(Math.max(1f, density));
        float half = Math.max(1f, density) / 2f;
        canvas.drawRoundRect(new RectF(rect.left + half, rect.top + half,
                rect.right - half, rect.bottom - half), r - half, r - half, rim);

        rim.setColor(dark ? 0x24FFFFFF : 0xD9FFFFFF);
        rim.setStrokeWidth(0.8f * density);
        float in = 1.4f * density;
        canvas.drawRoundRect(new RectF(rect.left + in, rect.top + in,
                rect.right - in, rect.bottom - in), Math.max(0f, r - in),
                Math.max(0f, r - in), rim);
    }

    @Override public void setAlpha(int alpha) { fill.setAlpha(alpha); }

    @Override public void setColorFilter(ColorFilter colorFilter) { fill.setColorFilter(colorFilter); }

    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
