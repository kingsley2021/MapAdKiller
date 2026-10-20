package io.github.ldxm666.mapclean;

import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.content.res.ColorStateList;
import android.graphics.Outline;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.PathInterpolator;

/**
 * Shared motion + material kit for the settings UI.
 *
 * Everything here is plain framework API: this module builds with {@code javac} against
 * {@code android.jar} only, so there is no AndroidX, no Compose and no Material Components.
 * The glass look approximates the reference design language (Kyant0/AndroidLiquidGlass) --
 * layered translucent plates with a bright specular top rim and a soft inner falloff.
 * Real backdrop blur needs RenderEffect (API 31+) and this module ships minSdk 26, so depth
 * is built from layered alpha and elevation instead.
 */
final class UiKit {

    /* ---------------------------------------------------------------- 缓动曲线 */

    /** Fast-out, slow-in -- enter transitions and dialogs popping in. */
    static final TimeInterpolator DECELERATE =
            interpolator(0.05f, 0.7f, 0.1f, 1f);
    /** Slow-out, fast-in -- exit transitions. */
    static final TimeInterpolator ACCELERATE =
            interpolator(0.3f, 0f, 0.8f, 0.15f);
    /** Neutral easing for size and position changes. */
    static final TimeInterpolator STANDARD =
            interpolator(0.2f, 0f, 0f, 1f);
    /** Slight overshoot -- toggles, chips, page settle, press release. */
    static final TimeInterpolator SPRING =
            interpolator(0.34f, 1.56f, 0.64f, 1f);

    static final long DUR_QUICK = 140L;
    static final long DUR_BASE = 240L;
    static final long DUR_SLOW = 380L;

    private UiKit() {}

    /* ---------------------------------------------------------------- 尺寸 */

    /** dp -> px. The floating layer positions itself in raw pixels, never in dp. */
    static int dp(android.content.Context c, float value) {
        return Math.round(value * c.getResources().getDisplayMetrics().density);
    }

    /** dp -> px, float form (corner radii, stroke widths). */
    static float dpf(android.content.Context c, float value) {
        return value * c.getResources().getDisplayMetrics().density;
    }

    /** PathInterpolator is API 21+, but guard anyway so a bad ROM cannot crash the UI. */
    private static TimeInterpolator interpolator(float a, float b, float c, float d) {
        try {
            return new PathInterpolator(a, b, c, d);
        } catch (Throwable ignored) {
            return new TimeInterpolator() {
                @Override public float getInterpolation(float t) {
                    return t < 0.5f ? 2f * t * t : 1f - 2f * (1f - t) * (1f - t);
                }
            };
        }
    }

    /* ---------------------------------------------------------------- 玻璃材质 */

    /**
     * Layered glass plate: specular rim gradient on top, translucent body inset by
     * {@code stroke} px, so the edge reads as glass thickness.
     */
    static Drawable glass(int body, int rimHi, int rimLo, float radius, int stroke) {
        GradientDrawable rim = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{rimHi, rimLo});
        rim.setShape(GradientDrawable.RECTANGLE);
        rim.setCornerRadius(radius);

        GradientDrawable inner = new GradientDrawable();
        inner.setShape(GradientDrawable.RECTANGLE);
        inner.setColor(body);
        inner.setCornerRadius(Math.max(0f, radius - stroke));

