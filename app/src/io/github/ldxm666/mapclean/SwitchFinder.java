package io.github.ldxm666.mapclean;

import android.content.Context;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 设置页搜索：一条横向卡片，输入即跨分类过滤所有开关。
 *
 * <h3>为什么不是简单 filter(List)</h3>
 * 设置页的开关分散在三个配置 group（高德 / 百度 / 腾讯）和若干折叠抽屉里，
 * 而且腾讯的工具目录有 90 多项。让每个页面各自实现一遍过滤等于写四份，
 * 还会有四份不同的匹配规则。这里抽出一条公共契约：
 *
 * <pre>
 *   Finder.of(page).index(...)   —— 页面把"这一页有哪些开关"报上来（一次性）
 *   Finder.of(page).query(...)   —— 用户输入
 * </pre>
 *
 * <h3>索引怎么建</h3>
 * 复用开关工厂已有的 {@code key → GlassSwitch} Map（{@code switches} /
 * {@code bmapSwitches} / {@code tmapSwitches}）与"键名 → 人类可读标签"的映射。
 * 标签由开关工厂在建行时回写，所以中英文、工具 ID、备注文字都能被搜到，
 * 而且永不与界面显示的文字脱节。
 *
 * <h3>匹配规则</h3>
 * 大小写不敏感，对"标题 + 备注 + 配置键"三段拼接后的子串匹配。
 * 这样「步行」「walk」「tool_walk」「ui_tool_grid」四种敲法都能命中同一个开关。
 */
final class SwitchFinder {

    /** 一个可被搜到的开关。 */
    static final class Entry {
        final String key;
        final String title;
        final String note;
        /** 该开关属于哪一页（0 百度 / 1 高德 / 2 腾讯 / 3 其他）。 */
        final int page;

        Entry(String key, String title, String note, int page) {
            this.key = key;
            this.title = title == null ? "" : title;
            this.note = note == null ? "" : note;
            this.page = page;
        }

        String haystack() {
            return (title + "\n" + note + "\n" + key).toLowerCase(Locale.ROOT);
        }
    }

    /** 目标页面的契约：给它一个查询，它自己决定显示/隐藏哪些行。 */
    interface Target {
        /** @return true = 这次查询由本页处理（用于短路，避免每页都算一遍） */
        boolean applyQuery(String query);

        /** 查询被清空时调用，恢复该页原本的展开/折叠状态。 */
        void resetQuery();
    }

    private final Context context;
    private final boolean dark;

    /** 每页的开关索引；页下标与 pager.addView 的顺序一致。 */
    private final List<List<Entry>> index = new ArrayList<List<Entry>>();
    private final List<Target> targets = new ArrayList<Target>();

    private final LinearLayout bar;
    private final EditText field;
    private final TextView clear;
    private final TextView count;
    private String query = "";

    /** 命中结果面板：跨页列出所有匹配项，点一下跳过去并展开所在抽屉。 */
    private final LinearLayout results;
    private final View resultsHost;

    private Runnable onNavigate;

