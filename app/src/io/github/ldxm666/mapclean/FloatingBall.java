package io.github.ldxm666.mapclean;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

/**
 * The always-present entry point: a draggable glass orb that snaps to the nearest screen
 * edge and auto-hides when the finger lifts.
 *
 * <h3>Interaction model</h3>
 * <ul>
 *   <li><b>Drag</b> -- follows the finger with a slight elastic lag, and the halo brightens
 *       with drag distance so the gesture feels connected.</li>
 *   <li><b>Release</b> -- springs to the nearest vertical edge (left or right), whichever the
 *       orb is closer to. It never rests mid-screen, so it cannot sit on top of the map's own
 *       controls for long.</li>
 *   <li><b>Auto-hide</b> -- once parked, the orb slides most of the way off-screen leaving a
 *       thin grab tab. Tapping or dragging the tab brings it back.</li>
 *   <li><b>Tap</b> -- opens the panel; only fires when the finger barely moved, so a drag can
 *       never be mistaken for a click.</li>
 * </ul>
 *
 * <h3>Why a custom View rather than a Button</h3>
 * The orb draws its own glass treatment (outer glow, rim, inner specular) and needs to report
 * a hit area larger than its visible circle. A stock view cannot do either, and the module
 * builds against {@code android.jar} only -- no AndroidX, no Material Components.
 */
public final class FloatingBall extends View {

    /** Notified when the user taps (not drags) the orb. */
    public interface Listener {
        void onTap();
        void onMoved(int left, int top);
    }

    /**
     * Turns the orb's centre into a position on screen.
     *
     * <p>Exists because the orb no longer sits in a full-screen container: {@code FloatingController}
     * hosts it in a FrameLayout that is only as large as the orb itself (a full-screen
     * transparent view above the map's SurfaceView measurably hurt frame pacing). In that
     * layout the <b>container</b> moves and the orb stays at its own origin; while the sheet is
     * open the container is grown to full size instead and the orb moves inside it. Both cases
     * are the host's business, not the orb's, so the orb just reports its centre.
     */
    public interface Frame {
        /** Position the host so the orb's drawn centre lands on (cx, cy). */
        void place(float cx, float cy);

        /** Width of the coordinate space the orb parks in (the Activity content root). */
        int spaceWidth();

        /** Height of that space. */
        int spaceHeight();
    }

    /** Hit area is wider than the visible orb so the tab is easy to grab while hidden. */
    private static final float TOUCH_PAD_DP = 8f;
    /** How far off-screen the orb parks, as a fraction of its own width. */
    private static final float HIDE_FRACTION = 0.62f;
    private static final float DRAG_SLOP_DP = 6f;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Path icon = new Path();

    /**
     * Diameter and radius are <b>not</b> final any more: the 外观 page resizes the orb live.
     * Only a discrete size change (a slider release, or a new attach) touches them, so the
     * layout pass this triggers can never run per frame.
     */
    private float size, radius;
    private final float pad;
    private final float slop;
    private int sizeDp;
    private int alphaPercent = FloatingConfig.BALL_ALPHA_DEFAULT;

    private Listener listener;
    /** Turns the orb's centre into a position; installed by {@code FloatingController.attach}. */
    private Frame frame;

    private float restX, restY;        // parked (visible) centre
    private float centerX, centerY;    // current animated centre
    private float downRawX, downRawY;  // finger position at ACTION_DOWN
    private float grabDX, grabDY;      // offset from centre to finger
    private boolean dragging;
    private boolean moved;
    private boolean hidden;
    private boolean sideLeft = false;  // parked against the left edge?
    private float dragGlow;            // 0..1, brightens while dragging

    private ValueAnimator settle;

    private int accent = 0xFF0A6CF5;
    private int body = 0xF2FFFFFF;
    private int glyph = 0xFF0A6CF5;

    public FloatingBall(Context c) {
        super(c);
        setClickable(true);
        setFocusable(true);
        pad = TOUCH_PAD_DP * c.getResources().getDisplayMetrics().density;
        slop = DRAG_SLOP_DP * c.getResources().getDisplayMetrics().density;
        // Read the persisted appearance here rather than at first draw: the controller builds
        // a fresh ball per Activity, so a rotation or a re-attach picks the new values up.
        setSizeDp(FloatingConfig.ballSizeDp());
        setAlphaPercent(FloatingConfig.ballAlphaPercent());
    }

    public void setListener(Listener l) { listener = l; }

    /** Install the positioner; see {@link Frame}. */
    public void setFrame(Frame f) { frame = f; }

