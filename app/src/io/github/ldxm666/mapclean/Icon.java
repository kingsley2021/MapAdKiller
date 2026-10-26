package io.github.ldxm666.mapclean;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/**
 * Tiny line-icon set drawn with {@link Canvas} — no vector assets, no dependency.
 *
 * <p>The module ships without XML resources for its settings UI (the build is plain
 * {@code javac} + {@code android.jar}), and text glyphs look cheap at 11 sp. Every icon here
 * lives on the same 24 × 24 grid, 1.9 dp stroke, round caps, so the bottom bar and rows read
 * as one family.
 */
final class Icon extends View {

    static final int HOME = 0;
    static final int TUNE = 1;
    static final int ARCHIVE = 2;
    static final int INFO = 3;
    static final int CHEVRON = 4;
    static final int DOWNLOAD = 5;
    static final int UPLOAD = 6;
    static final int REFRESH = 7;
    static final int TRASH = 8;
    static final int COPY = 9;
    static final int PASTE = 10;
    static final int SHIELD = 11;
    static final int SEND = 12;
    static final int SEARCH = 13;
    static final int CHECK = 14;
    static final int EXTERNAL = 15;
    static final int SAVE = 16;
    static final int BOLT = 17;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF oval = new RectF();
    private final int kind;
    private int color;
    private float strokeDp = 1.9f;

