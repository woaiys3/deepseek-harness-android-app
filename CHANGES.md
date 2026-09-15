## v1.13.6（正式版 + Lite 共存版 + 兼容版 · 2026-09-15）

> 接 v1.13.5：把「**升级用户**」那条路径也覆盖到 —— dex 收权在“文件已存在直接返回”分支也要做。
> versionCode **33**，内核仍为 DSH 0.1.5-rc.1。

### 🐛 `extractRishDex()` 的“已存在”分支没收权（升级用户中招）
- v1.13.5 只在**写出后**给 `files/rish/rish_shizuku.dex` 收权，而升级用户走的是
  `if (dex.exists() && dex.length() > 0) return dex;` —— 旧版留下的 0666 副本不会被修。
- 现该分支也调用 `secureDexPermissions(dex)`；加上解压收尾的 `secureDexFiles(payload)`（每次启动无条件跑），
  「新装 / 覆盖升级 / App 自提」**三条路径全部覆盖**（本次只改 App Java，未动插件/payload/内核）。
- 这是用户追问“不覆盖旧文件真的可以吗”逼出来的一处漏洞，已修。

---

## v1.13.5（正式版 + Lite 共存版 + 兼容版 · 2026-09-15）

> `payload/rish/rish_shizuku.dex` 权限是 0666 → Android 14+ 的 ART 拒绝加载可写 dex
> （进程直接 SIGABRT / exit 134，终端上只有一句 “Aborted”）—— 修。
> versionCode **32**，内核仍为 DSH 0.1.5-rc.1。

### 🐛 payload 里的 rish dex 是 0666，导致 rish 调用静默崩溃
- **现象**（用户/AI 提交的报告，已逐条核实）：任何指向 payload 副本的 rish 调用直接崩溃，
  `exit=134`（SIGABRT），终端只看到 `Aborted`；真因只在 logcat：
  `java.lang.SecurityException: Writable dex file '<path>' is not allowed`。
  同一文件两份权限不一致：`files/payload/rish/rish_shizuku.dex` 是 `-rw-rw-rw-` ❌，
  `files/rish/rish_shizuku.dex`（`$SHIZUKU_DEX` 指向的那份）是 `-r--r--r--` ✅ → 主流程不受影响。
- **核实结果**：
  - `assets/payload.zip` 的 **28604 个条目 unix 权限全部为 0**（create_system=MS-DOS，external_attr=0），
    即 **权限不是打包带进去的，而是解压时决定的**（这一点报告说得对）；
  - 解压写入用 `FileOutputStream` 创建文件（本机 umask 下即 rw-rw-rw-）；且 `prepareTarget()` 里的
    `setWritable(true, false)` 会把 **group/other 的写位也加上**（0444 → 0666）——
    这就是“`chmod 444` 后一更新就复发”的机制。
- **修法**（`MainActivity.java` 四处，四份同步，只改 App Java）：
  1. 解压写入循环：`name.endsWith(".dex")` → `secureDexPermissions(target)`（`chmod 0444`）；
  2. 解压收尾：`secureDexFiles(payload)` 兜底把 `rish/rish_shizuku.dex`、`vscreen/vscreen_shizuku.dex`
     收成 0444（覆盖升级时旧文件不会被重写，扫不到就白搭）；
  3. `extractRishDex()`：**App 自己那份** dex 写出后立即收权（插件 begin 前也存在窗口期）；
  4. `prepareTarget()`：`setWritable(true, false)` → `setWritable(true, true)`（不再把写位扩散到 group/other）。
- ⚠️ **本次未在真机复验**（做这轮时用户已关无线调试）。验收方式：
  ```bash
  adb shell ls -l /data/user/0/com.deepseek.harness/files/payload/rish/rish_shizuku.dex
  # 期望 -r--r--r--（旧版是 -rw-rw-rw-）
  ```
  再用报告里的复现命令跑一次，应当不再 `Aborted`、正常输出。

---

## v1.13.4（正式版 + Lite 共存版 + 兼容版 · 2026-09-15）

> App 侧 JSON 取字段改成**认识转义** —— 修 `shizuku_shell` 传“带引号/换行命令”被截断的隐蔽 bug。
> versionCode **31**，内核仍为 DSH 0.1.5-rc.1。

### 🐛 `shizuku_shell` 传带引号/换行的命令会被截断（静默执行错的东西）
- **根因**：`MainActivity.jsonField()` 是朴素实现（“找 `"key"` → 找 `:` → 取下一个 `"` 到再下一个 `"`”），
  **不认 `\"` `\\` `\n` 等转义**；而插件是用 `JSON.stringify()` 发的正常 JSON，命令里的 `"` 会被写成 `\"` → 在那个引号处提前结束。
- **真机可复现的例子**（用同一段算法跑出来的）：
  ```
  插件发： {"command":"pm install -r \"/sdcard/Download/my app.apk\"", ...}
  旧实现解析出： "pm install -r \"          ← 断在转义引号处，路径全丢，命令照跑
  新实现解析出： "pm install -r \"/sdcard/Download/my app.apk\""
  ```
  多行命令同理：`{"command":"id\necho hi"}` 旧实现得到字面 `id\necho hi`（`\n` 没还原成换行）。
- **修法**：`jsonField()` 改为逐字符扫描、处理 `\" \\ \/ \n \r \t \b \f \uXXXX` 的标准转义（约 25 行，无新依赖）。
  同一个方法也被 `/schedule`、`/setting`、`/clipboard`、`/overlay`、`/usage` 使用 → 那几条一并变正确。
- **验证（本地单测）**：`tmp-diag/json-test/`（`JsonFieldTest.java` 的方法体由 awk **从源码逐字抽取**，避免手抄走样）
  11 个用例全过 `PASS=11 FAIL=0`：普通值回归、转义引号、换行、反斜杠、`\uXXXX`、值含键名、数字字段（与旧行为一致）、
  缺字段、空串、`sh -c "…"`、转义斜杠路径。
- 说明：本轮**只改 App Java**，未动插件/payload/内核 → 引擎内特权通道行为不变（v1.13.2 已端到端验证）。

---

## v1.13.3（正式版 + Lite 共存版 + 兼容版 · 2026-09-15）

> 控制台「管理 Shizuku」弹不出授权框 —— 修。versionCode **30**，内核仍为 DSH 0.1.5-rc.1。

### 🐛 控制台点「管理」（Shizuku 行）不弹授权框，而引擎里 AI 调 shizuku 却会弹
- **真机现象（用户回报）**：没有给本应用授权 Shizuku 时，引擎里让 AI 调 shizuku → **能弹出** Shizuku 授权框；
  但在控制台权限页点 Shizuku 行的「管理」→ **什么都不弹**。
- **根因**：`conPermAction("shizuku")` 里原来只调了 `probeShizuku()`（纯探测），**从未发出过真实请求**，
  自然不会触发 Shizuku 的授权框；而引擎里那条路会 spawn `rish`（发 `REQUEST_BINDER` 广播），
  Shizuku 应用收到**真实请求**才弹框。
- **修法**：
  1. Shizuku 行改走统一入口 `showShizukuDialog()`（实时探测 → 未授权则请求 → 仍未授权再给手动引导）；
  2. 新增 `triggerShizukuPrompt()`：在 `Shizuku.requestPermission()` 之外，**补两条与引擎同款的真实触发**
     —— ① `Shizuku.getBinder()`（client provider 路径）② App 自己 spawn 一个 `rish`（同 dex、同 env 清理、
     同广播）；真机实测 `requestPermission()` 在本机（Shizuku 13.6.0 + ColorOS 15）不弹框；
  3. 3 秒后回探刷新（授权后界面立刻变），12 秒仍未授权 → 弹「需手动授权」引导框（带「打开 Shizuku」按钮）。

---

## v1.13.2（正式版 + Lite 共存版 + 兼容版 · 2026-09-15）

> 接 v1.13.1：真机端到端实测又抓出并修掉 1 个**我自己引入**的 bug（工具输出 schema 非法）。
> versionCode **29**。内核仍为 DSH 0.1.5-rc.1。
> ⚠️ **v1.13.1（vc28）只用于调试、不要发布**——它的 `shizuku_shell` 结果会被判非法输出。

