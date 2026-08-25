# DeepSeek Harness Android · 移动端优化改动清单

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
