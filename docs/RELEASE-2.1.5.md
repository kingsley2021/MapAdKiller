# MapAdKiller 2.1.5 更新说明

发布日期：2026-10-12。版本码：215。对比基线 **v2.1.2（版本码 212）**。

## 2.1.5 修了什么：百度地图完全失效

**只用高德的话，2.1.5 与 2.1.4 没有区别；用百度地图的话请务必升级。**

2.1.4 的模块对百度地图**完全没有反应**：开关照写、界面照旧，logcat 里连一条 `BMapClean` 都没有。

原因是一个 APK 只能声明一个 Xposed 入口（`META-INF/xposed/java_init.list`），百度、腾讯两半
只能靠共享源码里的**显式转发**到达（`mapclean.MainHook.onModuleLoaded` 与
`mapadkiller.MainHook.onPackageReady`）。之前为了让「只并入高德」的免 root 精简构建能编译
（那个构建会把整个 `bmapclean` 包删掉），这两处转发被**直接从共享源码里删掉了** ——
精简构建过了，完整模块的百度却跟着一起失效：`H.module` 恒为 null，每次挂钩都以
`miss <id> (module null)` 失败，一个钩子都没装上。

修复：恢复两处转发；精简构建改为**生成 no-op 桩类**（`tools/build_variant.py` 的 STUBS
新增 `bmapclean/MainHook`），而不是把调用从共享源码里删掉。

顺带补上这次缺的**构建期护栏**：`app/build.ps1` 在 DEX 里直接校验
`Lio/github/ldxm666/bmapclean/MainHook;` 与 `Lio/github/ldxm666/mapadkiller/TmapHooks;`
两个类型描述符必须存在，缺一个就 fail the build；精简构建那边也把前者加进 required 列表。

实机验证：`baidu_adapter version=22.0.0 mode=capabilities` →
**`event=install_done pkg=com.baidu.BaiduMap ok=60 miss=0`**（60 个钩子全部装上，0 个 miss）。

---

## 2.1.4 的内容（本次一并发布）

发布日期：2026-10-12。版本码：214。对比基线：**v2.1.2（版本码 212）**。

本版的重头戏是**全新的「免 root 内置版」**：不需要 root、不需要 LSPosed、不需要任何框架，
装完就是一台"干净"的高德地图，界面自己就精简好了。

---

## 头条 · 免 root 内置版（本次新增）🎉

把 MapAdKiller 的**高德部分**直接并入高德地图本体，得到一个独立 APK。
安装后打开高德，界面右侧常驻一颗悬浮球，点开即是模块的全部高德设置。

| 项 | 说明 |
| --- | --- |
| 目标版本 | 高德地图 **17.00.0.2005**（versionCode 170000） |
| 架构 | arm64-v8a / armeabi-v7a / x86 / x86_64 |
| 宿主处理 | LSPatch v1.2 集成模式；内嵌 `origin.apk` 与官方原包**逐字节一致** |
| 入口 | 应用内悬浮球，**不申请任何权限**（不是系统悬浮窗，退出高德即消失） |
| 安装包 | 见下方附件 `AMap-2.1.5-rootless-integrated.apk` |

### 内置版这次修好的四个问题（都是真机实测反馈）

**① 「设置家」无效 —— 而且现在默认就是开着的**

以前打开「设置家」看不到那一行，把「内容」分类的开关全部打开后它又冒出来了，
看起来像开关坏了。真正的原因是：那条行由高德自己绘制，模块只决定放不放行，
而**首页那块面板被高德缓存复用了**，改开关根本不会重建它 —— 你看到"全打开内容开关后出现"，
看到的是那次被迫的整体重建。

本版让开关**写完立刻生效**：模块捕获面板实例后原地重建，不需要退出重进、不需要重启。
同时它不再默认关闭（「设置家 / 设置单位 / 常去地点」是地图核心功能，不属于推荐内容）。

> 实测：关掉开关后那一行立即消失，没有任何重启。

**② 步行 / 骑行打开了，首页工具栏里却找不到**

高德自己的工具槽列表在你这台机器上**只有两项**（公交地铁、更多工具），
而模块以前**只会删、不会加** —— 所以步行(104)/骑行(105) 从来没有机会出现在首页。
另外实测确认：首页工具栏只有 **5 格**。

本版按"已开启"的顺序自动补齐空位（最多 5 格），且只补首页那一份数据，
不动高德自己的存储，所以「更多工具」页里的内容不会被污染。

> 实测工具栏：`公交地铁 / 更多工具 / 步行 / 骑行`。

**③ 「更多工具」没有独立开关**

它以前埋在「工具 → 其他」一百多个工具的最后，基本等于没有。
本版把它单独提到**「首页」分组**，标签「更多工具入口」，并从工具分类里移除，避免一个功能两个开关。

**④ 地图的兴趣点图标 / 名称开关完全无效**

