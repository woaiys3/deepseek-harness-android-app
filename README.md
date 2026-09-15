# DeepSeek Harness 手机版（Android）

> 把 DeepSeek Harness（DSH）打包成**可直接安装的 Android APK** —— 装上就能用，还能让 AI **免 Root 真正操作手机**。

![License](https://img.shields.io/github/license/woaiys3/deepseek-harness-android-app)
![Stars](https://img.shields.io/github/stars/woaiys3/deepseek-harness-android-app)
![Release](https://img.shields.io/github/v/release/woaiys3/deepseek-harness-android-app)

## ✨ 核心亮点

- 📦 **APK 一键安装**：不用 Termux、不用敲命令，下载安装即用（包名 `com.deepseek.harness`）
- 🔓 **免 Root 系统特权**：通过 Shizuku 打通系统 shell —— AI 能**装应用、点屏幕、改系统设置、截图、模拟输入**，这是"手机上的 AI Agent"，不只是聊天窗口
- 🟢 **可选特权，不授予也能正常用**（v1.4.0）：不装 Shizuku/无 root 也能用——文件读写、预览、编辑只需「所有文件访问」权限；未授权时 AI 不会反复尝试系统操作，需要时会**引导你授权**
- 🔀 **Root 优先，Shizuku 备用**（v1.4.0）：有 root 走 su 通道，无 root 走 Shizuku，自动选择
- 👁️ **无障碍屏幕助手**（v1.7.0）：系统设置开启「DeepSeek Harness 屏幕助手」后，AI 能**读屏、点击、输入、滚动、无障碍截图理解**——**不需要 root / Shizuku**，与特权通道互补
- 🖥️ **虚拟屏 vscreen**（v1.10+）：AI 可以创建一块**独立于主屏的真·虚拟屏**，把 App 启动进去、在里面点击/滑动/输入，**你的主屏照常用**；右上角悬浮窗**实时显示虚拟屏画面**（H.264 视频流，可拖动、可双指缩放），AI 在干什么全程可见。实现移植自开源项目 [Operit](https://github.com/AAswordman/Operit)（LGPL-3.0，详见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)）
- 🎛️ **原生控制台**（v1.12+）：冷启动先进 App 自己的控制台（**解压 / 权限 / 插件开关 / 日志** 四页），不必先等引擎起来；引擎状态判定全面重做——探测不再吃掉一次性 token、不再误报“未启动”、不会重复拉起两个引擎
- 🔐 **Shizuku 通道改走 App 进程**（v1.13.1+）：特权命令执行从“引擎内 `rish` 子进程”改为**经 App 进程内的 Shizuku API**（新增本地 `/shell` 路由 + 随机令牌鉴权），根治手机上偶发的 `Request timeout. The connection between the current app … and Shizuku app …`
- ⏰ **前台保活**（v1.4.0）：AI 干活时挂后台/锁屏不被杀，任务完成推送通知
- 🔔 **AI 发通知**（v1.4.0）：只需通知权限，任务完成/需要关注时推送到通知栏
- 🧠 **完整 DSH 内核**：`@deepseek-ai/dsh` **0.1.5-rc.1**（v1.11 升级），保留插件生态 + RPC API，前端用 DSH 原生界面
- 📱 **移动端适配**：触摸优化 + 软键盘适配 + 首次启动权限引导页（9 项权限一站式配置）
- 💾 **卸载不丢数据**：dshroot 外置到 `/sdcard/DeepSeekHarness`，重装/升级不清空 AI 的运行时改动
- 🐋 鲸鱼品牌图标，横竖屏自由旋转

## 📸 界面预览

> 截图于 v1.2.0（2026-08-17）与 v1.4.0（2026-08-20），完整文件见 `docs/screenshots/`。

| | | |
|---|---|---|
| ![界面截图 1](docs/screenshots/screenshot-1.jpg) | ![界面截图 2](docs/screenshots/screenshot-2.jpg) | ![界面截图 3](docs/screenshots/screenshot-3.jpg) |
| ![界面截图 4](docs/screenshots/screenshot-4.jpg) | ![界面截图 5](docs/screenshots/screenshot-5.jpg) | ![界面截图 6](docs/screenshots/screenshot-6.jpg) |

**v1.4.0（Lite 共存版）实测截图**：

| | | |
|---|---|---|
| ![Lite 截图 1](docs/screenshots/screenshot-lite-1.jpg) | ![Lite 截图 2](docs/screenshots/screenshot-lite-2.jpg) | ![Lite 截图 3](docs/screenshots/screenshot-lite-3.jpg) |

## 🛠️ 手机端插件（本项目的核心特色）

| 插件 | 能力 |
|---|---|
| `dsh-tool-shizuku` | 特权 shell：任意系统命令（pm/am/settings/dumpsys…），异步执行 + 环境消毒 + dex 只读自愈 |
| `dsh-tool-android` | 结构化系统操作：包管理 / 应用管理 / 系统设置 / 截图 / 模拟输入 |
| `dsh-tool-accessibility` | 无障碍读屏 + 模拟操作（v1.7.0）：读控件树 / 点击 / 输入 / 返回主页 / 滚动 / 无障碍截图理解 |
| `dsh-tool-vscreen` | **虚拟屏（v1.10+）**：建屏 / 把 App 启动到虚拟屏 / 看虚拟屏画面 / 在虚拟屏上点击·滑动·按键 / 关屏（8 个工具） |

> 通过这四个插件，AI 不再只是"聊聊天"，而是能**真正控制你的手机**——特权通道（root/Shizuku）负责系统级操作，无障碍通道（无需授权）负责读屏与交互，虚拟屏通道让 AI 在**不打扰主屏**的前提下跑自动化任务。

## 👁️ 无障碍屏幕助手（v1.7.0）

让 AI **看着屏幕操作手机**：读屏、点击、输入、滚动、截图理解——**不需要 root / Shizuku**。

### 开启方式
1. 系统设置 → 无障碍 →（已下载的服务/服务）→ 开启「DeepSeek Harness 屏幕助手」
2. 在 App 里让 AI：先用 `android_screen` 读屏 → 用 `android_tap` / `android_type` / `android_scroll` 操作 → 需要看图时用 `android_see` 截图理解

### AI 可用工具
| 工具 | 能力 |
|---|---|
| `android_a11y_status` | 查询无障碍服务状态（未开启时返回引导文案） |
| `android_screen` | 读当前屏幕控件树（文字 / 坐标 / 可点击性 / 可输入性） |
| `android_tap` | 按文字 / 描述 / 坐标点击 |
| `android_type` | 输入文本到输入框（WebView / 网页输入框用 `paste:true` 走剪贴板粘贴） |
| `android_back` / `android_home` | 系统返回键 / 回桌面 |
| `android_scroll` | 上 / 下 / 左 / 右滚动 |
| `android_see` | 无障碍截图并发送给视觉模型理解（需 Android 11+ 与支持图片的模型，如 `deepseek-v4-flash-vision-exp`） |

> 无障碍通道与特权通道互补：无障碍不依赖授权、擅长读屏与点击；Shizuku/root 通道擅长系统级操作（装应用 / 改设置 / 系统输入）。

## 🖥️ 虚拟屏 vscreen（v1.10+）

让 AI 在**一块独立于主屏的虚拟屏**里干活：你的主屏照常用，AI 在自己的屏里启动 App、点击、滑动、输入；右上角悬浮窗**实时显示虚拟屏画面**，AI 在干什么全程可见。

### 用法（3 步）

1. **安装 APK**（正式版或 Lite 共存版）
2. **装好并授权 [Shizuku](https://shizuku.rikka.app/)**（或设备有 root）—— 虚拟屏服务端必须以 shell 身份运行，没这一步虚拟屏用不了
3. **打开一次 App** —— 虚拟屏桥自动启动（此时它会拉起 shell 身份的服务端）
4. **让 AI 建屏**（或直接调 `android_vscreen_create`）→ 右上角自动弹出预览悬浮窗（**可单指拖动、双指缩放**）

### 尺寸：一律手机比例

- 竖屏 **9:16**（默认 1008×1792）、横屏 **16:9**（1792×1008）
- 即使显式传了非手机比例的宽高（如 1520×720），服务端也会**归一化**成 16:9（v1.11 修复）
- 切换横竖屏需先关屏再建屏

### AI 可用工具

| 工具 | 能力 |
|---|---|
| `android_vscreen_create` | 建虚拟屏（`orientation` 可选 portrait / landscape） |
| `android_vscreen_status` | 查询当前虚拟屏（displayId / 宽高 / 是否在跑） |
| `android_vscreen_launch` | 把指定 App 启动到虚拟屏 |
| `android_vscreen_see` | 截虚拟屏画面发给视觉模型（返回换算系数，供坐标换算） |
| `android_vscreen_tap` / `swipe` / `key` | 在虚拟屏上点击 / 滑动 / 按键 |
| `android_vscreen_close` | 关闭虚拟屏 |

### 权限

| 权限 | 用途 | 必需？ |
|---|---|---|
| **Shizuku 或 root** | **整个虚拟屏功能**——服务端（现役核心 `vscreen/Main.java`）必须以 **shell 身份**运行：建屏、截图、把 App 启动到虚拟屏、注入触摸/按键全在它身上 | **必需**。没有 Shizuku/root 就拉不起服务端，**虚拟屏完全不可用**（不是“能看不能点”） |
| 悬浮窗（显示在其他应用上层） | 虚拟屏预览窗 | 要看预览必须授权 |
| 存储（所有文件） | 虚拟屏截图保存 | 截图必需 |
| ~~无障碍~~ | — | **不需要**。虚拟屏与无障碍服务无关（不读控件树、不走 `dispatchGesture`），插件/服务端/桥都没有引用无障碍 |

### 技术要点

预览**不是轮询截图**，而是 **服务端 MediaCodec H.264 编码 → App 内解码 → 渲染到悬浮窗**的实时视频流。
该视频流方案与虚拟屏创建机制**移植/对齐自开源项目 [Operit](https://github.com/AAswordman/Operit)**（LGPL-3.0）。

### 已知限制

- 需要 **Android 11+**；
- **需要 Shizuku 或 root**（原因见上表：服务端跑在 shell 身份下）；
- 正式版与 Lite 共存版**同时启动**时 8999 端口互斥 → 虚拟屏实际二选一；
- 虚拟屏为 PUBLIC 类型显示，部分系统弹窗（如输入法）行为与主屏有差异。

## 📦 安装

下载 [Releases](https://github.com/woaiys3/deepseek-harness-android-app/releases) 里的 APK 安装即可：

- **`DeepSeekHarness-official-v1.13.6.apk`（正式版，推荐）**：包名 `com.deepseek.harness`，从旧版本同签名升级
- **`DeepSeekHarness-Lite-v1.13.6.apk`（Lite 共存版）**：包名 `com.deepseek.harness.beta`（端口 3082），与正式版完全独立、可同时安装；数据独立在 `/sdcard/DeepSeekHarnessLite/`，API Key 需单独填
- **`DeepSeekHarness-compat-v1.13.6.apk`（兼容版）**：包名 `com.deepseek.harness.compat`（端口 3084），老 WebView 设备可用

要求：
- Android 7.0（API 24）及以上
- 系统操作能力需配合 [Shizuku](https://shizuku.rikka.app/)（免 Root 授权）或有 root；**都不授予也能正常使用**（文件操作只需「所有文件访问」权限）
- API Key 在 App 内页面填写，只存本机，绝不打包进 APK

> 🆘 **打不开 / 白屏 / 连接失败？** 先看 [启动排查](docs/启动排查.md)（常见问题都能自助解决）。

## 🐞 遇到问题？日志在哪 / 怎么反馈

- **v1.12 及以后（有控制台）**：打开 App → 控制台 →「日志」页 —— 「查看日志」看 `dsh-web.log` 末尾 200 行（可**截图**发来），「分享」把日志**以文件形式**发出（QQ / 微信 / 邮件都能选；启动失败时还会带上 `startup-diag.txt`）
- **不用 App 也能取（v1.7.1 起，无需 root / adb）**：用文件管理器进手机存储根目录 ——
  正式版 `/sdcard/DeepSeekHarness/dsh-web.log`、Lite 共存版 `/sdcard/DeepSeekHarnessLite/dsh-web.log`、兼容版 `/sdcard/DeepSeekHarnessCompat/dsh-web.log`；
  启动失败时同目录另有 `startup-diag.txt`（错误 + 端口 + node 是否存活 + 日志尾部）
- **v1.7.0 及更早**：既没有控制台也没有外部日志镜像 → 只能 `adb logcat | grep -i deepseek`（引擎日志在 App 私有目录，无 root 一般读不到）；建议先升级到 v1.12+
- 反馈请附：**日志** + 机型 / Android 版本 + 用的哪个包（正式版 / Lite / 兼容版）+ 复现步骤

## 📁 目录结构

```
CHANGES.md              版本改动记录（含 @Suyi222 贡献的 v1.1.1 稳定基线）

android-app/             APK 构建工程
├── build.sh             一键打包脚本
├── env.sh               编译工具链环境（可 export PREFIX 覆盖）
├── AndroidManifest.xml  包名/targetSdk(28)/横竖屏自由旋转/Shizuku 声明
├── libs/                Shizuku 官方 aar（api/provider/aidl 13.1.5）
├── res/                 图标 + 字符串资源
├── sdk/                 放 platform android.jar（见 sdk/README.md）
└── src/.../MainActivity.java   Android 原生壳（权限引导页/加载页/引擎启动）

mobile-patch/            移动端适配（注入 DSH 前端，不覆盖原生代码）
├── inject.sh            注入脚本（mobile.css + mobile.js 到 dist）
├── mobile.css           触摸优化 + 竖屏适配 + 插件管理页 UI 适配
└── mobile.js            软键盘适配（VisualViewport 方案，横竖屏通用）

plugins/                 手机端自定义 DSH 工具插件
├── dsh-tool-shizuku/    特权 shell（Shizuku 通道）
├── dsh-tool-android/    结构化系统操作（包管理/应用/设置/截图/输入）
└── dsh-tool-accessibility/  无障碍读屏/模拟操作（v1.7.0）

dsh-patches/             DSH 源码补丁归档 + overlay
├── README.md            补丁说明（适配原因/升级 DSH/打包）
├── apply.sh             重新应用源码补丁
└── overlay/             改好后的源码文件

config/cordis.patch.yml  DSH 组合配置（禁原生模块 + 插入 bash-local/shizuku/android 插件）

docs/开发指南.md            项目开发指南（架构/常用命令/注意事项）
```

## 🔨 构建说明

详见 `docs/开发指南.md` 第六节「常用命令」与第七节「注意事项」。

关键点：
- `targetSdk` 必须保持 **28**（≥29 会导致 node 二进制 EACCES 起不来）
- 需准备 `runtime/`（node v26 + 依赖库）和 `dshroot/`（DSH 内核）才能打完整 APK
- `build.sh` 会自动注入 mobile.css/mobile.js，并做 API Key 安全检查

> ⚠️ 这是源码与配置仓库，**不含 APK 二进制、签名密钥（release.jks）、node 运行时、payload.zip、凭证文件**。
> 📦 安装包（DeepSeekHarness.apk）、node 运行时与 DSH 内核分块包见 [Releases](https://github.com/woaiys3/deepseek-harness-android-app/releases)；构建源码前需准备 runtime/ 与 dshroot/（分块包合并方法见 Release 说明）。

## 💬 交流讨论

遇到问题、想提建议、或想交流用法？欢迎加入 QQ 群 / QQ 频道：

| QQ 群 | QQ 频道 |
|---|---|
| ![QQ 群](docs/qq-group.jpg) | ![QQ 频道](docs/qq-channel.jpg) |

> 也可以直接在 [Issues](https://github.com/woaiys3/deepseek-harness-android-app/issues) 反馈，我会尽快回复。

## 🙏 致谢与开源许可

- **[Operit](https://github.com/AAswordman/Operit)**（[AAswordman](https://github.com/AAswordman)）—— 本项目**虚拟屏（vscreen）功能移植/对齐自 Operit 的 shower 模块**（虚拟屏创建、H.264 视频流预览、反射/隐藏 API 规避等）。Operit 采用 **LGPL-3.0**，因此本仓库的虚拟屏相关文件**同样按 LGPL-3.0 分发**。完整致谢与许可说明见 **[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)**。
- **[Shizuku](https://github.com/RikkaApps/Shizuku)**（[RikkaApps](https://github.com/RikkaApps)，Apache-2.0）—— 免 Root 特权通道。
- **DeepSeek Harness（`@deepseek-ai/dsh`）** —— 本项目的运行内核。
- **Node.js**（MIT）—— APK 内置运行时。

## 📄 许可证

本项目源码**主体**采用 [MIT](LICENSE) 许可证。

- ⚠️ **例外**：`android-app/src/com/deepseek/harness/vscreen/`（虚拟屏）**移植自 [Operit](https://github.com/AAswordman/Operit)，按 LGPL-3.0 分发**。
  这部分及其衍生修改**必须继续以 LGPL-3.0 分发**并保留原始声明，详见 **[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)**（LGPL-3.0 / GPL-3.0 全文见 [`licenses/`](licenses/)）。
- 依赖的 DSH 内核（@deepseek-ai/dsh）为 MIT；Shizuku SDK 为 Apache-2.0；node 运行时为 MIT。
- 仓库不含签名密钥与凭证；安装包与运行时见 [Releases](https://github.com/woaiys3/deepseek-harness-android-app/releases)。
