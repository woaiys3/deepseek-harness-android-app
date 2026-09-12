/**
 * Android 手机端系统能力插件：给 DeepSeek Harness 提供结构化、语义化的系统操作工具。
 *
 * 覆盖：包管理（安装/卸载/清数据/授权）、应用管理（启动/强制停止）、
 *       系统设置读写、截图、模拟输入（点击/滑动/文本/按键）。
 *
 * 特权通道（由 MainActivity 探测后通过环境变量告知）：
 *   - ROOT_AVAILABLE=1  ：设备有 root（su 可用），走 su -c 通道
 *   - SHIZUKU_AVAILABLE=1：Shizuku 已授权，走 app_process rish 通道
 * 两者都未授予时**不注册任何工具**：AI 工具列表里没有 android_*，
 * 自然不会反复尝试系统操作；此时文件读写走 DSH 自带 fs/bash 工具。
 *
 * 审批策略：默认自动执行（已授权通道）。设环境变量 SHIZUKU_APPROVE=ask
 * 时，危险操作（安装/卸载/清数据/授权/改设置/输入）会逐次弹审批框。
 */
import { defineTool } from "@deepseek-ai/dsh-tools";
import { spawn } from "node:child_process";
import { get as httpGet, request as httpRequest } from "node:http";
import { chmodSync, existsSync } from "node:fs";

const name = "tool-android";
const inject = ["tools"];

const APP_PROC = "/system/bin/app_process";
const SHIZUKU_LOADER = "rikka.shizuku.shell.ShizukuShellLoader";
const MAX_STDOUT = 8000;
const MAX_STDERR = 2000;

/** 特权通道是否可用（root 或 Shizuku 任一授予即可）。 */
function privilegedAvailable() {
  return process.env.ROOT_AVAILABLE === "1" || process.env.SHIZUKU_AVAILABLE === "1";
}

/** 危险操作白名单：SHIZUKU_APPROVE=ask 时这些 action 需要审批。 */
const DANGEROUS_ACTIONS = new Set([
  "install", "uninstall", "clear", "grant", "revoke", "force_stop",
  "put", "tap", "swipe", "text", "keyevent"
]);

function sanitizeEnv(env) {
  const clean = { ...env };
  delete clean.LD_LIBRARY_PATH;
  delete clean.LD_PRELOAD;
  delete clean.LD_DEBUG;
  return clean;
}

function ensureDexReadOnly(dex) {
  try {
    if (dex && existsSync(dex)) chmodSync(dex, 0o444);
  } catch (_) {}
}

/** 只允许安全的 shell 令牌字符，防止命令注入。 */
function safe(s) {
  return String(s ?? "").replace(/[^a-zA-Z0-9._\/:=-]/g, "");
}

/** 单引号包裹的 shell 字面量：保留任意字符（中文路径、空格、标点）。
 *  用户提供的路径/文本绝不能用 safe() —— 它会把非 ASCII 字符直接删掉（静默改坏入参）。 */