    /** Current diameter in dp (the value the 外观 row shows). */
    public int getSizeDp() { return sizeDp; }

    /**
     * Resize the orb.
     *
     * <p>Requests a layout pass -- but only when the number actually changed, and only from a
     * discrete user action (slider release/preview, attach). The drag path never calls this,
     * so the "no requestLayout per frame" rule still holds for dragging the orb itself.
     *
     * <p>{@link #onSizeChanged} re-parks the orb afterwards: {@code settle()},
     * {@code hide()} and the drag clamp all read {@code radius}, so a resize without a
     * re-park would leave the orb resting half off the edge (or drifting out of bounds).
     */
    public void setSizeDp(int dp) {
        int clamped = Math.max(FloatingConfig.BALL_SIZE_MIN,
                Math.min(FloatingConfig.BALL_SIZE_MAX, dp));
        if (clamped == sizeDp && size > 0f) return;
        sizeDp = clamped;
        size = clamped * getResources().getDisplayMetrics().density;
        radius = size / 2f;
        requestLayout();
        invalidate();
    }

    /** Current opacity in percent. */
    public int getAlphaPercent() { return alphaPercent; }

    /**
     * Opacity of the whole orb, drawn glass included.
     *
     * <p>Applied with {@link View#setAlpha} rather than per-{@code paint.setAlpha} calls: the
     * orb paints four different layers (halo, plate, rims, glyph) plus the view-level press
     * scale animation, and a single view alpha keeps them all consistent instead of
     * re-deriving every literal in {@link #onDraw}. It also invalidates by itself, so there
     * is no extra redraw call here.
     */
    public void setAlphaPercent(int percent) {
        int clamped = Math.max(FloatingConfig.BALL_ALPHA_MIN,
                Math.min(FloatingConfig.BALL_ALPHA_MAX, percent));
        alphaPercent = clamped;
        setAlpha(clamped / 100f);
    }

    /** Dark-mode tinting, applied by the controller when the host theme changes. */
    public void setPalette(int bodyColor, int accentColor, int glyphColor) {
        body = bodyColor;
        accent = accentColor;
        glyph = glyphColor;
        invalidate();
    }

    @Override protected void onMeasure(int wSpec, int hSpec) {
        int w = (int) Math.ceil(size + pad * 2f);
        int h = (int) Math.ceil(size + pad * 2f);
        setMeasuredDimension(w, h);
    }

