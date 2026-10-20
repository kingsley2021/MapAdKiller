package io.github.ldxm666.mapadkiller;

import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Version 17 protocol adapter: fixed paths and stable identifiers, never display text. */
public final class AmapData {
    public interface Settings {
        boolean enabled(String key);
        default boolean selected(String key) { return enabled(key); }
    }
    public static final Settings CURRENT = new Settings() {
        @Override public boolean enabled(String key) {
            // 热键走 volatile 读（零 map 查、零时间比较）；其余键保持原样。
            Config.Flag hot = Config.hotFlag(key);
            return hot != null ? hot.on() : Config.visible(key);
        }
        @Override public boolean selected(String key) {
            Config.Flag hot = Config.hotFlag(key);
            return hot != null ? hot.on() : Config.selected(key);
        }
    };
    public static final String[][] TOOLS = {
        {"102", "驾车"}, {"103", "公交地铁"}, {"168", "租车"}, {"106", "打车"},
        {"317", "订酒店"}, {"327", "火车票"}, {"469", "顺风车"}, {"483", "高德扫街"},
        {"461", "高德出行节"}, {"362", "代驾"}, {"418", "秒送"}, {"151", "实时公交"},
        {"380", "旅游度假"}, {"116", "车主服务"}, {"113", "景点游玩"}, {"115", "离线地图"},
        {"104", "步行"}, {"105", "骑行"}, {"86", "更多工具"}
    };

    /**
     * How many tool cells the home toolbar renders.
     *
     * <p>Measured from the installed build: {@code com.autonavi...toolbox.b.a} walks
     * {@code id0…id4} (its loop bound is 5) and lays the kept cells out in one row. Writing
     * more slots than this is harmless but invisible, so the payload is written for exactly
     * this many cells.
     */
    public static final int HOME_SLOTS = 5;

    /** The app's fixed 「更多工具」 entry; it lives in the slot list like any other tool. */
    public static final String MORE_TOOLS = "86";

    private static final String[] SLOT_FIELDS = {
        "id", "schema", "name", "size", "containerClass", "isLottie", "displayLottie",
        "displayImage", "imageUrlRender", "lottieUrlRender", "imageClass", "itemDataForClick",
        "display", "imageToken", "moreItem", "label", "tips", "redDot", "badge",
        "imageLegacy", "icon", "weight", "cid", "red_dot_key"
    };
    private AmapData() {}

    public static boolean toolVisible(String id, Settings s) {
        boolean visible = toolVisibleRaw(id, s);
        reportTool(id, visible);
        return visible;
    }

    private static boolean toolVisibleRaw(String id, Settings s) {
        if (!s.enabled(Config.K_TOOLS_VISIBLE)) return false;
        boolean allowlist = s.enabled(Config.K_TOOLS_ALLOWLIST);
        String key = AmapToolCatalog.key(id);
        return key != null && (allowlist ? s.selected(key) : s.enabled(key));
    }

    // ── 节流诊断（每个 id 只打一次，总数封顶）────────────────────────────────────
    // 用途：定位「工具栏隐藏未生效」时，先在真机确认某个 id 到底有没有走这里。
    // 判读：
    //   · 日志里**没有** id=317/116  ⇒ 这条 UI 不经过 toolVisible（走的是别的构建器）；
    //   · 有且 visible=false         ⇒ 过滤算对了，是构建器在过滤后把格子又加回来了；
    //   · 有且 visible=true          ⇒ 配置/默认值判定与预期不符。
    // 封顶 60 条 + 每个 id 一次，首页反复重建也不会刷屏。
    private static final java.util.Set<String> TOOL_REPORTED =
            java.util.Collections.synchronizedSet(new java.util.HashSet<String>());
    private static final int TOOL_REPORT_MAX = 60;