function shq(s) {
  return "'" + String(s ?? "").replace(/'/g, "'\\''") + "'";
}

/** App 本地 HTTP 服务端口（MainActivity 注入环境变量 APP_NOTIFY_PORT，默认 3081）。 */
const appPort = () => parseInt(process.env.APP_NOTIFY_PORT || "3081", 10);

/** 调 App 本地 HTTP 端点（GET）。用于 usage/overlay 等 App 层能力，无需特权通道。
 *  返回解析后的 JSON 对象（DSH 工具运行时要求 execute 返回对象，字符串会被 schema 校验拒绝）。 */
function appRequest(path, params) {
  return new Promise((resolve) => {
    const qs = params
      ? "?" + Object.entries(params).map(([k, v]) =>
          encodeURIComponent(k) + "=" + encodeURIComponent(v)).join("&")
      : "";
    const req = httpGet({ host: "127.0.0.1", port: appPort(), path: path + qs, timeout: 8000 }, (res) => {
      let data = "";
      res.setEncoding("utf8");
      res.on("data", (c) => { data += c; if (data.length > 65536) req.destroy(); });
      res.on("end", () => {
        if (!data) return resolve({ ok: false, error: "empty response" });
        try {
          resolve(JSON.parse(data));
        } catch (e) {
          resolve({ ok: false, error: "App 响应解析失败: " + String(data).slice(0, 120) });
        }
      });
    });
    req.on("error", () => resolve({ ok: false, error: "App 本地服务不可用（请先启动 DeepSeek Harness）" }));
    req.on("timeout", () => { req.destroy(); resolve({ ok: false, error: "App 本地服务超时" }); });
    req.end();
  });
}

/** 调 App 本地 HTTP 端点（POST + JSON body）。
 *  /clipboard 与 /schedule 必须用 POST：App 侧会把请求行的 query 丢掉（path.substring(0,'?')），
 *  只把 body 交给处理器，所以 GET?action=write 会被当成 action=read 静默返回读结果。 */
function appPost(path, obj, timeoutMs) {
  return new Promise((resolve) => {
    const payload = JSON.stringify(obj ?? {});
    const req = httpRequest({
      host: "127.0.0.1",
      port: appPort(),
      path,
      method: "POST",
      timeout: timeoutMs || 8000,
      headers: {
        "Content-Type": "application/json",
        "Content-Length": Buffer.byteLength(payload)
      }
    }, (res) => {
      let data = "";
      res.setEncoding("utf8");
      res.on("data", (c) => { data += c; if (data.length > 65536) req.destroy(); });
      res.on("end", () => {
        if (!data) return resolve({ ok: false, error: "empty response" });
        try {
          resolve(JSON.parse(data));
        } catch (e) {
          resolve({ ok: false, error: "App 响应解析失败: " + String(data).slice(0, 120) });
        }
      });
    });
    req.on("error", () => resolve({ ok: false, error: "App 本地服务不可用（请先启动 DeepSeek Harness）" }));
    req.on("timeout", () => { req.destroy(); resolve({ ok: false, error: "App 本地服务超时" }); });
    req.write(payload);
    req.end();
  });
}

/** 异步执行一条 Shizuku shell 命令。 */
function shizukuCmd(command, dex, appId, timeoutMs) {
  if (!dex) {
    return Promise.resolve({ ok: false, exit_code: -1, stdout: "", stderr: "", error: "SHIZUKU_DEX 未配置" });
  }
  ensureDexReadOnly(dex);
  const timeout = Math.max(1000, Math.min(timeoutMs || 30000, 120000));
  return new Promise((resolve) => {
    let child;
    try {
      child = spawn(APP_PROC, [
        `-Djava.class.path=${dex}`,
        "/system/bin",
        "--nice-name=rish",
        SHIZUKU_LOADER,
        "-c", command
      ], {
        env: { ...sanitizeEnv(process.env), RISH_APPLICATION_ID: appId || "com.deepseek.harness" },
        stdio: ["ignore", "pipe", "pipe"]
      });
    } catch (e) {
      resolve({ ok: false, exit_code: -1, stdout: "", stderr: "", error: String(e && e.message || e) });
      return;
    }
    let stdout = "";
    let stderr = "";
    let settled = false;
    const timer = setTimeout(() => { try { child.kill("SIGKILL"); } catch (_) {} }, timeout);
    const finish = (ok, exitCode, err) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      resolve({
        ok,
        exit_code: exitCode,
        stdout: stdout.trim().slice(0, MAX_STDOUT),
        stderr: stderr.trim().slice(0, MAX_STDERR),
        ...(err ? { error: err } : {})
      });
    };
    child.stdout.on("data", (d) => { stdout += d; });
    child.stderr.on("data", (d) => { stderr += d; });
    child.on("error", (e) => finish(false, -1, String(e && e.message || e)));
    child.on("close", (code, signal) => {
      if (signal === "SIGKILL" && code === null) finish(false, -1, "命令超时被强制终止");
      else finish(code === 0, code ?? -1, undefined);
    });
  });
}

