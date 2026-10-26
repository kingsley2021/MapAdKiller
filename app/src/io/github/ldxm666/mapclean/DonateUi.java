package io.github.ldxm666.mapclean;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 「赞赏支持」弹窗 —— 模块版（{@code MainActivity}）与内置版（{@code FloatingPanel}）**共用同一份 UI**。
 *
 * <h3>为什么抽出来</h3>
 * 之前两处各写一份，样式不一致且都很素（只有标题 + 二维码 + 一行小字 + 系统按钮）。
 * 统一到这里后：①两版观感完全一致 ②以后改样式只改一处 ③不依赖 {@code Skin} 实例
 * （{@code FloatingPanel} 没有 Skin），自带配色，两版都能用。
 *
 * <h3>视觉规格</h3>
 * 圆角 24dp 白卡 + 1px 描边；顶部胶囊标签；20sp 主标题 + 12.5sp 副标题；
 * 二维码放在独立白卡里（18dp 圆角 + 描边 + 6dp 投影 + 12dp 内边距）；
 * 两行说明；底部 46dp 主色按钮。整宽 = min(屏宽 − 48dp, 340dp)。
 */
final class DonateUi {

    /** 主色（与模块 Skin.accent 同源）。 */
    private static final int ACCENT = 0xFF2B7FFF;
    private static final int ACCENT_SOFT = 0x1A2B7FFF;
    private static final int TX = 0xFF1A1A1A;
    private static final int TX2 = 0xFF6B7280;
    private static final int TX3 = 0xFF9AA0A6;
    private static final int CARD = 0xFFFFFFFF;
    private static final int LINE = 0x14000000;

    private DonateUi() {}

    static void show(Context ctx) {
        try {
            final Context c = ctx;
            LinearLayout box = new LinearLayout(c);
            box.setOrientation(LinearLayout.VERTICAL);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(CARD);
            bg.setCornerRadius(dp(c, 24));
            bg.setStroke(Math.max(1, dp(c, 1)), LINE);
            box.setBackground(bg);
            box.setPadding(dp(c, 22), dp(c, 22), dp(c, 22), dp(c, 16));

            // ① 顶部胶囊标签
            TextView badge = new TextView(c);
            badge.setText("赞赏支持");
            badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
            badge.setTypeface(Typeface.DEFAULT_BOLD);
            badge.setTextColor(ACCENT);
            GradientDrawable pill = new GradientDrawable();
            pill.setColor(ACCENT_SOFT);
            pill.setCornerRadius(dp(c, 99));
            badge.setBackground(pill);
            badge.setPadding(dp(c, 12), dp(c, 5), dp(c, 12), dp(c, 5));
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            blp.gravity = Gravity.CENTER_HORIZONTAL;
            box.addView(badge, blp);

            // ② 主标题 + ③ 副标题
            TextView title = new TextView(c);
            title.setText("感谢投喂");
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f);
            title.setTypeface(Typeface.DEFAULT_BOLD);
            title.setTextColor(TX);
            title.setGravity(Gravity.CENTER);
            addTop(c, box, title, 12);

            TextView sub = new TextView(c);
            sub.setText("你的支持是持续维护的动力");
            sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
            sub.setTextColor(TX3);
            sub.setGravity(Gravity.CENTER);
            addTop(c, box, sub, 6);

            // ④ 二维码白卡
            FrameLayout qrCard = new FrameLayout(c);
            GradientDrawable cardBg = new GradientDrawable();
            cardBg.setColor(0xFFFFFFFF);
            cardBg.setCornerRadius(dp(c, 18));
            cardBg.setStroke(Math.max(1, dp(c, 1)), LINE);
            qrCard.setBackground(cardBg);
            try { qrCard.setElevation(dp(c, 6)); } catch (Throwable ignored) { }

            Bitmap qr = null;
            try { qr = DonateAsset.bitmap(); } catch (Throwable ignored) { }

            int side = dp(c, 216);
            if (qr != null) {
                ImageView iv = new ImageView(c);
                iv.setImageBitmap(qr);
                iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
                FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(side, side);
                ilp.gravity = Gravity.CENTER;
                int m = dp(c, 12);
                ilp.setMargins(m, m, m, m);
                qrCard.addView(iv, ilp);
            } else {
                TextView fail = new TextView(c);
                fail.setText("收款码加载失败");
                fail.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
                fail.setTextColor(TX3);
                fail.setGravity(Gravity.CENTER);
                FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(side, side);
                flp.gravity = Gravity.CENTER;
                qrCard.addView(fail, flp);
            }
            addTop(c, box, qrCard, 18);

            // ⑤ 两行说明
            TextView cap1 = new TextView(c);
            cap1.setText("cakercc 的赞赏码");
            cap1.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
            cap1.setTypeface(Typeface.DEFAULT_BOLD);
            cap1.setTextColor(TX2);
            cap1.setGravity(Gravity.CENTER);
            addTop(c, box, cap1, 16);

            TextView cap2 = new TextView(c);
            cap2.setText("微信 / 支付宝 扫一扫");
            cap2.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
            cap2.setTextColor(TX3);
            cap2.setGravity(Gravity.CENTER);
            addTop(c, box, cap2, 4);

            // ⑥ 主按钮
            final Dialog[] holder = new Dialog[1];
            TextView close = new TextView(c);
            close.setText("关闭");
            close.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
            close.setTypeface(Typeface.DEFAULT_BOLD);
            close.setTextColor(0xFFFFFFFF);
            close.setGravity(Gravity.CENTER);
            GradientDrawable btnBg = new GradientDrawable();
            btnBg.setColor(ACCENT);
            btnBg.setCornerRadius(dp(c, 14));
            close.setBackground(btnBg);
            close.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    try { if (holder[0] != null) holder[0].dismiss(); } catch (Throwable ignored) { }
                }
            });
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(c, 46));
            clp.topMargin = dp(c, 20);
            box.addView(close, clp);

            // ★ 用裸 Dialog 而不是 AlertDialog：AlertController 的内容面板会额外内缩 ~16dp/边，
            //   导致「窗口 340dp、白卡只有 308dp」。裸 Dialog + 透明 windowBackground + setLayout
            //   才能让白卡宽度精确等于 min(屏宽−48dp, 340dp)。
            Dialog dlg = new Dialog(c);
            dlg.requestWindowFeature(Window.FEATURE_NO_TITLE);
            dlg.setContentView(box);
            dlg.setCanceledOnTouchOutside(true);
            holder[0] = dlg;
            dlg.show();

            Window w = dlg.getWindow();
            if (w != null) {
                w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
                w.setDimAmount(0.45f);
                w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
                int screen = c.getResources().getDisplayMetrics().widthPixels;
                int width = Math.min(screen - dp(c, 48), dp(c, 340));
                w.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
            }
            Activity a = asActivity(c);
            if (a != null) { try { a.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED); } catch (Throwable ignored) { } }
        } catch (Throwable t) {
            // 弹窗失败绝不影响调用方
        }
    }

    private static void addTop(Context c, LinearLayout parent, View child, int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        lp.topMargin = dp(c, topDp);
        parent.addView(child, lp);
    }

    private static Activity asActivity(Context c) {
        Context cur = c;
        for (int i = 0; i < 8 && cur != null; i++) {
            if (cur instanceof Activity) return (Activity) cur;
            if (!(cur instanceof ContextWrapper)) return null;
            cur = ((ContextWrapper) cur).getBaseContext();
        }
        return null;
    }

    private static int dp(Context c, float v) {
        return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f);
    }
}