    SwitchFinder(Context c, boolean darkTheme) {
        this.context = c;
        this.dark = darkTheme;

        bar = new LinearLayout(c);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackground(UiKit.plate(dark ? 0x1AFFFFFF : 0x0F000000, UiKit.dpf(c, 14f)));
        bar.setPadding(UiKit.dp(c, 12), 0, UiKit.dp(c, 8), 0);

        TextView glyph = new TextView(c);
        glyph.setText("⌕");
        glyph.setTextSize(16f);
        glyph.setTextColor(cTxS());
        bar.addView(glyph);

        field = new EditText(c);
        field.setHint("搜索开关 / 工具名 / 配置键");
        field.setHintTextColor(cTxS());
        field.setTextColor(cTxP());
        field.setTextSize(14f);
        field.setSingleLine(true);
        field.setBackground(null);
        field.setPadding(UiKit.dp(c, 8), 0, UiKit.dp(c, 4), 0);
        // 设置页底下就是 pager，软键盘弹起会把它顶得很难看；回车即收起。
        field.setImeOptions(EditorInfo.IME_ACTION_SEARCH | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        field.setInputType(EditorInfo.TYPE_CLASS_TEXT);
        field.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                setQuery(s == null ? "" : s.toString());
            }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
        });
        bar.addView(field, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));

        count = new TextView(c);
        count.setTextSize(11.5f);
        count.setTextColor(cTxS());
        count.setVisibility(View.GONE);
        bar.addView(count);

        clear = new TextView(c);
        clear.setText("✕");
        clear.setTextSize(13f);
        clear.setTextColor(cTxS());
        clear.setGravity(Gravity.CENTER);
        clear.setContentDescription("清除搜索");
        clear.setVisibility(View.GONE);
        clear.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { field.setText(""); }
        });
        bar.addView(clear, new LinearLayout.LayoutParams(
                UiKit.dp(c, 30), LinearLayout.LayoutParams.MATCH_PARENT));

        results = new LinearLayout(c);
        results.setOrientation(LinearLayout.VERTICAL);
        results.setVisibility(View.GONE);

        HorizontalScrollView hs = new HorizontalScrollView(c);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setClipToPadding(false);
        hs.addView(results, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        resultsHost = hs;
    }

    // ── 配色（与 MainActivity 同一套 Liquid Glass 色板，避免为了一条搜索框再建一个 palette）──
    private int cTxP()   { return dark ? 0xFFF2F4F8 : 0xFF171B23; }
    private int cTxS()   { return dark ? 0xFF98A0AC : 0xFF7A8290; }
    private int cAccent(){ return dark ? 0xFF7AB1FF : 0xFF0A6CF5; }
    private int cRipple(){ return dark ? 0x267AB1FF : 0x140A6CF5; }

    /** 注册一页；page 下标必须与 pager 子视图顺序一致。 */
    void register(int page, Target target) {
        while (index.size() <= page) {
            index.add(new ArrayList<Entry>());
            targets.add(null);
        }
        index.set(page, new ArrayList<Entry>());
        targets.set(page, target);
    }

    /** 报一个开关上来。重复 key 只保留第一条（同名键在不同 group 里本就互斥）。 */
    void index(String key, String title, String note, int page) {
        if (key == null) return;
        if (page < 0 || page >= index.size()) return;
        List<Entry> list = index.get(page);
        for (int i = 0; i < list.size(); i++) {
            if (key.equals(list.get(i).key)) return;
        }
        list.add(new Entry(key, title, note, page));
    }

    LinearLayout bar() { return bar; }

    View resultsHost() { return resultsHost; }

    /** 页面切换后调用：让搜索框反映当前页可搜的范围。 */
    void attach(int page) {
        // 空页不显示搜索框（"其他"页只有两三项，搜了反而碍事）。
        boolean has = page >= 0 && page < index.size() && !index.get(page).isEmpty();
        bar.setVisibility(has ? View.VISIBLE : View.GONE);
        if (!has) setQuery("");
    }

    void setOnNavigate(Runnable r) { onNavigate = r; }

    private void setQuery(String value) {
        String next = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (next.equals(query)) return;
        query = next;
        boolean on = !query.isEmpty();
        clear.setVisibility(on ? View.VISIBLE : View.GONE);
        if (!on) count.setVisibility(View.GONE);
        int hits = 0;
        for (int p = 0; p < targets.size(); p++) {
            Target t = targets.get(p);
            if (t == null) continue;
            if (on) {
                hits += filterPage(p, query);
            } else {
                t.resetQuery();
            }
        }
        if (on) {
            count.setVisibility(View.VISIBLE);
            count.setText(hits + " 项");
            rebuildResults(hits);
        } else {
            results.removeAllViews();
            results.setVisibility(View.GONE);
        }
        if (onNavigate != null) onNavigate.run();
    }

    /**
     * 让一页自己过滤，并统计它的命中数。
     *
     * <p>命中统计走 {@link #index}（页面建行时报上来的那几段文字），
     * 实际显隐走页面挂在行上的 haystack tag。两条路用的是同一套
     * 「标题 + 备注 + 配置键」文本，所以计数与画面不会打架。
     */
    private int filterPage(int page, String q) {
        int n = 0;
        List<Entry> list = index.get(page);
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).haystack().contains(q)) n++;
        }
        Target t = targets.get(page);
        if (t != null) t.applyQuery(q);
        return n;
    }

    /** 跨页结果面板：横向滚动的胶囊，点一下跳到那一页。 */
    private void rebuildResults(int hits) {
        results.removeAllViews();
        if (hits == 0) {
            results.setVisibility(View.GONE);
            return;
        }
        results.setVisibility(View.VISIBLE);
        for (int p = 0; p < index.size(); p++) {
            List<Entry> list = index.get(p);
            int n = 0;
            for (int i = 0; i < list.size(); i++) {
                if (list.get(i).haystack().contains(query)) n++;
            }
            if (n == 0) continue;
            results.addView(chip(PAGE_LABEL[p] + " · " + n, p));
        }
    }

    private static final String[] PAGE_LABEL = {"百度", "高德", "腾讯", "其他"};

    private View chip(String label, final int page) {
        TextView c = new TextView(context);
        c.setText(label);
        c.setTextSize(12.5f);
        c.setGravity(Gravity.CENTER);
        c.setPadding(UiKit.dp(context, 13), UiKit.dp(context, 7),
                UiKit.dp(context, 13), UiKit.dp(context, 7));
        c.setTextColor(cAccent());
        c.setBackground(UiKit.rippleBare(cRipple(), UiKit.dpf(context, 16f)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        if (results.getChildCount() > 0) lp.setMarginStart(UiKit.dp(context, 6));
        c.setLayoutParams(lp);
        c.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (onNavigate != null) onNavigate.run();
                jumpToPage(page);
            }
        });
        UiKit.pressable(c, 0.95f);
        return c;
    }

    /** 由设置页在跳页后回调（Pager.setOnPageChanged 已经存在，直接复用）。 */
    interface PageJumper { void jump(int page); }

    private PageJumper jumper;

    void setPageJumper(PageJumper j) { jumper = j; }

    private void jumpToPage(int page) {
        if (jumper != null) jumper.jump(page);
    }

    /** 当前查询；供页面自己决定"这一行算不算命中"时用。 */
    String query() { return query; }

    boolean matches(Entry e) { return !query.isEmpty() && e.haystack().contains(query); }

    /** 切页 / 退出时收起键盘，否则键盘会留在下一页上面。 */
    void dismissKeyboard() {
        try {
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager) context.getSystemService(
                            Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(field.getWindowToken(), 0);
            field.clearFocus();
        } catch (Throwable ignored) { }
    }
}
