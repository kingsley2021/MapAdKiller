package io.github.ldxm666.mapclean;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

/**
 * The module's slider: the same glass language as {@link GlassSwitch}, drawn by hand.
 *
 * <h3>Why not a stock SeekBar</h3>
 * A framework {@code SeekBar} brings its own thumb drawable, its own track drawable and its
 * own padding rules, none of which can be made to match the sheet's glass plates without
 * shipping {@code res/} assets. This module builds against {@code android.jar} only and
 * generates every drawable in code, so the slider is drawn here with the same primitives the
 * switch uses: a translucent channel, a specular top rim, and a white thumb with an accent
 * ring.
 *
 * <h3>Positions, not values</h3>
 * The view works in <b>discrete positions</b> {@code 0..stops-1}; the caller owns the mapping
 * from a position to a stored value (see {@code FloatingPanel.Metric}). That is what lets the
 * panel-width row carry a "自动" stop in front of its numeric range without special-casing
 * arithmetic in the widget, and it makes every drag snap to a legal stored value.
 *
 * <h3>Contract</h3>
 * <ul>
 *   <li>{@link #setPosition(int)} is the programmatic path and never notifies, so hydrating
 *       from storage cannot write back to storage.</li>
 *   <li>{@link OnPositionChange} fires with {@code settled=false} while the finger moves
 *       (live preview) and {@code settled=true} once on lift -- the moment to persist.</li>
 *   <li>No animation and no repeating invalidate: the view redraws only when the position,
 *       the pressed state or the size actually changes.</li>
 * </ul>
 */
final class GlassSlider extends View {

    /** Notified on user interaction only; {@code settled} marks the end of the gesture. */
    interface OnPositionChange {
        void onPositionChanged(GlassSlider view, int position, boolean settled);
    }

    private static final float TRACK_H_DP = 6f;
    private static final float THUMB_DP = 20f;
    /** Finger-sized hit box, same height as {@link GlassSwitch}'s, keeps the row even. */
    private static final float TOUCH_H_DP = 48f;
    /** How far the channel pokes out past the thumb's resting centre at either end. */
    private static final float OVERHANG_DP = 5f;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final float trackH, thumbR, viewH, overhang, radius;

    private int stops = 2;
    private int position;
    private boolean pressed;
    private boolean tracking;

    private int trackOff = 0x2E8A94A4;
    private int trackOn = 0xFF0A6CF5;
    private int trackOnTop = 0xFF3D82FF;
    private Shader fill;

    private OnPositionChange listener;

    /** Height of the interactive band measured from the view's bottom edge; 0 = centred. */
    private float trackBand;

    GlassSlider(Context c) {
        super(c);
        float d = c.getResources().getDisplayMetrics().density;
        trackH = TRACK_H_DP * d;
        thumbR = THUMB_DP * d / 2f;
        viewH = TOUCH_H_DP * d;
        overhang = OVERHANG_DP * d;
        radius = trackH / 2f;
        setClickable(true);
        setFocusable(true);
    }

    /**
     * Anchor the drawn channel to the bottom {@code dp}-tall band instead of the middle.
     *
     * <p>This is what lets one view own a whole row -- label and value on top, track below --
     * so there is no "wrong half" of the row to press. The touch surface grows to the whole
     * row; the picture stays exactly where it was.
     */
    void setTrackBandDp(float dp) {
        trackBand = dp * getResources().getDisplayMetrics().density;
        rebuildFill();
        invalidate();
    }

    /** Vertical centre of the channel: the middle of the bottom band, or of the view. */
    private float channelCenterY() {
        float h = getHeight();
        if (trackBand <= 0f || trackBand >= h) return h / 2f;
        return h - trackBand / 2f;
    }

    /** Number of discrete stops; the caller supplies the value behind each one. */
    void setStops(int count) {
        int next = Math.max(2, count);
        if (next == stops) return;
        stops = next;
        if (position >= stops) position = stops - 1;
        invalidate();
    }

    /** Current stop. */
    int getPosition() { return position; }

    /** Programmatic set: clamps, redraws, never notifies. */
    void setPosition(int value) {
        int next = Math.max(0, Math.min(stops - 1, value));
        if (next == position) return;
        position = next;
        invalidate();
    }

    void setTrackColors(int off, int on, int onTop) {
        trackOff = off;
        trackOn = on;
        trackOnTop = onTop;
        rebuildFill();
        invalidate();
    }

    void setOnPositionChange(OnPositionChange l) { listener = l; }

