# tools/REGISTRY.md —— 工具资产台账

> **动手前先读这里。** 命中已有工具就直接用，不要重复造轮子。
> 台账只登记**实际执行过**的通用工具；一次性探针留在 `.scratch/`，不入册。

约定：所有工具都支持 `python tools/<name>.py --help`；参数不写死路径；失败有非零退出码。

---

## 逆向取证

### `dex_query.py` —— DEX 查询/反汇编（零依赖，纯 Python）

不用 jadx 也能把 DEX 查清楚：类/方法/字段/字符串、指令反汇编、xref。

| 参数 | 作用 |
| --- | --- |
| `--Apk <apk>` | 目标 APK（必填；多 DEX 自动展开） |
| `--Class <Ldesc;>` | 列出该类的全部方法/字段，附字面量 |
| `--Methods` / `--Fields` / `--Strings` | 与 `--Class` 组合，选择要输出的部分 |
| `--Dump <method>` | 反汇编指定方法（按名，全部重载） |
| `--Xref <text>` | 找**引用该字符串**的方法（opcode 0x1A/0x1B 原字节扫描） |
| `--InvokeXref <cls;->m` | 找调用该方法的方法 |
| `--FieldXref <cls;->f` | 找读写该字段的方法（opcode 0x52–0x6D 原字节扫描） |
| `--Package <Lprefix>` | 限定包前缀 |
| `--Dex <classesN.dex>` | 只扫某个 DEX（**大包必加**，否则很慢） |
| `--Exact` | 精确匹配而非子串 |
| `--Find <text>` | 按类型描述符搜类 |

```bash
# 例：腾讯 11.7.0 里谁在读工具列表字段
python tools/dex_query.py --Apk base.apk --FieldXref "GetHomepageGroupToolsResponse;->groups"
# 例：反汇编底栏的 setTabViews
python tools/dex_query.py --Apk base.apk --Class "Lcom/tencent/map/ama/mainpage/frame/tab/LiquidTabBarGroup;" --Dump setTabViews
```

⚠ 性能：`--Xref/--InvokeXref/--FieldXref` 加 `--Package` 而不加 `--Dex` 时要解码大量方法体，
可能跑到十几分钟。**先 `--Dex` 定位，再按需放开。**

⚠ 已知坑（已修）：字符串表曾按声明的 UTF-16 长度切片，把每条中文串都截断了，
导致中文 xref 恒为 0 命中。现在按 `\x00` 终止符取值。

### `check_anchors.py` —— Hook 锚点静态校验（**改完 hook 必跑**）

把模块里写死的每个 `(类, 方法)` 对着目标 APK 验一遍。少一个就是 logcat 里一条
`miss <id>`，只有真机才看得出来 —— 这个脚本把它提前到编译前。

```bash
python tools/check_anchors.py --Apk <target.apk> --Anchors tools/anchors-tmap-11.7.0.json
```

锚点用 JSON 声明（`{"anchors": [{"label","class","methods"}]}` 或裸数组）。
现成清单：`tools/anchors-tmap-11.7.0.json`（腾讯 11.7.0，38 条锚点，实测全通过）。

### `extract_target_apk.py` / `target_dex.py`

从设备拉取目标 APK / 抽取其中 DEX。拉取也可以直接用 `rea.pull_adb_package`
（带 SHA-256，推荐）。

### `device_prefs.py`

读设备上的模块配置（调试开关状态用）。

### `extract_hooks.py`

从既有产物里反向抽取 hook 点清单。

---

## 构建与验证

### `verify_module.py` —— 模块 APK 只读校验

校验版本号、scope、minApiVersion、官方依赖 JAR 的 SHA-256、DEX 里是否有官方 service/interface 类。

```bash
python tools/verify_module.py --ModuleApk output/MapClean-lsp-v2.1.6.apk \
    --SourceDirectory . --ExpectedVersion 2.1.6 --ExpectedVersionCode 216
```

⚠ 在 23 个 DEX 的宿主包上跑 `dex_definitions` 可能超时；模块自身（单 DEX）很快。

### `app/build.ps1` —— 模块构建（唯一入口）

```bash
powershell -NoProfile -ExecutionPolicy Bypass -File app/build.ps1
```

产出 `app/dist/BMapClean.apk` + `output/MapClean-lsp-v<版本>.apk`，自带 zipalign + v1/v2/v3 签名。
第 3 步之后会**在 DEX 里查类型描述符**，缺少任一转发目标就 fail the build。

> ⚠ 本会话里 DSH 的 PowerShell 工具因工作区 ACL 被拒（`SetNamedSecurityInfoW failed (Win32 5)`），
> 但用 Python `subprocess` 直接起 `powershell.exe` 可以正常跑。构建走这条路。

### `build_variant.py` —— 免 root 内置版（LSPatch）构建

按 `TARGETS` 生成"只并入某一家的精简构建"：删掉其它厂商整包，用 `tools/variant-stubs/`
里的 no-op 桩顶住共享代码的引用，并在 DEX/源码两级做 required/banned/不变量检查。

```bash
python tools/build_variant.py --Target amap --OriginalApk <原包.apk> --Version 2.1.6
```

### `build_nonroot.py` / `build-nonroot.ps1` / `build_delivery_pack.py` / `build_source_zip.py`

内置版底层、交付包打包、源码包打包。

### `source_closure.py`

算源码闭包（哪些文件进了产物）。

---

## 版本号四处同步（**别漏**）

`src/io/github/ldxm666/mapclean/Version.java`（`NAME` / `CODE` / `TARGETS`）、
`META-INF/xposed/module.prop`、`AndroidManifest.xml`、`app/build.ps1` 的输出文件名。