    private static void reportTool(String id, boolean visible) {
        try {
            if (id == null || id.length() == 0) return;
            if (TOOL_REPORTED.size() >= TOOL_REPORT_MAX) return;
            if (!TOOL_REPORTED.add(id)) return;
            H.log("event=amap_tool_visible id=" + id
                    + " label=" + toolLabel(id)
                    + " key=" + AmapToolCatalog.key(id)
                    + " visible=" + visible);
        } catch (Throwable ignored) {
        }
    }

    /** id → 中文标题；查不到就回 "?"（不猜、不抛）。 */
    private static String toolLabel(String id) {
        try {
            for (String[] entry : AmapToolCatalog.ENTRIES) {
                if (id.equals(entry[0])) return entry[1];
            }
            for (String[] entry : TOOLS) {
                if (id.equals(entry[0])) return entry[1];
            }
        } catch (Throwable ignored) {
        }
        return "?";
    }

    /**
     * 把一段「工具面板 payload 字符串」过滤后返回（字符串进出，供 hook {@code k84.f()} 使用）。
     *
     * <h3>为什么需要这个入口</h3>
     * 17.02 实测（classes.dex / classes5.dex）：{@code k84.f()Ljava/lang/String;} 被 **5 处**调用：
     * <pre>
     *   quickservice/toolbox/a.a(...)        ← QS 面板工厂**内部兜底**（入参没有 id0 时）
     *   ToolboxPanelV2.init(...)             ← 兜底
     *   ToolboxPanelV3.init(...)             ← 兜底
     *   BootBizDataPreloaderImpl.getToolboxData()  ← 它自己也是用 k84.f() 建缓存字段 g
     *   BootBizDataPreloaderImpl.prepare(...)
     * </pre>
     * 也就是说：只要走到任何一条兜底，payload 就**直接来自 {@code k84.f()}**，
     * 完全不经过 {@code getToolboxData} / {@code AjxModuleLocalStorage} 上的 hook ⇒
     * 这正是「过滤逻辑没错、工具却照样显示」的根因。所以必须在**最上游**也拦一道。
     *
     * <p>参数 {@code fill} 恒为 false：{@code k84.f()} 是 App 自己的默认清单，
     * 往里"发明槽位"会污染它的默认数据。
     */
    public static String toolboxRaw(String raw, Settings s) {
        if (raw == null || raw.length() == 0 || raw.length() > 4 * 1024 * 1024) return raw;
        try {
            JSONObject source = new JSONObject(raw);
            JSONObject out = toolbox(source, s, false);
            return out == source ? raw : out.toString();
        } catch (Throwable t) {
            return raw;   // 解析不了就原样放行，绝不因为诊断/过滤把面板搞崩
        }
    }

    public static boolean toolSelected(String id, Settings s) {
        String key = AmapToolCatalog.key(id);
        return key != null && s.selected(key);
    }

    public static String storage(String key, String raw, Settings s) throws Exception {
        if (raw == null || raw.length() == 0) return raw;
        if ("local_tool_list_for_xml_v2".equals(key)) return toolbox(new JSONObject(raw), s).toString();
        if (!"recommendTools".equals(key) && !"aosRecommendTools".equals(key)
                && !"choiceTools".equals(key)) return raw;
        JSONObject out = new JSONObject(raw);
        String listKey = "choiceTools".equals(key) ? "choiceTools" : "recommendTools";
        JSONArray source = out.optJSONArray(listKey);
        if (source == null) return raw;
        JSONArray kept = new JSONArray();
        for (int i = 0; i < source.length(); i++) {
            JSONObject tool = source.optJSONObject(i);
            if (tool == null || toolVisible(tool.optString("id"), s)) kept.put(source.opt(i));
        }
        if (kept.length() == source.length()) return raw;
        out.put(listKey, kept);
        return out.toString();
    }

    /** Filter-only form: keep the app's own slots, drop the ones switched off. */
    public static JSONObject toolbox(JSONObject source, Settings s) throws Exception {
        return toolbox(source, s, false);
    }