    Icon(Context context, int kind, int color) {
        super(context);
        this.kind = kind;
        this.color = color;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    Icon stroke(float dp) {
        strokeDp = dp;
        invalidate();
        return this;
    }

    void setColor(int value) {
        if (color == value) return;
        color = value;
        invalidate();
    }

    int color() {
        return color;
    }

    @Override protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        int size = Math.min(w, h);
        if (size <= 0) return;
        float scale = size / 24f;
        canvas.save();
        canvas.translate((w - size) / 2f, (h - size) / 2f);
        canvas.scale(scale, scale);
        paint.setColor(color);
        // Canvas is scaled by `scale`, so pre-divide to land on strokeDp real dp after it.
        paint.setStrokeWidth(strokeDp * density() / scale);
        path.reset();

        switch (kind) {
            case HOME:
                path.moveTo(4f, 10.6f);
                path.lineTo(12f, 3.9f);
                path.lineTo(20f, 10.6f);
                path.moveTo(6.2f, 9.4f);
                path.lineTo(6.2f, 19.6f);
                path.lineTo(17.8f, 19.6f);
                path.lineTo(17.8f, 9.4f);
                path.moveTo(10f, 19.6f);
                path.lineTo(10f, 14.2f);
                path.lineTo(14f, 14.2f);
                path.lineTo(14f, 19.6f);
                canvas.drawPath(path, paint);
                break;
            case TUNE:
                path.moveTo(4f, 7f); path.lineTo(7.1f, 7f);
                path.moveTo(11.2f, 7f); path.lineTo(20f, 7f);
                path.moveTo(4f, 12f); path.lineTo(13.1f, 12f);
                path.moveTo(17.2f, 12f); path.lineTo(20f, 12f);
                path.moveTo(4f, 17f); path.lineTo(5.1f, 17f);
                path.moveTo(9.2f, 17f); path.lineTo(20f, 17f);
                canvas.drawPath(path, paint);
                paint.setStyle(Paint.Style.FILL);
                canvas.drawCircle(9.1f, 7f, 2.1f, paint);
                canvas.drawCircle(15.1f, 12f, 2.1f, paint);
                canvas.drawCircle(7.1f, 17f, 2.1f, paint);
                paint.setStyle(Paint.Style.STROKE);
                break;
            case ARCHIVE:
                path.moveTo(3.6f, 4.6f);
                path.lineTo(20.4f, 4.6f);
                path.lineTo(20.4f, 8.2f);
                path.lineTo(3.6f, 8.2f);
                path.close();
                path.moveTo(5.2f, 8.2f);
                path.lineTo(5.2f, 19.4f);
                path.lineTo(18.8f, 19.4f);
                path.lineTo(18.8f, 8.2f);
                path.moveTo(9.8f, 12.2f);
                path.lineTo(14.2f, 12.2f);
                canvas.drawPath(path, paint);
                break;
            case INFO:
                oval.set(3.6f, 3.6f, 20.4f, 20.4f);
                canvas.drawOval(oval, paint);
                path.moveTo(12f, 10.8f);
                path.lineTo(12f, 16.6f);
                canvas.drawPath(path, paint);
                paint.setStyle(Paint.Style.FILL);
                canvas.drawCircle(12f, 7.6f, 1.1f, paint);
                paint.setStyle(Paint.Style.STROKE);
                break;
            case CHEVRON:
                path.moveTo(9.6f, 5.4f);
                path.lineTo(16.1f, 12f);
                path.lineTo(9.6f, 18.6f);
                canvas.drawPath(path, paint);
                break;
            case DOWNLOAD:
                path.moveTo(12f, 4f);
                path.lineTo(12f, 14.6f);
                path.moveTo(7.8f, 10.4f);
                path.lineTo(12f, 14.6f);
                path.lineTo(16.2f, 10.4f);
                path.moveTo(4.5f, 19.6f);
                path.lineTo(19.5f, 19.6f);
                canvas.drawPath(path, paint);
                break;
            case UPLOAD:
                path.moveTo(12f, 19.6f);
                path.lineTo(12f, 9f);
                path.moveTo(7.8f, 13.2f);
                path.lineTo(12f, 9f);
                path.lineTo(16.2f, 13.2f);
                path.moveTo(4.5f, 4.4f);
                path.lineTo(19.5f, 4.4f);
                canvas.drawPath(path, paint);
                break;
            case REFRESH:
                path.moveTo(19.4f, 8.2f);
                path.cubicTo(18.1f, 5.0f, 15.3f, 3.2f, 12f, 3.2f);
                path.cubicTo(7.1f, 3.2f, 3.2f, 7.1f, 3.2f, 12f);
                path.cubicTo(3.2f, 16.9f, 7.1f, 20.8f, 12f, 20.8f);
                path.cubicTo(16.1f, 20.8f, 19.4f, 18.2f, 20.6f, 14.7f);
                path.moveTo(19.4f, 3.1f);
                path.lineTo(19.4f, 8.2f);
                path.lineTo(14.3f, 8.2f);
                canvas.drawPath(path, paint);
                break;
            case TRASH:
                path.moveTo(4f, 7f);
                path.lineTo(20f, 7f);
                path.moveTo(9.4f, 7f);
                path.lineTo(9.4f, 4.4f);
                path.lineTo(14.6f, 4.4f);
                path.lineTo(14.6f, 7f);
                path.moveTo(6.4f, 7f);
                path.lineTo(7.4f, 19.6f);
                path.lineTo(16.6f, 19.6f);
                path.lineTo(17.6f, 7f);
                path.moveTo(10.4f, 10.8f);
                path.lineTo(10.4f, 16.2f);
                path.moveTo(13.6f, 10.8f);
                path.lineTo(13.6f, 16.2f);
                canvas.drawPath(path, paint);
                break;
            case COPY:
                path.moveTo(9f, 9f);
                path.lineTo(19.6f, 9f);
                path.lineTo(19.6f, 19.6f);
                path.lineTo(9f, 19.6f);
                path.close();
                path.moveTo(15f, 9f);
                path.lineTo(15f, 5.4f);
                path.lineTo(4.4f, 5.4f);
                path.lineTo(4.4f, 15f);
                path.lineTo(9f, 15f);
                canvas.drawPath(path, paint);
                break;
            case PASTE:
                path.moveTo(6f, 5.4f);
                path.lineTo(4.6f, 5.4f);
                path.lineTo(4.6f, 20.2f);
                path.lineTo(19.4f, 20.2f);
                path.lineTo(19.4f, 5.4f);
                path.lineTo(18f, 5.4f);
                path.moveTo(9f, 3.4f);
                path.lineTo(9f, 6.2f);
                path.lineTo(15f, 6.2f);
                path.lineTo(15f, 3.4f);
                path.close();
                path.moveTo(8.6f, 10.4f);
                path.lineTo(15.4f, 10.4f);
                path.moveTo(8.6f, 14.4f);
                path.lineTo(15.4f, 14.4f);
                path.moveTo(8.6f, 18.2f);
                path.lineTo(13f, 18.2f);
                canvas.drawPath(path, paint);
                break;
            case SHIELD:
                path.moveTo(12f, 3.2f);
                path.lineTo(19f, 6f);
                path.lineTo(19f, 11.2f);
                path.cubicTo(19f, 15.9f, 16.2f, 18.9f, 12f, 20.8f);
                path.cubicTo(7.8f, 18.9f, 5f, 15.9f, 5f, 11.2f);
                path.lineTo(5f, 6f);
                path.close();
                canvas.drawPath(path, paint);
                break;
            case SEND:
                path.moveTo(21f, 4.2f);
                path.lineTo(2.9f, 11.2f);
                path.lineTo(8.6f, 13.2f);
                path.lineTo(10.8f, 19.4f);
                path.lineTo(13.8f, 15.2f);
                path.lineTo(18.6f, 18.6f);
                path.close();
                path.moveTo(8.6f, 13.2f);
                path.lineTo(21f, 4.2f);
                canvas.drawPath(path, paint);
                break;
            case SEARCH:
                oval.set(4f, 4f, 17.2f, 17.2f);
                canvas.drawOval(oval, paint);
                path.moveTo(15.8f, 15.8f);
                path.lineTo(20.2f, 20.2f);
                canvas.drawPath(path, paint);
                break;
            case CHECK:
                path.moveTo(5f, 12.6f);
                path.lineTo(9.7f, 17.3f);
                path.lineTo(19.2f, 7.2f);
                canvas.drawPath(path, paint);
                break;
            case EXTERNAL:
                path.moveTo(13.8f, 4.2f);
                path.lineTo(19.8f, 4.2f);
                path.lineTo(19.8f, 10.2f);
                path.moveTo(19.8f, 4.2f);
                path.lineTo(10.8f, 13.2f);
                path.moveTo(17.4f, 14.4f);
                path.lineTo(17.4f, 19.2f);
                path.lineTo(4.8f, 19.2f);
                path.lineTo(4.8f, 6.6f);
                path.lineTo(9.6f, 6.6f);
                canvas.drawPath(path, paint);
                break;
            case SAVE:
                path.moveTo(4.6f, 5.4f);
                path.lineTo(15.4f, 5.4f);
                path.lineTo(19.4f, 9.4f);
                path.lineTo(19.4f, 19.4f);
                path.lineTo(4.6f, 19.4f);
                path.close();
                path.moveTo(8f, 5.4f);
                path.lineTo(8f, 10.2f);
                path.lineTo(15.2f, 10.2f);
                path.lineTo(15.2f, 5.4f);
                path.moveTo(8f, 19.4f);
                path.lineTo(8f, 14f);
                path.lineTo(16f, 14f);
                path.lineTo(16f, 19.4f);
                canvas.drawPath(path, paint);
                break;
            case BOLT:
                path.moveTo(13.2f, 2.6f);
                path.lineTo(5.4f, 13.2f);
                path.lineTo(11.2f, 13.2f);
                path.lineTo(10.6f, 21.4f);
                path.lineTo(18.6f, 10.4f);
                path.lineTo(12.8f, 10.4f);
                path.close();
                canvas.drawPath(path, paint);
                break;
            default:
                break;
        }
        canvas.restore();
    }

    private float density() {
        return getResources().getDisplayMetrics().density;
    }
}
