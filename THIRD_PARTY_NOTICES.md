# 第三方组件与开源许可声明 · Third-Party Notices

本仓库**整体**采用 **MIT** 许可（见 [`LICENSE`](LICENSE)）。

但**虚拟屏（vscreen）功能**包含来自 **Operit** 的代码，**该部分按 LGPL-3.0 分发**。
同一个仓库里同时存在两种许可，**请分别遵守**——尤其当你要复用/修改虚拟屏相关文件时。

---

## 1. Operit — LGPL-3.0（虚拟屏 vscreen）

| 项 | 内容 |
|---|---|
| 项目 | **Operit** — Android 上能力强大的 AI Agent |
| 仓库 | <https://github.com/AAswordman/Operit> |
| 作者 | [AAswordman](https://github.com/AAswordman) |
| 许可证 | **GNU Lesser General Public License v3.0（LGPL-3.0）** |
| 许可证全文 | [`licenses/lgpl-3.0.txt`](licenses/lgpl-3.0.txt)（LGPL-3.0 以 GPL-3.0 为基础，故同时提供 [`licenses/gpl-3.0.txt`](licenses/gpl-3.0.txt)） |

### 1.1 本项目中源自 Operit 的部分

虚拟屏功能的实现**移植/对齐自 Operit 的 shower（虚拟屏）模块**。其中两个文件在源码里**已经带有原始许可声明**：

```java
// android-app/src/com/deepseek/harness/vscreen/FakeContext.java
// android-app/src/com/deepseek/harness/vscreen/Workarounds.java
// 第 1 行： Licensed under LGPL-3.0; source: https://github.com/AAswordman/Operit (shower shell)
```

| 文件 | 与本项目的关系 |
|---|---|
| `android-app/src/com/deepseek/harness/vscreen/FakeContext.java` | **取自 Operit**（shower shell）；最小 `Context` 实现，供 app_process 环境下构造 `DisplayManager` 使用 |
| `android-app/src/com/deepseek/harness/vscreen/Workarounds.java` | **取自 Operit**（shower shell）；隐藏 API / 反射规避处理 |
| `android-app/src/com/deepseek/harness/vscreen/Main.java` | 虚拟屏服务端。其**核心机制移植/对齐 Operit**：`MediaCodec` H.264 编码器 Surface + `setVideoSink` 推流、SPS/PPS（csd-0/csd-1）缓存重放、`DisplayManager.createVirtualDisplay()` 建屏、`InputManager` 反射定向注入、`ActivityOptions.setLaunchDisplayId` 启动到指定虚拟屏、idleWatcher 保活 |
| `plugins/dsh-tool-vscreen/` | 本项目自研的 DSH 插件封装（通过 HTTP 调用上述服务端；接口与工具 schema 为本项目设计） |

> **保险起见的口径**：尽管只有上表前两个文件带显式 LGPL 头，本声明**按最保守方式把整个虚拟屏模块
> （`android-app/src/com/deepseek/harness/vscreen/`）视为 LGPL-3.0 覆盖范围**。
> 这样无论后续维护者把哪些文件判定为"衍生作品"，都不会出现许可缺口。

### 1.2 如果你要继续修改这些文件

- **修改后的这些文件（及其衍生）必须继续以 LGPL-3.0 分发**，并保留原始版权与许可声明（LGPL-3.0 §2、§4）；
- 不得对本模块附加任何"禁止修改 / 禁止为调试而反向工程"的限制（LGPL-3.0 §4）；
- 本项目**不要求**你对仓库其余（MIT 部分）做任何开源——两部分的许可彼此独立。

### 1.3 本项目已履行的 LGPL-3.0 义务

| 义务（条款） | 履行方式 |
|---|---|
| 显著声明"使用了该 Library 且该 Library 及其使用受 LGPL-3.0 约束"（§4a） | 本文件 + `README.md` 的「致谢与开源许可」章节 |
| 随作品附带 GPL-3.0 与 LGPL-3.0 全文副本（§4b） | [`licenses/lgpl-3.0.txt`](licenses/lgpl-3.0.txt)、[`licenses/gpl-3.0.txt`](licenses/gpl-3.0.txt) |
| 提供可重新链接/重新编译的对应源码（§4d） | **本仓库即为完整对应源码**（含 `android-app/`、`plugins/`、构建脚本 `android-app/build.sh`），任何人可获取、修改、自行编译与重新链接 |
| 允许为调试修改而反向工程（§4） | 本项目未附加任何禁止条款 |

> 若发现归属有遗漏或希望调整声明方式，欢迎提 [Issue](https://github.com/woaiys3/deepseek-harness-android-app/issues) 或直接联系作者。

---

## 2. Shizuku — Apache-2.0

| 项 | 内容 |
|---|---|
| 项目 | **Shizuku**（免 Root 使用系统 API 的特权通道） |
| 仓库 | <https://github.com/RikkaApps/Shizuku> |
| 许可证 | Apache License 2.0 |

**使用方式**：本项目的特权通道通过 Shizuku 提供的 SDK 与 `rish`（Shizuku shell loader）实现。

- `android-app/libs/shizuku-api.aar`、`shizuku-aidl.aar`、`shizuku-provider.aar`
- APK 内置 `assets/rish_shizuku.dex`（运行时由 App 释放，用于在 shell 身份下执行特权命令）

**未做修改**，按原样分发。

---

## 3. Node.js 运行时与 npm 依赖

| 项 | 说明 |
|---|---|
| `runtime/`（APK 内置） | **Node.js** 运行时（MIT 许可），随包分发 |
| dshroot 内的依赖闭包 | 来自 npm 的 `@deepseek-ai/*` 及其他第三方包，**版权与许可归各自作者所有**，随包原样分发；本仓库不重新授权它们 |

---

## 4. 摘要

| 范围 | 许可证 |
|---|---|
| 本仓库其余全部内容（App 外壳、4 个自研插件、补丁面、构建脚本、文档） | **MIT** |
| `android-app/src/com/deepseek/harness/vscreen/`（虚拟屏） | **LGPL-3.0**（源自 Operit） |
| `android-app/libs/*.aar`、`assets/rish_shizuku.dex`（Shizuku） | **Apache-2.0** |
| APK 内置 Node 运行时、npm 依赖 | 各自原许可 |