    /**
     * Rewrite the home toolbar's slot payload.
     *
     * <h3>Why this is more than a filter</h3>
     * The app does not build the toolbar from a list of enabled tools: it renders whatever
     * slots its own {@code local_tool_list_for_xml_v2} happens to contain, and it rewrites
     * that list itself from the 「更多工具」 page. Filtering alone therefore only ever
     * <i>removes</i> cells -- switching 步行 or 骑行 on did nothing, because those ids were
     * never in the list. On this build the list held exactly two entries
     * ({@code 103 公交地铁} and {@code 86 更多工具}), so 「打开了步行骑行但 UI 没有」 was
     * structural, not a rendering glitch.
     *
     * <p>The payload is now reconciled instead: the app's own order is preserved for the
     * tools it placed, and enabled catalogue tools that are missing fill the remaining cells.
     *
     * @param fill only the boot payload is filled. {@code local_tool_list_for_xml_v2} is the
     *             app's own store and is read back by the 「更多工具」 page, so invented slots
     *             are never written into it.
     */
    public static JSONObject toolbox(JSONObject source, Settings s, boolean fill) throws Exception {
        List<Integer> kept = new ArrayList<Integer>();
        int count = 0;
        for (; count < 10 && source.has("id" + count); count++) {
            String id = source.optString("id" + count);
            if (id.length() > 0 && toolVisible(id, s)) kept.add(count);
        }

        List<String[]> extra = new ArrayList<String[]>();
        if (fill) {
            java.util.Set<String> present = new java.util.HashSet<String>();
            for (int index : kept) present.add(source.optString("id" + index));
            for (String[] entry : AmapToolCatalog.ENTRIES) {
                if (kept.size() + extra.size() >= HOME_SLOTS) break;
                if (present.contains(entry[0]) || !toolVisible(entry[0], s)) continue;
                extra.add(entry);
                present.add(entry[0]);
            }
        }
        if (count == kept.size() && extra.isEmpty()) return source;

        JSONObject out = new JSONObject(source.toString());
        int cells = Math.max(count, HOME_SLOTS);
        for (int i = 0; i < cells; i++) {
            for (String field : SLOT_FIELDS) out.remove(field + i);
            if (i < kept.size()) copySlot(source, out, kept.get(i), i);
            else if (i - kept.size() < extra.size()) writeSlot(out, extra.get(i - kept.size()), i);
            else blankSlot(out, i);
        }
        if (kept.isEmpty() && extra.isEmpty()) out.put("king_switch", "0");
        return out;
    }

