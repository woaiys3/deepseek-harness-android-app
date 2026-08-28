/**
 * Android Shizuku / Root 特权工具插件：给 DeepSeek Harness 提供特权 shell 能力。
 *
 * 特权通道（二选一，由 MainActivity 探测后通过环境变量告知）：
 *   - ROOT_AVAILABLE=1  ：设备有 root（su 可用），走 su -c 通道
 *   - SHIZUKU_AVAILABLE=1：Shizuku 已授权，走 app_process rish 通道
 * 两者都未授予时（ROOT_AVAILABLE != 1 且 SHIZUKU_AVAILABLE != 1），
 * **不注册特权工具** —— AI 的工具列表里没有 shizuku_shell，自然不会反复尝试调用；
 * 此时文件读写走 DSH 自带的 fs/bash 工具（只需"所有文件访问权限"，无需特权）。
 *
 * 默认在已授权的通道下自动执行（无需逐次审批）。
 * 如需恢复"每次确认"，在 MainActivity 里给 node 设置环境变量 SHIZUKU_APPROVE=ask。
 *
 * 注意：使用异步 spawn 而非 spawnSync，避免同步阻塞 node 事件循环，
 * 否则命令执行期间整个 DSH 后端（HTTP/WebSocket）会卡死，前端报 "Failed to fetch"。
 */
import { defineTool } from "@deepseek-ai/dsh-tools";
import { spawn } from "node:child_process";
import { chmodSync, existsSync } from "node:fs";

const name = "tool-shizuku";
const inject = ["tools"];

const APP_PROC = "/system/bin/app_process";
const SHIZUKU_LOADER = "rikka.shizuku.shell.ShizukuShellLoader";
const MAX_STDOUT = 8000;
const MAX_STDERR = 2000;

/** 特权通道是否可用（root 或 Shizuku 任一授予即可）。 */
function privilegedAvailable() {
  return process.env.ROOT_AVAILABLE === "1" || process.env.SHIZUKU_AVAILABLE === "1";
}

/**
 * 剥离会污染系统 app_process 链接的环境变量。
 * DSH 运行时为了加载 Node 自带的 .so 会设置 LD_LIBRARY_PATH（内含自定义 libz.so，
 * SONAME=libz.so.1），而 /system/bin/app_process 是系统二进制，必须用系统库
 * （/apex 的 libunwindstack.so 需要 SONAME=libz.so）。原样继承 process.env 会让
 * 系统链接器报 "cannot find libz.so from verneed[1]"。
 */
function sanitizeEnv(env) {
  const clean = { ...env };
  delete clean.LD_LIBRARY_PATH;
  delete clean.LD_PRELOAD;
  delete clean.LD_DEBUG;
  return clean;
}

/**
 * 确保 rish dex 只读。Android 15 的 ART 拒绝加载"当前 uid 可写"的 dex
 * （logcat: Writable dex file ... is not allowed → Abort）。MainActivity 从 assets
 * 提取的 dex 默认是 600（属主可写），所以每次执行前强制 chmod 444。
 */
function ensureDexReadOnly(dex) {
  try {
    if (dex && existsSync(dex)) chmodSync(dex, 0o444);
  } catch (_) {
    // 尽力而为：chmod 失败不阻断，让 app_process 的报错自然暴露。
  }
}

/** 异步调用 rish 执行一条 shell 命令（不阻塞事件循环，内部函数）。 */
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
    const timer = setTimeout(() => {
      try { child.kill("SIGKILL"); } catch (_) {}
    }, timeout);

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
      const killed = signal === "SIGKILL" && code === null;
      if (killed) {
        finish(false, -1, "命令超时（" + timeout + "ms）被强制终止");
      } else {
        finish(code === 0, code ?? -1, undefined);
      }
    });
  });
}

