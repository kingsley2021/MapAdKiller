package io.github.ldxm666.mapclean;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;

/**
 * The photographic backdrop used by the liquid-glass surfaces.
 *
 * <p>A frosted pane only reads as glass when there is something behind it to blur. This
 * class loads the bundled wallpaper once, centre-crops it to the screen, and keeps a small,
 * heavily blurred copy. Glass panes sample that blurred copy at their own screen position,
 * so every card shows the real colour of what is behind it instead of a flat tint.
 */
final class Backdrop {

    private static Bitmap source;
    private static Bitmap blurred;
    private static float scale = 1f;

    private Backdrop() {}

    static synchronized void prepare(Context context, int width, int height) {
        if (source != null || width <= 0 || height <= 0) return;
        try {
            Drawable drawable = ModuleResources.drawable(context, "glass_bg");
            if (!(drawable instanceof BitmapDrawable)) {
                int id = context.getResources().getIdentifier("glass_bg", "drawable",
                        context.getPackageName());
                if (id != 0) drawable = context.getResources().getDrawable(id, null);
            }
            if (!(drawable instanceof BitmapDrawable)) return;
            Bitmap raw = ((BitmapDrawable) drawable).getBitmap();
            if (raw == null) return;

            source = cropToFill(raw, width, height);

            int smallW = Math.max(1, width / 6);
            int smallH = Math.max(1, height / 6);
            Bitmap small = Bitmap.createScaledBitmap(source, smallW, smallH, true);
            boxBlur(small, 3);
            blurred = small;
            scale = width / (float) smallW;
        } catch (Throwable ignored) {
        }
    }

    static Bitmap source() { return source; }

    static Bitmap blurred() { return blurred; }

    /** How many screen pixels one pixel of {@link #blurred()} covers. */
    static float scale() { return scale; }

    private static Bitmap cropToFill(Bitmap raw, int width, int height) {
        float factor = Math.max(width / (float) raw.getWidth(), height / (float) raw.getHeight());
        int w = Math.round(raw.getWidth() * factor);
        int h = Math.round(raw.getHeight() * factor);
        Bitmap scaled = Bitmap.createScaledBitmap(raw, w, h, true);
        int x = Math.max(0, (w - width) / 2);
        int y = Math.max(0, (h - height) / 2);
        return Bitmap.createBitmap(scaled, x, y, Math.min(width, w), Math.min(height, h));
    }

    /** Three-pass box blur: cheap on the 1/6-size bitmap, invisible banding after upscale. */
    private static void boxBlur(Bitmap bitmap, int radius) {
        int w = bitmap.getWidth();
        int h = bitmap.getHeight();
        int[] pixels = new int[w * h];
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h);
        int[] temp = new int[pixels.length];
        for (int pass = 0; pass < 2; pass++) {
            blurHorizontal(pixels, temp, w, h, radius);
            blurVertical(temp, pixels, w, h, radius);
        }
        bitmap.setPixels(pixels, 0, w, 0, 0, w, h);
    }

    private static void blurHorizontal(int[] in, int[] out, int w, int h, int radius) {
        int span = radius * 2 + 1;
        for (int y = 0; y < h; y++) {
            int row = y * w;
            long a = 0, r = 0, g = 0, b = 0;
            for (int i = -radius; i <= radius; i++) {
                int color = in[row + clamp(i, w)];
                a += (color >>> 24) & 0xFF;
                r += (color >> 16) & 0xFF;
                g += (color >> 8) & 0xFF;
                b += color & 0xFF;
            }
            for (int x = 0; x < w; x++) {
                out[row + x] = ((int) (a / span) << 24) | ((int) (r / span) << 16)
                        | ((int) (g / span) << 8) | (int) (b / span);
                int add = in[row + clamp(x + radius + 1, w)];
                int sub = in[row + clamp(x - radius, w)];
                a += ((add >>> 24) & 0xFF) - ((sub >>> 24) & 0xFF);
                r += ((add >> 16) & 0xFF) - ((sub >> 16) & 0xFF);
                g += ((add >> 8) & 0xFF) - ((sub >> 8) & 0xFF);
                b += (add & 0xFF) - (sub & 0xFF);
            }
        }
    }

    private static void blurVertical(int[] in, int[] out, int w, int h, int radius) {
        int span = radius * 2 + 1;
        for (int x = 0; x < w; x++) {
            long a = 0, r = 0, g = 0, b = 0;
            for (int i = -radius; i <= radius; i++) {
                int color = in[clamp(i, h) * w + x];
                a += (color >>> 24) & 0xFF;
                r += (color >> 16) & 0xFF;
                g += (color >> 8) & 0xFF;
                b += color & 0xFF;
            }
            for (int y = 0; y < h; y++) {
                out[y * w + x] = ((int) (a / span) << 24) | ((int) (r / span) << 16)
                        | ((int) (g / span) << 8) | (int) (b / span);
                int add = in[clamp(y + radius + 1, h) * w + x];
                int sub = in[clamp(y - radius, h) * w + x];
                a += ((add >>> 24) & 0xFF) - ((sub >>> 24) & 0xFF);
                r += ((add >> 16) & 0xFF) - ((sub >> 16) & 0xFF);
                g += ((add >> 8) & 0xFF) - ((sub >> 8) & 0xFF);
                b += (add & 0xFF) - (sub & 0xFF);
            }
        }
    }

    private static int clamp(int value, int max) {
        return value < 0 ? 0 : (value >= max ? max - 1 : value);
    }
}
