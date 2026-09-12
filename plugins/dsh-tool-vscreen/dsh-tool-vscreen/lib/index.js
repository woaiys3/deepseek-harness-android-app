/**
 * 虚拟屏幕插件（v1.10）：给 AI 提供真正的独立虚拟屏（createVirtualDisplay）能力。
 *
 * v1.10 架构：虚拟屏服务由 App 进程内嵌启动（打开 App 即自动运行，监听 8999）——
 * 不再需要 root/Shizuku 启动特权进程（shell/app_process 在 Android 15 enforcing 下
 * createVirtualDisplay 被 Binder 拒：Bad fd）。预览是 App 内 H264 悬浮窗（建屏自动弹出，
 * 可拖动/缩放），AI 通过本插件的 see 拿截图，tap/swipe/key/launch 通过 Shizuku
 * `input -d` / `am start --display` 注入（App 域注入被 InputDispatcher 拒）。
 *
 * 本插件职责：
 *   1）等待 App 已启动的 server（打开 App 一次即可，无需授权特权）；
 *   2）把 8 个工具（create/status/launch/see/tap/swipe/key/close）映射到 server 的 HTTP API。
 *
 * 依赖：App 已安装并打开过一次；Android 11+（createVirtualDisplay）。
 * 注意：DSH 工具输出校验为 additionalProperties:false——execute 返回的每个字段
 * 都必须在 output.schema 中显式声明，否则结果会被判定为非法输出（历史踩坑）。
 */
import { defineTool } from "@deepseek-ai/dsh-tools";
import { spawn } from "node:child_process";
import { chmodSync, existsSync } from "node:fs";
import { readFile } from "node:fs/promises";
import { get as httpGet, request as httpRequest } from "node:http";

const name = "tool-vscreen";
const inject = ["tools"];

const APP_PROC = "/system/bin/app_process";
const VS_DEX = "/data/local/tmp/vscreen_shizuku.jar"; // server jar 位置（特权可读）
const SERVER_PORT = 8999;
const SERVER_MAIN = "com.deepseek.harness.vscreen.VirtualScreenServer";
const MAX_STDOUT = 8000;
const MAX_STDERR = 2000;
const START_TIMEOUT_MS = 12000; // 等 server 起来

const GUIDE_NO_PRIV =
  "虚拟屏需要系统特权（root 或 Shizuku）：请在 App 权限页授权 Shizuku（或 root），然后重试。";
const GUIDE_NO_SERVER =
  "虚拟屏幕服务不可用：可能未授权特权，或服务启动失败。请确认已授权 Shizuku/root 后重试。";

/** 特权通道是否可用（root 或 Shizuku 任一授予即可）。 */
function privilegedAvailable() {
  return process.env.ROOT_AVAILABLE === "1" || process.env.SHIZUKU_AVAILABLE === "1";
}

/** 剥离会污染系统 app_process 链接的环境变量（DSH 的 LD_LIBRARY_PATH 会弄坏 app_process）。 */
function sanitizeEnv(env) {
  const clean = { ...env };
  delete clean.LD_LIBRARY_PATH;
  delete clean.LD_PRELOAD;
  delete clean.LD_DEBUG;
  return clean;
}

/** 确保 dex 只读（Android 15 ART 拒绝 uid 可写的 dex）。 */
function ensureDexReadOnly(dex) {
  try {
    if (dex && existsSync(dex)) chmodSync(dex, 0o444);
  } catch (_) {}
}