/** 异步调用 su 执行一条 shell 命令（root 通道，与 shizukuCmd 相同的结果结构）。 */
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
    const timer = setTimeout(() => {
      try { child.kill("SIGKILL"); } catch (_) {}
    }, timeout);

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
      const killed = signal === "SIGKILL" && code === null;
      if (killed) {
        finish(false, -1, "命令超时（" + timeout + "ms）被强制终止");
      } else {
        finish(code === 0, code ?? -1, undefined);
      }
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

function apply(ctx) {
  const approval = () => ctx.get("approval");
  const available = privilegedAvailable();

  // 1) 特权 shell（默认自动执行，SHIZUKU_APPROVE=ask 时逐次审批）
  // 未授予 root 且未授予 Shizuku 时**不注册**本工具：AI 工具列表里没有它，
  // 就不会反复尝试特权命令；此时文件读写应使用 DSH 自带的 fs/bash 工具。
  if (available) {
    ctx.tools.register(defineTool({
      name: "shizuku_shell",
      description:
        "通过特权通道（root su 或 Shizuku，二选一，自动选择可用者）以系统 shell 权限执行一条命令，用于普通 bash 工具做不到的、需要系统/root 级权限的操作（例如 pm install/uninstall、am 启动/停止应用、settings 修改系统设置、dumpsys 查询系统状态、grant/revoke 运行时权限等）。" +
        "命令会在已授权的通道下自动执行（默认无需逐次确认）；仅当环境变量 SHIZUKU_APPROVE=ask 时才需要逐次审批。优先用普通 `bash` 工具，只有确实需要系统特权时才用本工具。",
      parameters: {
        command: {
          type: "string",
          required: true,
          description: "要执行的 shell 命令。会以系统/root 权限运行，请写清楚、可审计。"
        },
        timeout_ms: {
          type: "number",
          description: "超时毫秒，默认 30000，最大 120000。"
        }
      },
      output: {
        schema: {
          type: "object",
          additionalProperties: false,
          properties: {
            ok: { type: "boolean", required: true },
            exit_code: { type: "number" },
            stdout: { type: "string" },
            stderr: { type: "string" },
            error: { type: "string" }
          }
        },
        render: (_args, value) => [{
          type: "text",
          text: (value.ok ? "" : "执行失败：" + (value.error || value.stderr || "未知错误") + "\n\n") +
            "exit_code: " + value.exit_code + "\n" +
            (value.stdout ? "stdout:\n" + value.stdout : "") +
            (value.stderr ? "\nstderr:\n" + value.stderr : "")
        }]
      },
      async execute(args, exec) {
        // 审批：默认自动允许。如需每次确认，设 SHIZUKU_APPROVE=ask。
        if (process.env.SHIZUKU_APPROVE === "ask") {
          const approver = approval();
          if (approver === undefined) {
            throw new Error("审批服务未挂载，无法安全执行特权命令");
          }
          const reason = "特权命令：" + args.command;
          const outcome = await approver.request({
            agent: exec.agent,
            toolName: "shizuku_shell",
            callId: exec.callId,
            reason,
            signal: exec.signal
          });
          if (outcome !== "allowed-once") {
            throw new Error(`特权命令未获批准（${outcome}）：${args.command}`);
          }
        }
        return await privCmd(args.command, args.timeout_ms);
      }
    }));
  }

  // 2) 授权状态探测（只读，不审批）。未授权时仍注册：AI 能自查"为什么没有特权工具"，
  //    得到"未授权"后就不会反复尝试特权命令；此时文件操作用 fs/bash 工具。
  ctx.tools.register(defineTool({
    name: "shizuku_status",
    description: "检查特权通道（root/Shizuku）是否可用且已授权。返回是否可用，以及失败时的原因。未授权时文件读写请使用 fs/bash 工具（只需所有文件访问权限），无需特权。",
    parameters: {},
    output: {
      schema: {
        type: "object",
        additionalProperties: false,
        properties: {
          available: { type: "boolean", required: true },
          channel: { type: "string" },
          detail: { type: "string" }
        }
      },
      render: (_args, value) => [{
        type: "text",
        text: value.available
          ? "特权通道可用（" + value.channel + "）"
          : "特权通道不可用：" + (value.detail || "未知原因") + "；文件读写请用 fs/bash 工具"
      }]
    },
    async execute() {
      if (process.env.ROOT_AVAILABLE === "1") {
        return { available: true, channel: "root(su)", detail: "已授予 root 权限" };
      }
      if (process.env.SHIZUKU_AVAILABLE === "1") {
        return { available: true, channel: "shizuku", detail: "Shizuku 已授权" };
      }
      const dex = process.env.SHIZUKU_DEX;
      if (!dex) return { available: false, channel: "none", detail: "root 与 Shizuku 均未授予" };
      const r = await shizukuCmd("echo __SHIZUKU_OK__", dex, process.env.SHIZUKU_APP_ID, 10000);
      const available = r.ok && r.stdout.includes("__SHIZUKU_OK__");
      let detail;
      if (available) {
        detail = "Shizuku 可用";
      } else if (r.error || /Permission denied|not found|Aborted|CANNOT LINK|Writable dex/i.test(r.stderr || "")) {
        detail = "Shizuku 服务端未运行或本应用未授权：" + (r.stderr || r.error || r.stdout || "未知错误");
      } else {
        detail = r.stdout || r.stderr || r.error || "未授权或未运行";
      }
      return { available, channel: "shizuku", detail };
    }
  }));

  // 3) AI 发通知（**不依赖特权**：只需 App 通知权限，走本地 127.0.0.1:3081）
  // 始终注册：即使没有 root/Shizuku，只要用户在系统设置里给了通知权限就能发。
  ctx.tools.register(defineTool({
    name: "android_notify",
    description: "向用户手机发送一条系统通知（标题 + 正文）。**只需要通知权限（POST_NOTIFICATIONS），不需要 Shizuku/root**。用于：后台任务完成、需要用户关注、长时间任务的进度提醒等。如果返回 ok:false 且提示通知权限未授予，请让用户在系统设置里为本应用开启通知权限后重试。",
    parameters: {
      title: {
        type: "string",
        required: true,
        description: "通知标题，简短（建议不超过 20 字）。"
      },
      text: {
        type: "string",
        required: true,
        description: "通知正文，说明发生了什么或需要用户做什么。"
      }
    },
    output: {
      schema: {
        type: "object",
        additionalProperties: false,
        properties: {
          ok: { type: "boolean", required: true },
          error: { type: "string" }
        }
      },
      render: (_args, value) => [{
        type: "text",
        text: value.ok ? "通知已发送 ✅" : "通知发送失败：" + (value.error || "未知错误")
      }]
    },
    async execute(args) {
      try {
        const http = await import("node:http");
        const port = Number(process.env.APP_NOTIFY_PORT) || 3081;
        const body = JSON.stringify({ title: String(args.title || ""), text: String(args.text || "") });
        const result = await new Promise((resolve) => {
          const req = http.request({
            host: "127.0.0.1",
            port,
            path: "/notify",
            method: "POST",
            headers: { "Content-Type": "application/json", "Content-Length": Buffer.byteLength(body) }
          }, (res) => {
            let d = "";
            res.on("data", (c) => d += c);
            res.on("end", () => {
              try { resolve(JSON.parse(d || "{}")); }
              catch (e) { resolve({ ok: false, error: "响应解析失败" }); }
            });
          });
          req.setTimeout(5000, () => { req.destroy(); resolve({ ok: false, error: "通知服务超时" }); });
          req.on("error", (e) => resolve({ ok: false, error: String(e && e.message || e) }));
          req.write(body);
          req.end();
        });
        return result;
      } catch (e) {
        return { ok: false, error: String(e && e.message || e) };
      }
    }
  }));

  // 4) 本地设置（**不依赖特权**：只需 App 的「修改系统设置」WRITE_SETTINGS 权限，改 Settings.System 各项）
  ctx.tools.register(defineTool({
    name: "android_setting_app",
    description:
      "通过 App 自身的 WRITE_SETTINGS 权限修改系统设置（Settings.System 命名空间）。**不需要 Shizuku/root**，但需要用户在权限引导页或系统设置里授予「修改系统设置」权限。" +
      "常用 key：screen_brightness（亮度 0-255）、screen_brightness_mode（0=手动 1=自动）、screen_off_timeout（屏幕超时毫秒，如 60000）、" +
      "accelerometer_rotation（自动旋转 0/1）、font_scale（字体大小，如 1.0/1.3）、volume_music/volume_ring/volume_alarm/volume_notification（音量 0-15）、" +
      "sound_effects_enabled（触摸音 0/1）、haptic_feedback_enabled（震动反馈 0/1）、notification_light_pulse（通知灯 0/1）、ringtone（铃声 Uri）。" +
      "改全局设置（Global/Secure 命名空间）请用 shizuku_shell 的 settings 命令（需要特权）。",
    parameters: {
      key: {
        type: "string", required: true,
        description: "Settings.System 的 key（见工具描述常用清单）"
      },
      value: {
        type: "string", required: true,
        description: "要写入的值（数字或字符串）"
      }
    },
    output: {
      schema: {
        type: "object",
        additionalProperties: false,
        properties: {
          ok: { type: "boolean", required: true },
          error: { type: "string" },
          stream: { type: "string" },
          level: { type: "number" },
          max: { type: "number" }
        }
      },
      render: (_args, value) => [{
        type: "text",
        text: value.ok
          ? (value.stream ? "已设置 " + value.stream + " = " + value.level + "（最大 " + value.max + "）✅" : "系统设置已修改 ✅")
          : "修改失败：" + (value.error || "未知错误")
      }]
    },
    async execute(args) {
      try {
        const http = await import("node:http");
        const port = Number(process.env.APP_NOTIFY_PORT) || 3081;
        const body = JSON.stringify({ key: String(args.key || ""), value: String(args.value == null ? "" : args.value) });
        const result = await new Promise((resolve) => {
          const req = http.request({
            host: "127.0.0.1", port, path: "/setting", method: "POST",
            headers: { "Content-Type": "application/json", "Content-Length": Buffer.byteLength(body) }
          }, (res) => {
            let d = "";
            res.on("data", (c) => d += c);
            res.on("end", () => {
              try { resolve(JSON.parse(d || "{}")); }
              catch (e) { resolve({ ok: false, error: "响应解析失败" }); }
            });
          });
          req.setTimeout(5000, () => { req.destroy(); resolve({ ok: false, error: "本地服务超时" }); });
          req.on("error", (e) => resolve({ ok: false, error: String(e && e.message || e) }));
          req.write(body);
          req.end();
        });
        return result;
      } catch (e) {
        return { ok: false, error: String(e && e.message || e) };
      }
    }
  }));

  // 5) 剪贴板（**不依赖特权**：读写系统剪贴板，无需任何特殊权限）
  ctx.tools.register(defineTool({
    name: "android_clipboard",
    description: "读写手机剪贴板。**不需要 Shizuku/root 和任何特殊权限**。action=read 读取当前剪贴板内容；action=write 把 content 写入剪贴板（如 AI 生成代码/文本后让用户粘贴）。",
    parameters: {
      action: {
        type: "string", required: true,
        enum: ["read", "write"],
        description: "read=读取剪贴板内容；write=写入剪贴板（需带 content）"
      },
      content: { type: "string", description: "write 时要写入的文本内容" }
    },
    output: {
      schema: {
        type: "object",
        additionalProperties: false,
        properties: {
          ok: { type: "boolean", required: true },
          content: { type: "string" },
          error: { type: "string" }
        }
      },
      render: (_args, value) => [{
        type: "text",
        text: value.ok
          ? (value.content !== undefined ? "剪贴板内容：\n" + value.content : "已写入剪贴板 ✅")
          : "剪贴板操作失败：" + (value.error || "未知错误")
      }]
    },
    async execute(args) {
      try {
        const http = await import("node:http");
        const port = Number(process.env.APP_NOTIFY_PORT) || 3081;
        const body = JSON.stringify({
          action: String(args.action || "read"),
          content: String(args.content == null ? "" : args.content)
        });
        const result = await new Promise((resolve) => {
          const req = http.request({
            host: "127.0.0.1", port, path: "/clipboard", method: "POST",
            headers: { "Content-Type": "application/json", "Content-Length": Buffer.byteLength(body) }
          }, (res) => {
            let d = "";
            res.on("data", (c) => d += c);
            res.on("end", () => {
              try { resolve(JSON.parse(d || "{}")); }
              catch (e) { resolve({ ok: false, error: "响应解析失败" }); }
            });
          });
          req.setTimeout(5000, () => { req.destroy(); resolve({ ok: false, error: "本地服务超时" }); });
          req.on("error", (e) => resolve({ ok: false, error: String(e && e.message || e) }));
          req.write(body);
          req.end();
        });
        return result;
      } catch (e) {
        return { ok: false, error: String(e && e.message || e) };
      }
    }
  }));

  // 6) 定时任务（**不依赖特权**：走系统 AlarmManager，到点自动拉起引擎执行任务，无需用户操作）
  ctx.tools.register(defineTool({
    name: "android_schedule",
    description:
      "设置一个定时任务：到点后**自动执行**（使用 Android AlarmManager + 自动拉起引擎，即使 App 不在前台也会执行，无需用户操作）。" +
      "适用：'10 分钟后帮我整理 /sdcard/Download 文件夹'、'明早 8 点提醒我打卡' 等。" +
      "执行方式：到点时 App 自动启动引擎，把任务文本作为消息发送给 AI 自动执行（需要 App 内已配置 API Key），完成后可配合 android_notify 通知用户。" +
      "参数：text=任务内容（要 AI 做的事，如 '整理下载文件夹'）；when=触发时间，支持相对秒数（如 600=10分钟后）或时间字符串（如 '08:00'=今天/明天8点、'2026-08-21 08:00:00'）。",
    parameters: {
      text: {
        type: "string", required: true,
        description: "提醒内容，例如 '10 分钟后提醒我喝水' 的 '喝水'"
      },
      when: {
        type: "string", required: true,
        description: "触发时间：纯数字=相对秒数（600=10分钟后）；'HH:mm'=今天/明天该时刻；'yyyy-MM-dd HH:mm:ss'=具体时间"
      }
    },
    output: {
      schema: {
        type: "object",
        additionalProperties: false,
        properties: {
          ok: { type: "boolean", required: true },
          at: { type: "string" },
          hint: { type: "string" },
          error: { type: "string" }
        }
      },
      render: (_args, value) => [{
        type: "text",
        text: value.ok ? "定时已设置（" + (value.at || "") + "）\n" + (value.hint || "") : "设置失败：" + (value.error || "未知错误")
      }]
    },
    async execute(args) {
      try {
        const http = await import("node:http");
        const port = Number(process.env.APP_NOTIFY_PORT) || 3081;
        const body = JSON.stringify({ text: String(args.text || ""), when: String(args.when || "") });
        const result = await new Promise((resolve) => {
          const req = http.request({
            host: "127.0.0.1", port, path: "/schedule", method: "POST",
            headers: { "Content-Type": "application/json", "Content-Length": Buffer.byteLength(body) }
          }, (res) => {
            let d = "";
            res.on("data", (c) => d += c);
            res.on("end", () => {
              try { resolve(JSON.parse(d || "{}")); }
              catch (e) { resolve({ ok: false, error: "响应解析失败" }); }
            });
          });
          req.setTimeout(5000, () => { req.destroy(); resolve({ ok: false, error: "本地服务超时" }); });
          req.on("error", (e) => resolve({ ok: false, error: String(e && e.message || e) }));
          req.write(body);
          req.end();
        });
        return result;
      } catch (e) {
        return { ok: false, error: String(e && e.message || e) };
      }
    }
  }));
}

export { apply, inject, name };