/** 异步执行一条 root(su) 命令，结果结构与 shizukuCmd 一致。 */
function suCmd(command, timeoutMs) {
  const timeout = Math.max(1000, Math.min(timeoutMs || 30000, 120000));
  return new Promise((resolve) => {
    let child;
    try {
      child = spawn("su", ["-c", command], {
        env: sanitizeEnv(process.env),
        stdio: ["ignore", "pipe", "pipe"]
      });
    } catch (e) {
      resolve({ ok: false, exit_code: -1, stdout: "", stderr: "", error: String(e && e.message || e) });
      return;
    }
    let stdout = "";
    let stderr = "";
    let settled = false;
    const timer = setTimeout(() => { try { child.kill("SIGKILL"); } catch (_) {} }, timeout);
    const finish = (ok, exitCode, err) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      resolve({
        ok,
        exit_code: exitCode,
        stdout: stdout.trim().slice(0, MAX_STDOUT),
        stderr: stderr.trim().slice(0, MAX_STDERR),
        ...(err ? { error: err } : {})
      });
    };
    child.stdout.on("data", (d) => { stdout += d; });
    child.stderr.on("data", (d) => { stderr += d; });
    child.on("error", (e) => finish(false, -1, String(e && e.message || e)));
    child.on("close", (code, signal) => {
      if (signal === "SIGKILL" && code === null) finish(false, -1, "命令超时被强制终止");
      else finish(code === 0, code ?? -1, undefined);
    });
  });
}

/** 选择特权通道执行：root(su) 优先，否则 Shizuku。 */
function privCmd(command, timeoutMs) {
  if (process.env.ROOT_AVAILABLE === "1") {
    return suCmd(command, timeoutMs);
  }
  return shizukuCmd(command, process.env.SHIZUKU_DEX, process.env.SHIZUKU_APP_ID, timeoutMs);
}

/** 会话式安装 APK。必须绕开两个坑：
 *  ① system_server 无权读 FUSE 挂载的 /storage/emulated/0，pm 直接报 "Can't open file"；
 *  ② ColorOS 上单发 `pm install -r` 会卡死无输出。
 *  所以先由特权 shell 把 APK 拷到 /data/local/tmp，再走 install-create/write/commit，
 *  并校验输出确实含 Success，否则如实报错（旧实现不看输出，失败也返回 exit_code:0）。 */
async function installApk(apkPath, expectPkg) {
  const tmp = "/data/local/tmp/dsh-install-" + Date.now() + ".apk";
  const before = "/data/local/tmp/dsh-pkgs-before.txt";
  const script = [
    `APK=${shq(apkPath)}`,
    `TMP=${shq(tmp)}`,
    `BEFORE=${shq(before)}`,
    'if [ ! -f "$APK" ]; then echo "找不到 APK: $APK" >&2; exit 21; fi',
    'cp -f "$APK" "$TMP" || { echo "复制到 /data/local/tmp 失败" >&2; exit 22; }',
    'pm list packages | sort > "$BEFORE"',
    "SID=$(pm install-create -r -t 2>&1 | grep -oE '[0-9]+' | head -1)",
    'if [ -z "$SID" ]; then echo "install-create 未返回 session id" >&2; rm -f "$TMP"; exit 23; fi',
    'pm install-write "$SID" base.apk "$TMP" 2>&1 || { echo "install-write 失败" >&2; rm -f "$TMP"; exit 24; }',
    'OUT=$(pm install-commit "$SID" 2>&1); RC=$?',
    'rm -f "$TMP"',
    'echo "$OUT"',
    '[ "$RC" -eq 0 ] || exit 25',
    'case "$OUT" in *Success*) ;; *) echo "install-commit 未返回 Success" >&2; exit 26;; esac',
    'pm list packages | sort > "$BEFORE.after"',
    'echo "本次新增包:"',
    'grep -vxFf "$BEFORE" "$BEFORE.after" || true',
    'rm -f "$BEFORE" "$BEFORE.after"'
  ].join("\n");
  const r = await privCmd(script, 120000);
  const stdout = String(r.stdout || "");
  const stderr = String(r.stderr || "");
  if (!r.ok || !/Success/.test(stdout)) {
    return {
      ok: false,
      exit_code: r.exit_code,
      stdout,
      stderr,
      error: r.error || "安装未成功（pm 未返回 Success）"
    };
  }
  if (expectPkg) {
    // 二次校验：commit 报 Success 也可能没真正生效，以 pm path 能否查到为准
    const v = await privCmd("pm path " + shq(expectPkg), 15000);
    const p = String(v.stdout || "");
    if (!/package:/.test(p)) {
      return {
        ok: false, exit_code: r.exit_code, stdout, stderr,
        error: "pm 返回 Success，但 pm path 查不到 " + expectPkg + "，安装未真正生效"
      };
    }
    return { ok: true, exit_code: 0, stdout: stdout + "\npm path: " + p.trim(), stderr };
  }
  return { ok: true, exit_code: 0, stdout, stderr };
}