这个开关的实现在**原生层**：挂住资源读取，只截地图样式表，把兴趣点要素改写成不可见，
**道路、水域、路名地名原样保留**。以前它一次都没真正装上，而且原生侧还把"没装成"报成了"装成了"。

本版补齐了原生库在集成模式下的加载路径，并让原生侧如实汇报状态。

> 实测：`native_init framework_ready=true` / `configure … accepted=1 framework_bound=1` /
> `style_filtered rules=2950 … asset=map_assets/style_X_MainStd_Std_D_s_…data`。
> 地图上只剩道路、水系与地名，兴趣点图标与名称全部消失。

### 安装前必读

- 内置版用 LSPatch 重新签名，**签名与官方高德不同** → 不能覆盖安装官方版，
  从官方版换过来必须先卸载（**会清除登录态与离线地图**）。
- 已经是 2.1.3 内置版的，可以直接覆盖安装，**不会丢数据**。

```powershell
# 从官方版换过来
adb uninstall com.autonavi.minimap
adb install "AMap-2.1.4-rootless-integrated.apk"
# 从 2.1.3 内置版升级
adb install -r "AMap-2.1.4-rootless-integrated.apk"
```

---

## LSPosed 模块 2.1.4

模块和内置版是**同一套源码**，上面四项修复在模块里同样生效；
如果你两个都用，会看到完全一致的行为。

- 模块 id / 应用名 / 签名**全部不变**（`io.github.ldxm666.mapadkiller`），可就地覆盖安装。
- 附件：`MapClean-lsp-v2.1.5.apk`（`versionCode 214`，`versionName 2.1.4`）。
- 兼容 libxposed **API 102**；作用域 `com.autonavi.minimap` / `com.baidu.BaiduMap` / `com.tencent.map`。

### 模块版本次同步的内容

除内置版的四项修复外，模块还拿到了原生库加载路径的完整加固
（普通 LSPosed 安装下本来就能用，这次是把它和集成模式统一到同一段逻辑），
以及原生侧"未绑定时不得谎报成功"的修正 —— 以后再出现类似问题，日志里会直接说清楚。

---

## 相对 2.1.2 的完整变更

### 2.1.3 的内容（本次一并发布）

- **「设置家 / 设置单位 / 常去地点」整行不再出现**：改为先对齐登录 uid，
  再交回高德原生构建器绘制，样式跟随 App；仅未登录或原版仍拒绝时才用同 payload 兜底渲染。
- **工具行不再重复两份 / 不再把父容器设为 `GONE`**：该父容器正是「设置家」那一行的宿主，
  一旦 `GONE`，之后加进去的内容永远不可见。
- **兜底渲染的尺寸换算**改为精确复刻宿主 `dx1.l(v) = round(v / 2 * density)`，
  修复字被撑大后拆行、标签被裁掉的问题。
- **首页推荐画布过滤加固**：卡名解析改为按宿主真实规则取值
  （`cardName` / `card_name` / `bizName` / `biz_name` / `name`，大小写与下划线归一化），
  流式信封同时读 `is_done` / `isDone`、同时写 `has_more` / `hasMore`。
- `AmapToolIcons.load()` 修复空指针，并新增 `tokenOf()` 兼容 `iconToken` 键名。
- 悬浮窗 UI 重做：作者头像 + 状态胶囊 + GitHub 文字链，横向滑动切换分类、带边界回弹、
  分类搜索、已开启工具排在最前。

### 2.1.4 的新增

- **免 root 内置版**（见上）。
- 开关**写入即生效**：首页工具面板原地重建，原生样式过滤重新下发，不再需要"退出重进"。
- `AmapData.toolbox(source, settings, fill)`：工具槽从"只过滤"改为"过滤 + 补齐"，
  仅作用于首页那一份数据。
- 工具目录新增「更多工具入口」独立开关，并说明工具栏只有 5 格。
- 默认值调整：「设置家 / 设置单位 / 常去地点」默认开启；
  迁移 `_amap_protocol_schema 210 → 211`，只对"迁移前本来是开"的安装恢复，不覆盖用户显式选择。
- 原生层：集成模式下模块 APK 的原生库布局与类加载器查找路径对齐，
  宿主 `nativeLibraryDir` 优先；未绑定时不再谎报成功，`configure` 日志新增 `framework_bound=`。
- 构建侧：定制版产物只可能带 LSPatch 自带的 v2 签名（原因见「构建与签名」一节），
  内置版固定为"LSPatch 直接产出并签名"。

---

## 安装时安全中心报红？那是误报

MIUI / 安全中心扫描这类**重打包**产物时会标红，这是集成方案的固有特征，不是病毒。
触发点有三个，全部来自打包机制本身，**无法通过"清理安装包"消除**：

1. 安装包里**内嵌了一个完整的 APK**（`assets/lspatch/origin.apk`，就是原版高德）——
   杀软把"包中包"当作释放器特征；
2. 运行时**从 assets 加载 dex**（`assets/lspatch/loader.dex`）——
   动态加载代码是恶意软件的高频特征；
