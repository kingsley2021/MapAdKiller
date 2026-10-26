package io.github.ldxm666.mapclean;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import android.app.Activity;
import android.content.Intent;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

/** Versioned user settings only. Preference groups keep identically named keys separate. */
public final class ConfigBackup {
    public static final String AMAP = "amap_enhancer_config", BAIDU = "bmapclean_config", UI = "mak_ui";
    public static final String LEARNED = "sdk_learned";
    public interface Store {
        Map<String, ?> read(String group);
        /** A null value removes the key, preserving an unset/default preference. */
        boolean write(String group, Map<String, Object> values);
    }
    private ConfigBackup() {}

    public static JSONObject encode(Store store, Map<String, Set<String>> schema, String app) throws Exception {
        JSONObject groups = new JSONObject();
        for (Map.Entry<String, Set<String>> group : schema.entrySet()) {
            Map<String, ?> stored = store.read(group.getKey());
            JSONObject values = new JSONObject();
            for (String key : group.getValue()) {
                Object value = stored.get(key);
                if (value instanceof Set && LEARNED.equals(key)) values.put(key, new JSONArray((Set<?>) value));
                else if (value == null || value instanceof Boolean) values.put(key, value == null ? JSONObject.NULL : value);
                else throw new IllegalArgumentException("配置类型错误：" + key);
            }
            groups.put(group.getKey(), values);
        }
        return new JSONObject().put("_app", app).put("_ver", 2).put("groups", groups);
    }

    public static Map<String, Map<String, Object>> decode(JSONObject backup, Map<String, Set<String>> schema) throws Exception {
        Map<String, Map<String, Object>> result = new LinkedHashMap<String, Map<String, Object>>();
        if (backup.has("groups")) {
            if (backup.optInt("_ver", 0) != 2) throw new IllegalArgumentException("不支持的配置版本");
            JSONObject groups = backup.getJSONObject("groups");
            for (Map.Entry<String, Set<String>> group : schema.entrySet()) {
                if (!groups.has(group.getKey())) continue;
                Map<String, Object> values = decodeGroup(groups.getJSONObject(group.getKey()), group.getValue());
                if (!values.isEmpty()) result.put(group.getKey(), values);
            }
        } else {
            // Legacy flat exports contain AMap settings only; never change Baidu on import.
            Map<String, Object> values = decodeGroup(backup, schema.get(AMAP));
            if (!values.isEmpty()) result.put(AMAP, values);
        }
        if (result.isEmpty()) throw new IllegalArgumentException("没有可识别的配置项");
        return result;
    }

    private static Map<String, Object> decodeGroup(JSONObject object, Set<String> allowed) throws Exception {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        if (allowed == null) return result;
        for (String key : allowed) {
            if (!object.has(key)) continue;
            Object value = object.get(key);
            if (value == JSONObject.NULL) value = null;
            else if (value instanceof JSONArray && LEARNED.equals(key)) {
                JSONArray list = (JSONArray) value;
                Set<String> roots = new LinkedHashSet<String>();
                for (int i = 0; i < list.length(); i++) {
                    Object item = list.get(i);
                    if (!(item instanceof String)) throw new IllegalArgumentException("SDK 清单类型错误");
                    roots.add((String) item);
                }
                value = roots;
            } else if (!(value instanceof Boolean)) throw new IllegalArgumentException("开关类型错误：" + key);
            result.put(key, value);
        }
        return result;
    }

    public static int apply(Store store, Map<String, Map<String, Object>> groups) {
        Map<String, Map<String, Object>> before = new LinkedHashMap<String, Map<String, Object>>();
        for (Map.Entry<String, Map<String, Object>> group : groups.entrySet()) {
            Map<String, ?> current = store.read(group.getKey());
            Map<String, Object> values = new LinkedHashMap<String, Object>();
            for (String key : group.getValue().keySet()) values.put(key, current.get(key));
            before.put(group.getKey(), values);
        }
        List<String> attempted = new ArrayList<String>();
        int count = 0;
        try {
            for (Map.Entry<String, Map<String, Object>> group : groups.entrySet()) {
                attempted.add(group.getKey());
                if (!store.write(group.getKey(), group.getValue())) throw new IllegalStateException("配置写入失败");
                count += group.getValue().size();
            }
            return count;
        } catch (RuntimeException failure) {
            boolean restored = true;
            for (int i = attempted.size() - 1; i >= 0; i--) {
                String group = attempted.get(i);
                try { restored &= store.write(group, before.get(group)); }
                catch (RuntimeException ignored) { restored = false; }
            }
            throw new IllegalStateException(restored ? "恢复失败，已保留原配置" : "恢复失败，部分配置回退失败，请重新导入", failure);
        }
    }
    // ══════════════════════════════════════════════════════════════════════════
    // 文件导入 / 导出（SAF：ACTION_CREATE_DOCUMENT / ACTION_OPEN_DOCUMENT）
    // ══════════════════════════════════════════════════════════════════════════
    //
    // 为什么用 SAF 而不是直接写路径：免 root 内置版没有存储权限，也不该申请；
    // SAF 让用户自己选位置，系统把流交给我们 —— 零权限、零路径猜测。
    //
    // 旧格式兼容：encode() 产出的就是 {"_app":..,"_ver":2,"groups":{..}}，
    // 所以**历史上复制粘贴出去的文本本身就是合法 JSON**，可以直接当文件导入。
    // 另外额外接受 {"app":..,"version":..,"values":{..}} 这种扁平形态（见 decodeAny）。

