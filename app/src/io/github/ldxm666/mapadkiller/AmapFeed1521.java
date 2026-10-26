// language: Java, file: AmapFeed1521.java, module: MapAdKiller, target: 高德 15.21.0.2038 首页信息流
package io.github.ldxm666.mapadkiller;

import android.util.Log;

import java.util.ArrayList;
import java.util.List;

import io.github.libxposed.api.XposedInterface;

/**
 * AmapFeed1521 — 高德 **15.21** 首页信息流广告卡过滤（打车 / 顺风车 / 代驾 / 天气 / 城市动态）。
 *
 * ════════════════════════════════════════════════════════════════════════════════════
 * 一、真机实证（2026-10-26，15.21.0.2038）
 * ════════════════════════════════════════════════════════════════════════════════════
 * 唯一响应：
 *   https://m5.amap.com/ws/faas/amap-navigation/main-page-aiqs?…
 *   data.cardList[]    ← 7 项，就是要删的卡片
 *   data.mapBizList[]  ← 地图业务图层（气泡/图层），**不是广告，原样保留**
 *
 * 7 项 dataKey / dataType（探针实测）：
 * <pre>
 *   0 ShareTripTaxiCard              ShareTripTaxiCard          高德打车
 *   1 HitchQSCardMainOrderManager    HitchQSCardMainOrderManager 顺风车特价路线
 *   2 Combine|CommuteShareTripTaxi|… CombineUI                  打车组合卡（内含 bigPrefix:"高德打车"）
 *   3 MoveMapIntentionCard           MoveMapIntentionCard       未对应截图，一并删
 *   4 ProductCategoryGuideCard       GraphicText2RowTagCardTemp 高德代驾 / 抢神券
 *   5 GuideCommuteCard               GuideCommuteCard           看看上下班堵不堵
 *   6 MultiUserShareCard             MultiUserShareCard         数据上云不丢失 / 一键登录
 * </pre>
 * 「回家 / 去单位 / 添加常去地点」那张原生卡**不在 cardList 里**（在别的字段）⇒ 清空 cardList 不影响它。
 *
 * ════════════════════════════════════════════════════════════════════════════════════
 * 二、做法
 * ════════════════════════════════════════════════════════════════════════════════════
 * 在**响应体进 JS 之前**把 `data.cardList` 置为 `[]`：
 *   · 主路径 `HttpResponse.getResponseBodyString()`（17.02 已实证是最后一个 Java 侧字符串闸门）；
 *   · 兜底 `ModuleRequest.notifyJs(...)`（8 参，body 在 String 参数里）。
 * 两条路径都按 **URL 含 `main-page-aiqs`** 判定 ⇒ 冷启后 30 秒内的**延迟第二批请求同样拦**。
 * `mapBizList` 一个字节都不动。
 *
 * 骨架：清空后如果宿主仍画灰块，说明它按别的标志位显示骨架；届时在报告里给出现象与下一步。
 *
 * 抗混淆锚点：`HttpResponse.getResponseBodyString()Ljava/lang/String;` · URL `main-page-aiqs` ·
 * 字段 `data.cardList` / `data.mapBizList`
 */
public final class AmapFeed1521 {

    private AmapFeed1521() {}

    /**
     * 真机取证：host 端真实路径是 {@code /ws/faas/amap-navigation/main-page}。
     * 全 8 个 DEX 里 {@code main-page-aiqs} 这个串**一次都不存在**（classes/classes5/classes6
     * 中仅出现 {@code main-page} / {@code main-page-assets} / {@code main-page-location}），
     * 所以旧常量永远匹配不上，过滤只在 {@code mapBizList} 兜底分支里生效。
     */
    private static final String URL_HINT = "amap-navigation/main-page";
    private static final int LOG_MAX = 1200;

    private static final java.util.Set<String> SEEN =
            java.util.Collections.synchronizedSet(new java.util.HashSet<String>());

    private static volatile int stripCount;