3. 内置**签名绕过**（让高德读到原厂签名）—— 属于反篡改绕过行为。

自己用的话，在安全中心选「继续安装」，或把高德加入信任名单即可。

**介意红标的用户请改用 [`MapClean-lsp-v2.1.5.apk`](https://github.com/ldxm666/MapAdKiller/releases/tag/v2.1.4)（LSPosed 模块版，约 4 MB）** ——
它只是把模块装进框架，**不改动任何地图安装包**，不会被报毒。

## 构建与签名

免 root 内置版由 `tools/build_variant.py` 产出（精简源码 → 编译内嵌模块 → LSPatch 打补丁 → 校验发布）。
脚本是 fail-closed 的，校验不通过不会产出半成品。

**产物只能保留 LSPatch 自己的 v2 签名**，原因不是包有损坏，而是包内**有意嵌套**：
`assets/lspatch/origin.apk` 是原封不动的官方 APK，而官方 APK 自身的条目
（`lib/*.so`、`assets/*`、`resources.arsc` 等）也仍在包里，它们的字节**物理上就位于
origin.apk 覆盖的那段区域**。于是：

- 统计各条目声明大小之和会得到 366 MB，而文件只有 200 MB；
- `zipalign -c` 会报一批 "header mismatch"，并认为 `origin.apk` 与其它条目区间重叠；
- 任何按声明大小重建的工具（`zipalign`、`apksigner`）都会把原区域复制两遍 → 产物变成 367 MB。

这些都是嵌套结构的正常结果，**Android 自身和 Python 的 zipfile 都读本地头、因此完全正常**
（`adb install` 与 MIUI 安装器均实测可装、应用可正常运行）。代价就是 v1/v3 签名补不上；
Android 7.0+ 认 v2，实际不受影响。

---

## 验证

| 层级 | LSPosed 模块 | 免 root 内置版 |
| --- | --- | --- |
| 编译 | BUILD OK，签名 `cbecdaeb…54ed`（与 2.1.2 同密钥） | BUILD OK，原包 `origin.apk` 逐字节一致 |
| 安装 | 可就地覆盖 | `adb install -r` Success（覆盖 2.1.3） |
| 版本 | `versionCode 214` / `versionName 2.1.4` | 宿主版本保持 17.00.0.2005 |
| 作用域 | 三家 | 仅 `com.autonavi.minimap` |
| DEX 扫描 | 含高德 hook，不含百度/腾讯 | 同左 |
| 原生库 | 四 ABI `libmapclean_labels.so` | 四 ABI + `liblspatch.so` |
| **实机运行** | 本次未单独回归（与内置版同源） | **已逐项验证**，见上 |

内置版实机关键日志：

```
I/MapAdKiller:    amap_tool_boot slots=103,86,104,105,-
I/MapAdKiller:    amap_home_chips built=DtLinearLayout uid_aligned=false items=3
I/MapAdKiller:    amap_native_home refresh ok signature_changed=true
I/MapCleanLabels: native_init framework_ready=true api=2
I/MapCleanLabels: native_install host=amap asset_only=true failures=0 api=2
I/MapCleanLabels: configure map=1 hide_poi=1 accepted=1 framework_bound=1
I/MapCleanLabels: style_filtered mask=1 rules=2950 bytes=173610 asset=map_assets/style_X_MainStd_Std_D_s_26_1788435170.data
```

---

## 附件

| 文件 | 说明 |
| --- | --- |
| `MapClean-lsp-v2.1.5.apk` | LSPosed 模块（高德/百度/腾讯） |
| `AMap-2.1.5-rootless-integrated.apk` | 高德地图免 root 内置版（本次重点） |
| `SHA256SUMS.txt` | 校验值 |

## 关于源码

自 2.1.4 起，发布页**只提供成品 APK**，不再附带源码包。

## 第三方

免 root 内置版使用 LSPatch v1.2（JingMatrix / LSPosed contributors，GPL-3.0）打包，
发布地址 <https://github.com/JingMatrix/LSPatch/releases/tag/v1.2>，许可证随包附带。

---


## 发布记录

| 目标 | 标签 / 版本 | 附件 |
| --- | --- | --- |
| 主项目 | [`v2.1.5`](https://github.com/ldxm666/MapAdKiller/releases/tag/v2.1.5) | `AMap-2.1.5-rootless-integrated.apk`（200,750,322 B）<br>`MapClean-lsp-v2.1.5.apk`（4,277,717 B）<br>`SHA256SUMS.txt` |
| LSP 模块仓库 | [`215-2.1.5`](https://github.com/Xposed-Modules-Repo/io.github.ldxm666.mapadkiller/releases/tag/215-2.1.5) | `MapClean-lsp-v2.1.5.apk`（4,277,717 B）<br>`SHA256SUMS.txt` |

- 模块签名与 2.1.2 起的所有版本相同（`cbecdaeb…54ed`），可就地覆盖安装。
- 2.1.4 的发布页保留原样，便于对照。
