package io.github.ldxm666.mapadkiller;

import android.content.pm.PackageInfo;
import org.json.JSONObject;

import io.github.libxposed.api.XposedInterface;

/**
 * 15.21.0.2038 专用：首页工具宫格逐项开关。
 *
 * <h3>15.21 与 17.02 的差异（DEX 实测）</h3>
 * <ul>
 *   <li>{@code BootBizDataPreloaderImpl.getToolboxData()}（classes4 @0x5617d8）读 MMKV
 *       {@code Key_toolbox_data}，为空时回落到方法内联的 **15 工具** JSON；
 *       字段 schema 与 17.02 完全一致：{@code id<i>/schema<i>/name<i>/imageClass<i>/
 *       imageToken<i>/size<i>/itemDataForClick<i>}。</li>
 *   <li>面板 {@code ToolboxPanelV2.init(Context,String,int,boolean)}（classes4 @0x4b8ca4）
 *       自己 {@code getToolboxData()} 后按 {@code id<i>/size<i>} 循环建格，
 *       **没有** 17.02 的 {@code toolbox.a.a(...)} 工厂。</li>
 *   <li>面板一屏按 {@code size<i>} 排 3 行 × 5 列 = **15 格**；写超过 15 个槽位不会渲染，
 *       所以这里以「宿主自己的槽位数」为上限，让用户打开的工具**顶掉**被关掉的默认工具，
 *       而不是默默写在第 16 个槽里看不见。</li>
 * </ul>
 *
 * <h3>为什么不写死槽位数</h3>
 * 不同版本/皮肤默认槽位数不同（15.21=15，17.02 首页=8）。这里读宿主 payload 的
 * {@code id0..idN} 连续段长度作为上限；读不到才退回 15。
 */
public final class AmapToolbox1521 {
    private static final String VERSION_PREFIX = "15.21";
    private static volatile boolean installed;

    private AmapToolbox1521() {}

    public static boolean matches(ClassLoader cl) {
        PackageInfo p = io.github.ldxm666.mapclean.Compatibility.packageInfo(cl, MainHook.PKG_AMAP);
        return p != null && p.versionName != null && p.versionName.startsWith(VERSION_PREFIX);
    }

    public static void install(final ClassLoader cl) {
        if (!matches(cl)) return;
        if (installed) return;
        installed = true;
        Class<?> preloader = H.cls(cl, "com.autonavi.bundle.amaphome.impl.BootBizDataPreloaderImpl");
        if (preloader == null) {
            H.log("event=tools1521_miss what=BootBizDataPreloaderImpl");
            return;
        }
        H.hookSig(preloader, "getToolboxData", "amap_tool_boot_1521b", new XposedInterface.Hooker() {
            @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                Object result = chain.proceed();
                if (!(result instanceof JSONObject)) return result;
                try {
                    JSONObject before = (JSONObject) result;
                    int hostSlots = hostSlots(before);
                    JSONObject out = AmapData.toolbox(before, AmapData.CURRENT, true, hostSlots);
                    int b = countSlots(before), a = countSlots(out);
                    H.log("event=tools1521_before " + dump(before));
                    H.log("event=tools1521_after " + dump(out));
                    if (a != b) {
                        H.log("event=tools1521_filter before=" + b + " after=" + a
                                + " slots=" + hostSlots + " ids=" + AmapData.slotIds(out));
                    } else if (out != before) {
                        H.log("event=tools1521_inject added=" + added(before, out) + " slots=" + hostSlots);
                    }
                    return out;
                } catch (Throwable t) {
                    H.log("event=tools1521_filter_failed err=" + t.getClass().getSimpleName());
                    return result;
                }
            }
        }, new Class<?>[0]);
        H.log("event=tools1521_install version=15.21");
    }

    /** 宿主 payload 的连续槽位数（id0..idN）。读不到退回 15（15.21 实测值）。 */
    private static int hostSlots(JSONObject o) {
        int n = countSlots(o);
        return n > 0 ? n : 15;
    }

    private static int countSlots(JSONObject o) {
        int n = 0;
        while (n < 64 && o != null && o.has("id" + n)) n++;
        return n;
    }

    /** 打印前 32 个槽位的 id/size/name，确认 size 与 id 对齐。 */
    private static String dump(JSONObject o) {
        StringBuilder sb = new StringBuilder("items=[");
        if (o != null) {
            for (int i = 0; i < 32 && o.has("id" + i); i++) {
                if (i > 0) sb.append(" | ");
                sb.append(i).append(':').append(o.optString("id" + i))
                        .append('/').append(o.optString("size" + i))
                        .append('/').append(o.optString("name" + i));
            }
        }
        return sb.append(']').toString();
    }

    /** 输出里出现、而原 payload 没有的工具数。 */
    private static int added(JSONObject before, JSONObject after) {
        java.util.Set<String> old = new java.util.HashSet<String>();
        for (int i = 0; i < countSlots(before); i++) old.add(before.optString("id" + i));
        int n = 0;
        for (int i = 0; i < countSlots(after); i++) {
            String id = after.optString("id" + i);
            if (id.length() > 0 && !old.contains(id)) n++;
        }
        return n;
    }
}