### 🐛 `shizuku_shell` 返回“结果 schema 不匹配”（v1.13.1 引入）
- **现象（真机、App 内 AI 原话）**：`id output is not obtainable through shizuku_shell right now because of
  this app-side schema mismatch (a bug in the tool's result schema versus what it returns)`。
- **根因**：`appShellCmd()` 的返回值里带了内部字段 `transport`，而 `shizuku_shell` 的输出 schema 是
  `additionalProperties:false`，DSH 校验不通过 → 整个工具结果作废（App 通道其实**已经执行并返回了**）。
- **修法**：`privCmd()` 返回前 `delete r.transport`（该字段只在插件内部作信号用）。
- 教训（与本项目已有的两条老坑同源）：**输出 schema 与实现必须同步**；`additionalProperties:false` 下
  多一个字段 = 结果作废（v1.6、v1.12 都踩过同类）。

---

## v1.13.1（正式版 + Lite 共存版 + 兼容版 · 2026-09-15）

> **Shizuku 特权通道改走 App 进程（根治“Request timeout”）**。versionCode **28**，内核仍为 DSH 0.1.5-rc.1。

### 🐛 Shizuku 工具一直“Request timeout” —— 真因与根治
- **真因（真机日志实锤）**：引擎内的 `rish` 子进程（`app_process … ShizukuShellLoader`，以正式版 uid 运行）
  调 Shizuku 时，Shizuku **服务端需要回头找 Shizuku 应用本体校验“这个包有没有被授权”**；
  而 ColorOS 会冻结/查杀 Shizuku 应用：
  ```
  OPM: handleAppExit … package=moe.shizuku.privileged.api reason=9 (EXCESSIVE RESOURCE USAGE)
       subreason=7 (EXCESSIVE CPU USAGE) … description=excessive binder traffic during cached state
  OplusHansManager: uid=10361 … F exit(), F stay=144 … unfreeze … reason: UidGone
  NativeFreezeManager: freezeForAppSwitchScene … mFgAppPkgname moe.shizuku.privileged.api
  ```
  应用一被冻结/查杀，那一跳就阻塞 → Shizuku 客户端库报
  `Request timeout. The connection between the current app (…) and Shizuku app may be blocked by your system…`
- **为什么以前查不出来**：`adb`（uid 2000 = Shizuku server 自己的 uid）**天然免检**，
  连把 `RISH_APPLICATION_ID` 填成不存在的包名都能跑通 —— 所以“从 adb 测 rish”永远看不到这个故障；
  而 App 进程内的 Shizuku API 通道（`Shizuku.getBinder()` + `IShizukuService.newProcess`，虚拟屏核心就是它拉起的）**实测一直正常**。
- **根治**：新增 App 本地路由 **`POST 127.0.0.1:<notifyPort>/shell`**（`MainActivity`），
  在 **App 进程内**用 Shizuku API 以 shell 身份执行命令并回收 stdout/stderr/退出码（带超时终止）；
  插件 `dsh-tool-shizuku` 的 `shizuku_shell` 改为**优先走这条通道**，`rish` 仅作旧 APK 兜底。
- **安全**：`/shell` 需要 **`APP_LOCAL_TOKEN`**（App 开机生成 32 位随机串、存 `dsh_prefs`、经 env 只交给本应用引擎），
  避免设备上任意应用通过 127.0.0.1 调用拿到 shell 权限。
- **`shizuku_status` 说真话**：两条通道都实跑探针，分别回报 `shizuku(App 进程 / Shizuku API)` / `shizuku(rish 兜底)`，失败时把两边原因都带出来。
- **环境侧（用户侧）**：Shizuku 应用需在 ColorOS 里允许后台活动（否则还会被厂商冻结）；
  本次已用 `cmd deviceidle whitelist +moe.shizuku.privileged.api` 把它加入电池优化白名单。

---

## v1.13（正式版 + Lite 共存版 + 兼容版 · 2026-09-14）

> **控制台/引擎判定大修**（12 项）+ **Shizuku 门卫修复** + **老 WebView `Iterator` 崩溃修复** + 界面/分享打磨。
> versionCode **27**。内核仍为 DSH 0.1.5-rc.1（随 v1.11 升级，本版未动）。

### 🔧 控制台与引擎状态（真机逐项实测）
- **关插件把 `cordis.patch.yml` 写坏 → 引擎启动即崩（致命）**：旧实现删掉 `- id:` 整行再把条目搬到文件顶部 →
  悬空 `name:` 造成 `duplicated mapping key`；且搬到顶部也不生效（patch 按顺序生效，须在 `- insert:` 之后）。
  现改为**原地**增删 `disabled:` + **结构自愈**（起引擎前/启动自检时自动修复）。
- **「停止」停不掉**：新增 `engineStartAborted` / `engineStoppedByUser`（打断在飞的等待 + 看门狗不再自动拉起）。
- **引擎在跑却显示“未启动”**：探测原本在主线程做网络 IO → `NetworkOnMainThreadException` 被吞；
  现探测全部移到后台线程并缓存（`conProbeEngineNow`），点击启动也先后台真探一次。
- **启动中界面“一闪一闪”+ 按钮提前点亮、点进去没反应**：真机 node 就绪需 17~43 秒，
  旧判定把“进程活着”当“已就绪”；现**只认真实端口探测**，未就绪期间显示「启动中…」并**禁用**入口。
- **重复拉起两个 node（抢 3080，通知端口 EADDRINUSE）**：`launchEngine` 增加“已有 node 存活”“端口已 listen”
  两道防线，只等待不再 spawn；`startNotifyServer` 改为幂等。
- **黑鲸鱼悬浮窗 / 常驻通知一直显示“引擎未运行”**：探测只认首页 HTML 的 `<title>`；
  现与 `isDshEngine` 对齐（`303/302 = token 有效`、`401 + dsh web authentication required = 引擎在跑`）。
- **定时任务**：`engineReady()` 同上修复；`enginePort()` 三版原**都硬编码 3080**（Lite 的定时任务会打到正式版引擎）→
  改为按包名派生（3080/3082/3084）。

### 🐛 Shizuku 门卫不认（正式版引擎自称 beta）
- `MainActivity.spawnNode()` 曾**硬编码** `SHIZUKU_APP_ID="com.deepseek.harness.beta"`，而三版共用同一份源码 →
  正式版/兼容版也在自称 beta，`rish` 拿它去 Shizuku 要授权时包名/uid 对不上 → 授权被拒。
- 现改为 `getPackageName()`（与 `ScheduleExecutor` 一致）并写回 `dsh_prefs:shizuku_app_id`。

### 🌐 老 WebView：`Failed to load plugins`（`Iterator is not defined`）
- 官方插件 `@deepseek-ai/dsh-client-ui-sidebar-documentpreview` 顶层直接读 `Iterator.prototype.join`
  （ES2025 Iterator Helpers，WebView 122+ 才有）→ 老设备 `ReferenceError` → 插件 import 失败 → 前端白页。
- **实测发现连现代 Chromium 都还没实现 `Iterator.prototype.join`**，所以补丁对所有设备都必要。
- 修法：Iterator polyfill 注入 `mobile-patch/mobile.js`（body 末尾普通脚本，早于所有 module，三版通吃）；
  兼容版另并入 `legacy-patch/polyfill.js`。

### 🎨 界面与分享
- **退出弹窗“外面还套了一层小白边/小黑边”**：系统对话框面板的圆角/描边露在直角色块外；
  现将窗口背景置为**全透明**、卡片自绘圆角。
- **「分享」日志改为文件形式**：新增最小只读 `LogShareProvider`（本项目无 androidx，且 targetSdk 28 不允许直传 `file://`），
  把 `dsh-web.log`/`startup-diag.txt` 拷到 `cache/share` 后以 `content://` + 读权限授权分享（多文件走 `ACTION_SEND_MULTIPLE`）。
- **无障碍按文字/坐标点击点不到按钮**（AI 操作手机的基础能力）：新增 `closestClickable()`（向上找可点击祖先，
  带面积护栏）；`findNodeByText` 改为择优匹配；`findNodeByPoint` 改为最深可点击节点。

### 🔧 验证
- 用**从 MainActivity.java 原文抽取**的同一份方法做单测（`tmp-diag/patch-test/`）：
  自愈→幂等、关/开插件、连点 5 轮不增生；产出文件用**内核自带的 yaml 解析器**逐个验证可解析。
