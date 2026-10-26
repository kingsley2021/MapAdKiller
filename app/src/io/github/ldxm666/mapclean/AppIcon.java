package io.github.ldxm666.mapclean;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

/**
 * 未安装地图 App 的「常态图标」：品牌色圆角方形 + 白色定位针。
 *
 * <h3>为什么不用真实图标</h3>
 * 已安装的 App 走 {@code ApplicationInfo.loadIcon()} 拿系统里的真图标（见
 * {@code MainActivity.appIcon}）。未安装的 App 在设备上**根本没有图标可取** —— 之前这里
 * 退化成一个 20dp 的下载箭头，看起来像「待下载的文件」而不是一个 App，用户明确嫌丑。
 *
 * <p>本类只负责「看起来像一个正经 App 图标」：圆角方形 + 品牌主色 + 白色定位针，
 * 与已安装图标的 42dp 圆角规格一致。**装了对应 App 就会自动显示它自己的真图标**，
 * 这里的画法只服务「未安装」这一种状态。
 */
final class AppIcon extends View {

    /** 各厂商品牌主色（上浅下深做渐变，模拟图标的光泽）。 */
    static final int[] BRAND_TOP = {0xFF3D8BFF, 0xFF4E9BFF, 0xFF35C07A};
    static final int[] BRAND_BOTTOM = {0xFF0B57D0, 0xFF1A6BE0, 0xFF12875A};

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint white = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private final Path pin = new Path();
    private int vendor;

    AppIcon(Context c, int vendor) {
        super(c);
        this.vendor = Math.max(0, Math.min(BRAND_TOP.length - 1, vendor));
        white.setColor(0xFFFFFFFF);
        white.setStyle(Paint.Style.FILL);
    }

    void setVendor(int v) {
        vendor = Math.max(0, Math.min(BRAND_TOP.length - 1, v));
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;

        float r = Math.min(w, h) * 0.235f;          // 与系统自适应图标接近的圆角比例
        box.set(0, 0, w, h);
        fill.setShader(new LinearGradient(0, 0, 0, h,
                BRAND_TOP[vendor], BRAND_BOTTOM[vendor], Shader.TileMode.CLAMP));
        canvas.drawRoundRect(box, r, r, fill);
        fill.setShader(null);

        // 白色定位针：上圆 + 下尖，中心挖一个品牌色小圆
        float cx = w * 0.5f, cy = h * 0.42f, rad = Math.min(w, h) * 0.20f;
        pin.reset();
        pin.addCircle(cx, cy, rad, Path.Direction.CW);
        pin.moveTo(cx - rad * 0.62f, cy + rad * 0.62f);
        pin.lineTo(cx, h * 0.80f);
        pin.lineTo(cx + rad * 0.62f, cy + rad * 0.62f);
        pin.close();
        canvas.drawPath(pin, white);

        fill.setColor(BRAND_BOTTOM[vendor]);
        canvas.drawCircle(cx, cy, rad * 0.40f, fill);
    }
}