    /** The tool ids of the first {@link #HOME_SLOTS} cells, for one-line logcat evidence. */
    public static String slotIds(JSONObject payload) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < HOME_SLOTS; i++) {
            String id = payload == null ? "" : payload.optString("id" + i);
            if (out.length() > 0) out.append(',');
            out.append(id.length() == 0 ? "-" : id);
        }
        return out.toString();
    }

    /** Move one of the app's own slots, action and icon included. */
    private static void copySlot(JSONObject from, JSONObject to, int source, int target) throws Exception {
        for (String field : SLOT_FIELDS) {
            String key = field + source;
            if (from.has(key)) to.put(field + target, from.opt(key));
        }
        if (to.optString("id" + target).length() == 0) {
            blankSlot(to, target);
            return;
        }
        to.put("display" + target, "flex");
        if (!to.has("size" + target)) to.put("size" + target, "90px");
    }

    /**
     * Build a slot for a tool the app did not place on the toolbar.
     *
     * <p>Every field comes from {@link AmapToolCatalog}, which is extracted from this build's
     * own tool directory -- no id, name, route or icon is invented. The click payload matches
     * exactly what the app's own click handler parses
     * ({@code com.autonavi...toolbox.v3.f.a}: {@code Uri.decode} then {@code optString("schema")};
     * the optional {@code ut} block is only used for analytics and its absence is handled).
     */
    private static void writeSlot(JSONObject to, String[] entry, int target) throws Exception {
        JSONObject item = AmapToolCatalog.item(entry);
        String id = item.optString("id");
        String schema = item.optString("schema");
        String icon = item.optString("iconV2");
        to.put("id" + target, id);
        to.put("name" + target, item.optString("name"));
        to.put("schema" + target, schema);
        to.put("size" + target, "90px");
        to.put("containerClass" + target, "");
        to.put("isLottie" + target, false);
        to.put("displayLottie" + target, "none");
        to.put("displayImage" + target, "flex");
        to.put("lottieUrlRender" + target, "");
        to.put("display" + target, "flex");
        to.put("moreItem" + target, MORE_TOOLS.equals(id));
        if (icon.startsWith("@")) to.put("imageToken" + target, icon.substring(1));
        else to.put("imageUrlRender" + target, icon);
        // Uri.encode, not URLEncoder: the app decodes with Uri.decode, which does not turn
        // "+" back into a space, and several tool routes contain one.
        to.put("itemDataForClick" + target, android.net.Uri.encode(
                new JSONObject().put("schema", schema).toString()));
    }

    /** A cell the renderer skips: it walks {@code id<i>} and ignores any cell sized 0. */
    private static void blankSlot(JSONObject to, int index) throws Exception {
        to.put("id" + index, "");
        to.put("size" + index, "0px");
        to.put("schema" + index, "");
        to.put("name" + index, "");
        to.put("display" + index, "none");
        to.put("displayImage" + index, "none");
        to.put("displayLottie" + index, "none");
        to.put("itemDataForClick" + index, "");
    }

    /**
     * Card names the home recommendation canvas is allowed to emit. Anything that
     * reaches the canvas while its switch is off is dropped here as a second line of
     * defence, so a single toggle can never release the whole feed again.
     *
     * Card names arrive as camelCase or snake_case depending on which emitter produced
     * them; the installed build reads cardName, card_name, bizName, biz_name and name.
     * Normalising here means one switch covers every spelling.
     */
    private static String normalize(String name) {
        if (name == null) return "";
        StringBuilder out = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '_' || c == '-' || c == ' ') continue;
            out.append(Character.toLowerCase(c));
        }
        return out.toString();
    }

    /**
     * Resolve a card's identity the same way the installed build does.
     *
     * The app's own helper (gu2.A) walks the tree with five key fallbacks
     * (cardName / card_name / bizName / biz_name / name). The previous version read
     * only component.extra_info.cardName, so any card using another spelling was never
     * recognised and was therefore never filtered.
     */
    public static String cardName(JSONObject source) {
        if (source == null) return "";
        JSONObject component = source.optJSONObject("component");
        String name = firstNonEmpty(component);
        if (name.length() == 0) name = firstNonEmpty(source);
        if (name.length() == 0) {
            JSONObject extra = component == null ? null : component.optJSONObject("extra_info");
            if (extra == null && component != null) extra = component.optJSONObject("extraInfo");
            name = firstNonEmpty(extra);
        }
        return name;
    }

    private static String firstNonEmpty(JSONObject o) {
        if (o == null) return "";
        for (String key : new String[]{"cardName", "card_name", "bizName", "biz_name", "name"}) {
            String value = o.optString(key);
            if (value.length() > 0 && !"null".equals(value)) return value;
        }
        return "";
    }

    public static boolean cardVisible(String name, Settings s) {
        String key = normalize(name);
        if (key.length() == 0) return true;
        if ("tipsranking".equals(key)) return s.enabled(Config.K_FEED_BOARD);
        if ("hourlyweather".equals(key) || "weatherforecast".equals(key))
            return s.enabled(Config.K_FEED_WEATHER);
        if ("frequentlocations".equals(key) || "frequentlocation".equals(key))
            return s.enabled(Config.K_HOME_CHIPS);
        if ("contentcenterfeed".equals(key)) return s.enabled(Config.K_FEED_CONTENT);
        if ("taxiqs".equals(key) || "taxiqsmarket".equals(key))
            return !s.enabled(Config.K_RIDE_CARD_OFF);
        return true;
    }

    /** Null drops only nonterminal cards; completion envelopes always reach the page. */
    public static JSONObject stream(JSONObject source, Settings s) throws Exception {
        JSONObject component = source.optJSONObject("component");
        if (component == null) return source;
        String name = cardName(source);
        boolean hide = !cardVisible(name, s);
        boolean terminal = source.optBoolean("is_done") || source.optBoolean("isDone");
        if (hide && !terminal) return null;
        boolean stopPaging = terminal && !s.enabled(Config.K_FEED_BOARD) && !s.enabled(Config.K_FEED_CONTENT);
        if (!hide && !stopPaging) return source;
        JSONObject out = new JSONObject(source.toString());
        if (hide) {
            JSONObject empty = new JSONObject();
            empty.put("type", "markdown"); empty.put("text", ""); empty.put("is_done", true);
            out.put("component", empty);
        }
        if (stopPaging) {
            // Writers disagree on the spelling; set both so whichever the reader uses is honoured.
            out.put("has_more", false);
            out.put("hasMore", false);
        }
        return out;
    }

    public static String requestPath(String url) {
        if (url == null) return "";
        int begin = url.indexOf("/ws/");
        if (begin < 0) return "";
        int end = url.indexOf('?', begin);
        return end < 0 ? url.substring(begin) : url.substring(begin, end);
    }
    public static boolean handles(String path) {
        return "/ws/promote/main-page/assets".equals(path)
            || "/ws/shield/dsp/profile/index/nodefaasv3".equals(path);
    }

    public static String response(String path, String raw, Settings s) throws Exception {
        if (raw == null || raw.length() == 0) return raw;
        JSONObject out = new JSONObject(raw);
        if ("/ws/promote/main-page/assets".equals(path)) {
            if (s.enabled(Config.K_FEED_FESTIVAL) || !out.has("data")) return raw;
            Object data = out.opt("data");
            if (data instanceof JSONArray) out.put("data", new JSONArray());
            else if (data instanceof JSONObject) out.put("data", new JSONObject());
            else return raw;
            return out.toString();
        }
        if (!"/ws/shield/dsp/profile/index/nodefaasv3".equals(path)) return raw;
        JSONObject data = out.optJSONObject("data");
        JSONArray cards = data == null ? null : data.optJSONArray("cardList");
        if (cards == null) return raw;
        JSONArray kept = new JSONArray();
        for (int i = 0; i < cards.length(); i++) {
            JSONObject card = cards.optJSONObject(i);
            if (card == null) { kept.put(cards.opt(i)); continue; }
            String type = card.optString("dataType");
            if (("MineRowSlideCard".equals(type) || "UserCircleCardV3".equals(type))
                    && !s.enabled(Config.K_MY_FRIENDS)) continue;
            if ("MineMemberRecommendTaskCardV2".equals(type) && !s.enabled(Config.K_MY_TASK)) continue;
            if (("PopularActivitiesCardV4".equals(type) || "PopularActivitiesCardV5".equals(type))
                    && !s.enabled(Config.K_MY_PROMO_ROW)) continue;
            if ("ContentCenterFeedCard".equals(type) && !s.enabled(Config.K_MY_GUESS)) continue;
            if ("MineNewBEntranceCardV3".equals(type) || "MineNewBEntranceCardV4".equals(type)) {
                JSONObject content = card.optJSONObject("content");
                if (content != null) {
                    if (!s.enabled(Config.K_MY_ORDER_ROW)) content.put("entranceList_1", new JSONArray());
                    if (!s.enabled(Config.K_MY_SERVICE_ROW)) content.put("entranceList_2", new JSONArray());
                    JSONArray one = content.optJSONArray("entranceList_1");
                    JSONArray two = content.optJSONArray("entranceList_2");
                    if (one != null && two != null && one.length() == 0 && two.length() == 0) continue;
                }
            }
            kept.put(card);
        }
        data.put("cardList", kept);
        return out.toString();
    }
}