- Iterator polyfill：在真实浏览器里模拟“无 Iterator 的老 WebView”，验证插件原句不再抛错、
  各类迭代器 `join` 行为与规范一致；并确认不会覆盖原生实现。
- 真机（PLT120 / Android 15）：逐秒采样验证 —— 启动过程不再闪烁、按钮不再提前点亮、
  node 始终只有 1 个；无障碍 `tap?text=启动引擎` 返回 `method="node-text"`（修复前 found:false / gesture）。

---

## v1.12（正式版 + Lite 共存版 + 兼容版 · 2026-09-13/14）

> **冷启动先进原生控制台**（不进 DSH 网页）：解压/启动分两步、权限八项检测、插件开关、日志查看导出。
> versionCode **26**。**只改 App 外壳，未动 DSH 内核与网页**。

- **控制台四页**：① 解压/启动（拆成两步，无“第 1/2 步”标签）② 权限（八项真实检测，点击跳系统页）
  ③ 插件开关（读写 `cordis.patch.yml` 的 `disabled`）④ 日志（查看/导出/分享）；主题跟随系统深浅色。
- **修：引擎在跑但控制台显示“未启动”**：
  ① 探测器用带 token 的 URL 探测，**把一次性 token 吃掉了**，WebView 随后拿同一个 token → 401（白屏）；
  现改为**只用不带 token 的 `/`** 探测（`401 + dsh web authentication required` 也算“引擎在跑”）。
  ② 旧实现只看内存里的进程句柄，App 重启后 `nodeProcess == null` → 恒显“未启动”；
  现改为**先探端口、再回退看句柄**。③ App 重启后丢 token → 新增 `conTokenFromLog()` 从引擎日志尾部捞回。
- **兼容版白屏的真因（历轮误判）**：`build-legacy.sh` 把 Vite 的 `<script type="module">` 换成
  head 里**不带 defer** 的普通 script → 在 `<div id="root">` 解析前执行 → `missing #root` 纯白页；
  现输出 `<script defer src="/assets/index.legacy.js">`。
- **「增加供应商」按钮置灰的真因**：`dsh-llm-pi-ai` 被 `cordis.patch.yml` 禁用（旧理由已过期）；
  现取消禁用（0.1.5 树里 `@earendil-works/pi-ai` 为纯 JS，无原生模块）。

---

## v1.11（正式版 + Lite 共存版 + 兼容版 · 2026-09-12）

> 内核升级 **DSH 0.1.5-rc.1**（原 0.1.1-rc.2）+ **6 个 android 插件缺陷修复** + **虚拟屏比例归一化**。
> versionCode 25。**这是 v1.7.5 之后最大的一次更新**（跨 v1.8 → v1.11，共 4 个小版本）。

### ⬆️ 内核升级到 DSH 0.1.5-rc.1（用「旧法」重建依赖树）
- 上一轮曾用 `npm install --install-strategy=nested` 重装整个依赖闭包 → 内核树 **1214 MB**、486 包 **1691 个副本**、APK **343~526 MB**，并且丢了 `dsh` 包内的 `config/agent-presets/`（npm tarball 只含 lib）。
- 本轮回到项目既有的「旧法」：以既有可工作内核树为基线 → 用 npm 解析出 0.1.5 的 **hoisted** 闭包 → 按旧树布局组装 → 重放补丁面 → 剥离原生模块 → 重建 APK。
- 结果：内核树 **214 MB**、树内 **0 个原生 `.node/.so/.dll`**、APK **123 MB**，结构完整。

### 🐛 修复 6 个 android_* 插件缺陷（均已真机逐条验证）

| 工具 | 问题 | 修复 |
|---|---|---|
| `android_see` | 截图落在 **App 私有目录**，跨包读必 EACCES（“看图”能力彻底失效） | 无障碍截图改写到**共享目录** `<sdcard>/<pkgRoot>/screenshots/`；并修掉真因——**无障碍端口串台**（见下） |
| `android_touch_status` | `held[]` 实际返回 `elapsedMs`，schema 未声明 → `additionalProperties:false` 判 invalid output | schema 补 `elapsedMs` |
| `android_schedule` | App 返回体带 `repeat`，schema 未声明 → 整个调用报 error，**而闹钟其实已注册**（“报错 ≠ 没执行”） | schema 补 `repeat`；返回值改为**只带已声明字段**；另暴露 `repeat`/`intervalMin` 参数 |
| `android_input text` | **中文全部丢失、exit_code 仍是 0**（插件 `safe()` 会删掉非 ASCII，`input text` 也只认 ASCII） | 文本不再过 `safe()`；ASCII 走 `input text`（空格转 `%s`）；**非 ASCII 自动改走「剪贴板 + 粘贴」** |
| `android_type` | 空字符串（＝清空输入框）被真值判断误判为“没传参” | 改为只判 `undefined/null`，放行 `""` |
| `android_package install` | **静默假成功**：单发 `pm install` 不解析输出，失败也返回 `exit_code:0`；且 `apk_path` 过 `safe()` 会把中文目录名删掉 | 先拷到 `/data/local/tmp`，再走 `install-create/install-write/install-commit`，**校验输出含 `Success`**，失败如实报错；给了 `package` 再用 `pm path` 二次校验 |

### 🖥️ 虚拟屏比例：真正做到“一律 9:16 / 16:9”
- **核心侧归一化**：`createDisplay()` 新增 `toPhoneSize()/normalizeShortEdge()`，短边取 144 的倍数 → 比例精确 9:16/16:9 且 16 像素对齐（如 `1520×720` → **1280×720**）。
- **插件侧默认竖屏**：不传 `orientation` 时插件显式发 `1008×1792`（此前**什么都不发**，尺寸完全由服务端默认值决定——旧核心默认 `720×1520` 并非 9:16）。
- **核心指纹升版**：`BUILD` / `EXPECTED_CORE_BUILD` → `vs112-20260912`。
  **教训**：虚拟屏核心是独立特权进程、**比 App 活得久**（实测跨 4 次装包存活），只改核心代码不升指纹 → App 判不出“跑的是旧 core” → 改动**静默失效**。

### 🔧 其他修复
- **附件按钮点了没反应**：官方前端用 `<input type="file">`，而 App 从未实现 `WebChromeClient.onShowFileChooser` → 已补（含单选/多选）。
- **读图三件套全挂**（`android_see` / `android_vscreen_see` / `read_image` 报 `EACCES: open '/data/user/0'`）：attachment-local 的 Android 补丁在 0.1.5 上漏移植两处 → 补 `syncDirectory` 的 EACCES/EPERM 容错 + `link()` 失败退化 `copyFile`。
- **“修了但手机上没生效”**：`FORCE_OVERWRITE_PREFIXES`（fast 同步白名单）补齐补丁面；并新增**内核树布局标记**（`assets/dshroot_layout.txt` = `hoisted-1`），布局变化时整棵重推。
- **三版本端口撞车**：引擎端口三套全写死 3080（注释却写“各用独立端口”）→ 同时装会 `EADDRINUSE`，插件的 3081/3181 会打到**另一个版本**的 App 上。现统一按包名派生：**3080 / 3082 / 3084**，通知端口 +1，无障碍端口 +101。
- **无障碍端口串台（`android_see` 报 EACCES 的真因）**：无障碍服务读的是**跨版本持久化**的 `dsh_prefs:a11y_port`，旧包写下的 3181 在升级后仍被读到 → 正式版和 Lite 都往 3181 绑，**后连的那个静默失败、先连的那个应答**，于是正式版拿到的是 **Lite** 的截图路径。现改为**按包名推导**并纠正脏值。
- **新增 `appPost()`**：App 本地服务会把请求行的 query 丢掉、只把 body 交给 `/clipboard`、`/schedule` → 这两个端点**必须 POST + JSON**；用 `GET?action=write` 会被当成 `read`（写入静默无效，随后粘贴的是剪贴板里的旧内容）。
- **移除右上角浮动「退出」按钮**（系统返回键的确认退出保留）。
- **🔐 移除硬编码的签名密码**：`android-app-fix/build-fix.sh` 里曾**明文写有签名密钥密码**（该文件在此前版本中已经公开，**该密码应视为已泄露**），现改为从 `KEYSTORE_PASS` 环境变量读取、缺失即报错——与其两个兄弟脚本（`android-app/build.sh`、`android-app-fix/build.sh`）保持一致。密钥文件 `release.jks` 始终未入库（`.gitignore` 已排除）。