/** 异步执行一条特权 shell 命令（root 或 Shizuku 通道，返回统一结构）。 */
function privShell(command, timeoutMs) {
  const timeout = Math.max(1000, Math.min(timeoutMs || 30000, 120000));
  const target = process.env.ROOT_AVAILABLE === "1" ? "su" : APP_PROC;
  return new Promise((resolve) => {
    let child;
    try {
      if (process.env.ROOT_AVAILABLE === "1") {
        child = spawn("su", ["-c", command], { env: sanitizeEnv(process.env), stdio: ["ignore", "pipe", "pipe"] });
      } else {
        ensureDexReadOnly(process.env.SHIZUKU_DEX);
        child = spawn(APP_PROC, [
          `-Djava.class.path=${process.env.SHIZUKU_DEX}`,
          "/system/bin",
          "--nice-name=rish",
          "rikka.shizuku.shell.ShizukuShellLoader",
          "-c", command
        ], { env: { ...sanitizeEnv(process.env), RISH_APPLICATION_ID: process.env.SHIZUKU_APP_ID || "com.deepseek.harness" }, stdio: ["ignore", "pipe", "pipe"] });
      }
    } catch (e) {
      resolve({ ok: false, exit_code: -1, stdout: "", stderr: "", error: String(e && e.message || e) });
      return;
    }
    let stdout = "";
    let stderr = "";
    let settled = false;
    const timer = setTimeout(() => { try { child.kill("SIGKILL"); } catch (_) {} }, timeout);
    const finish = (ok, code, err) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      resolve({ ok, exit_code: code, stdout: stdout.trim().slice(0, MAX_STDOUT), stderr: stderr.trim().slice(0, MAX_STDERR), ...(err ? { error: err } : {}) });
    };
    child.stdout.on("data", (d) => { stdout += d; });
    child.stderr.on("data", (d) => { stderr += d; });
    child.on("error", (e) => finish(false, -1, String(e && e.message || e)));
    child.on("close", (code, signal) => {
      const killed = signal === "SIGKILL" && code === null;
      if (killed) finish(false, -1, "命令超时（" + timeout + "ms）被强制终止");
      else finish(code === 0, code ?? -1, undefined);
    });
  });
}

/** 后台启动 vscreen server（如果还没起来）。 */
async function ensureServer() {
  if (await serverAlive()) return { ok: true };

  // v1.10：虚拟屏 server 由 App 进程内嵌启动（打开 App 即自动运行，监听 8999，
  // 无需 root/Shizuku 拉特权进程）。这里只需等待 App 已启动的 server 就绪。
  for (let i = 0; i < 15; i++) {
    await sleep(1000);
    if (await serverAlive()) return { ok: true };
  }
  const logTail = await readLogTail(60);
  return {
    ok: false,
    error:
      "虚拟屏服务未就绪（" + (logTail ? "日志：" + logTail + "；" : "") +
      "）。请先打开一次 DeepSeek Harness App（App 会自动启动虚拟屏服务，无需授权特权）后重试。"
  };
}

/** 读取 /data/local/tmp/vscreen.log 末尾 N 字符（特权通道读，失败返回空串）。 */
async function readLogTail(maxChars) {
  try {
    const r = await privShell(`tail -c ${maxChars} /data/local/tmp/vscreen.log 2>/dev/null || echo __NOLOG__`, 4000);
    const out = (r.stdout || "").trim();
    if (!out || out.includes("__NOLOG__") || out.includes("No such file")) return "";
    return out.replace(/\n/g, " | ");
  } catch (_) {
    return "";
  }
}

/** 探测 server 是否在监听。 */
function serverAlive() {
  return new Promise((resolve) => {
    const req = httpGet({ host: "127.0.0.1", port: SERVER_PORT, path: "/vscreen/status", timeout: 1500 }, (res) => {
      let d = ""; res.setEncoding("utf8");
      res.on("data", (c) => { d += c; if (d.length > 4096) req.destroy(); });
      res.on("end", () => resolve(true));
    });
    req.on("error", () => resolve(false));
    req.on("timeout", () => { req.destroy(); resolve(false); });
    req.end();
  });
}

/** 调 server HTTP API，返回 JSON。 */
function vsReq(path, timeoutMs) {
  return new Promise((resolve) => {
    const req = httpGet({ host: "127.0.0.1", port: SERVER_PORT, path, timeout: timeoutMs || 8000 }, (res) => {
      let d = "";
      res.setEncoding("utf8");
      res.on("data", (c) => { d += c; if (d.length > 1024 * 1024) req.destroy(); });
      res.on("end", () => resolve(safeParse(d)));
    });
    req.on("error", () => resolve({ ok: false, error: GUIDE_NO_SERVER }));
    req.on("timeout", () => { req.destroy(); resolve({ ok: false, error: "虚拟屏服务超时" }); });
    req.end();
  });
}