    /**
     * Re-park after the orb's own size changed.
     *
     * <p>{@code settle()}, {@code hide()} and the drag clamp are all expressed in terms of
     * {@code radius}, so a resize leaves the stored resting position pointing at where the
     * <i>old</i> orb used to be -- with a bigger orb that is off the screen edge, with a
     * smaller one it floats away from the edge. Recomputing here (and not from the caller)
     * means every path that changes the size is covered: slider preview, a fresh attach, and
     * a restored configuration.
     *
     * <p>{@code oldw == 0} is the first measure, where the controller parks the orb itself;
     * it is skipped so this cannot fight {@code attach()}.
     */
    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (oldw <= 0 || oldh <= 0 || !isParked()) return;
        int spaceW = spaceWidth(), spaceH = spaceHeight();
        if (spaceW <= 0 || spaceH <= 0) return;
        boolean wasHidden = hidden;
        hidden = false;                       // hide() early-returns while hidden
        settle(false, spaceW, spaceH);
        if (wasHidden) hide(spaceW);          // slide the tab back off the edge at the new size
    }

    /**
     * Width of the coordinate space the orb parks in.
     *
     * <p>Not {@code getParent().getWidth()}: the host wraps the orb, so its width is the orb's
     * own width. The real space is the Activity content root, which the frame reports.
     */
    private int spaceWidth() {
        if (frame != null) {
            int w = frame.spaceWidth();
            if (w > 0) return w;
        }
        return getParent() instanceof View ? ((View) getParent()).getWidth() : getWidth();
    }

    private int spaceHeight() {
        if (frame != null) {
            int h = frame.spaceHeight();
            if (h > 0) return h;
        }
        return getParent() instanceof View ? ((View) getParent()).getHeight() : getHeight();
    }

    /** Centre currently drawn, in parent coordinates. */
    float centerX() { return centerX; }
    float centerY() { return centerY; }

    boolean parkedLeft() { return sideLeft; }

    boolean isHidden() { return hidden; }

    /** True once the orb has a real size and position (i.e. it can be re-parked safely). */
    boolean isParked() { return getWidth() > 0 && getHeight() > 0 && centerY > 0f; }

    /**
     * Move the orb so its drawn centre lands on (cx, cy), keeping the internal state in sync.
     *
     * Callers must use this instead of {@link View#setX}/{@link View#setY}: the settle and
     * hide maths read {@code centerX}/{@code centerY}, so positioning the view directly left
     * those fields at 0 and the orb always snapped to the top-left corner.
     */
    void moveTo(float cx, float cy, int parentW) {
        centerX = cx;
        centerY = cy;
        applyTranslation(parentW);
    }

    /**
     * Park the orb against the nearer edge.
     *
     * @param animate slide rather than jump (used after a drag release)
     * @param parentW width of the container the orb lives in
     * @param parentH height of the container
     */
    void settle(boolean animate, int parentW, int parentH) {
        // The arguments are only a hint from the caller. The host wraps the orb now, so its
        // own width is the orb's width; the authoritative space is the content root the frame
        // reports. Re-reading it here means a stale call site can never park the orb inside a
        // 62dp box (which would look like the orb teleporting to the top-left corner).
        parentW = spaceWidth();
        parentH = spaceHeight();
        float minX = pad + radius;
        float maxX = parentW - pad - radius;
        if (maxX < minX) maxX = minX;
        sideLeft = centerX < (minX + maxX) / 2f;
        restX = sideLeft ? minX : maxX;
        // Keep the orb fully inside the vertical range, and never behind the status bar.
        float minY = pad + radius + UiKit.dp(getContext(), 28);
        float maxY = parentH - pad - radius - UiKit.dp(getContext(), 40);
        if (maxY < minY) maxY = minY;
        restY = Math.max(minY, Math.min(maxY, centerY));

        if (!animate) {
            centerX = restX;
            centerY = restY;
            applyTranslation(parentW);
            return;
        }
        animateTo(restX, restY, parentW, true);
    }

    /** Slide out of the way, leaving only a grab tab. */
    void hide(int parentW) {
        if (hidden) return;
        hidden = true;
        float offset = size * HIDE_FRACTION;
        float target = sideLeft ? restX - offset : restX + offset;
        animateTo(target, restY, parentW, false);
    }

    /** Bring the orb fully back into view. */
    void reveal(int parentW) {
        if (!hidden) return;
        hidden = false;
        animateTo(restX, restY, parentW, false);
    }

    private void animateTo(final float tx, final float ty, final int parentW, final boolean snap) {
        cancelSettle();
        settle = ValueAnimator.ofFloat(0f, 1f);
        settle.setDuration(snap ? 260L : 200L);
        settle.setInterpolator(snap ? UiKit.SPRING : UiKit.STANDARD);
        final float fx = centerX, fy = centerY;
        settle.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(ValueAnimator a) {
                float t = (Float) a.getAnimatedValue();
                centerX = fx + (tx - fx) * t;
                centerY = fy + (ty - fy) * t;
                applyTranslation(parentW);
            }
        });
        settle.start();
    }

    private void cancelSettle() {
        if (settle != null) {
            settle.cancel();
            settle = null;
        }
    }

    /**
     * Position the orb so that its drawn centre lands on (centerX, centerY).
     *
     * <p>Delegates to the {@link Frame}: with an orb-sized host the host moves and the orb
     * stays at its origin; while the sheet is open the host is full-screen and the orb moves
     * inside it. The fallback keeps the old full-screen-parent behaviour for any caller that
     * never installed a frame.
     */
    private void applyTranslation(int parentW) {
        if (frame != null) {
            frame.place(centerX, centerY);
            return;
        }
        setX(centerX - getWidth() / 2f);
        setY(centerY - getHeight() / 2f);
    }

    @Override protected void onDetachedFromWindow() {
        cancelSettle();
        super.onDetachedFromWindow();
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        int parentW = spaceWidth();
        int parentH = spaceHeight();

        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                cancelSettle();
                downRawX = e.getRawX();
                downRawY = e.getRawY();
                grabDX = centerX - downRawX;
                grabDY = centerY - downRawY;
                dragging = true;
                moved = false;
                animate().cancel();
                animate().scaleX(1.08f).scaleY(1.08f).setDuration(90L).start();
                return true;

            case MotionEvent.ACTION_MOVE: {
                float rawX = e.getRawX(), rawY = e.getRawY();
                float dx = rawX - downRawX, dy = rawY - downRawY;
                if (!moved && Math.hypot(dx, dy) > slop) {
                    moved = true;
                    // A drag from a hidden tab must first bring the orb back.
                    if (hidden) {
                        hidden = false;
                        grabDX = -radius;   // keep the orb under the finger, not the tab
                        grabDY = 0f;
                        centerX = rawX + grabDX;
                        centerY = rawY + grabDY;
                    }
                }
                if (moved) {
                    centerX = rawX + grabDX;
                    centerY = rawY + grabDY;
                    float minX = pad + radius;
                    float maxX = parentW - pad - radius;
                    if (maxX < minX) maxX = minX;
                    float minY = pad + radius + UiKit.dp(getContext(), 24);
                    float maxY = parentH - pad - radius - UiKit.dp(getContext(), 32);
                    if (maxY < minY) maxY = minY;
                    centerX = Math.max(minX, Math.min(maxX, centerX));
                    centerY = Math.max(minY, Math.min(maxY, centerY));
                    applyTranslation(parentW);
                    dragGlow = Math.min(1f, dragGlow + 0.08f);
                    invalidate();
                }
                return true;
            }

            case MotionEvent.ACTION_UP:
                release(parentW, parentH, true);
                return true;

            case MotionEvent.ACTION_CANCEL:
                release(parentW, parentH, false);
                return true;

            default:
                return super.onTouchEvent(e);
        }
    }

    private void release(int parentW, int parentH, boolean allowTap) {
        if (!dragging) return;
        dragging = false;
        animate().cancel();
        animate().scaleX(1f).scaleY(1f).setDuration(UiKit.DUR_BASE)
                .setInterpolator(UiKit.SPRING).start();
        fadeGlow();

        if (!moved && allowTap) {
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            performClick();
            return;
        }
        if (moved) {
            settle(true, parentW, parentH);
            if (listener != null) {
                listener.onMoved(Math.round(centerX), Math.round(centerY));
            }
        }
    }

    private void fadeGlow() {
        if (dragGlow == 0f) return;
        UiKit.animateFloat(dragGlow, 0f, UiKit.STANDARD, UiKit.DUR_SLOW,
                new ValueAnimator.AnimatorUpdateListener() {
                    @Override public void onAnimationUpdate(ValueAnimator a) {
                        dragGlow = (Float) a.getAnimatedValue();
                        invalidate();
                    }
                });
    }

    @Override public boolean performClick() {
        super.performClick();
        if (listener != null) listener.onTap();
        return true;
    }

    @Override protected void onDraw(Canvas canvas) {
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        float held = dragging ? 1f : 0f;

        // Outer halo: soft, and brighter while dragging or held.
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(accent);
        paint.setAlpha((int) (46 + 52 * Math.max(dragGlow, held)));
        canvas.drawCircle(cx, cy, radius + pad * 0.5f, paint);

        // Body plate.
        rect.set(cx - radius, cy - radius, cx + radius, cy + radius);
        paint.setAlpha(255);
        paint.setColor(body);
        canvas.drawRoundRect(rect, radius, radius, paint);

        // Specular top rim: a hairline of light so the orb reads as glass, not a flat dot.
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(UiKit.dpf(getContext(), 1.2f));
        paint.setColor(0x59FFFFFF);
        rect.set(cx - radius + 1f, cy - radius + 1f, cx + radius - 1f, cy + radius - 1f);
        canvas.drawRoundRect(rect, radius - 1f, radius - 1f, paint);

        // Accent ring.
        paint.setStrokeWidth(UiKit.dpf(getContext(), 1.6f));
        paint.setColor(accent);
        paint.setAlpha(190);
        canvas.drawRoundRect(rect, radius - 1f, radius - 1f, paint);

        // Glyph: a three-slider "tune" mark -- reads as settings at a glance and is
        // distinct from the map's own icons.
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(UiKit.dpf(getContext(), 2f));
        paint.setColor(glyph);
        paint.setAlpha(255);
        float w = radius * 0.52f;
        float[] ys = {-radius * 0.34f, 0f, radius * 0.34f};
        for (int i = 0; i < ys.length; i++) {
            float y = cy + ys[i];
            canvas.drawLine(cx - w, y, cx + w, y, paint);
        }
        float[] kx = {cx - w * 0.42f, cx + w * 0.46f, cx - w * 0.08f};
        for (int i = 0; i < kx.length; i++) {
            canvas.drawCircle(kx[i], cy + ys[i], UiKit.dpf(getContext(), 2.1f), paint);
        }
        paint.setAlpha(255);
    }
}