    @Override protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec);
        if (MeasureSpec.getMode(wSpec) == MeasureSpec.UNSPECIFIED) w = (int) Math.ceil(thumbR * 8f);
        // Honour an explicit height: the metric row gives this view a fixed, taller-than-48dp
        // box so the label band above the track is part of the same touch surface.
        int h = MeasureSpec.getMode(hSpec) == MeasureSpec.UNSPECIFIED
                ? (int) Math.ceil(viewH) : MeasureSpec.getSize(hSpec);
        if (h < (int) Math.ceil(viewH)) h = (int) Math.ceil(viewH);   // never below the 48dp hit box
        setMeasuredDimension(Math.max(w, (int) Math.ceil(thumbR * 2f)), h);
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        rebuildFill();
    }

    /** The accent fill is a vertical gradient, so it has to be rebuilt when geometry changes. */
    private void rebuildFill() {
        float cy = channelCenterY();
        fill = new LinearGradient(0f, cy - trackH / 2f, 0f, cy + trackH / 2f,
                trackOnTop, trackOn, Shader.TileMode.CLAMP);
    }

    private float channelLeft() { return overhang; }

    private float channelRight() { return Math.max(channelLeft() + 1f, getWidth() - overhang); }

    /** Thumb centres stay a full radius inside the view, so the disc is never clipped. */
    private float travelLeft() { return thumbR; }

    private float travelRight() { return Math.max(travelLeft(), getWidth() - thumbR); }

    private float thumbCenterX() {
        float l = travelLeft(), r = travelRight();
        if (r <= l) return getWidth() / 2f;
        float t = stops <= 1 ? 0f : position / (float) (stops - 1);
        return l + (r - l) * t;
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                tracking = true;
                pressed = true;
                // Claim the gesture from *every* ancestor, not just the direct parent: the
                // sheet's horizontal category swipe is several ViewGroups up, and it used to
                // win the gesture -- dragging the slider flipped the page to another category
                // and the value never changed (device report r6).
                claimGesture(true);
                setPositionFromX(e.getX(), true);
                invalidate();
                return true;

            case MotionEvent.ACTION_MOVE:
                if (!tracking) return false;
                setPositionFromX(e.getX(), true);
                return true;

            case MotionEvent.ACTION_UP:
                if (!tracking) {
                    releaseGesture();      // never leave an ancestor disallowed
                    return false;
                }
                tracking = false;
                pressed = false;
                setPositionFromX(e.getX(), true);
                releaseGesture();
                invalidate();
                performClick();      // emits the settled callback exactly once
                return true;

            case MotionEvent.ACTION_CANCEL:
                if (!tracking) {
                    releaseGesture();
                    return false;
                }
                tracking = false;
                pressed = false;
                releaseGesture();
                invalidate();
                if (listener != null) listener.onPositionChanged(this, position, true);
                return true;

            default:
                return super.onTouchEvent(e);
        }
    }

    /**
     * Ask the whole ancestor chain to stop intercepting.
     *
     * <p>Walked by hand up to the root: {@code requestDisallowInterceptTouchEvent} is
     * implemented per {@code ViewGroup} and does not propagate upwards by itself, so a single
     * call on the immediate parent left the sheet's swipe handler free to steal the drag.
     * Bounded so a pathological hierarchy cannot spin.
     */
    private void claimGesture(boolean claim) {
        android.view.ViewParent parent = getParent();
        for (int guard = 0; parent != null && guard < 32; guard++) {
            parent.requestDisallowInterceptTouchEvent(claim);
            parent = (parent instanceof View) ? ((View) parent).getParent() : null;
        }
    }

    private void releaseGesture() {
        claimGesture(false);
    }

    /**
     * Release the ancestor claim when this view leaves the hierarchy mid-gesture (a page
     * rebuild while the finger is down). Without it a half-finished drag could leave the
     * sheet's swipe handler disabled for the rest of the gesture.
     */
    @Override protected void onDetachedFromWindow() {
        releaseGesture();
        tracking = false;
        pressed = false;
        super.onDetachedFromWindow();
    }

    private void setPositionFromX(float x, boolean fromUser) {
        float l = travelLeft(), r = travelRight();
        float t = r <= l ? 0f : (x - l) / (r - l);
        int next = Math.round(t * (stops - 1));
        if (next < 0) next = 0;
        if (next > stops - 1) next = stops - 1;
        if (next == position) return;
        position = next;
        invalidate();
        // A tick per stop is what makes a discrete slider feel detented instead of mushy.
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        if (fromUser && listener != null) listener.onPositionChanged(this, position, false);
    }

    /** Activation is not a value change; only the settled notification is emitted here. */
    @Override public boolean performClick() {
        super.performClick();
        if (listener != null) listener.onPositionChanged(this, position, true);
        return true;
    }

    @Override protected void onDraw(Canvas canvas) {
        float cy = channelCenterY();
        float l = channelLeft(), r = channelRight();
        float top = cy - trackH / 2f, bottom = cy + trackH / 2f;
        float cx = thumbCenterX();

        paint.setStyle(Paint.Style.FILL);
        paint.setShader(null);

        // Channel: same translucent tone the switch uses for its OFF track.
        paint.setColor(trackOff);
        rect.set(l, top, r, bottom);
        canvas.drawRoundRect(rect, radius, radius, paint);

        // Filled part up to the thumb, in the accent gradient the selected chip uses.
        if (cx > l && fill != null) {
            paint.setShader(fill);
            rect.set(l, top, Math.min(cx, r), bottom);
            canvas.drawRoundRect(rect, radius, radius, paint);
            paint.setShader(null);
        }

        // Thumb: soft contact shadow, white disc, accent ring. Same recipe as GlassSwitch.
        paint.setColor(pressed ? 0x40000000 : 0x2E000000);
        canvas.drawCircle(cx, cy + thumbR * 0.10f, thumbR, paint);
        paint.setColor(0xFFFFFFFF);
        canvas.drawCircle(cx, cy, thumbR, paint);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(UiKit.dpf(getContext(), 1.4f));
        paint.setColor(trackOn);
        paint.setAlpha(pressed ? 255 : 170);
        canvas.drawCircle(cx, cy, thumbR - paint.getStrokeWidth() / 2f, paint);

        paint.setStyle(Paint.Style.FILL);
        paint.setAlpha(255);
    }
}