    public static void install(final ClassLoader cl) {
        hookHttpResponse(cl);
        hookNotifyJs(cl);
        H.log(Log.INFO, MainHook.TAG, "event=feed1521_install");
    }

    // ── 主路径：HttpResponse.getResponseBodyString ────────────────────────────

    private static void hookHttpResponse(final ClassLoader cl) {
        Class<?> resp = H.cls(cl, "com.autonavi.core.network.inter.response.HttpResponse");
        if (resp == null) {
            H.log(Log.WARN, MainHook.TAG, "event=feed1521_miss what=HttpResponse");
            return;
        }
        H.hookSig(resp, "getResponseBodyString", "amap_feed1521_body", new XposedInterface.Hooker() {
            @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                Object r = chain.proceed();
                if (!(r instanceof String)) return r;
                try {
                    String out = process("http", urlOf(chain.getThisObject()), (String) r);
                    return out == null ? r : out;
                } catch (Throwable ignored) {
                    return r;
                }
            }
        });
        H.log(Log.INFO, MainHook.TAG, "event=feed1521_hook name=http_body");
    }

    // ── 兜底：ModuleRequest.notifyJs(cb,int,int,String,String,int,String,String) ──

    private static void hookNotifyJs(final ClassLoader cl) {
        Class<?> req = H.cls(cl, "com.autonavi.minimap.ajx3.modules.net.ModuleRequest");
        Class<?> cb = H.cls(cl, "com.autonavi.minimap.ajx3.core.JsFunctionCallback");
        if (req == null || cb == null) {
            H.log(Log.WARN, MainHook.TAG, "event=feed1521_miss what=ModuleRequest");
            return;
        }
        H.hookSig(req, "notifyJs", "amap_feed1521_notify", new XposedInterface.Hooker() {
            @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                try {
                    for (int i = 0; i < chain.getArgs().size(); i++) {
                        Object a = chain.getArg(i);
                        if (!(a instanceof String)) continue;
                        String s = (String) a;
                        if (s.length() < 200 || s.indexOf("cardList") < 0) continue;
                        String out = process("notifyJs", null, s);
                        if (out == null) continue;
                        Object[] args = chain.getArgs().toArray();
                        args[i] = out;
                        return chain.proceed(args);
                    }
                } catch (Throwable ignored) { }
                return chain.proceed();
            }
        }, cb, int.class, int.class, String.class, String.class, int.class, String.class, String.class);
        H.log(Log.INFO, MainHook.TAG, "event=feed1521_hook name=notify_js");
    }

    // ── 过滤 + 取证 ──────────────────────────────────────────────────────────

    /**
     * @return 改写后的 body；不需要改写返回 null（调用方原样透传）。
     */
    private static String process(String src, String url, String body) {
        if (body == null || body.length() < 64) return null;
        sweep(src, url, body);
        boolean isFeed = isFeedUrl(url) || body.indexOf("mapBizList") >= 0;
        if (!isFeed || body.indexOf("cardList") < 0) return null;
        try {
            org.json.JSONObject root = new org.json.JSONObject(body);
            org.json.JSONObject data = root.optJSONObject("data");
            if (data == null) return null;
            org.json.JSONArray cardList = data.optJSONArray("cardList");
            if (cardList == null) return null;

            int before = cardList.length();
            probe(src, url, body, cardList);
            if (before == 0) return null;

            // ★ 逐项过滤，不再整块清空。
            //
            // 旧实现：K_FEED_BOARD=false 时 data.put("cardList", new JSONArray())，
            // 一次删掉 7 张卡（ShareTripTaxiCard / 顺风车 / 打车组合 / 代驾·抢神券 / 通勤 /
            // 多用户…）。用户想单独要打车卡就只能去关 ride_card_off，而 ride_card_off 又是
            // COMPACT 的总闸 ⇒ 整个推荐画布重建 ⇒ 天气/榜单/内容流一起炸开。
            //
            // 新实现：cardList 里**只**摘「打车」家族，且只由 K_TAXI_CARD 决定；
            // 其它 card type 原样保留，不受任何 feed 开关影响（不再出现"一个开关清空整块"）。
            boolean showTaxi = Config.visible(Config.K_TAXI_CARD);
            org.json.JSONArray kept = new org.json.JSONArray();
            StringBuilder removed = new StringBuilder();
            int removedCount = 0;
            for (int i = 0; i < cardList.length(); i++) {
                org.json.JSONObject card = cardList.optJSONObject(i);
                if (card == null) { kept.put(cardList.opt(i)); continue; }
                if (!keepCard(card)) {
                    removedCount++;
                    if (removed.length() > 0) removed.append(',');
                    removed.append(card.optString("dataType", "?"));
                    continue;
                }
                kept.put(card);
            }
            if (removedCount == 0) {
                if (stripCount++ < 5) {
                    H.log(Log.INFO, MainHook.TAG, "event=feed1521_keep src=" + src
                            + " show_taxi=" + showTaxi + " cardList=" + before);
                }
                return null;
            }

            data.put("cardList", kept);
            String out = root.toString();
            if (stripCount++ < 5) {
                H.log(Log.INFO, MainHook.TAG, "event=feed1521_strip src=" + src
                        + " url=" + shorten(url) + " cardList=" + before + " -> " + kept.length()
                        + " removed=[" + removed + "] show_taxi=" + showTaxi
                        + " len=" + body.length() + "->" + out.length());
            }
            return out;
        } catch (Throwable t) {
            H.log(Log.WARN, MainHook.TAG, "event=feed1521_err " + t.getClass().getSimpleName());
            return null;
        }
    }

    /** 一次性结构取证（同 URL 同长度只打一次）。 */
    private static void probe(String src, String url, String body, org.json.JSONArray cardList) {
        if (!SEEN.add(src + "|" + body.length() + "|" + (url == null ? "" : url.hashCode()))) return;
        // 每个 cardList 元素的全部识别字段，一次打全（17.02 真机取证用）。
        try {
            for (int i = 0; i < cardList.length() && i < 20; i++) {
                org.json.JSONObject c = cardList.optJSONObject(i);
                if (c == null) continue;
                H.log(Log.INFO, MainHook.TAG, "event=feed1521_card idx=" + i
                        + " dataType=" + c.optString("dataType", "")
                        + " dataKey=" + c.optString("dataKey", "")
                        + " cardName=" + c.optString("cardName", "")
                        + " cardClass=" + c.optString("cardClass", "")
                        + " name=" + c.optString("name", ""));
            }
        } catch (Throwable ignored) { }
        StringBuilder sb = new StringBuilder();
        sb.append("event=feed1521_probe src=").append(src)
          .append(" url=").append(shorten(url))
          .append(" len=").append(body.length())
          .append(" cardList=").append(cardList.length())
          .append(" keys=[");
        for (int i = 0; i < cardList.length() && i < 8; i++) {
            org.json.JSONObject o = cardList.optJSONObject(i);
            if (o == null) continue;
            if (i > 0) sb.append(',');
            sb.append(o.optString("dataType", "?"));
        }
        sb.append(']');
        H.log(Log.INFO, MainHook.TAG, clamp(sb.toString()));
    }

    /**
     * 这张卡是不是「打车」家族 —— 只有这一族受 {@link Config#K_TAXI_CARD} 控制。
     *
     * <p>真机 17.02.0.1541 证据（event=feed1521_probe）：
     * {@code cardList=[ShareTripTaxiCard, HitchQSCardMainOrderManager, CombineUI,
     * LocalCombineRecommend, MoveMapIntentionCard, FoodRecommend, GuideCommuteCard,
     * MultiUserShareCard, GraphicText2RowTagCardTemp]}。
     * <ul>
     *   <li>{@code ShareTripTaxiCard} —— 用户截图那张「去哪里打车 / 打车享优惠 · 行程有保障」；</li>
     *   <li>{@code CombineUI} + dataKey 含 {@code ShareTripTaxi} —— 「打车组合卡」；</li>
     *   <li>其它（顺风车 / 代驾·抢神券 / 通勤 / 多用户）**不属于打车**，不在这里拦。</li>
     * </ul>
     * 判定同时看 dataType / dataKey / cardName，任一命中即算 —— 服务端几种拼写都出现过。
     */
    private static boolean isTaxiCard(org.json.JSONObject card) {
        try {
            String type = card.optString("dataType", "");
            String key = card.optString("dataKey", "");
            String name = card.optString("cardName", "");
            if ("ShareTripTaxiCard".equalsIgnoreCase(type)) return true;
            if (type.indexOf("ShareTripTaxi") >= 0) return true;
            if (key.indexOf("ShareTripTaxi") >= 0) return true;
            if (name.indexOf("ShareTripTaxi") >= 0) return true;
            // 打车组合卡：dataType=CombineUI，dataKey 形如
            // Combine|CommuteShareTripTaxi|...（15.21 探针原文）。
            return "CombineUI".equalsIgnoreCase(type) && key.indexOf("Taxi") >= 0;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 这张卡该不该保留 —— **逐项过滤**的唯一入口。
     *
     * <p>规则（按优先级）：
     * <ol>
     *   <li>打车家族 ⇒ 只看 {@link Config#K_TAXI_CARD}（true=留）；</li>
     *   <li>四个内容开关全关 ⇒ **除打车卡外一律丢**（用户要的"干净首页"：
     *       天气 / 扫街榜 / 内容流 / 出行节一个都不许露）；</li>
     *   <li>有任一内容开关打开 ⇒ 按 dataType/dataKey/cardName 里的关键字归类，
     *       对应开关关着就丢；认不出的类型**放行**（避免误杀原生卡）。</li>
     * </ol>
     * 关键字来自 17.02 真机 probe（见 {@link #probe}），不是猜的。
     */
    private static boolean keepCard(org.json.JSONObject card) {
        try {
            if (isTaxiCard(card)) return Config.visible(Config.K_TAXI_CARD);
            boolean weather = Config.visible(Config.K_FEED_WEATHER);
            boolean board = Config.visible(Config.K_FEED_BOARD);
            boolean content = Config.visible(Config.K_FEED_CONTENT);
            boolean festival = Config.visible(Config.K_FEED_FESTIVAL);
            String norm = (card.optString("dataType", "") + "|"
                    + card.optString("dataKey", "") + "|"
                    + card.optString("cardName", "")).toLowerCase();
            // ① 已知家族：只认自己那个开关（白名单，先归类再判）。
            if (hit(norm, WEATHER_KW)) return weather;
            if (hit(norm, BOARD_KW)) return board;
            if (hit(norm, CONTENT_KW)) return content;
            if (hit(norm, FESTIVAL_KW)) return festival;
            // ② 认不出的卡：四个内容开关全关 ⇒ **丢**（白名单语义：
            //    服务端随时会加新卡，黑名单必然漏；用户要的"干净首页"必须一个不多）。
            //    有任一开关打开 ⇒ 放行，避免把原生基建卡误杀成首页空洞。
            return weather || board || content || festival;
        } catch (Throwable ignored) {
            return true;
        }
    }

    /** 天气家族 —— 17.02 真机 probe / AmapData.cardName 双证据。 */
    private static final String[] WEATHER_KW = {
            "weather", "hourlyweather", "weatherforecast"};
    /** 扫街榜 / 各类榜单家族。 */
    private static final String[] BOARD_KW = {
            "tipsranking", "ranking", "rank", "board", "bangdan", "榜单"};
    /** 内容流 / 推荐流家族。 */
    private static final String[] CONTENT_KW = {
            "contentcenter", "contentfeed", "feed", "recommend"};
    /** 出行节 / 促销活动家族。 */
    private static final String[] FESTIVAL_KW = {
            "festival", "promote", "activity", "coupon"};

    private static boolean hit(String norm, String[] kws) {
        for (String k : kws) if (norm.indexOf(k) >= 0) return true;
        return false;
    }

    // ── 工具 ────────────────────────────────────────────────────────────────

    /** 一次性 URL 普查：把所有 amap-navigation / billboard / weather / aiqs 响应登记一次。 */
    private static final java.util.Set<String> SWEPT =
            java.util.Collections.synchronizedSet(new java.util.HashSet<String>());

    private static void sweep(String src, String url, String body) {
        try {
            String u = url == null ? "(null)" : url;
            String low = u.toLowerCase();
            boolean want = low.indexOf("amap-navigation") >= 0 || low.indexOf("billboard") >= 0
                    || low.indexOf("weather") >= 0 || low.indexOf("aiqs") >= 0
                    || body.indexOf("cardList") >= 0;
            if (!want) return;
            String key = src + "|" + u + "|" + body.length();
            if (!SWEPT.add(key)) return;
            StringBuilder sb = new StringBuilder("event=feed1521_sweep src=").append(src)
                    .append(" url=").append(shorten(u)).append(" len=").append(body.length());
            if (body.indexOf("cardList") >= 0) sb.append(" HAS_cardList");
            if (body.indexOf("mapBizList") >= 0) sb.append(" HAS_mapBizList");
            if (body.indexOf("billboard") >= 0) sb.append(" HAS_billboard");
            if (body.indexOf("weather") >= 0) sb.append(" HAS_weather");
            if (body.indexOf("ranking") >= 0) sb.append(" HAS_ranking");
            if (body.indexOf("feed") >= 0) sb.append(" HAS_feed");
            // data 下的字段名清单（只记名字，不记内容）
            try {
                org.json.JSONObject root = new org.json.JSONObject(body);
                org.json.JSONObject data = root.optJSONObject("data");
                if (data != null) {
                    sb.append(" data_keys=[");
                    java.util.Iterator<String> it = data.keys();
                    int n = 0;
                    while (it.hasNext() && n++ < 24) {
                        String k = it.next();
                        Object v = data.opt(k);
                        sb.append(k).append(':');
                        if (v instanceof org.json.JSONArray) sb.append('A').append(((org.json.JSONArray) v).length());
                        else if (v instanceof org.json.JSONObject) sb.append('O');
                        else sb.append('s');
                        sb.append(' ');
                    }
                    sb.append(']');
                }
            } catch (Throwable ignored) { }
            H.log(Log.INFO, MainHook.TAG, clamp(sb.toString()));
        } catch (Throwable ignored) { }
    }

    /**
     * 只认首页推荐主接口；{@code main-page-assets}（静态资源清单）与
     * {@code main-page-location}（空壳，cardList 都不存在）必须排除，
     * 否则会把资源清单当卡片流改写。
     */
    private static boolean isFeedUrl(String url) {
        if (url == null) return false;
        if (url.indexOf(URL_HINT) < 0) return false;
        return url.indexOf("main-page-assets") < 0 && url.indexOf("main-page-location") < 0;
    }

    private static String urlOf(Object response) {
        if (response == null) return null;
        try {
            Object req = response.getClass().getMethod("getRequest").invoke(response);
            if (req == null) return null;
            for (String n : new String[]{"getUrl", "getUrlString", "getOriginalUrl"}) {
                try {
                    Object v = req.getClass().getMethod(n).invoke(req);
                    if (v instanceof String) return (String) v;
                } catch (Throwable ignored) { }
            }
        } catch (Throwable ignored) { }
        return null;
    }

    private static String shorten(String url) {
        if (url == null) return "null";
        int q = url.indexOf('?');
        String base = q < 0 ? url : url.substring(0, q);
        return base.length() > 80 ? base.substring(0, 80) + "…" : base;
    }

    private static String clamp(String s) {
        return s.length() <= LOG_MAX ? s : s.substring(0, LOG_MAX) + "…";
    }
}