### 🙏 开源致谢
- 虚拟屏（vscreen）实现**移植/对齐 [Operit](https://github.com/AAswordman/Operit)**（LGPL-3.0）→ 本仓库虚拟屏相关文件同样按 LGPL-3.0 分发，全文与说明见 [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md)。

---

## v1.10（正式版 + Lite 共存版 + 兼容版 · 2026-09-11）

> versionCode 24。**虚拟屏修复版**：解决「`android_vscreen_create` 每次都失败（`displayId=-1`）」+「物理屏看不到虚拟屏」两个问题。
> 预览方案对齐 [Operit](https://github.com/AAswordman/Operit)：**H.264 视频流实时解码渲染到悬浮窗**（不是轮询截图）。

### ✨ 虚拟屏架构重做
- **服务改为 App 进程内嵌启动**：不再依赖 Shizuku/root 拉起 `app_process` shell 进程——shell 域在 Android 15 enforcing 下 `createVirtualDisplay` 会被 Binder 拒（`Bad file descriptor`）。
- **建屏即自动弹出独立预览悬浮窗**：实时显示虚拟屏画面（画中画），**不依赖 App 前后台**，一直显示到虚拟屏关闭。
- **预览窗可拖动、可双指缩放**（对齐 Operit 交互），默认右上角留边距。
- **预览 = H.264 视频流**：服务端 MediaCodec 编码器 → `setVideoSink` 推给 App 内本地 sink → `H264PreviewRenderer` 解码 → 渲染到 SurfaceView。含 **SPS/PPS（csd-0/csd-1）缓存重放**（编码器在建屏瞬间就发配置帧，后挂的 sink 拿不到）。
- **截图**改走 PixelCopy（公开 API，从虚拟屏 Surface 读帧）；**输入**走 Shizuku `input -d` / `am start --display`。
- **不再请求 MediaProjection 授权**（用户反感弹窗，且主 App 也用不到）。

### 🐛 修复
- 预览窗 `updateViewLayout` 崩溃（SurfaceView → 容器）＋ 触摸穿透（去 `FLAG_NOT_TOUCH_MODAL`，改用 FrameLayout 容器接收触摸）。
- `dsh-tool-vscreen` 的 `readFile` 从 `node:fs` 误用为 Promise → AI 的 see 报「读取截图失败」；改从 `node:fs/promises` 导入。
- `AbortSignal.any` polyfill（内置 node 版本 < 20.3 无此 API）。

### ⚠️ 已知边界
- 需要 **Android 11+**；无悬浮窗权限时预览窗不显示；无 Shizuku 时可“看”不可“点”。
- 正式版与 Lite 共存版**同时启动**时 **8999 端口互斥** → 虚拟屏实际二选一。

---

## v1.9（正式版 + Lite 共存版 + 兼容版 · 2026-08-29）

> 内核 DSH 0.1.1-rc.2，versionCode 23，targetSdk 28。虚拟屏（vscreen）能力 + 存储权限自动请求修复（“未知错误”根因）。

### 🔧 v1.9 二次修复（对齐 Operit 成熟方案，Android 15 实测问题解决）
- **存活方案（关键）**：真机（Android 15 + Shizuku）实测 server 启动后被杀（日志停在 HTTP listening，进程消失）——根因是 rish `-c` 命令会话结束会清理子进程（setsid/nohup/double-fork 均无效）。**对齐 Operit（Android 最成熟同类）**：改为 App 在 Shizuku 授权后通过 `IShizukuService.newProcess()` 启动 server（独立进程，App 持有 IRemoteProcess，不受命令会话清理）——项目 libs 已内置 shizuku-aidl.aar（13.1.5，IShizukuService.newProcess 直接可用）。MainActivity `ensureVscreenServer()` 实现：newProcess cp jar → chmod → newProcess 启动 app_process server；启动时自动探测 8999（已活则跳过）。
- **Android 10+ 安全模型（实测）**：App 进程内建虚拟屏被拒（Requires CAPTURE_VIDEO_OUTPUT or MediaProjection）——确认虚拟屏必须由特权进程创建（shell uid 有 CAPTURE_VIDEO_OUTPUT）；曾尝试 App 内公开 API 建屏（Operit VirtualDisplayManager 思路）实测被权限拒绝，已回退特权 server 方案。
- **注入改为特权短命令**：tap/swipe/key 由插件执行 `input -d <displayId> tap/swipe/keyevent`（一次性命令，无需长驻特权进程；`-d` 语法已实测正确）。
- **插件 ensureServer 重构**：Shizuku 场景只探测 8999（App 已自动启动 server）不再自行 rish 启动；root 场景保留 su 路径。

### ✨ 新增（虚拟屏 vscreen）
- **真·虚拟屏服务（VirtualScreenServer）**：app_process 特权进程（root 或 Shizuku shell 通道），反射 `DisplayManager.createVirtualDisplay()` 创建独立虚拟显示器；MediaCodec H.264 编码器 surface（无编码器环境回退 ImageReader）；`ActivityOptions.setLaunchDisplayId` 启动 App 到虚拟屏；`InputManager` 反射 + `setDisplayId` 定向注入触摸/按键；FakeContext（60+ 抽象方法的最小 Context 实现）供 DisplayManager 构造时传入，解决 app_process 无 Context 的 NPE。
- **插件 `dsh-tool-vscreen`（8 工具）**：android_vscreen_create / status / launch / see / tap / swipe / key / close。see 通过 attachments 注入注册（截图作为图片发送给视觉模型），返回 screenW/H、imageW/H、scaleX/Y 供坐标换算。
- **部署链路**：server jar 随 APK assets 打包（vscreen_shizuku.jar），App 启动提取到外部共享目录（/sdcard/DeepSeekHarness*/vscreen/，App 可写、shell 可读），插件以特权通道拷贝到 /data/local/tmp 后 app_process 加载；每次启动强制覆盖（防旧 jar 残留）。
- **可观测性**：server 日志写 /data/local/tmp/vscreen.log（shell 可读）；插件启动失败自动带出日志末尾；cp 失败报真实 stderr（不再“未知错误”）。

### 🐛 修复（“未知错误”根因）
- **首次启动存储权限**：Android 10+ 上 targetSdk28 的 App 写 /sdcard 必须先运行时授权；之前不弹窗，提取 jar 到外部目录 EACCES 后静默 fallback 私有目录 → 特权通道（shell）读不到 → vscreen 永远起不来，AI 只看到“未知错误”。现 onCreate 自动请求存储权限，授权回调里重新提取 jar 到外部目录（日志：vscreen jar re-extracted after permission grant），模拟器全流程验证通过。
- **manifest 加 requestLegacyExternalStorage="true"**（Android 10/11 legacy 访问保险，三版本）。
- **vscreen 工具无屏时明确报错**：launch/tap/swipe/key/see 在未 create 时返回“虚拟屏未创建，请先调用 /vscreen/create”，不再假成功（之前 launch 会启动到主屏 displayId 0、tap 静默失败）。
- **android_vscreen_see 返回尺寸字段**：server 截图响应补齐 screenW/H、imageW/H、scaleX/Y（之前插件拿默认 0/1，坐标换算失效）。
- **正式版/Lite/兼容版 MainActivity 统一**：FORCE_OVERWRITE_PREFIXES 增加 dsh-tool-vscreen（随 APK 覆盖旧插件，避免旧版插件挡住更新）。

### ⚠️ 说明
- 虚拟屏需要 **Android 11+** 且 root 或 Shizuku 授权；模拟器无 H.264 编码器/软渲染限制，请以真机验证为准。
- 已验证（模拟器）：jar 部署链路、app_process 启动（shell uid）、FakeContext 修复 NPE、插件 8 工具注册、无屏操作明确报错、see 尺寸字段、存储权限自动请求全流程。
- 未验证（需真机）：createVirtualDisplay 成功建屏（模拟器无 H.264 编码器 + ImageReader Binder fd 限制；真机有硬件编码器，为 Operit/scrcpy 同款标准路径）。


# DeepSeek Harness Android · 移动端优化改动清单

## v1.8（2026-08-29 凌晨 · **未单独发布**）

> versionCode 22。**中间构建**：虚拟屏插件（`dsh-tool-vscreen`）首次随包进入 payload，但**服务端尚未落地**
> （包内没有 `assets/vscreen_shizuku.dex`，App 内也没有任何 vscreen 代码）——该版本的虚拟屏必然不可用。
> 约 1 小时后被 **v1.9** 完整取代，未单独发布；此条仅作版本沿革记录。

---

## v1.7.5（正式版 + Lite 共存版 + 兼容版 · 2026-08-28）

> 内核 DSH 0.1.1-rc.2，versionCode 21，targetSdk 28。Termux 共存修复 + 无障碍手势引擎 + 工具输出校验修复。

### 🐛 修复
- **Termux 共存**：内置 node 在 Termux 环境编译，OPENSSLDIR 被编译死为 /data/data/com.termux/files/usr——装了 Termux 的设备启动即崩（EACCES），没装时靠 ENOENT 静默才碰巧正常。payload 内置最小 openssl.cnf，启动时注入 OPENSSL_CONF 指向它，有无 Termux 均稳定。
- **android_usage 报错/空结果**：appRequest 不解析 JSON（execute 返回字符串，被 DSH 工具运行时 schema 校验拒绝："value" must be an object）；已改为返回对象，并为 android_usage / android_overlay 补全输出 schema（days/apps/running/engineUp/granted/msg）；days 参数（GET query 丢失）与 android_overlay 的 action 参数一并修复。
- **无障碍手势引擎**：新增通用触摸原语（多指同时、按住保持、拖动、分数坐标 fx/fy、网格截图）；修复等待时序下手指数误抬起、手势中途出错的状态污染、同请求内 down 后 move 误判；网格截图 Immutable bitmap 崩溃修复。

### ✨ 新增（无障碍）
- android_swipe / android_hold / android_touch（状态式虚拟触摸屏，多指核心）/ android_gesture（多笔组合手势）/ android_touch_status（查询按住的手指）
- android_tap 支持 fx/fy 分数坐标（免疫截图缩放误差）；android_see 返回屏幕/截图尺寸与换算系数 + grid 网格叠加；android_screen 无节点界面（Unity/游戏）自动提示改用截图 + 分数坐标


# DeepSeek Harness Android · 移动端优化改动清单

## v1.7.0（正式版 + Lite + 兼容版 · 2026-08-27）

> 内核 DSH 0.1.1-rc.2，versionCode 17，targetSdk 28。新增**无障碍自动化（读屏 + 模拟操作）+ 屏幕理解（无障碍截图 + 视觉模型）**。

### ✨ 新功能
- **无障碍屏幕助手**：系统设置 → 无障碍开启「DeepSeek Harness 屏幕助手」后，AI 可读屏（android_screen）、点击（android_tap）、输入（android_type）、返回/主页（android_back/android_home）、滚动（android_scroll）、截图理解（android_see，无障碍截图 + attachments 发给视觉模型）
- 无障碍服务本地 HTTP 端口 = 通知端口 + 100（正式版 3181 / Lite 3183 / 兼容版 3185），三版本共存不冲突
- **启动失败诊断**：失败时多位置写 startup-diag.txt（外部目录/App 专属/Download），引擎日志镜像到外部 dsh-web.log（无需 root/adb 可读排查）

### 🐛 修复
- **工具名冲突导致引擎启动失败**：无障碍插件 android_input 与特权版（dsh-tool-android）android_input 重名，真机有 Shizuku/root 授权时引擎加载插件树报 duplicate → 无障碍输入改名 **android_type**
- **pwsh-sandbox 禁用不生效**：cordis.patch.yml 里条目 id 写的是 dsh-pwsh-sandbox，插件树实际 id 是 pwsh-sandbox → 禁用匹配不上、引擎启动 pending 崩溃 → 修正 id
- **表格窄屏被裁切无法横滑**：mobile.css 表格滚动类名哈希随前端构建变化失效 + 前端 md-table-wide 依赖 hover 显示滚动条（触屏无 hover）→ 改用稳定选择器 `[class*="tableScroll"]` 且移动端始终 `overflow-x: auto`
- **无障碍输入不进 WebView/网页输入框**：setText 只改无障碍节点、不触发前端 input 事件 → android_type 支持 `paste:true`（剪贴板粘贴，触发前端更新）

### 发布
- GitHub Release v1.7.0：正式版 / Lite / 兼容版 三 APK

## v1.6.5（正式版 + Lite + 兼容版 · 2026-08-25）

> 内核 DSH 0.1.1-rc.2，versionCode 16，targetSdk 28。三个版本可共存：正式版（com.deepseek.harness，3080）/ Lite（.beta，3082）/ **兼容版（.compat，3084，新）**。

### ✨ 新功能
- **AI 工作区（可选）**：权限页 SAF 选择外部共享存储文件夹（如 /sdcard/Documents）作为 AI 文件操作工作根目录，启动时经 `DSH_WORKSPACE` 环境变量传给引擎，bash 工具 cwd 自动切换；不限制工作区外访问权限
- **悬浮窗改 DSH 官方黑鲸鱼图标**（无背景）+ 展开面板显示引擎状态与 **AI 回复状态**（空闲/回复中，每 ~6 秒经 session.list 的 running 字段刷新）
- **内置 curl**：Android 系统无 curl，打包 termux NDK 原生构建的 curl 8.21.0 + libcurl/libnghttp2/3/libngtcp2/libssh2 依赖到 runtime，AI 可直接使用

### 🐛 修复
- **老安卓 WebView 兼容**：DSH 前端（Vite 6）需 Chromium 80+（module script + 可选链/nullish），Android 7/8 出厂 WebView（Chromium 51/59）白屏被误认“引擎启动失败”。正式版/Lite 启动检测 WebView 版本并提示引导；新增**兼容版 APK**（esbuild 打包 + polyfill 转译，老 WebView 可用）
- **版本比较 bug**：versionName 带后缀（1.6.5-test/lite/compat）时版本段解析失败变 0，导致误弹“发现新版本”，已改为提取数字前缀
- 悬浮窗会话状态解析（session.list 响应结构为 result.value.items，此前多解析一层）

### 发布
- GitHub Release v1.6.5：正式版 / Lite / 兼容版 三 APK

## v1.6.1（2026-08-24）

- Write 工具修复（dsh-tool-fs 写文件链路）

## v1.6.0（2026-08-24）

- 修复：history unavailable（attachment-local 缺 maxImageDimension → 默认 2000）、识图必挂（补 readImageRequest）、read_image EACCES（syncDirectory 容错）、grep/glob（内置 rg 15.2.0 + libpcre2）、android_usage/overlay（GET 读超时 + JSON.parse + schema）、Lite SHIZUKU_APP_ID
- 新功能：定时任务 Kun 式增强（repeat daily/interval + 结果通知）、应用使用时长（UsageStats /usage）、小鲸鱼悬浮窗（OverlayService）、android_overlay 工具

## v1.5.5（✅ 正式版：慢启动根因修复 · 2026-08-23）

> 纯修复版：功能基线同 v1.5.4（无端口冲突自动换端口，端口被占会启动失败属预期）。
> 内核 DSH 0.1.1-rc.2。真机验证（MT6835 / Android 15）：第二次冷启动 ~4s（正式版）/ ~4.6s（Lite，删除外部旧目录后完全内部存储），无超时。

### 已修复
- **慢启动根因（90s 超时）**：v1.5.1 引入的 `isDshEngine()` 健康检查只读首页**前 4096 字节**查找 `<title>DeepSeek Harness</title>`，但 DSH 首页实际约 14KB，`<title>` 位于**第 ~13.4KB 处**（13KB 内联引导脚本在前）→ 永远匹配不到 → `waitForServer` 干等 90s 超时（引擎其实 5s 就绪）。改为**读完整页面**（上限 256KB，本地读取 <50ms）；`ScheduleExecutor.engineReady()` 同步修复
- **排查排除项（有实测证据）**：payload 与 v1.4.0 逐字节一致；真机 cpuset 0-7 / top-app / ~2GHz 无资源限制；外部 FUSE 存储非主因（内部模式同样生效）

### 发布
- GitHub Release v1.5.5：`DeepSeekHarness-v1.5.5.apk`（正式版 com.deepseek.harness，versionCode 12）+ `DeepSeekHarness-Lite-v1.5.5.apk`（Lite 共存版 com.deepseek.harness.beta，端口 3082）

### 📌 说明
- 规划中的新功能（定时任务 Kun 式增强 / 应用使用时长 UsageStats / 小鲸鱼悬浮窗 / 插件适配加强）**代码已完成**（工作区 build/ 与 build-lite/），归入 **v1.6**

## v1.5.0（⚠️ 测试版本：功能增强 + 稳定性修复 · 2026-08-21）

> ⚠️ **本版本为测试版本（非正式版）**：新功能已实现且主要链路验证通过，
> 但**端口冲突处理存在已知 bug**（3080 被占用时引擎可能起不来，下版本修），
> 且定时任务等新功能仍需更多真机验证。发布目的是让用户提前体验，**不保证完全稳定**。

### 新功能
- **① 端口冲突处理**（⚠️ 有 bug，见下）：默认端口被占时自动换空闲端口（3081~3099）
- **② ABI 检测**：非 arm64 设备启动时提示（引擎仅支持 64 位）
- **③ 补丁启动自检**：`cordis.patch.yml` 缺失/被改坏时自动从 APK 恢复（防"没带禁用配置启动失败"）
- **④ 电池优化引导**：未设"不限制"时弹窗引导（防后台被杀）
- **⑤ 本地设置通道**：`android_setting_app` 工具——给「修改系统设置」权限即可改亮度/音量/超时等（免 Shizuku）；**音量走 AudioManager 真实生效**（修复 Settings.System 记录不生效的坑）
- **⑥ 定时任务**：`android_schedule` 工具——AlarmManager 系统闹钟 + 前台服务执行，到点**自动拉起引擎执行任务**（无需用户操作），结果可发通知
- **⑦ 剪贴板工具**：`android_clipboard`——AI 读写剪贴板（免权限）
- **⑧ 更新提示**：启动时查 GitHub 最新版，有新版弹窗引导下载

### 已修复
- **音量调节不生效**：`Settings.System.putInt("volume_music")` 只改记录不调音量 → 改走 `AudioManager.setStreamVolume` 真实生效；工具输出 schema 补 `stream/level/max` 字段（修复"写入成功但报输出无效"）
- **定时任务只发通知不执行**：BroadcastReceiver 里跑线程会被系统回收 → 改走**前台服务**执行；闹钟改 `setAlarmClock`（无需权限、Doze 也触发）；**DSH API 调用格式修正**（缺 `type/rpcId/method/payload` 包装 → 补全）；**sessionId 解析修正**（indexOf 偏移错误 → 精确匹配 `"sessionId":"`）
- **定时任务执行日志**：写到外部目录（`/sdcard/DeepSeekHarnessLite/scheduled-log.txt` / `DeepSeekHarness/scheduled-log.txt`），便于排查

### ⚠️ 已知问题（下版本修）
- **① 端口冲突处理有 bug**：3080 被占时换端口后引擎可能未在目标端口启动（WebView 显示占位服务内容）；连带 `ScheduleExecutor` 固定端口与主引擎换端口后不一致。**v1.5.1 修**

### 验证
- [x] 音量（AudioManager + schema）：真机通过
- [x] 定时任务（闹钟→前台服务→自动执行→结果通知）：真机通过（scheduled-log 确认"任务已发送给 AI"）
- [x] 剪贴板：真机通过
- [x] 编译：63 class + 2 插件无错误
- [ ] 端口冲突（已知 bug，待修）
- [ ] 补丁自检 / 电池优化引导 / ABI / 更新提示：逻辑简单，未逐一真机验证

---

## v1.4.0（可选特权降级 + 前台保活 + AI 通知 · 2026-08-20）

> **背景**：此前系统操作（装应用/改设置/模拟输入）全部依赖 Shizuku，
> 未授权时工具仍注册，AI 反复调用失败；且 root 设备无法利用 root 权限。
> 用户诉求：不授予 root/Shizuku 也能正常使用（文件读写/预览/编辑只需
> 「所有文件访问」权限），未授权时 AI 不要一直尝试调用特权工具；
> AI 干活时 App 挂后台不被杀；AI 能发通知（只需通知权限）。

### 功能
- **特权通道二选一**：MainActivity 启动时探测 `su -c id`（root）与 Shizuku
  授权状态，通过环境变量 `ROOT_AVAILABLE` / `SHIZUKU_AVAILABLE` 传给内核；
  插件执行时 **root(su) 优先，否则 Shizuku**（新增 `suCmd`，与 rish 同构）。
- **未授权不注册特权工具**（关键）：`dsh-tool-shizuku` / `dsh-tool-android`
  在两者都未授予时**不注册** `shizuku_shell` / `android_*` —— AI 工具列表里
  没有它们，自然不会反复尝试；只保留只读的 `shizuku_status` 供 AI 自查，
  其描述明确提示"文件读写请用 fs/bash 工具（只需所有文件访问权限）"。
  需要系统操作时 AI 会**引导用户授权**（弹 Shizuku 授权页），而非反复失败。
- **文件操作不依赖特权**：DSH 内核自带 `dsh-tool-fs`（read/write/edit）与
  bash 工具本就可用，授予「所有文件访问」即可编辑 /sdcard 文件，无需 Shizuku。
- **前台保活服务（EngineService.java）**：引擎启动时 `startForegroundService`
  拉起常驻通知服务（`foregroundServiceType="dataSync"`、START_STICKY），挂后台/
  锁屏引擎持续运行、AI 后台任务不被杀；用户主动「退出」时停止服务。
- **AI 发通知（android_notify 工具，仅需通知权限）**：MainActivity 起本地
  ServerSocket `127.0.0.1:3081`，收到 `{"title","text"}` JSON 即发系统通知；
  插件 `android_notify` **始终注册**（不依赖特权），端口由 `APP_NOTIFY_PORT`
  环境变量指定。真机验证：标题/正文正常显示。
- **权限引导页**：Shizuku 行改为「Shizuku / Root 特权（可选）」，文案说明
  不授权也能正常使用；root 探测在后台线程执行并缓存（避免主线程跑 su、
  避免 Magisk 弹窗反复触发），onResume 时重置重测。
- 版本号：versionCode 5 → **6**，versionName 1.3.3 → **1.4.0**

### 修复的 bug
- **通知空消息**：`handleNotifyConnection` 用 `readLine()` 读到空行即停，但 HTTP
  正文在空行之后 → title/text 永远为空。改为解析 Content-Length 后精确读 body。
- **聊天记录互通（端口冲突）**：共存版与正式版抢 3080 端口，共存版引擎没起来时
  WebView 直连正式版引擎 → 显示正式版聊天记录。共存版改独立端口 3082/3083。

### DeepSeek Harness Lite（共存版，v1.4.0-lite）
- 给"不敢直接升级正式版"的用户试用：包名 `com.deepseek.harness.beta`（与正式版
  完全独立共存）、外部目录 `/sdcard/DeepSeekHarnessLite/`、独立端口 3082/3083、
  独立 dshhome（API Key 需单独填）。
- 真机验证通过：无 Shizuku 时 fs 工具建文件成功、聊天记录与正式版隔离、通知
  标题正文正常、需特权时引导授权。

### 验证
- [x] 构建通过（versionCode 6，dex 含 MainActivity/EngineService 102336 bytes）
- [x] 未授权场景：工具列表仅 shizuku_status（node 实测四种场景）
- [x] 引擎自测 HTTP 200（payload 实测）
- [x] 真机（Lite 版）：无 Shizuku 时 AI 成功创建 /sdcard/Download/test.txt
- [x] 真机（Lite 版）：通知标题/正文正常显示
- [x] 真机（Lite 版）：需特权操作时引导授权（弹 Shizuku 授权页）

---

## v1.3.3（修复：相册出现大量"零分零秒视频" · 2026-08-18）

> **背景**：外部运行目录 `/sdcard/DeepSeekHarness/dshroot` 含 2 万+ 文件
> （node_modules 的 .js/.ts/.d.ts 等），Android MediaStore 对未知类型文件做
> **内容嗅探**，把大量文本文件**误判为视频** → 相册出现"零分零秒"的假视频，
> 所有使用外部 dshroot 的用户都会遇到。

### 修复
- **MainActivity 启动时自动创建 `/sdcard/DeepSeekHarness/.nomedia`**：
  MediaStore 忽略整个外部目录（含 dshroot），相册不再出现误判文件。
  幂等（已存在则跳过），外部目录可写时生效。
- 版本号：versionCode 4 → **5**，versionName 1.3.2 → **1.3.3**

### 用户侧修复（已装旧版的用户）
- 手动创建：文件管理器在 `/sdcard/DeepSeekHarness/` 下新建空文件 `.nomedia`；
  或直接升级 v1.3.3（自动创建）
- 相册里已出现的假视频可直接删除（都是 0 字节/损坏文本文件，无内容）；
  删除后若相册仍显示，重启相册或清除相册缓存

### 验证
- [ ] 构建通过（versionCode 5，dex 含 MainActivity）
- [ ] 引擎自测 HTTP 200
- [ ] .nomedia 创建逻辑进 dex（字符串验证）

---

## v1.3.2（修复：升级用户 UI 不更新 · 2026-08-18）

> **背景**：v1.3.0/v1.3.1 的侧栏改造与竖屏适配改的是**核心源码**
> （dsh-client-ui-layout / dsh-client-ui-cordis 的 client.js）。外部运行目录
> `/sdcard/DeepSeekHarness/dshroot` 采用"已有文件不覆盖"策略，而这两个
> client.js **不在强制覆盖白名单** → 从旧版升级的用户，外部目录保留旧文件，
> 页面仍是旧 UI（无三条杠侧栏、竖屏不适配）；只有干净安装/清数据重装的用户
> 才是新版 UI。真机反馈"下载 v1.3.x 页面还是旧版本"即此根因。

### 修复
- **MainActivity.java `FORCE_OVERWRITE_PREFIXES` 增加 2 项**（升级时强制覆盖）：
  - `dsh-client-ui-layout/lib/client.js`（侧栏改造：三条杠/浮层侧栏/gridColumn）
  - `dsh-client-ui-cordis/lib/client.js`（插件按钮 header 单实例）
- 版本号：versionCode 3 → **4**，versionName 1.3.1 → **1.3.2**
- buildenv 重建（清数据被删）：从 /sdcard/github 归档恢复 + 工具 wrapper 重写 + devhome 软链 + v1.3.x UI 文件同步

### 用户侧修复（已装旧版的用户）
- 方式一：直接升级 v1.3.2 → 启动时自动强制覆盖这两个文件（REVISION 变化触发补齐）
- 方式二：删除 `/sdcard/DeepSeekHarness/dshroot` 重开 App（全量重新解压）

### 验证
- [x] dex 含 MainActivity ✅（94140 bytes，构建校验通过）
- [x] 引擎自测 HTTP 200 ✅（v1.3.2 payload 完整实测）
- [x] 白名单含 layout/cordis client.js ✅（dex 字符串验证）
- [x] payload 含 v1.3.x 新版 UI（gridColumn / toggleSidebar / header.utilities）✅

---

## v1.3.1（移动端 UI 打磨 + 插件按钮核心化 · 2026-08-17 深夜 ~ 08-18）

### 移动端布局打磨（mobile.css + mobile.js，运行目录同步生效）
- **消息操作条多行**：`.p-xYUq_actions`（复制/赞踩/分支/时间/耗时/token）`flex-wrap:wrap !important`
  （压过插件运行时注入的同名规则），窄屏不再一行溢出。
- **标题栏让位三条杠**：`.wSkVaW_header` 窄屏 `padding-left:52px`。
- **正文/输入框扩宽**：消息区 `.Md3f7G_scroll` padding 32→8px；composer
  `--dsh-composer-side-clearance/inset` 16/8→4px。
- **输入栏按钮重叠修复**：滚动容器 `scrollbar-gutter:auto`（有会话时滚动条不再占位压窄输入栏）；
  输入栏右侧按钮保持一行（nowrap）+ 模型名限宽 26vw + gap 6px。
- **Bash 工具卡片防横向溢出**：命令/输出 `white-space:pre-wrap` + `word-break:break-all`。
- **后台任务菜单防溢出**：`.QsffPG_menu` 窄屏 `right:0` 左展开 + 限宽。
- **设置页单栏适配**：`.VOzbGW_panel` 窄屏上下排列（导航横排可横向滚动 + 内容区
  `overflow-y:auto` 可滚动）。
- **键盘防自动聚焦**：mobile.js 用 pointerdown 位置判断 focus 来源——切换话题/新会话
  自动聚焦输入框时立即 blur（不弹键盘），只有用户点击输入框才弹。

### 插件按钮（真·改核心源码）
- **`dsh-client-ui-cordis/lib/client.js`**：CordisPanel 注册从 `sidebar.footer.action`
  改为 `conversation.session.header.utilities`（**单一实例**）：
  - 修掉双实例 bug（sidebar+header 各注册一份 → 两个独立 open 状态 → 面板开在一侧、
    点另一侧按钮关不掉）；
  - 面板本身 fixed 全屏（bottom:128px 左下），不依赖侧边栏展开。
- **位置**：mobile.css 把 header 里的 `[data-cordis-badge]` `position:fixed` 到三条杠
  下方（52px/8px，32×32，隐藏文字只留图标）；**不动 DOM**（按钮留在 #root 内，
  React 事件委托才有效）。
- 侧边栏里不再有插件按钮（注册已移走）。

### 其他
- **Session log 按钮禁用**：补丁加 `session-log-download: disabled`（右上角导出 ZIP 入口移除）。
- **补丁自动加载澄清**：`$DSH_HOME/cordis.patch.yml` 由 profile-boot homePatches 自动加载，
  App 启动**不需要 --patch**；加了反而 duplicate 崩溃（曾误改 MainActivity 又撤回）。
- **备份**：`/sdcard/github/backup-20260817-可运行版/`（mobile-patch + dshroot 关键文件 + 可用 APK）。

### 验证
- 最终 APK：dex 含 MainActivity ✅、payload 含核心注册 ✅、引擎自测 HTTP 200 ✅
- 真机验证：插件按钮开关面板正常、AI 生成插件审批后可关闭 ✅

---

## v1.3.0（闪退修复 + 侧栏改造真正落地 · 2026-08-17 深夜）

> ⚠️ **v1.2.0 的 APK 是坏的（安装即闪退）**：build.sh 里 javac 路径硬编码指向
> 已不存在的旧 Termux 目录（/data/data/com.coomi.android/...），javac 失败但被
> `|| true` 吞掉，dex 里没有 MainActivity，安装后启动报 ClassNotFoundException。

### 修复内容
- **build.sh**：javac 路径改为从 DSH_DEV_HOME 推导 + 失败立即中止 + class 数非空校验
  + **dex 必须含 MainActivity 才放行**（防止再产出坏包）；构建环境 env.sh / d8 /
  apksigner 硬编码旧路径全部重写为新路径。
- **重新构建**：javac 49 个 class，dex 含 MainActivity（93KB），payload 引擎自测 HTTP 200。

### 侧栏改造（真·改核心文件，替代 v1.2.0 外部注入）
- **直接修改 `dsh-client-ui-layout/lib/client.js`（AppFrame 组件源码）**：
  - 窄屏（viewport<1024，竖屏+手机横屏）时 sidebar 列恒 0 宽 → 聊天区全宽；
  - 折叠时左上角渲染**三条杠按钮**（内联 SVG + 内联样式，点击 `actions.toggleSidebar()`）；
  - 展开时侧栏转 **fixed 浮层**（280px，z-30，阴影），不挤压聊天区；
  - 展开时渲染**全屏遮罩**（z-25），点击遮罩任意位置即收起侧栏；
  - 窄屏隐藏侧栏/详情拖拽把手；
  - **CenterColumn/DetailsColumn 显式指定 `gridColumn: 2/3`**——否则 sidebarCol 展开时
    转 fixed 脱离 grid 后，grid 自动放置会把聊天区排到第 1 列（0px）→ 聊天区消失、
    右边变成空白详情列（真机踩坑：展开侧栏后右侧纯色/内容靠左，靠这个修复）。
- **mobile-patch 清理**：删除 v1.2.0 的旧三条杠注入（mobile.js 第二个 IIFE + mobile.css
  相关段落），避免与 AppFrame 原生实现重复（双按钮/双实现冲突）；保留 v1.1.1 的
  软键盘适配与触摸优化。
- 类名事实纠正：`.pI_x6G_*`（layout）、`.hHd-Xa_*`（sidebar）、`data-sidebar-collapsed`
  都是**真实存在**的运行时插件 CSS module 类名（不在预构建 bundle 里，由
  ModuleLoader 动态注入），v1.2.0 的旧实现类名其实没写错，但改为原生实现更干净。
- 快速打包：payload.zip 仅 3 个小文件变化 → 用 `jar uf` 就地更新 APK 条目 +
  zipalign + 重签（约 2 分钟，跳过全量构建）。

### 验证
- 最终 APK：dex 含 MainActivity ✅、payload 含新 client.js ✅、引擎自测 HTTP 200 ✅
- 运行目录同步：外部 /sdcard/DeepSeekHarness/dshroot（App 立即生效，重启即可见）✅

---

## v1.2.0（竖屏 UI 改造 · 2026-08-17）

> 竖屏适配升级：**左侧竖栏（rail）改为左上角三条杠按钮**，聊天区全宽；
> 点三条杠等价于原 rail 顶部小鲸鱼按钮（展开/收起侧栏），功能不缺。

### 改动内容
- **mobile.css**：
  - 竖屏（portrait）下 AppFrame 侧栏列恒为 0 宽（`grid-template-columns: 0 minmax(0,1fr) 0 !important`），
    左侧 56px rail 整列隐藏，聊天区全宽；
  - 点三条杠展开侧栏时，侧栏以 **fixed 覆盖层**浮在聊天区上方（`position: fixed; z-index: 30;` + 阴影），
    **不挤压**聊天区；横屏不受影响；
  - 竖屏隐藏侧栏拖拽把手；
  - 新增 `.dsh-mobile-menu-btn`：左上角三条杠按钮，透明底、主题色线条（`--dsw-alias-label-secondary`），
    尺寸 36px（图标 22px，与小鲸鱼 24px 相当），按压时才出现柔和背景，风格与 App 主题一致。
- **mobile.js**：新增注入逻辑——
  - 竖屏 + rail 折叠（`data-sidebar-collapsed`）时显示三条杠按钮；
  - 点击 = 等价于点击原 rail 顶部小鲸鱼按钮（`.hHd-Xa_toggle.click()` → `toggleSidebar`）；
  - 展开侧栏后按钮自动隐藏（侧栏自带收起按钮），收起后恢复显示；
  - MutationObserver 监听 `data-sidebar-collapsed` 变化 + resize/orientationchange 刷新。

### 验证
- 新 APK（`android-app/DeepSeekHarness.apk`，109MB）构建成功并签名，包内 payload 已含 v2 mobile.css/js；
- 运行目录同步：`/sdcard/DeepSeekHarness/dshroot`、内部 `payload/dshroot`（立即生效，无需重装）。

---

## v1.1.1 稳定基线（原始记录）

> ⚠️ **说明**：本 PR 为**稳定基线**（对应实测可用的 v1.1.1），
> **UI 移动端适配属于半成品（WIP）**：侧边栏自动收起、设置页"点击跳转"等实验性
> UI 变换在部分设备上可能引入启动/渲染风险，故**不包含在本基线**（将以独立分支/后续版本提供）。
> 本基线优先保证：**启动稳定 + 基础移动端可用**。

> 本 PR 基于原作者 v0.1.0 源码，聚焦两类问题：
> **① 真机启动稳定性（node 运行时 + 服务器保活）② 基础移动端可用性（竖屏/触摸/退出）**
> 改动文件：`AndroidManifest.xml` / `build.sh` / `MainActivity.java` / `mobile-patch/*` / `README.md`

---

## 一、启动稳定性（修复真机 ERR_CONNECTION_REFUSED）

### 1.1 Node 运行时 soname 符号链接丢失（致命，已修复）
- **根因**：`build.sh` 用 `jar cMf` 打包 payload，**把符号链接全部压平成普通内容**；解压后
  `runtime/lib/` 只剩带版本号的文件（`libz.so.1.3.2` 等），`libz.so.1`、`libcrypto.so`、
  `libssl.so`、`libsqlite3.so.0` 全部缺失 → node 启动即报
  `CANNOT LINK EXECUTABLE: library "libz.so.1" not found`。
- **修复**：`build.sh` 在组装 payload 时**按 LINKS.txt 把 soname 目标复制成同名实体文件**
  （不依赖设备是否支持软链接，动态加载器按名字找文件即可）。经真机验证 node 正常启动。
  - 代价：payload 增大 ~34MB（版本化 .so 的实体副本）。

### 1.2 服务器保活：看门狗 + WebView 自动重试（新增）
- **根因**：原版 `webView.loadUrl()` 只执行一次；若 node 未就绪或进程被系统回收，页面永久停在
  `net::ERR_CONNECTION_REFUSED`，且没有任何恢复手段。
- **修复**（`MainActivity.java`）：
  - **WebView 失败重试**：主框架加载失败时每 2.5s 自动 `loadUrl(URL_HOME)`，直到服务器就绪（上限 120 次）。
  - **node 看门狗**：后台线程每 5s 检查 `healthOk()` + `nodeProcess.isAlive()`；node 死亡且服务不可用
    时自动重启引擎并刷新页面（20s 防抖避免风车重启）。

### 1.3 外部 dshroot 前端资源强制覆盖（保证 UI 资源随 APK 更新）
- **根因**：外部 `/sdcard/DeepSeekHarness/dshroot` 采用"已有文件永不覆盖"策略，
  旧版本的 `dist/mobile.css`/`mobile.js`/`index.html` 不会被新 APK 覆盖 → 移动端样式不生效。
- **修复**：将 `dsh-web-frontend/dist/mobile.css`、`mobile.js`、`index.html` 加入
  `FORCE_OVERWRITE_PREFIXES` 强制覆盖白名单，随 APK 更新。

---

## 二、基础移动端可用性

### 2.1 解锁竖屏（AndroidManifest.xml）
- `android:screenOrientation="sensorLandscape"` → `"unspecified"`（自由旋转）。
- 版本号升至 `versionCode=2 / versionName=1.1.0`。

### 2.2 mobile.css 重写（修复"死代码"）
- **根因**：原 mobile.css 使用的类名（`gdEzaW_`、`hHd-Xa_`、`pbvGtq_`、`qSYn7G_` 等）在
  真实前端构建（0.1.0-rc.6 dist）中**不存在**，全部规则无效。
- **修复**：改用从真实构建提取的类名（`_rail_1hk8w`、`_wrap_1ao1y`、`_answer_d4nqi`、
  `_markdown_1nba0`、`_block_10eou`、`_item_19372` 等）：触摸优化（点击目标 ≥44px）、
  竖屏内容全宽、鲸鱼蓝皮肤（`--dsw-alias-brand-primary: #4D6BFE`）。

### 2.3 mobile.js（稳定基线）：软键盘适配
- VisualViewport + translateY 方案，竖屏横屏通用，rAF 节流。
- 刻意**不包含**实验性 UI 变换（侧边栏自动收起、设置页点击跳转等），以保证各设备启动/渲染稳定。

### 2.4 退出交互（MainActivity.java）
- 右上角常驻「退出」浮动按钮（确认对话框后退出）。
- 系统返回键：有历史先 `goBack()`（可关侧边栏），无历史弹确认退出。

---

## 三、验证情况

| 项目 | 结果 |
|---|---|
| node 启动（soname 修复） | ✅ 真机验证 node 正常运行 |
| ERR_CONNECTION_REFUSED 恢复 | ✅ 看门狗 + 自动重试生效 |
| 竖屏自由旋转 | ✅ 已解锁 |
| 移动端样式注入生效 | ✅ 强制覆盖白名单保证更新 |
| 退出按钮 / 返回键 | ✅ |
| 依赖完整性 | ✅ payload 内 239 个依赖齐全（云端实测 dsh --version 可跑） |

## 四、构建与注意事项

- 构建：`bash android-app/build.sh`（需 android.jar、java-17、aapt/d8/zipalign/apksigner、release.jks）。
- 签名：本 PR 未包含签名密钥；安装包需自行签名。
- targetSdk 保持 28（≥29 会导致 node 二进制 EACCES）。
- 首次启动需解压 payload（2 万+ 文件，约 1-3 分钟），期间勿切后台。
- 外部存储权限未授予时回退内部 dshroot；授予后外部优先（已有文件不覆盖，白名单除外）。