function safeParse(raw) {
  try { return JSON.parse(raw); } catch (e) { return { ok: false, error: "响应解析失败: " + String(raw).slice(0, 120) }; }
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/** 强制关闭 server（可选）。 */
async function killServer() {
  await privShell("pkill -f com.deepseek.harness.vscreen.VirtualScreenServer || true", 5000);
}

const renderOp = (_args, value) => [{
  type: "text",
  text: value.ok ? "操作成功。" : "执行失败：" + (value.error || "未知错误")
}];

function apply(ctx) {
  const available = privilegedAvailable();
  if (!available) {
    ctx.logger?.warn?.("[tool-vscreen] 未授予 root/Shizuku，虚拟屏工具未注册");
    return;
  }

  // 1) 创建虚拟屏
  ctx.tools.register(defineTool({
    name: "android_vscreen_create",
    description:
      "创建一块独立于主屏的真正虚拟屏（createVirtualDisplay）。之后用 android_vscreen_launch 把 App 启动到虚拟屏，" +
      "用 android_vscreen_see 看虚拟屏画面，用 android_vscreen_tap/swipe/key 在虚拟屏上操作，用户可在悬浮窗实时看到虚拟屏。" +
      "适合游戏/无控件界面（如 Unity）或想不打扰主屏地运行自动化任务。需要 root 或 Shizuku 特权 + Android 11+。" +
      "首次调用会自动启动虚拟屏服务。" +
      "横屏：传 width=1520、height=720（宽>高即横屏）；竖屏反之。切换横竖屏需先 android_vscreen_close 再 create。",
    parameters: {
      width: { type: "number", description: "虚拟屏宽度（px），缺省 = 主屏宽度；横屏时传大于 height 的值（如 1520）" },
      height: { type: "number", description: "虚拟屏高度（px），缺省 = 主屏高度；横屏时传小于 width 的值（如 720）" },
      dpi: { type: "number", description: "虚拟屏密度（dpi），缺省 420" }
    },
    output: {
      schema: {
        type: "object",
        additionalProperties: false,
        properties: {
          ok: { type: "boolean", required: true },
          error: { type: "string" },
          displayId: { type: "number" },
          width: { type: "number" },
          height: { type: "number" },
          hint: { type: "string" }
        }
      },
      render: renderOp
    },
    async execute(args) {
      const started = await ensureServer();
      if (!started.ok) return { ok: false, error: started.error };
      const w = Number(args.width) || 0;
      const h = Number(args.height) || 0;
      const d = Number(args.dpi) || 0;
      const qs = [];
      if (w) qs.push("width=" + w);
      if (h) qs.push("height=" + h);
      if (d) qs.push("dpi=" + d);
      const r = await vsReq("/vscreen/create" + (qs.length ? "?" + qs.join("&") : ""), 15000);
      if (!r.ok) return { ok: false, error: r.error || "创建失败" };
      cachedDisplayId = typeof r.displayId === "number" ? r.displayId : -1;
      return { ok: true, displayId: r.displayId, width: r.width, height: r.height, hint: "虚拟屏已创建，可用 android_vscreen_launch 或 android_vscreen_see" };
    }
  }));

  // 2) 查询虚拟屏状态
  ctx.tools.register(defineTool({
    name: "android_vscreen_status",
    description: "查询当前虚拟屏状态（displayId/尺寸/是否运行）。",
    parameters: {},
    output: {
      schema: {
        type: "object",
        additionalProperties: false,
        properties: {
          ok: { type: "boolean", required: true },
          error: { type: "string" },
          displayId: { type: "number" },
          width: { type: "number" },
          height: { type: "number" },
          running: { type: "boolean" }
        }
      },
      render: (a, v) => [{
        type: "text",
        text: v.ok
          ? (v.displayId >= 0 ? "虚拟屏运行中，displayId=" + v.displayId + "，" + v.width + "x" + v.height : "虚拟屏未创建（displayId=-1）")
          : "查询失败：" + (v.error || "未知错误")
      }]
    },
    async execute() {
      const r = await vsReq("/vscreen/status", 6000);
      if (!r.ok) return { ok: false, error: r.error };
      return { ok: true, displayId: r.displayId, width: r.width, height: r.height, running: r.running };
    }
  }));

  // 3) 启动应用到虚拟屏
  ctx.tools.register(defineTool({
    name: "android_vscreen_launch",
    description:
      "把应用启动到指定虚拟屏。app 参数填应用包名（如 com.tencent.mm），或应用显示名会尝试解析包名。" +
      "启动成功后该应用只显示在虚拟屏上，主屏不变，且用户可在悬浮窗看到。",
    parameters: {
      app: { type: "string", description: "应用包名或应用名", required: true }
    },
    output: {
      schema: {
        type: "object",
        additionalProperties: false,
        properties: {
          ok: { type: "boolean", required: true },
          error: { type: "string" },
          package: { type: "string" },
          displayId: { type: "number" }
        }
      },
      render: renderOp
    },
    async execute(args) {
      if (!args.app || String(args.app).trim() === "") return { ok: false, error: "app 不能为空" };
      const app = String(args.app).trim();
      // 不在这里查包名：服务端会用已安装应用列表做模糊匹配（插件没有可靠的特权 shell 通道）
      const pkg = app;
      const id = await getDisplayId();
      if (id < 0) return { ok: false, error: "虚拟屏未创建，请先调用 android_vscreen_create" };
      // 启动交给特权服务端（它按包名解析组件再 am start --display；插件不再自己起 rish）
      const r = await vsReq(`/vscreen/launch?pkg=${encodeURIComponent(pkg)}`, 15000);
      return r.ok ? { ok: true, package: pkg, displayId: id } : { ok: false, error: r.error || "启动失败" };
    }
  }));

  // 4) 截图虚拟屏（喂给视觉模型）
  ctx.inject(["attachments"], (imageCtx) => {
    imageCtx.tools.register(defineTool({
      name: "android_vscreen_see",
      description:
        "截取虚拟屏当前画面并把截图作为图片发送给模型查看（server ImageReader 抓帧，无需 MediaProjection 弹窗）。" +
        "返回 screenW/H（虚拟屏逻辑尺寸）与 scaleX/Y；截图上量到的像素 (px,py) → 虚拟屏坐标 = (px×scaleX, py×scaleY)。" +
        "适合 Unity/游戏等无控件界面。建议 grid:true 叠加 4×4 网格按行列定位。",
      parameters: {
        grid: { type: "boolean", description: "true 时叠加 4×4 网格线" }
      },
      output: {
        schema: {
          type: "object",
          additionalProperties: false,
          properties: {
            ok: { type: "boolean", required: true },
            error: { type: "string" },
            path: { type: "string" },
            displayId: { type: "number" },
            screenW: { type: "number" },
            screenH: { type: "number" },
            imageW: { type: "number" },
            imageH: { type: "number" },
            scaleX: { type: "number" },
            scaleY: { type: "number" },
            grid: { type: "number" },
            image: {
              type: "object",
              additionalProperties: false,
              properties: {
                attachmentId: { type: "string", required: true },
                mediaType: { type: "string", required: true },
                bytes: { type: "number" },
                width: { type: "number" },
                height: { type: "number" },
                name: { type: "string" }
              }
            }
          }
        },
        render: (_args, value) => {
          if (!value.ok) return renderOp(value);
          const meta = [
            `虚拟屏截图, ${value.image.mediaType}, ${value.image.width}x${value.image.height} px, ${value.image.bytes} bytes`,
            `虚拟屏尺寸 ${value.screenW}x${value.screenH}, 截图尺寸 ${value.imageW}x${value.imageH}, scaleX=${value.scaleX} scaleY=${value.scaleY}${value.grid ? `, 已叠加 ${value.grid}x${value.grid} 网格` : ""}`,
            "图上像素 (px,py) → 虚拟屏坐标 = (px×scaleX, py×scaleY)；分数坐标 fx=px/imageW, fy=py/imageH"
          ].join("\n");
          return [{
            type: "text",
            text: `<path>${value.path}</path>\n<type>image</type>\n<content>\n${meta}\n</content>`
          }, {
            type: "image",
            attachment: {
              attachmentId: value.image.attachmentId,
              mediaType: value.image.mediaType,
              bytes: value.image.bytes,
              width: value.image.width,
              height: value.image.height,
              ...(value.image.name === void 0 ? {} : { name: value.image.name })
            }
          }];
        }
      },
      async execute(args) {
        const attachments = imageCtx.get("attachments");
        if (attachments === void 0) {
          return { ok: false, error: "cannot screenshot: no attachment service is mounted" };
        }
        const r = await vsReq("/vscreen/see", 12000);
        if (!r.ok) return { ok: false, error: r.error || "截图失败" };
        if (!r.path) return { ok: false, error: "虚拟屏未创建或截图失败" };
        let data;
        try {
          data = await readFile(r.path);
        } catch (e) {
          return { ok: false, error: "读取截图失败: " + String(e && e.message || e) };
        }
        try {
          const ref = await attachments.saveImage({ data, mediaType: "image/png", name: "vscreen.png" });
          return {
            ok: true,
            path: r.path,
            displayId: typeof r.displayId === "number" ? r.displayId : 0,
            screenW: typeof r.screenW === "number" ? r.screenW : 0,
            screenH: typeof r.screenH === "number" ? r.screenH : 0,
            imageW: typeof r.imageW === "number" ? r.imageW : 0,
            imageH: typeof r.imageH === "number" ? r.imageH : 0,
            scaleX: typeof r.scaleX === "number" ? r.scaleX : 1,
            scaleY: typeof r.scaleY === "number" ? r.scaleY : 1,
            grid: 0,
            image: {
              attachmentId: ref.attachmentId,
              mediaType: "image/png",
              bytes: data.length,
              width: typeof r.imageW === "number" ? r.imageW : 0,
              height: typeof r.imageH === "number" ? r.imageH : 0,
              name: "vscreen.png"
            }
          };
        } catch (e) {
          return { ok: false, error: "保存截图失败: " + String(e && e.message || e) };
        }
      }
    }));
  });

  // 5) 点击
  ctx.tools.register(defineTool({
    name: "android_vscreen_tap",
    description: "在虚拟屏上点击（绝对像素坐标，来自 android_vscreen_see 截图 ×scaleX/Y）。",
    parameters: {
      x: { type: "number", description: "点击 x", required: true },
      y: { type: "number", description: "点击 y", required: true }
    },
    output: {
      schema: {
        type: "object",
        additionalProperties: false,
        properties: { ok: { type: "boolean", required: true }, error: { type: "string" } }
      },
      render: renderOp
    },
    async execute(args) {
      const x = Math.round(Number(args.x));
      const y = Math.round(Number(args.y));
      if (!Number.isFinite(x) || !Number.isFinite(y)) return { ok: false, error: "坐标无效" };
      const id = await getDisplayId();
      if (id < 0) return { ok: false, error: "虚拟屏未创建，请先调用 android_vscreen_create" };
      // 走特权服务端 HTTP（服务端本身是 shell uid，不依赖插件的 rish 通道）
      const r = await vsReq(`/vscreen/tap?x=${x}&y=${y}`, 8000);
      return r.ok ? { ok: true } : { ok: false, error: r.error || "点击失败" };
    }
  }));

  // 6) 滑动
  ctx.tools.register(defineTool({
    name: "android_vscreen_swipe",
    description: "在虚拟屏上滑动（绝对像素坐标，起点→终点）。",
    parameters: {
      x1: { type: "number", description: "起点 x", required: true },
      y1: { type: "number", description: "起点 y", required: true },
      x2: { type: "number", description: "终点 x", required: true },
      y2: { type: "number", description: "终点 y", required: true },
      durationMs: { type: "number", description: "滑动时长毫秒（缺省 300）" }
    },
    output: {
      schema: {
        type: "object",
        additionalProperties: false,
        properties: { ok: { type: "boolean", required: true }, error: { type: "string" } }
      },
      render: renderOp
    },
    async execute(args) {
      const nums = [args.x1, args.y1, args.x2, args.y2].map(Number);
      if (nums.some((n) => !Number.isFinite(n))) return { ok: false, error: "坐标无效" };
      const dur = Math.max(0, Math.round(Number(args.durationMs) || 300));
      const id = await getDisplayId();
      if (id < 0) return { ok: false, error: "虚拟屏未创建，请先调用 android_vscreen_create" };
      const r = await vsReq(`/vscreen/swipe?x1=${Math.round(nums[0])}&y1=${Math.round(nums[1])}&x2=${Math.round(nums[2])}&y2=${Math.round(nums[3])}&dur=${dur}`, 12000);
      return r.ok ? { ok: true } : { ok: false, error: r.error || "滑动失败" };
    }
  }));

  // 7) 按键
  ctx.tools.register(defineTool({
    name: "android_vscreen_key",
    description: "向虚拟屏发送按键。key 取值：back / home / enter / menu / volume_up / volume_down。",
    parameters: {
      key: { type: "string", description: "back/home/enter/menu/volume_up/volume_down", required: true }
    },
    output: {
      schema: {
        type: "object",
        additionalProperties: false,
        properties: { ok: { type: "boolean", required: true }, error: { type: "string" }, key: { type: "string" } }
      },
      render: renderOp
    },
    async execute(args) {
      const keyMap = {
        back: 4, home: 3, enter: 66, menu: 82,
        volume_up: 24, volume_down: 25, app_switch: 187
      };
      const k = String(args.key || "").trim().toLowerCase();
      const kc = keyMap[k];
      if (!kc) return { ok: false, error: "未知按键: " + args.key };
      const id = await getDisplayId();
      if (id < 0) return { ok: false, error: "虚拟屏未创建，请先调用 android_vscreen_create" };
      const r = await vsReq(`/vscreen/key?keycode=${kc}`, 8000);
      return r.ok ? { ok: true, key: k } : { ok: false, error: r.error || "按键失败" };
    }
  }));

  // 8) 关闭
  ctx.tools.register(defineTool({
    name: "android_vscreen_close",
    description: "关闭/销毁虚拟屏（释放虚拟屏与抓帧器），虚拟屏上的应用回到主屏。",
    parameters: {},
    output: {
      schema: {
        type: "object",
        additionalProperties: false,
        properties: { ok: { type: "boolean", required: true }, error: { type: "string" } }
      },
      render: renderOp
    },
    async execute() {
      const r = await vsReq("/vscreen/close", 8000);
      cachedDisplayId = -1;
      return r.ok ? { ok: true } : { ok: false, error: r.error || "关闭失败" };
    }
  }));
}

// ==================== 注入 ====================

/** 虚拟屏 displayId 缓存（create 成功设置 / close 重置 / status 兜底）。 */
let cachedDisplayId = -1;

async function getDisplayId() {
  if (cachedDisplayId >= 0) return cachedDisplayId;
  try {
    const r = await vsReq("/vscreen/status", 4000);
    if (r && r.ok && typeof r.displayId === "number" && r.displayId >= 0) {
      cachedDisplayId = r.displayId;
      return cachedDisplayId;
    }
  } catch (_) {}
  return -1;
}

/** 应用名 → 包名（pm list packages 过滤；找不到原样返回）。 */
async function resolvePackageName(appName) {
  const r = await privShell("pm list packages -3 | grep -i " + appName.replace(/[^a-zA-Z0-9_.]/g, ""), 10000);
  if (r.ok && r.stdout) {
    const lines = r.stdout.split("\n").map((s) => s.trim()).filter(Boolean);
    if (lines.length === 1) return lines[0].replace("package:", "");
  }
  return appName;
}

export { name, inject, apply };
