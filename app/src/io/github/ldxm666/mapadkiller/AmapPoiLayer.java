package io.github.ldxm666.mapadkiller;

import java.lang.reflect.Field;
import java.util.List;
import io.github.libxposed.api.XposedInterface;

/**
 * Removes the POI icons and names drawn on the map, while leaving geography intact.
 *
 * <h3>Why this is two mechanisms</h3>
 * The map's own POIs (shops, hotels, scenic spots) are rendered by the native engine from
 * tile data. They never pass through a Java-side list, so filtering item arrays alone can
 * never reach them. The engine does expose a switch for it -- {@code nativeSetProcessLabel}
 * -- and that is what actually turns the labels off.
 *
 * <p>Separately, overlay point/label batches <i>are</i> pushed through Java
 * ({@code nativeSetPointInfo}, {@code nativeSetThirdLabelInfo}, ...). Those carry entries
 * whose properties sometimes expose a {@code mainKey}; the ad-like keys used by the ranking
 * cards are dropped there so they cannot reappear as pins.
 *
 * <h3>Why the reflection is defensive</h3>
 * The obvious earlier implementation read {@code StylePointProperties.styleInfo} and
 * {@code .mainKey} directly. On 17.00.0.2005 neither field exists on that class --
 * {@code StylePointProperties} has {@code visible}, {@code mainPriority} and friends, while
 * {@code mainKey} only exists on {@code ThirdLabelItemProperties}. The lookup therefore threw
 * {@code NoSuchFieldException} on every call, the whole filter aborted, and the switch looked
 * dead even though the hook was installed and firing.
 *
 * <p>Every field read here is optional: a missing field yields null and the entry is simply
 * left alone. That keeps the hook correct across app versions whose scene structures differ,
 * which is exactly the situation that caused the bug.
 */
final class AmapPoiLayer {

    /** Keys the ranking / recommendation cards use; these are the ones worth hiding. */
    private static final int KEY_RANKING = 12024;
    private static final int KEY_PROMO = 12029;

    private AmapPoiLayer() {}