        LayerDrawable ld = new LayerDrawable(new Drawable[]{rim, inner});
        int s = Math.max(1, stroke);
        ld.setLayerInset(1, s, s, s, s);
        return ld;
    }

    /** Solid rounded plate. */
    static GradientDrawable plate(int color, float radius) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setColor(color);
        g.setCornerRadius(radius);
        return g;
    }

    /** Vertical gradient plate -- accent buttons. */
    static GradientDrawable gradient(int top, int bottom, float radius) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{top, bottom});
        g.setShape(GradientDrawable.RECTANGLE);
        g.setCornerRadius(radius);
        return g;
    }

    /** Outline-only plate -- selected chips, focus rings. */
    static GradientDrawable outlined(int fill, int strokeColor, int strokePx, float radius) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setColor(fill);
        g.setCornerRadius(radius);
        g.setStroke(Math.max(1, strokePx), strokeColor);
        return g;
    }

    /** Wrap content in a bounded ripple. */
    static Drawable ripple(int color, Drawable content, float radius) {
        GradientDrawable mask = new GradientDrawable();
        mask.setShape(GradientDrawable.RECTANGLE);
        mask.setCornerRadius(radius);
        mask.setColor(0xFF000000);
        return new RippleDrawable(ColorStateList.valueOf(color), content, mask);
    }

    /** Ripple with no static content -- transparent until pressed. */
    static Drawable rippleBare(int color, float radius) {
        return ripple(color, null, radius);
    }

    /* ---------------------------------------------------------------- 变换工具 */

    /** Clip a view's children to a rounded outline. */
    static void clipRounded(final View v, final float radius) {
        v.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
        v.setClipToOutline(true);
    }

    /**
     * Springy press feedback: shrink to {@code scale} while held, overshoot slightly on
     * release. This is the tactile "press" gesture used by every tappable surface.
     */
    static void pressable(final View v, final float scale) {
        v.setOnTouchListener(new View.OnTouchListener() {
            private boolean down;
            private ValueAnimator anim;

            /**
             * Drives scaleX/scaleY from our own ValueAnimator rather than
             * {@code view.animate()}. Sharing the ViewPropertyAnimator meant that any
             * later {@code animate().cancel()} on the same view -- e.g. the pager tabs
             * re-tinting themselves in selectTab() -- killed the release animation
             * mid-flight and left the chip permanently squashed to 0.95.
             */
            private void to(float target) {
                if (anim != null) anim.cancel();
                float from = v.getScaleX();
                if (from == target) return;
                anim = ValueAnimator.ofFloat(from, target);
                anim.setDuration(target < from ? DUR_QUICK : DUR_BASE);
                anim.setInterpolator(target < from ? DECELERATE : SPRING);
                anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                    @Override public void onAnimationUpdate(ValueAnimator a) {
                        float s = (Float) a.getAnimatedValue();
                        v.setScaleX(s);
                        v.setScaleY(s);
                    }
                });
                anim.start();
            }

            @Override public boolean onTouch(View view, android.view.MotionEvent e) {
                switch (e.getActionMasked()) {
                    case android.view.MotionEvent.ACTION_DOWN:
                        down = true;
                        to(scale);
                        break;
                    case android.view.MotionEvent.ACTION_UP:
                    case android.view.MotionEvent.ACTION_CANCEL:
                        if (down) {
                            down = false;
                            to(1f);
                        }
                        break;
                    default:
                        break;
                }
                return false;   // never consume: clicks and ripples still work
            }
        });
    }

    /**
     * Animate a view's alpha in with a small upward drift.
     *
     * The view is hidden and the drift applied first so there is no flash, but the
     * animation always terminates at alpha 1 and clears the translation, and
     * {@code withEndAction} force-corrects the final state. An earlier build left views
     * at alpha 0 whenever the animation was cancelled mid-flight by a layout pass,
     * which made the whole header and pager invisible.
     */
    static void enter(final View v, float fromY, long delay) {
        v.animate().cancel();
        v.setAlpha(0f);
        v.setTranslationY(fromY);
        v.animate().alpha(1f).translationY(0f)
                .setStartDelay(delay).setDuration(DUR_SLOW)
                .setInterpolator(DECELERATE)
                .withEndAction(new Runnable() {
                    @Override public void run() {
                        v.setAlpha(1f);
                        v.setTranslationY(0f);
                    }
                }).start();
    }

    /** Guarantee a view is visible; used as a safety net after any entrance run. */
    static void reveal(final View v) {
        if (v == null) return;
        v.animate().cancel();
        v.setAlpha(1f);
        v.setTranslationY(0f);
    }

    /**
     * Staggered entrance for a list of children: each row fades up a little later than
     * the last, giving the page a deliberate "settling" feel instead of popping in.
     */
    static void enterStaggered(ViewGroup parent, float fromY, long step, long startDelay) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            long d = startDelay + i * step;
            if (d > 900L) d = 900L;             // never make the tail feel slow
            enter(child, fromY, d);
        }
    }

    /** Animate an integer property with a named interpolator. */
    static void animateInt(int from, int to, TimeInterpolator ip, long duration,
                           ValueAnimator.AnimatorUpdateListener cb) {
        ValueAnimator a = ValueAnimator.ofInt(from, to);
        a.setDuration(duration);
        a.setInterpolator(ip);
        a.addUpdateListener(cb);
        a.start();
    }

    /** Animate a float property with a named interpolator. */
    static void animateFloat(float from, float to, TimeInterpolator ip, long duration,
                             ValueAnimator.AnimatorUpdateListener cb) {
        ValueAnimator a = ValueAnimator.ofFloat(from, to);
        a.setDuration(duration);
        a.setInterpolator(ip);
        a.addUpdateListener(cb);
        a.start();
    }

    /** Blend two ARGB colours. */
    static int blend(int from, int to, float t) {
        if (t <= 0f) return from;
        if (t >= 1f) return to;
        int a = (int) (android.graphics.Color.alpha(from)
                + (android.graphics.Color.alpha(to) - android.graphics.Color.alpha(from)) * t);
        int r = (int) (android.graphics.Color.red(from)
                + (android.graphics.Color.red(to) - android.graphics.Color.red(from)) * t);
        int g = (int) (android.graphics.Color.green(from)
                + (android.graphics.Color.green(to) - android.graphics.Color.green(from)) * t);
        int b = (int) (android.graphics.Color.blue(from)
                + (android.graphics.Color.blue(to) - android.graphics.Color.blue(from)) * t);
        return android.graphics.Color.argb(a, r, g, b);
    }
}
