package io.github.ldxm666.mapclean;

import android.content.Context;
import android.graphics.Canvas;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.Scroller;

/**
 * Horizontal paging container -- **no AndroidX**.
 *
 * This project builds with {@code javac + android.jar} (no Gradle, no dependency
 * repository), so ViewPager / ViewPager2 are unavailable. Children sit side by side at
 * {@code x = i * width} and paging is driven by the parent's {@code scrollX}:
 *
 * <ul>
 *   <li>drag tracks the finger exactly, with rubber-band resistance past the first and
 *       last page so the edge reads as elastic rather than dead;</li>
 *   <li>release settles to a whole page by a blend of throw velocity and distance, with a
 *       spring interpolator so it lands with a touch of overshoot;</li>
 *   <li>neighbouring pages fade and scale slightly by distance, giving the switch a
 *       sense of depth instead of a flat slide;</li>
 *   <li>horizontal intent is claimed only when {@code |dx| > slop && |dx| > |dy| * 1.3},
 *       so vertical scrolling inside a page is never stolen.</li>
 * </ul>
 */
public final class Pager extends ViewGroup {

    public interface OnPageChanged {
        void onPageChanged(int index);
    }

    /** How far past an edge the content may travel, as a fraction of the page width. */
    private static final float EDGE_RESISTANCE = 0.32f;
    /** Fraction of the page a drag must cover to commit without a fling (18 %). */
    private static final float SWIPE_COMMIT = 0.12f;
    /** Settle duration base; longer throws travel for longer. */
    private static final long SETTLE_BASE_MS = 240L;

    private final Scroller scroller;
    private final int touchSlop;
    private final int minFling;
    private final int maxFling;

    private OnPageChanged listener;
    private int page = 0;

    private float downX, downY, lastX;
    private boolean dragging;
    private boolean settled = true;
    private VelocityTracker vt;

    public Pager(Context c) {
        super(c);
        scroller = new Scroller(c);
        ViewConfiguration vc = ViewConfiguration.get(c);
        touchSlop = vc.getScaledTouchSlop();
        minFling = vc.getScaledMinimumFlingVelocity();
        maxFling = vc.getScaledMaximumFlingVelocity();
        setClipToPadding(false);
        setChildrenDrawingOrderEnabled(true);
    }

    public void setOnPageChanged(OnPageChanged l) { listener = l; }

    public int page() { return page; }

    public int pageCount() { return getChildCount(); }

    /** Switch to page {@code p}; {@code animate=false} snaps (init / relayout). */
    public void setPage(int p, boolean animate) {
        int n = getChildCount();
        if (n == 0) return;
        if (p < 0) p = 0;
        if (p > n - 1) p = n - 1;
        int w = getWidth();
        int target = p * w;
        boolean changed = page != p;
        page = p;
        if (animate && w > 0) {
            int distance = Math.abs(target - getScrollX());
            long duration = Math.min(520L, SETTLE_BASE_MS + distance / 3L);
            settled = false;
            scroller.startScroll(getScrollX(), 0, target - getScrollX(), 0, (int) duration);
            invalidate();
        } else {
            scroller.abortAnimation();
            settled = true;
            scrollTo(target, 0);
            applyTransforms();
        }
        if (changed && listener != null) listener.onPageChanged(p);
    }

