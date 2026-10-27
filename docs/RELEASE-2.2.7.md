# MapAdKiller / MapClean 2.2.7（versionCode 2270）

发布日期：2026-10-27。版本码：2270。基线 2.2.6（版本码 2260）。

本轮重点：**百度地图 22.1.0（1660）**。高德侧功能沿用 2.2.6，只同步版本号。

---

## 百度地图

### 1. 首页整页白屏（最贵的一条）

`applySearchChips` 反射回调组件自己的 `getView`，在钩子里重入；且 `field()` 在第一个名字找不到时直接抛，
后面那句兜底是永远执行不到的死代码。改法：钩子把 `chain.proceed()` 的返回值往下传，
反射改走 `fieldOrNull`。

### 2. 搜索框下面那排快捷入口（酒店 / 景点 / 美食 / 厕所 / 加油站）

同一排 chip 有**两个宿主**，都要挂：

- **首页收起态**：条目类 `aihome.panel.presenter.QueryViewHolder`（classes11.dex，超类 `RecyclerView$ViewHolder`）。
  挂它的构造器 → 向上找到那条**没有资源 id** 的 `RecyclerView` → GONE，并向上逐层收掉「没有别的可见孩子」的空壳。
  只 GONE 不收壳会留白。
- **搜索页**：`PoiListHeaderMenu` 三个构造器 → 按 id 抓 `search_box_line2` / `search_box_line2_content` 一起隐藏。

关键事实：`aihome_poi_first_title`（0x7f0e00c8）inflate 出来的那一刻那排是**空的**，
chip 是 `PoiListHeaderMenu.k()` 里再 `addView` 进去的 —— 挂 inflate 永远抓空壳，这就是「开关关了没反应」的根因。

### 3. 路线页浮层与横幅

- 15 个「创建即隐藏」锚点（低碳、古今地图、周边、特惠打车、各类推荐位）。
- 横幅广告**两套渲染同时断源**：
  - RN 模板 `talos.view.advert.RNAdvertContainer`
  - 原生壳 `PubTravelBaseHomeCard.fetchAd()` / `showAd(...)` → `RotateBannerAdView` → `BannerAdView`
- 只 GONE 广告容器会留 ~1000px 白块：装它的壳还在（壳里有宽度 0 的占位兄弟，`getChildCount()==1` 判据失效）。
  判据改成「父容器没有别的**可见**孩子」，向上收 4 层。

### 4. 路线页开关

最下方的「探索周边」「特惠打车」加入设置页开关（默认隐藏）。

### 5. 进路线规划闪退（地铁）

穿山甲断点从 `TTAdSdk.init(Context, TTAdConfig)`（返回 false）改成 `TTAdSdk.start(TTAdSdk$Callback)`（空实现）。

### 6. 地图 POI 按类型关

13 个分类 + 长尾桶，共 15 行开关。按 STY 里图标资源名（`MapRes/xxx`）过滤，
缓存 schema `poi-file-assets-v8` → `v9`，开关拨动会重建。

### 7. 性能

- 拆掉全局 `TextView.setText` 钩子（每次设文本 4 次 `contains` + `toString()`，整机明显卡）。
- 真机基线（22.1.0 / 1660）：`dumpsys gfxinfo` 416 帧 **0.00% janky**，50th 7ms / 95th 8ms / 99th 15ms。
- `uiautomator dump` 会污染 gfxinfo（a11y 树遍历），性能必须在任何 dump 之前测。

### 8. 锚点

30 个 `NUM_*` 全部重测为 22.1.0 的值；`NUM_USER_CENTER` 从 `0` 补成真值 `0x7f0254eb`；
新增 6 个搜索区锚点。

---

## 高德地图

### 1. 隐藏工具栏不再连带「设置家 / 设置单位」

隐藏工具栏时，同一行的「设置家」「设置单位」被一起带走了。改成按各自的锚点单独判定，
工具栏隐藏只影响工具栏本身。

### 2. 自动巡航灵敏度

巡航判定原来只在位置变化超过阈值时才动，实际走起来反应偏慢。放宽触发条件后跟随更及时。

### 3. 新增「自动抓取钩子」按钮

面板里加了一个按钮，一键跑一遍锚点抓取，不用再连电脑。

### 4. 新增日志抓取

面板里可以直接抓一段时间的运行日志，方便反馈问题时把日志一起发出来。

---

## 产物

| 文件 | 说明 |
|---|---|
| `百度地图-2.2.7-免root内置版.apk` | 228,755,944 B，内嵌模块，LSPatch，免 root |
| `高德地图-2.2.7-免root内置版.apk` | 183,479,890 B，同上 |
| `MapClean-lsp-v2.2.7.apk` | LSPosed 模块（需 root / LSPosed） |

内置版与原厂签名不同，**不能覆盖安装**，必须先卸载官方 App。

---

## 已知未解决

- 选择路线页**顶部**的服务端横幅还没去掉（本轮唯一没修掉的用户报障）。
- POI 分类受 STY 里图标资源名覆盖度限制，长尾类型归到「其它」桶。