/** 通用结果 schema（所有工具共用，避免 exit_code/exitCode 不匹配的坑）。 */
function resultSchema(extraProps = {}) {
  return {
    type: "object",
    additionalProperties: false,
    properties: {
      ok: { type: "boolean", required: true },
      exit_code: { type: "number" },
      stdout: { type: "string" },
      stderr: { type: "string" },
      error: { type: "string" },
      ...extraProps
    }
  };
}

function renderResult(value) {
  return [{
    type: "text",
    text: (value.ok ? "" : "执行失败：" + (value.error || value.stderr || "未知错误") + "\n\n") +
      "exit_code: " + value.exit_code + "\n" +
      (value.stdout ? "stdout:\n" + value.stdout : "") +
      (value.stderr ? "\nstderr:\n" + value.stderr : "")
  }];
}

/** android_usage 专用渲染：把 apps 数组展示出来（通用 renderResult 只读 shell 字段，会吞掉数据）。 */
function renderUsage(value) {
  if (!value.ok) return renderResult(value);
  const lines = [`应用使用时长（最近 ${value.days ?? 1} 天，按时长降序）：`];
  const apps = Array.isArray(value.apps) ? value.apps : [];
  if (apps.length === 0) {
    lines.push("（无数据：可能未授予「使用情况访问」权限，或该时段无使用记录）");
  }
  for (const a of apps) {
    const label = (a && a.name) || (a && a.pkg) || "未知应用";
    const min = a && typeof a.min === "number" ? a.min : Math.round((a && a.ms || 0) / 60000);
    lines.push(`- ${label} (${a.pkg || ""}): ${min} 分钟`);
  }
  return [{ type: "text", text: lines.join("\n") }];
}

/** android_overlay 专用渲染：展示 running/engineUp/granted（通用 renderResult 只读 shell 字段）。 */
function renderOverlay(value) {
  if (!value.ok) return renderResult(value);
  const parts = [];
  if (typeof value.running === "boolean") parts.push("悬浮窗运行中: " + value.running);
  if (typeof value.engineUp === "boolean") parts.push("引擎在线: " + value.engineUp);
  if (typeof value.granted === "boolean") parts.push("悬浮窗权限: " + (value.granted ? "已授予" : "未授予"));
  if (parts.length === 0) parts.push("操作成功。");
  return [{ type: "text", text: parts.join("，") }];
}

/** 在 SHIZUKU_APPROVE=ask 时对危险操作做审批。 */
async function maybeApprove(ctx, exec, toolName, action, reason) {
  if (process.env.SHIZUKU_APPROVE !== "ask") return;
  const approver = ctx.get("approval");
  if (approver === undefined) throw new Error("审批服务未挂载，无法安全执行");
  const outcome = await approver.request({
    agent: exec.agent,
    toolName,
    callId: exec.callId,
    reason: `${action}: ${reason}`,
    signal: exec.signal
  });
  if (outcome !== "allowed-once") throw new Error(`操作未获批准（${outcome}）：${reason}`);
}