    /** Re-apply the page transform; call after layout changes. */
    public void applyTransforms() {
        int w = getWidth();
        if (w <= 0) return;
        float scroll = getScrollX();
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            float offset = (i * w - scroll) / (float) w;   // 0 = centred, +-1 = one page away
            float dist = Math.min(1f, Math.abs(offset));
            // Only the current page and the one being dragged in are worth drawing. Marking
            // the rest INVISIBLE keeps the neighbours from being re-rasterised on every
            // frame of a swipe (elevation + clipToOutline makes that expensive).
            int want = dist < 1.01f ? View.VISIBLE : View.INVISIBLE;
            if (child.getVisibility() != want) child.setVisibility(want);
            // Pages slide 1:1 with the finger. Earlier builds also scaled and faded the
            // neighbour every frame, which forced the (elevated, clipped) cards to be
            // re-rasterised mid-drag and made the swipe feel heavy and laggy.
            if (child.getAlpha() != 1f) child.setAlpha(1f);
            if (child.getScaleX() != 1f) {
                child.setScaleX(1f);
                child.setScaleY(1f);
            }
            child.setTranslationX(0f);
            child.setRotationY(0f);
        }
    }

    /**
     * Measure every page at the full container size.
     *
     * A ViewGroup that forgets onMeasure leaves its children unmeasured, so they get a
     * zero-size layout and draw nothing -- the pager then looks empty even though the
     * pages were added. Each page is forced to EXACTLY the container width/height so the
     * inner ScrollView can fill it.
     */
    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        int height = MeasureSpec.getSize(heightSpec);
        int childWidth = MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY);
        int childHeight = MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY);
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            child.measure(childWidth, childHeight);
        }
        setMeasuredDimension(width, height);
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int w = r - l;
        int h = b - t;
        for (int i = 0; i < getChildCount(); i++) {
            getChildAt(i).layout(i * w, 0, (i + 1) * w, h);
        }
        if (!settled) return;                  // do not fight an in-flight settle
        scrollTo(page * w, 0);
        applyTransforms();
    }

    @Override public void computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollTo(scroller.getCurrX(), 0);
            applyTransforms();
            postInvalidateOnAnimation();
        } else if (!settled) {
            settled = true;
            applyTransforms();
        }
    }

    @Override public boolean onInterceptTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX();
                downY = e.getY();
                lastX = downX;
                dragging = false;
                scroller.abortAnimation();
                settled = true;
                return false;
            case MotionEvent.ACTION_MOVE: {
                if (dragging) return true;
                float dx = Math.abs(e.getX() - downX);
                float dy = Math.abs(e.getY() - downY);
                // Require a clear horizontal intent before stealing from a vertical scroller.
                if (dx > touchSlop && dx > dy * 1.3f) {
                    dragging = true;
                    lastX = e.getX();
                    return true;
                }
                return false;
            }
            default:
                return false;
        }
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        int w = getWidth();
        int n = getChildCount();
        if (w <= 0 || n == 0) return false;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX();
                downY = e.getY();
                lastX = downX;
                dragging = false;
                scroller.abortAnimation();
                settled = true;
                if (vt == null) vt = VelocityTracker.obtain();
                vt.clear();
                vt.addMovement(e);
                return true;

            case MotionEvent.ACTION_MOVE: {
                if (vt != null) vt.addMovement(e);
                float x = e.getX();
                if (!dragging) {
                    float dx = Math.abs(x - downX);
                    float dy = Math.abs(e.getY() - downY);
                    if (dx > touchSlop && dx > dy * 1.3f) {
                        dragging = true;
                        lastX = x;
                    } else {
                        return true;
                    }
                }
                int delta = (int) (lastX - x);
                lastX = x;
                int max = (n - 1) * w;
                int want = getScrollX() + delta;
                if (want < 0 || want > max) {
                    // Rubber-band: move only a fraction of the overscroll.
                    int over = want < 0 ? want : want - max;
                    int base = want < 0 ? 0 : max;
                    want = base + (int) (over * EDGE_RESISTANCE);
                }
                scrollTo(want, 0);
                applyTransforms();
                return true;
            }

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                if (vt != null) vt.addMovement(e);
                if (!dragging) {
                    recycleVt();
                    return true;    // a plain tap belongs to the child
                }
                float vx = 0f;
                if (vt != null) {
                    vt.computeCurrentVelocity(1000, maxFling);
                    vx = vt.getXVelocity();
                }
                recycleVt();
                int cur = getScrollX();
                int target;
                float travelled = (cur - (float) page * w) / (float) w;   // signed, 0..1
                if (Math.abs(vx) > minFling) {
                    // Baseline must be the page the drag *started* on (field {@code page},
                    // not updated mid-drag). Rounding the release offset and then adding
                    // +-1 double-counts and skips two pages on a fast half-page flick.
                    target = page + (vx < 0 ? 1 : -1);
                } else if (travelled > SWIPE_COMMIT) {
                    target = page + 1;
                } else if (travelled < -SWIPE_COMMIT) {
                    target = page - 1;
                } else {
                    target = page;
                }
                if (target < 0) target = 0;
                if (target > n - 1) target = n - 1;
                setPage(target, true);
                dragging = false;
                return true;
            }
            default:
                return true;
        }
    }

    @Override protected int getChildDrawingOrder(int count, int i) {
        // Draw the nearest page last so its shadow/scale sits above the neighbours.
        int w = getWidth();
        if (w <= 0) return i;
        int centre = Math.round((float) getScrollX() / (float) w);
        int order = i + centre - count / 2;
        if (order < 0) order += count;
        if (order >= count) order -= count;
        return order;
    }

    private void recycleVt() {
        if (vt != null) {
            vt.recycle();
            vt = null;
        }
    }
}
