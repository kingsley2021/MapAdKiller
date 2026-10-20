package io.github.ldxm666.mapclean;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

/**
 * Capsule switch drawn from scratch.
 *
 * Replaces the framework {@code Switch}: the stock thumb/track scaling looks foreign next
 * to the glass surfaces and cannot animate its colours. This one animates a single 0..1
 * progress value, driving track tint, thumb travel and a subtle thumb squash so the throw
 * feels physical.
 *
 * <h3>Contract (v2.2.0 rewrite)</h3>
 * <ul>
 *   <li>The view owns its own gesture: a tap anywhere on the capsule toggles it and fires
 *       {@link OnCheckedChange}. Callers must <b>not</b> also set an {@code OnClickListener}
 *       that flips the state -- that double-toggles.</li>
 *   <li>{@link #setChecked} is the <i>programmatic</i> path and never notifies, so hydrating
 *       from storage cannot write back to storage.</li>
 *   <li>The drawn state always tracks {@link #progress}. An earlier build forced the track
 *       to OFF whenever the view was disabled, so a switch that was genuinely ON still
 *       painted grey -- the whole pill looked dead and never changed colour on toggle.</li>
 *   <li>The touch target is a full 48dp tall even though the capsule is only 30dp, and the
 *       reported size is {@code width x 48dp} so the parent row gets a comfortable hit box.</li>
 * </ul>
 */
public final class GlassSwitch extends View {

    /** Notified only for user-initiated toggles (tap), never for {@link #setChecked}. */
    public interface OnCheckedChange {
        void onCheckedChanged(GlassSwitch view, boolean checked);
    }

    private static final float TRACK_W_DP = 50f;
    private static final float TRACK_H_DP = 30f;
    private static final float THUMB_DP = 24f;
    private static final float PAD_DP = 3f;
    /** Vertical footprint: keeps the capsule centred inside a finger-sized hit box. */
    private static final float TOUCH_H_DP = 48f;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private final float trackW, trackH, thumbSize, pad, radius, viewH;

    private float progress;          // 0 = off, 1 = on (may overshoot mid-spring)
    private boolean checked;
    private boolean enabled = true;
    private boolean pressed;

    private ValueAnimator anim;
    private OnCheckedChange listener;
    private String label;

    private int trackOff = 0x2E8A94A4;
    private int trackOn = 0xFF0A6CF5;
    private int thumbOff = 0xFFFFFFFF;
    private int thumbOn = 0xFFFFFFFF;

    public GlassSwitch(Context c) { this(c, null); }

    public GlassSwitch(Context c, AttributeSet a) {
        super(c, a);
        setClickable(true);
        setFocusable(true);
        float d = c.getResources().getDisplayMetrics().density;
        trackW = TRACK_W_DP * d;
        trackH = TRACK_H_DP * d;
        thumbSize = THUMB_DP * d;
        pad = PAD_DP * d;
        radius = trackH / 2f;
        viewH = TOUCH_H_DP * d;
        progress = 0f;
        refreshDescription();
    }

    public void setTrackColors(int off, int on) { trackOff = off; trackOn = on; invalidate(); }

    public void setThumbColors(int off, int on) { thumbOff = off; thumbOn = on; invalidate(); }

    /** Row title, used for the accessibility description ("…，已开启"). */
    public void setLabel(String value) {
        label = value;
        refreshDescription();
    }

    public void setOnCheckedChange(OnCheckedChange l) { listener = l; }

    public boolean isChecked() { return checked; }

    public void setChecked(boolean value) { setChecked(value, true); }

    /** {@code animate=false} snaps without motion -- used while hydrating from storage. */
    public void setChecked(boolean value, boolean animate) {
        boolean was = checked;
        checked = value;
        float target = value ? 1f : 0f;
        if (!animate) {
            cancelAnim();
            progress = target;
            if (was != value) refreshDescription();
            invalidate();
            return;
        }
        if (was == value && progress == target) return;
        if (was != value) refreshDescription();
        animateTo(target);
    }