    /** requestCode：导出 / 导入。 */
    public static final int REQ_EXPORT = 0x4A01;
    public static final int REQ_IMPORT = 0x4A02;
    /** 超过这个大小直接拒绝（防误选大文件）。 */
    public static final long MAX_BYTES = 256 * 1024;

    /** 由设置页在启动时绑定一次（Store/schema/app 都在那边）。 */
    private static Store boundStore;
    private static Map<String, Set<String>> boundSchema;
    private static String boundApp = "MapAdKiller";

    /** 导入预览（解析成功但尚未写入）。 */
    private static Map<String, Map<String, Object>> pending;
    private static String pendingUnknown = "";
    private static volatile String lastError;

    public static void bind(Store store, Map<String, Set<String>> schema, String app) {
        boundStore = store;
        boundSchema = schema;
        if (app != null && app.length() > 0) boundApp = app;
    }

    /** 导出：弹系统「保存到…」对话框。 */
    public static void startExport(Activity a, int reqCode) {
        try {
            String stamp = new java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US)
                    .format(new java.util.Date());
            Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("application/json");
            i.putExtra(Intent.EXTRA_TITLE, "MapAdKiller-" + stamp + ".json");
            a.startActivityForResult(i, reqCode);
        } catch (Throwable t) {
            lastError = "无法打开保存对话框：" + t.getClass().getSimpleName();
        }
    }

    /** 导入：弹系统「选择文件」对话框。 */
    public static void startImport(Activity a, int reqCode) {
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            // 有些文件管理器给 .json 的 MIME 是 application/octet-stream，
            // 用 */* + 内容校验更稳。
            i.setType("*/*");
            a.startActivityForResult(i, reqCode);
        } catch (Throwable t) {
            lastError = "无法打开选择文件对话框：" + t.getClass().getSimpleName();
        }
    }

    /** 取走上一次 start* 的失败原因（没有就返回 null）。 */
    public static String takeError() {
        String e = lastError;
        lastError = null;
        return e;
    }

    /** 处理导出结果。返回**给用户看的文案**。 */
    public static String handleExportResult(Activity a, int reqCode, int resultCode, Intent data) {
        try {
            if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
                return "已取消导出";
            }
            if (boundStore == null || boundSchema == null) return "导出失败：设置页未初始化";
            byte[] out = encode(boundStore, boundSchema, boundApp).toString(2).getBytes("UTF-8");
            java.io.OutputStream os = a.getContentResolver().openOutputStream(data.getData());
            if (os == null) return "导出失败：无法写入所选位置";
            try {
                os.write(out);
                os.flush();
            } finally {
                try { os.close(); } catch (Throwable ignored) { }
            }
            String name = "";
            try { name = data.getData().getLastPathSegment(); } catch (Throwable ignored) { }
            int total = 0;
            for (Map.Entry<String, Set<String>> group : boundSchema.entrySet()) total += group.getValue().size();
            return "已导出 " + (name == null || name.length() == 0 ? "配置文件" : name)
                    + "（" + total + " 项，" + out.length + " 字节）";
        } catch (Throwable t) {
            return "导出失败：" + reason(t);
        }
    }

    /**
     * 处理导入结果：**只解析 + 预览，不写入**。
     * 用户确认后调用 {@link #confirmImport()} 才真正写。
     */
    public static String handleImportResult(Activity a, int reqCode, int resultCode, Intent data) {
        pending = null;
        pendingUnknown = "";
        try {
            if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
                return "已取消导入";
            }
            byte[] raw = readAll(a, data.getData());
            if (raw == null) return "导入失败：无法读取文件（或超过 " + (MAX_BYTES / 1024) + " KB）";
            String text = new String(raw, "UTF-8").trim();
            if (text.length() == 0) return "导入失败：文件是空的";
            if (text.charAt(0) != '{') return "导入失败：不是 JSON（文件开头不是 { ）";

            JSONObject root;
            try {
                root = new JSONObject(text);
            } catch (Throwable t) {
                return "导入失败：不是合法 JSON（" + reason(t) + "）";
            }
            if (boundSchema == null) return "导入失败：设置页未初始化";

            Map<String, Map<String, Object>> groups = decodeAny(root, boundSchema);
            if (groups == null || groups.isEmpty()) {
                return "导入失败：缺少 values/groups 字段（这个文件不是本模块导出的）";
            }
            pending = groups;

            int known = 0, unknown = 0;
            StringBuilder uk = new StringBuilder();
            for (Map.Entry<String, Map<String, Object>> g : groups.entrySet()) {
                Set<String> allowed = boundSchema.get(g.getKey());
                for (String k : g.getValue().keySet()) {
                    if (allowed != null && allowed.contains(k)) {
                        known++;
                    } else {
                        unknown++;
                        if (uk.length() < 200) uk.append(uk.length() == 0 ? "" : ", ").append(k);
                    }
                }
            }
            pendingUnknown = unknown == 0 ? "" : "；忽略未知键 " + unknown + " 项（" + uk + "）";
            return "解析成功：可导入 " + known + " 项" + pendingUnknown + "。确认后会覆盖同名配置。";
        } catch (Throwable t) {
            pending = null;
            return "导入失败：" + reason(t);
        }
    }

    /** 有没有待确认的导入。 */
    public static boolean hasPendingImport() { return pending != null; }

    /** 待确认导入的分组明细，例如「高德 42 项 · 百度 18 项」。 */
    public static String pendingSummary() {
        if (pending == null) return "";
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Map<String, Object>> group : pending.entrySet()) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(label(group.getKey())).append(' ').append(group.getValue().size()).append(" 项");
        }
        return sb.toString();
    }

    private static String label(String group) {
        if (AMAP.equals(group)) return "高德";
        if (BAIDU.equals(group)) return "百度";
        if (UI.equals(group)) return "界面";
        return group;
    }

    /** 用户确认后真正写入；返回文案。 */
    public static String confirmImport() {
        if (pending == null) return "没有待导入的配置";
        if (boundStore == null) return "导入失败：设置页未初始化";
        try {
            String detail = pendingSummary();
            int n = apply(boundStore, pending);
            String extra = pendingUnknown;
            pending = null;
            pendingUnknown = "";
            return "已导入 " + n + " 项" + (detail.length() == 0 ? "" : "（" + detail + "）") + extra;
        } catch (Throwable t) {
            pending = null;
            return "导入失败：" + reason(t);
        }
    }

    // ── 内部工具 ────────────────────────────────────────────────────────────

    private static byte[] readAll(Activity a, Uri uri) {
        try {
            java.io.InputStream in = a.getContentResolver().openInputStream(uri);
            if (in == null) return null;
            try {
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                long total = 0;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > MAX_BYTES) return null;   // 超限直接放弃
                    bos.write(buf, 0, n);
                }
                return bos.toByteArray();
            } finally {
                try { in.close(); } catch (Throwable ignored) { }
            }
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 接受两种形态：
     * <ol>
     *   <li>本模块的 {"_app":..,"_ver":2,"groups":{..}}（encode 产出，也是旧版复制粘贴的文本）；</li>
     *   <li>扁平形态 {"app":..,"version":..,"values":{..}} —— 归到 {@link #AMAP} 组。</li>
     * </ol>
     */
    private static Map<String, Map<String, Object>> decodeAny(JSONObject root,
                                                              Map<String, Set<String>> schema) throws Exception {
        if (root.has("groups")) return decode(root, schema);
        if (root.has("values")) {
            JSONObject groups = new JSONObject();
            groups.put(AMAP, root.getJSONObject("values"));
            return decode(new JSONObject().put("_app", boundApp).put("_ver", 2).put("groups", groups), schema);
        }
        return null;
    }

    private static String reason(Throwable t) {
        if (t == null) return "未知错误";
        String m = t.getMessage();
        return (m == null || m.length() == 0) ? t.getClass().getSimpleName() : m;
    }
}