    /** Reads a field, tolerating both missing fields and inaccessible ones. */
    private static Object read(Object target, String name) {
        if (target == null) return null;
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException missing) {
                type = type.getSuperclass();
            } catch (Throwable ignored) {
                return null;
            }
        }
        return null;
    }

    private static Integer asInt(Object value) {
        return value instanceof Integer ? (Integer) value : null;
    }

    private static void writeBoolean(Object target, String name, boolean value) {
        if (target == null) return;
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                field.setBoolean(target, value);
                return;
            } catch (NoSuchFieldException missing) {
                type = type.getSuperclass();
            } catch (Throwable ignored) {
                return;
            }
        }
    }

    /**
     * Drop ad-like entries from one overlay batch.
     *
     * Walks {@code data.items}, then each entry's {@code properties}. When those properties
     * do not expose {@code mainKey}, one nested level ({@code properties.styleInfo}) is tried,
     * because the two structures differ between scene types and app versions.
     */
    private static void filterItems(Object data, String tag) {
        if (data == null || Config.visible(Config.K_MAP_POI)) return;
        Object value = read(data, "items");
        if (!(value instanceof List)) return;
        int hidden = 0, seen = 0;
        for (Object entry : (List<?>) value) {
            if (entry == null) continue;
            seen++;
            Object props = read(entry, "properties");
            if (props == null) continue;

            Object holder = props;
            Integer main = asInt(read(holder, "mainKey"));
            if (main == null) {
                Object nested = read(holder, "styleInfo");
                if (nested != null) {
                    holder = nested;
                    main = asInt(read(holder, "mainKey"));
                }
            }
            if (main == null) continue;
            if (main == KEY_RANKING || main == KEY_PROMO) {
                // Only flip a `visible` that is genuinely there; otherwise the write is a no-op
                // and the entry stays, which is the correct outcome for an unknown structure.
                if (read(holder, "visible") != null) {
                    writeBoolean(holder, "visible", false);
                    hidden++;
                }
            }
        }
        // One line per batch, and only when something changed or the first batch arrives.
        if (hidden != 0) H.log("amap_poi hidden=" + hidden + " of=" + seen + " tag=" + tag);
    }

    static void install(final ClassLoader cl) {
        // ★ 性能默认：**默认不装**这三个 native 标注 hook（`labels_on` 默认 false）。
        //
        // 理由（用户连续两轮反馈「卡」）：
        //   · 这三个方法位于「每次刷新第三方标注 / POI / 样式点」的热路径上；若高德在
        //     渲染帧里调用它们，我们的 hook 就变成每帧一次 Java→hook→native 往返。
        //   · 地图是 SurfaceView/GL 独立 surface 渲染，`dumpsys gfxinfo` 与
        //     `Choreographer#doFrame` 都**测不到**地图层的真实帧 ⇒ 这类开销在现有
        //     测量手段下是"隐形"的，只能靠默认关掉来消除风险。
        //   · **功能不损失**：地图标注的真正实现是 `libmapclean_labels.so` 在原生层
        //     改样式 protobuf（见 app/native/label_assets.cpp），本类只是"补充过滤"，
        //     注释原文即为「这段不做主力」。
        //
        // 需要恢复时：面板里把 `labels_on` 打开（或直接写 prefs `labels_on=true`）。
        // 保留 `labels_off` 作为"强制关"的兼容键（两者任一为关，就不装）。
        if (!Config.selected(Config.K_LABELS_ON) || Config.selected(Config.K_LABELS_OFF)) {
            H.log("event=labels_off labels_on=" + Config.selected(Config.K_LABELS_ON)
                    + " native_label_hooks=NOT_INSTALLED");
            return;
        }
        Class<?> scene = H.cls(cl, "com.autonavi.jni.vmap.dsl.VMapSceneWrapper");
        if (scene == null) return;

        // NOTE on which lever is correct here.
        //
        // 兴趣点的隐藏由 libmapclean_labels.so 在**原生层**完成（见 app/native/label_assets.cpp）：
        // 它挂住 AAssetManager_open，只截 map_assets/style_*X_Main 这一张样式 protobuf，
        // 用 style::amap() 把兴趣点要素改写成不可见，道路 / 水域 / 地名原样保留。
        // 那才是“去地图标注”的真正实现，由 NativeLabelAssets 开关。
        //
        // 这里曾经改为 hook setProcessLabel / setGlobalProcessLabel 并强行下发 false。
        // 验证下来那是**错的**：processLabel 是引擎的总开关，关掉后路名、城市名
        // 会一起消失（截图里整张图一个文字都没了），与开关承诺的“保留道路与地名”矛盾。
        // 已回退；下面只保留两类无害的补充过滤。

        // 补充一：第三方标注批次里带 mainKey 的推广/榜单条目直接置不可见。
        // 引擎自己画的基础 POI 不走这里，所以这段不做主力，只是不让它们以图钉形式重新出现。
        Class<?> labels = H.cls(cl, "com.autonavi.jni.vmap.dsl.MapSceneObjDef$ThirdLabelInfos");
        Class<?> points = H.cls(cl, "com.autonavi.jni.vmap.dsl.MapSceneObjDef$StylePointInfos");
        if (labels != null) {
            for (String method : new String[]{"nativeSetThirdLabelInfo",
                    "nativeSetUpdate3rdLabelsWithPoiData"}) {
                H.hookSig(scene, method, "amap_poi_" + method, new XposedInterface.Hooker() {
                    @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                        filterItems(chain.getArg(3), method);
                        return chain.proceed();
                    }
                }, int.class, int.class, int.class, labels);
            }
        }
        if (points != null) {
            H.hookSig(scene, "nativeSetStylePointInfo", "amap_poi_nativeSetStylePointInfo",
                    new XposedInterface.Hooker() {
                        @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            filterItems(chain.getArg(3), "nativeSetStylePointInfo");
                            return chain.proceed();
                        }
                    }, int.class, int.class, int.class, points);
        }
    }

    /** Shallow copy of a chain's arguments, so one may be replaced before {@code proceed}. */
    private static Object[] copyArgs(XposedInterface.Chain chain) {
        java.util.List<Object> source = chain.getArgs();
        Object[] args = new Object[source.size()];
        for (int i = 0; i < args.length; i++) args[i] = source.get(i);
        return args;
    }
}