function apply(ctx) {
  const dex = () => process.env.SHIZUKU_DEX;
  const appId = () => process.env.SHIZUKU_APP_ID;

  // ===== App 层工具（无需 root/Shizuku，走 App 本地 HTTP 服务）=====
  // 这些能力由 DeepSeek Harness App 自身实现（UsageStats/悬浮窗权限），
  // 不依赖特权通道，因此即使未授权 root/Shizuku 也注册。

  // 应用使用时长（UsageStats：需用户在系统设置授予「使用情况访问」权限）
  ctx.tools.register(defineTool({
    name: "android_usage",
    description:
      "查询手机各应用的使用时长（UsageStats）：返回最近 N 天每个应用的前台使用时长（毫秒/分钟），按时长降序。" +
      "可用于回答「今天/本周哪些应用用得最多」「某应用用了多久」等问题。App 需已授予「使用情况访问」权限。",
    parameters: {
      days: { type: "number", description: "查询最近几天（默认 1，上限 30）" }
    },
    output: {
      schema: {
        type: "object",
        additionalProperties: false,
        properties: {
          ok: { type: "boolean", required: true },
          error: { type: "string" },
          days: { type: "number" },
          apps: {
            type: "array",
            items: {
              type: "object",
              additionalProperties: false,
              properties: {
                pkg: { type: "string" },
                name: { type: "string" },
                ms: { type: "number" },
                min: { type: "number" }
              }
            }
          }
        }
      },
      render: (_a, v) => renderUsage(v)
    },
    async execute(args, exec) {
      return await appRequest("/usage", args.days ? { days: String(Math.max(1, Math.min(30, Number(args.days) || 1))) } : undefined);
    }
  }));

  // 小鲸鱼悬浮窗控制（含引擎状态显示；需已授予悬浮窗权限）
  ctx.tools.register(defineTool({
    name: "android_overlay",
    description:
      "控制 DeepSeek Harness 的小鲸鱼悬浮窗：show=显示悬浮窗（小鲸鱼图标，点击展开引擎状态面板）；" +
      "hide=隐藏；status=查询悬浮窗与引擎运行状态。需已授予悬浮窗权限。",
    parameters: {
      action: {
        type: "string", required: true, enum: ["show", "hide", "status"],
        description: "show=显示悬浮窗；hide=隐藏；status=查询状态"
      }
    },
    output: {
      schema: {
        type: "object",
        additionalProperties: false,
        properties: {
          ok: { type: "boolean", required: true },
          error: { type: "string" },
          running: { type: "boolean" },
          engineUp: { type: "boolean" },
          granted: { type: "boolean" },
          msg: { type: "string" }
        }
      },
      render: (_a, v) => renderOverlay(v)
    },
    async execute(args, exec) {
      return await appRequest("/overlay", { action: String(args.action || "status") });
    }
  }));

  // 未授予 root 且未授予 Shizuku 时：不注册下面这些系统操作工具（android_package 等），
  // AI 工具列表里没有特权工具，就不会反复尝试系统操作；
  // 此时文件读写用 DSH 自带的 fs/bash 工具（只需所有文件访问权限）。
  if (!privilegedAvailable()) return;

  // 1) 包管理
  ctx.tools.register(defineTool({
    name: "android_package",
    description:
      "Android 包管理：列出已安装应用、安装 APK、卸载应用、清除应用数据、授予/撤销运行时权限。" +
      "底层走 pm 命令（root su 或 Shizuku 特权通道）。" +
      "install 会自动把 APK 拷到 /data/local/tmp 并用 install-create/install-write/install-commit 会话式安装" +
      "（单发 pm install 在 ColorOS 上会卡死，且 system_server 读不了 /storage/emulated/0），" +
      "并校验 pm 输出确实含 Success，失败会如实报错而不是静默假成功。",
    parameters: {
      action: {
        type: "string", required: true,
        enum: ["list", "install", "uninstall", "clear", "grant", "revoke"],
        description: "操作类型：list=列出应用；install=安装APK(需apk_path)；uninstall=卸载(需package)；clear=清数据(需package)；grant/revoke=授权/撤销(需package+permission)"
      },
      package: { type: "string", description: "包名，如 com.example.app" },
      apk_path: { type: "string", description: "install 时的 APK 绝对路径" },
      permission: { type: "string", description: "grant/revoke 时的权限名，如 android.permission.CAMERA" },
      third_party_only: { type: "boolean", description: "list 时是否只列第三方应用" },
      filter: { type: "string", description: "list 时按关键字过滤包名" }
    },
    output: { schema: resultSchema(), render: (_a, v) => renderResult(v) },
    async execute(args, exec) {
      await maybeApprove(ctx, exec, "android_package", args.action, args.package || args.apk_path || "");
      const a = safe(args.action);
      const pkg = safe(args.package);
      // 路径不能过 safe()：它会把中文目录名（如 "APK安装包"）整段删掉
      const apk = String(args.apk_path ?? "").trim();
      const perm = safe(args.permission);
      let cmd = "";
      switch (a) {
        case "list":
          cmd = "pm list packages" + (args.third_party_only ? " -3" : "") +
                (args.filter ? " | grep " + safe(args.filter) : "");
          break;
        case "install":
          if (!apk) throw new Error("install 需要 apk_path 参数");
          return await installApk(apk, pkg);
        case "uninstall": cmd = "pm uninstall " + pkg; break;
        case "clear": cmd = "pm clear " + pkg; break;
        case "grant": cmd = "pm grant " + pkg + " " + perm; break;
        case "revoke": cmd = "pm revoke " + pkg + " " + perm; break;
        default: throw new Error("未知 action: " + a);
      }
      return await privCmd(cmd, 60000);
    }
  }));

  // 2) 应用管理
  ctx.tools.register(defineTool({
    name: "android_app",
    description: "Android 应用管理：启动应用、强制停止应用、查看当前前台应用。底层走 am/dumpsys（root su 或 Shizuku 特权通道）。",
    parameters: {
      action: {
        type: "string", required: true,
        enum: ["launch", "force_stop", "current"],
        description: "launch=启动应用(需package)；force_stop=强制停止(需package)；current=查看当前前台应用"
      },
      package: { type: "string", description: "目标包名" },
      activity: { type: "string", description: "launch 时指定 activity 组件名（可选，留空自动用 launcher 入口）" }
    },
    output: { schema: resultSchema(), render: (_a, v) => renderResult(v) },
    async execute(args, exec) {
      await maybeApprove(ctx, exec, "android_app", args.action, args.package || "");
      const a = safe(args.action);
      const pkg = safe(args.package);
      let cmd = "";
      switch (a) {
        case "launch":
          cmd = args.activity
            ? "am start -n " + pkg + "/" + safe(args.activity)
            : "monkey -p " + pkg + " -c android.intent.category.LAUNCHER 1";
          break;
        case "force_stop": cmd = "am force-stop " + pkg; break;
        case "current": cmd = "dumpsys activity activities | grep -E 'mResumedActivity|mFocusedApp' | head -5"; break;
        default: throw new Error("未知 action: " + a);
      }
      return await privCmd(cmd, 30000);
    }
  }));

  // 3) 系统设置
  ctx.tools.register(defineTool({
    name: "android_setting",
    description: "Android 系统设置读写：settings get/put/list，namespace 为 global/system/secure。改设置影响系统行为，请谨慎。",
    parameters: {
      action: { type: "string", required: true, enum: ["get", "put", "list"], description: "get=读；put=写；list=列出某 namespace 全部" },
      namespace: { type: "string", enum: ["global", "system", "secure"], description: "设置命名空间" },
      key: { type: "string", description: "设置项 key" },
      value: { type: "string", description: "put 时写入的值" }
    },
    output: { schema: resultSchema(), render: (_a, v) => renderResult(v) },
    async execute(args, exec) {
      await maybeApprove(ctx, exec, "android_setting", args.action, (args.namespace || "") + " " + (args.key || ""));
      const a = safe(args.action);
      const ns = safe(args.namespace);
      const key = safe(args.key);
      const val = safe(args.value);
      let cmd = "";
      switch (a) {
        case "get": cmd = "settings get " + ns + " " + key; break;
        case "put": cmd = "settings put " + ns + " " + key + " " + val; break;
        case "list": cmd = "settings list " + ns; break;
        default: throw new Error("未知 action: " + a);
      }
      return await privCmd(cmd, 30000);
    }
  }));

  // 4) 截图
  ctx.tools.register(defineTool({
    name: "android_screenshot",
    description: "截取当前屏幕，保存为 PNG，返回文件路径。默认存到 /sdcard/DeepSeekHarness/screenshots/。",
    parameters: {
      save_path: { type: "string", description: "可选，完整保存路径；留空自动生成" }
    },
    output: {
      schema: resultSchema({ path: { type: "string" } }),
      render: (_a, v) => [{
        type: "text",
        text: v.ok ? "截图已保存：\n" + v.path : "截图失败：" + (v.error || v.stderr || "未知错误")
      }]
    },
    async execute(args, exec) {
      await maybeApprove(ctx, exec, "android_screenshot", "screenshot", "");
      const ts = new Date().toISOString().replace(/[:.]/g, "-");
      const path = args.save_path
        ? safe(args.save_path)
        : "/sdcard/DeepSeekHarness/screenshots/shot-" + ts + ".png";
      const r = await privCmd("mkdir -p " + path.substring(0, path.lastIndexOf("/")) + "; screencap -p " + path + " && echo __SHOT_OK__", 30000);
      return { ...r, path: r.ok ? path : "" };
    }
  }));

  // 5) 模拟输入
  ctx.tools.register(defineTool({
    name: "android_input",
    description: "模拟用户输入（需特权通道 root/Shizuku）：点击坐标、滑动、输入文本、发送按键事件。用于自动化操作当前屏幕。坐标以屏幕像素为单位。" +
      "text 动作：纯 ASCII 走系统 input text；含中文/emoji 等非 ASCII 字符时自动改为「写剪贴板 + 粘贴」——" +
      "input text 通道会静默丢弃非 ASCII 字符且不报错，绝不能用它输入中文。中文也可直接用无障碍版 android_type。",
    parameters: {
      action: { type: "string", required: true, enum: ["tap", "swipe", "text", "keyevent"], description: "tap=点击；swipe=滑动；text=输入文本；keyevent=按键" },
      x: { type: "number", description: "tap 的 x 坐标 / swipe 起点 x" },
      y: { type: "number", description: "tap 的 y 坐标 / swipe 起点 y" },
      x2: { type: "number", description: "swipe 终点 x" },
      y2: { type: "number", description: "swipe 终点 y" },
      duration: { type: "number", description: "swipe 持续时间(毫秒，默认 300)" },
      text: { type: "string", description: "text 动作要输入的文本（支持中文：非 ASCII 会自动走剪贴板粘贴）" },
      keycode: { type: "number", description: "keyevent 的按键码，如 3=HOME, 4=BACK, 26=POWER" }
    },
    output: { schema: resultSchema(), render: (_a, v) => renderResult(v) },
    async execute(args, exec) {
      await maybeApprove(ctx, exec, "android_input", args.action, args.text || "");
      const a = safe(args.action);
      let cmd = "";
      switch (a) {
        case "tap": cmd = "input tap " + Math.round(Number(args.x) || 0) + " " + Math.round(Number(args.y) || 0); break;
        case "swipe":
          cmd = "input swipe " + Math.round(Number(args.x) || 0) + " " + Math.round(Number(args.y) || 0) + " " +
                Math.round(Number(args.x2) || 0) + " " + Math.round(Number(args.y2) || 0) + " " +
                Math.round(Number(args.duration) || 300);
          break;
        case "text": {
          const text = String(args.text ?? "");
          if (!/^[\x20-\x7e]*$/.test(text)) {
            // 非 ASCII（中文/emoji 等）：input text 会静默丢弃这些字符且 exit_code 仍为 0，
            // 所以改走「剪贴板 + 粘贴」；写剪贴板走 App 本地 HTTP POST（GET 会被当成 read，见 appPost）。
            const wrote = await appPost("/clipboard", { action: "write", content: text });
            if (!wrote || wrote.ok !== true) {
              return {
                ok: false, exit_code: -1, stdout: "", stderr: "",
                error: "文本含非 ASCII 字符，需经剪贴板粘贴，但写入剪贴板失败：" +
                  ((wrote && wrote.error) || "未知错误")
              };
            }
            const pasted = await privCmd("input keyevent 279", 15000); // 279 = KEYCODE_PASTE
            if (!pasted.ok) return pasted;
            return {
              ok: true, exit_code: 0, stderr: "",
              stdout: "已通过剪贴板粘贴输入（" + text.length + " 字符，含非 ASCII）"
            };
          }
          // ASCII：空格必须转成 %s，否则会被命令解析当成参数分隔符吃掉
          cmd = "input text " + shq(text.replace(/ /g, "%s"));
          break;
        }
        case "keyevent": cmd = "input keyevent " + Math.round(Number(args.keycode) || 0); break;
        default: throw new Error("未知 action: " + a);
      }
      return await privCmd(cmd, 15000);
    }
  }));
}

export { apply, inject, name };
