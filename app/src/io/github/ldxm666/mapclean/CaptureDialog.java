package io.github.ldxm666.mapclean;

import android.app.Dialog;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 与面板同款的确认弹窗：深/浅色圆角卡片 + 一个主按钮 + 一个文字按钮。
 *
 * <p>系统 {@code AlertDialog} 在高德这种全屏地图上会弹出一张白底方角卡片，和面板完全是两套
 * 视觉，用户直接说「丑」。这里全部自绘，颜色/圆角/字重都和面板一致；纯代码，不依赖资源。
 */
public final class CaptureDialog {

    private CaptureDialog() { }

    public static void show(Context ctx, String title, CharSequence message,
                            String primaryLabel, Runnable onPrimary,
                            String secondaryLabel, Runnable onSecondary) {
        build(ctx, title, message, primaryLabel, onPrimary, secondaryLabel, onSecondary, true);
    }

    public static void showBlocking(Context ctx, String title, CharSequence message,
                                    String primaryLabel, Runnable onPrimary,
                                    String secondaryLabel, Runnable onSecondary) {
        build(ctx, title, message, primaryLabel, onPrimary, secondaryLabel, onSecondary, false);
    }

    private static void build(Context ctx, String title, CharSequence message,
                              final String primaryLabel, final Runnable onPrimary,
                              final String secondaryLabel, final Runnable onSecondary,
                              boolean cancelable) {
        if (ctx == null) return;
        final boolean dark = (ctx.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        final int cardBg = dark ? 0xFF1F2126 : 0xFFF7F8FA;
        final int stroke = dark ? 0x1AFFFFFF : 0x14000000;
        final int titleColor = dark ? 0xFFFFFFFF : 0xFF1A1A1A;
        final int msgColor = dark ? 0xCCFFFFFF : 0x99000000;

        final Dialog dialog = new Dialog(ctx);
        try {
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        } catch (Throwable ignored) { }

        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(ctx, 20f));
        bg.setColor(cardBg);
        bg.setStroke(Math.max(1, dp(ctx, 1f / 3f)), stroke);
        card.setBackground(bg);
        int pad = dp(ctx, 20f);
        card.setPadding(pad, dp(ctx, 18f), pad, dp(ctx, 12f));

        TextView t = new TextView(ctx);
        t.setText(title);
        t.setTextSize(17f);
        t.setTextColor(titleColor);
        t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        card.addView(t, wrap());

        TextView m = new TextView(ctx);
        m.setText(message);
        m.setTextSize(13f);
        m.setTextColor(msgColor);
        m.setLineSpacing(0f, 1.25f);
        LinearLayout.LayoutParams mlp = wrap();
        mlp.topMargin = dp(ctx, 8f);
        card.addView(m, mlp);

        View divider = new View(ctx);
        divider.setBackgroundColor(dark ? 0x14FFFFFF : 0x14000000);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, dp(ctx, 1f / 3f)));
        dlp.topMargin = dp(ctx, 16f);
        card.addView(divider, dlp);

        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = dp(ctx, 10f);
        card.addView(row, rlp);

        if (secondaryLabel != null && secondaryLabel.length() > 0) {
            TextView sec = new TextView(ctx);
            sec.setText(secondaryLabel);
            sec.setTextSize(13f);
            sec.setTextColor(dark ? 0xFF8A8F98 : 0xFF6B7280);
            sec.setGravity(Gravity.CENTER);
            sec.setPadding(dp(ctx, 14f), dp(ctx, 10f), dp(ctx, 14f), dp(ctx, 10f));
            sec.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    dismiss(dialog);
                    if (onSecondary != null) onSecondary.run();
                }
            });
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            slp.rightMargin = dp(ctx, 8f);
            row.addView(sec, slp);
        }

        TextView primary = new TextView(ctx);
        primary.setText(primaryLabel);
        primary.setTextSize(13f);
        primary.setTextColor(Color.WHITE);
        primary.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        primary.setGravity(Gravity.CENTER);
        primary.setPadding(dp(ctx, 16f), dp(ctx, 10f), dp(ctx, 16f), dp(ctx, 10f));
        GradientDrawable btn = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0xFF3D7EFF, 0xFF2A5FE0});
        btn.setCornerRadius(dp(ctx, 14f));
        primary.setBackground(btn);
        primary.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                dismiss(dialog);
                if (onPrimary != null) onPrimary.run();
            }
        });
        row.addView(primary, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        dialog.setContentView(card);
        dialog.setCancelable(cancelable);
        dialog.setCanceledOnTouchOutside(cancelable);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
            w.setDimAmount(cancelable ? 0.45f : 0.55f);
            w.addFlags(Window.FEATURE_NO_TITLE);
            int width = Math.min(ctx.getResources().getDisplayMetrics().widthPixels - dp(ctx, 48f),
                    dp(ctx, 340f));
            w.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
            try {
                w.setGravity(Gravity.CENTER);
            } catch (Throwable ignored) { }
        }
        try {
            dialog.show();
        } catch (Throwable ignored) { }
    }

    private static void dismiss(Dialog d) {
        try { if (d != null && d.isShowing()) d.dismiss(); } catch (Throwable ignored) { }
    }

    private static LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private static int dp(Context ctx, float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                ctx.getResources().getDisplayMetrics());
    }
}