    private void animateTo(float target) {
        cancelAnim();
        anim = ValueAnimator.ofFloat(progress, target);
        anim.setDuration(target > progress ? 240L : 180L);
        anim.setInterpolator(target > progress ? UiKit.SPRING : UiKit.ACCELERATE);
        anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(ValueAnimator a) {
                progress = (Float) a.getAnimatedValue();
                invalidate();
            }
        });
        anim.start();
    }

    private void cancelAnim() {
        if (anim != null) {
            anim.cancel();
            anim = null;
        }
    }

    @Override protected void onDetachedFromWindow() {
        cancelAnim();
        super.onDetachedFromWindow();
    }

    /**
     * Cosmetic only. The switch stays touchable either way: the surrounding row is the
     * alternate hit target, and a disabled view that still claims {@code clickable}
     * silently swallows every tap (that is the "grey pill that does nothing" report).
     */
    @Override public void setEnabled(boolean value) {
        if (enabled == value) return;
        enabled = value;
        invalidate();
    }

    @Override public boolean isEnabled() { return enabled; }

    @Override protected void onMeasure(int wSpec, int hSpec) {
        setMeasuredDimension((int) Math.ceil(trackW), (int) Math.ceil(viewH));
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (!enabled) return false;     // let the parent row take the tap
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                pressed = true;
                animate().cancel();
                animate().scaleX(0.94f).scaleY(0.94f).setDuration(90L).start();
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE: {
                boolean inside = e.getX() >= 0 && e.getX() <= getWidth()
                        && e.getY() >= 0 && e.getY() <= getHeight();
                if (pressed != inside) {
                    pressed = inside;
                    invalidate();
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
                releasePress();
                if (pressed) {
                    pressed = false;
                    invalidate();
                    performClick();
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                pressed = false;
                releasePress();
                invalidate();
                return true;
            default:
                return super.onTouchEvent(e);
        }
    }

    private void releasePress() {
        animate().cancel();
        animate().scaleX(1f).scaleY(1f).setDuration(UiKit.DUR_BASE)
                .setInterpolator(UiKit.SPRING).start();
    }

    /** A user tap: flip, animate, notify. Programmatic {@link #setChecked} stays silent. */
    @Override public boolean performClick() {
        super.performClick();
        if (!enabled) return true;
        boolean next = !checked;
        setChecked(next, true);
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        if (listener != null) listener.onCheckedChanged(this, next);
        return true;
    }

    private void refreshDescription() {
        setContentDescription((label == null ? "开关" : label) + (checked ? "，已开启" : "，已关闭"));
    }

    @Override protected void onDraw(Canvas canvas) {
        // Position uses a clamped value so the spring overshoot cannot fling the thumb
        // outside the capsule; the squash keeps the raw value for its momentum read.
        float p = Math.max(0f, Math.min(1f, progress));
        int track = UiKit.blend(trackOff, trackOn, p);
        int thumb = UiKit.blend(thumbOff, thumbOn, p);
        int dim = enabled ? 255 : 120;

        float left = (getWidth() - trackW) / 2f;
        float cy = getHeight() / 2f;
        float top = cy - trackH / 2f;

        paint.setStyle(Paint.Style.FILL);
        paint.setAlpha(dim);

        // Soft accent halo behind the capsule while it is being held.
        if (pressed) {
            paint.setColor((trackOn & 0x00FFFFFF) | 0x33000000);
            rect.set(left - pad * 2f, top - pad * 2f, left + trackW + pad * 2f, top + trackH + pad * 2f);
            canvas.drawRoundRect(rect, radius + pad * 2f, radius + pad * 2f, paint);
        }

        rect.set(left, top, left + trackW, top + trackH);
        paint.setColor(track);
        canvas.drawRoundRect(rect, radius, radius, paint);

        // Inner top rim: a hairline of light so the track reads as a glass channel.
        paint.setColor(0x1FFFFFFF);
        rect.set(left + 1f, top + 1f, left + trackW - 1f, top + trackH - 1f);
        canvas.drawRoundRect(rect, radius - 1f, radius - 1f, paint);

        // Thumb squashes horizontally mid-throw, which reads as momentum.
        float squash = 1f + 0.16f * (float) Math.sin(Math.PI * p);
        float tw = thumbSize * squash;
        float th = thumbSize / squash;
        float cx = left + pad + thumbSize * 0.5f + (trackW - pad * 2f - thumbSize) * p;

        paint.setColor(0x33000000);
        canvas.drawCircle(cx, cy + th * 0.06f, th / 2f, paint);
        paint.setColor(thumb);
        canvas.drawCircle(cx, cy, th / 2f, paint);
        // Highlight crescent on top of the thumb for a glassy read.
        paint.setColor(0x40FFFFFF);
        canvas.drawCircle(cx, cy - th * 0.18f, tw / 2f * 0.78f, paint);

        paint.setAlpha(255);
    }
}
