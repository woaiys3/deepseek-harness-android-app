/**
 * 无障碍屏幕助手插件（v1.7）：给 AI 提供「读屏 + 操作屏幕 + 屏幕截图理解」能力。
 * 与 App 侧 AccessibilityService 通过本地 HTTP（APP_A11Y_PORT，默认 3181）通信。
 * 用户需在系统设置 → 无障碍开启「DeepSeek Harness 屏幕助手」，未开启时返回引导文案。
 *
 * 注意：DSH 工具输出校验为 additionalProperties:false——execute 返回的每个字段
 * 都必须在 output.schema 中显式声明，否则结果会被判定为非法输出（历史踩坑）。
 */
import { defineTool } from "@deepseek-ai/dsh-tools";
import { get as httpGet, request as httpRequest } from "node:http";
import { readFile } from "node:fs/promises";

const name = "tool-accessibility";
const inject = ["tools"];

const a11yPort = () => parseInt(process.env.APP_A11Y_PORT || "3181", 10);

const GUIDE_TEXT =
  "无障碍服务未开启或不可用：请在手机系统设置 → 无障碍 →（已下载的服务/服务）→ 开启「DeepSeek Harness 屏幕助手」，然后打开 App 后重试。";

function a11yRequest(path, params, timeoutMs) {
  return new Promise((resolve) => {
    const qs = params
      ? "?" + Object.entries(params).map(([k, v]) =>
          encodeURIComponent(k) + "=" + encodeURIComponent(v)).join("&")
      : "";
    const req = httpGet({
      host: "127.0.0.1",
      port: a11yPort(),
      path: path + qs,
      timeout: timeoutMs || 8000
    }, (res) => {
      let data = "";
      res.setEncoding("utf8");
      res.on("data", (c) => { data += c; if (data.length > 262144) req.destroy(); });
      res.on("end", () => resolve(data || "{\"ok\":false,\"error\":\"empty response\"}"));
    });
    req.on("error", () => resolve("{\"ok\":false,\"error\":\"App 本地服务不可用\"}"));
    req.on("timeout", () => { req.destroy(); resolve("{\"ok\":false,\"error\":\"App 本地服务超时\"}"); });
    req.end();
  });
}

/** POST JSON（/gesture 用）。 */
function a11yPost(path, body, timeoutMs) {
  return new Promise((resolve) => {
    const payload = JSON.stringify(body);
    const req = httpRequest({
      host: "127.0.0.1",
      port: a11yPort(),
      path,
      method: "POST",
      headers: { "Content-Type": "application/json", "Content-Length": Buffer.byteLength(payload) },
      timeout: timeoutMs || 8000
    }, (res) => {
      let data = "";
      res.setEncoding("utf8");
      res.on("data", (c) => { data += c; if (data.length > 262144) req.destroy(); });
      res.on("end", () => resolve(data || "{\"ok\":false,\"error\":\"empty response\"}"));
    });
    req.on("error", () => resolve("{\"ok\":false,\"error\":\"App 本地服务不可用\"}"));
    req.on("timeout", () => { req.destroy(); resolve("{\"ok\":false,\"error\":\"App 本地服务超时\"}"); });
    req.end(payload);
  });
}

function parseResult(raw, hint) {
  try {
    const v = JSON.parse(raw);
    if (!v.ok && v.error && /服务不可用|超时|empty response/.test(v.error)) {
      return { ok: false, error: (hint || GUIDE_TEXT) };
    }
    return v;
  } catch (e) {
    return { ok: false, error: "无障碍服务响应解析失败: " + String(raw).slice(0, 120) };
  }
}

function renderResult(value) {
  return [{
    type: "text",
    text: value.ok ? "操作成功。" : "执行失败：" + (value.error || "未知错误")
  }];
}

/** 屏幕节点树渲染成 AI 可读文本列表（带索引，方便 android_tap 引用坐标）。 */
function renderScreen(_args, value) {
  if (!value.ok) return renderResult(value);
  const lines = [];
  lines.push("当前前台应用: " + (value.package || "未知"));
  lines.push("节点数: " + value.count + (value.truncated ? "（已截断，仅显示部分）" : ""));
  if (value.hint) lines.push("提示: " + value.hint);
  lines.push("");
  const nodes = value.nodes || [];
  nodes.forEach((n, i) => {
    const flags = [];
    if (n.clickable) flags.push("可点击");
    if (n.input) flags.push("可输入");
    if (n.checked) flags.push("已选中");
    if (n.scrollable) flags.push("可滚动");
    const label = n.text || n.desc || "(无文字)";
    const short = label.length > 120 ? label.slice(0, 120) + "…" : label;
    lines.push(`[${i}] ${short}  (${n.x},${n.y} ${n.w}x${n.h})${flags.length ? " " + flags.join("/") : ""}`);
  });
  return [{ type: "text", text: lines.join("\n") }];
}

function apply(ctx) {
  // 状态查询（始终注册：AI 先查状态，未开启时引导用户去系统设置开启）
  ctx.tools.register(defineTool({
    name: "android_a11y_status",
    description:
      "查询 DeepSeek Harness 无障碍服务（屏幕助手）是否已开启，以及当前屏幕焦点应用。" +
      "无障碍服务开启后，AI 才能读取屏幕内容并替你点击/输入/滚动（android_screen/android_tap 等）。" +
      "若未开启（running=false），请引导用户：系统设置 → 无障碍 →（已下载的服务/服务）→ 开启「DeepSeek Harness 屏幕助手」。",
    parameters: {},
    output: {
      schema: {
        type: "object",
        additionalProperties: false,
        properties: {
          ok: { type: "boolean", required: true },
          error: { type: "string" },
          running: { type: "boolean" },
          package: { type: "string" },
          nodeCount: { type: "number" },
          canScreenshot: { type: "boolean" },
          apiLevel: { type: "number" }
        }
      },
      render: (_a, v) => renderResult(v)
    },
    async execute(args, exec) {
      const raw = await a11yRequest("/status", undefined, 4000);
      const v = parseResult(raw);
      if (!v.ok) return { ok: false, error: v.error || GUIDE_TEXT };
      return {
        ok: true,
        running: v.running === true,
        package: v.package || "",
        nodeCount: typeof v.nodeCount === "number" ? v.nodeCount : 0,
        canScreenshot: v.canScreenshot === true,
        apiLevel: typeof v.apiLevel === "number" ? v.apiLevel : 0
      };
    }
  }));

  // 读屏（控件树）
  ctx.tools.register(defineTool({
    name: "android_screen",
    description:
      "读取当前屏幕的控件树（无障碍）：返回前台应用包名、屏幕可见控件的文字/描述/坐标/可点击性。" +
      "坐标是屏幕绝对像素坐标，可直接用于 android_tap 的 x/y。" +
      "用于回答「屏幕上现在有什么」「帮我找到某某按钮/选项」「当前在哪个界面」。需要已开启无障碍服务。",
    parameters: {},
    output: {
      schema: {
        type: "object",
        additionalProperties: false,
        properties: {
          ok: { type: "boolean", required: true },
          error: { type: "string" },
          package: { type: "string" },
          count: { type: "number" },
          truncated: { type: "boolean" },
          hint: { type: "string" },
          nodes: {
            type: "array",
            items: {
              type: "object",
              additionalProperties: false,
              properties: {
                text: { type: "string" },
                desc: { type: "string" },
                cls: { type: "string" },
                x: { type: "number" },
                y: { type: "number" },
                w: { type: "number" },
                h: { type: "number" },
                clickable: { type: "boolean" },
                input: { type: "boolean" },
                checked: { type: "boolean" },
                selected: { type: "boolean" },
                scrollable: { type: "boolean" },
                depth: { type: "number" }
              }
            }
          }
        }
      },
      render: renderScreen
    },
    async execute(args, exec) {
      const raw = await a11yRequest("/dump", undefined, 8000);
      const v = parseResult(raw);
      if (!v.ok) return { ok: false, error: v.error || GUIDE_TEXT };
      const nodes = Array.isArray(v.nodes) ? v.nodes.map((n) => ({
        text: typeof n.text === "string" ? n.text : "",
        desc: typeof n.desc === "string" ? n.desc : "",
        cls: typeof n.cls === "string" ? n.cls : "",
        x: typeof n.x === "number" ? n.x : 0,
        y: typeof n.y === "number" ? n.y : 0,
        w: typeof n.w === "number" ? n.w : 0,
        h: typeof n.h === "number" ? n.h : 0,
        clickable: n.clickable === true,
        input: n.input === true,
        checked: n.checked === true,
        selected: n.selected === true,
        scrollable: n.scrollable === true,
        depth: typeof n.depth === "number" ? n.depth : 0
      })) : [];
      return {
        ok: true,
        package: v.package || "",
        count: nodes.length,
        truncated: v.truncated === true,
        ...(typeof v.hint === "string" && v.hint ? { hint: v.hint } : {}),
        nodes
      };
    }
  }));

  // 点击
  ctx.tools.register(defineTool({
    name: "android_tap",
    description:
      "点击屏幕上的控件。传 text（控件文字，模糊包含匹配，优先可点击项）、desc（内容描述）、x/y（屏幕绝对像素坐标）或 fx/fy（0~1 分数坐标）。" +
      "优先用 fx/fy 分数坐标（相对屏幕比例）：截图会被模型查看器缩放，用绝对像素容易点偏，分数坐标免疫缩放。" +
      "至少给一个；同时给了 text 与坐标时按 text 查找优先，找不到再按坐标点。需要已开启无障碍服务。",
    parameters: {
      text: { type: "string", description: "控件文字（模糊包含匹配）" },
      desc: { type: "string", description: "控件内容描述（模糊包含匹配）" },
      x: { type: "number", description: "屏幕绝对 x 坐标（像素）" },
      y: { type: "number", description: "屏幕绝对 y 坐标（像素）" },
      fx: { type: "number", description: "分数 x 坐标（0~1，相对屏幕宽度比例；推荐，避免截图缩放误差）" },
      fy: { type: "number", description: "分数 y 坐标（0~1，相对屏幕高度比例；推荐，避免截图缩放误差）" }
    },
    output: {
      schema: {
        type: "object",
        additionalProperties: false,
        properties: {
          ok: { type: "boolean", required: true },
          error: { type: "string" },
          found: { type: "boolean" },
          method: { type: "string" }
        }
      },
      render: (_a, v) => renderResult(v)
    },
    async execute(args, exec) {
      const params = {};
      if (args.text !== undefined && String(args.text).length > 0) params.text = String(args.text);
      if (args.desc !== undefined && String(args.desc).length > 0) params.desc = String(args.desc);
      if (args.x !== undefined) params.x = String(Number(args.x));
      if (args.y !== undefined) params.y = String(Number(args.y));
      if (args.fx !== undefined) params.fx = String(Number(args.fx));
      if (args.fy !== undefined) params.fy = String(Number(args.fy));
      if (Object.keys(params).length === 0) {
        return { ok: false, error: "android_tap 需要至少一个参数：text / desc / x / y / fx / fy" };
      }
      const raw = await a11yRequest("/tap", params, 8000);
      const v = parseResult(raw);
      if (!v.ok) return { ok: false, error: v.error || GUIDE_TEXT };
      return {
        ok: v.found !== false,
        found: v.found === true,
        method: typeof v.method === "string" ? v.method : "",
        ...(v.found === false && v.error ? { error: v.error } : {})
      };
    }
  }));

  // 输入文本（无障碍版；与特权版 android_input 区分，避免工具名冲突）
  ctx.tools.register(defineTool({
    name: "android_type",
    description:
      "在当前聚焦的输入框中输入文本（通过无障碍服务）。输入前通常先用 android_tap 点击目标输入框使其聚焦。需要已开启无障碍服务。\n" +
      "注意：目标输入框如果是网页/WebView（如网页版表单、contenteditable 编辑器），默认 setText 只改无障碍节点、不触发前端 input 事件，界面不刷新——此时请用 paste:true（走剪贴板粘贴，会触发前端更新）。原生 App 输入框默认即可。\n" +
      "注：本工具是无障碍版输入（不需要 root/Shizuku）；已授权 Shizuku/root 时另有系统级 android_input（input text/tap/swipe/keyevent），两者能力不同。",
    parameters: {
      text: { type: "string", required: true, description: "要输入的文本" },
      paste: { type: "boolean", description: "是否用剪贴板粘贴方式输入（WebView/网页输入框建议 true；默认 false 用 setText）" }
    },
    output: {
      schema: {
        type: "object",
        additionalProperties: false,
        properties: {
          ok: { type: "boolean", required: true },
          error: { type: "string" },
          focused: { type: "boolean" },
          method: { type: "string" }
        }
      },
      render: (_a, v) => renderResult(v)
    },
    async execute(args, exec) {
      if (!args.text) return { ok: false, error: "android_type 需要 text 参数" };
      const params = { text: String(args.text) };
      if (args.paste === true) params.mode = "paste";
      const raw = await a11yRequest("/input", params, 8000);
      const v = parseResult(raw);
      if (!v.ok) return { ok: false, error: v.error || GUIDE_TEXT };
      return {
        ok: v.ok !== false,
        ...(v.focused !== undefined ? { focused: v.focused === true } : {}),
        ...(v.method ? { method: String(v.method) } : {}),
        ...(v.error ? { error: v.error } : {})
      };
    }
  }));

  // 返回 / 回桌面
  const globalAction = (toolName, actionPath, description) => {
    ctx.tools.register(defineTool({
      name: toolName,
      description,
      parameters: {},
      output: {
        schema: {
          type: "object",
          additionalProperties: false,
          properties: {
            ok: { type: "boolean", required: true },
            error: { type: "string" }
          }
        },
        render: (_a, v) => renderResult(v)
      },
      async execute(args, exec) {
        const raw = await a11yRequest(actionPath, undefined, 6000);
        const v = parseResult(raw);
        if (!v.ok) return { ok: false, error: v.error || GUIDE_TEXT };
        return { ok: v.ok !== false, ...(v.error ? { error: v.error } : {}) };
      }
    }));
  };
  globalAction("android_back", "/back", "模拟按下系统返回键（回到上一界面）。需要已开启无障碍服务。");
  globalAction("android_home", "/home", "模拟按下系统 Home 键（回到桌面）。需要已开启无障碍服务。");

  // 滚动
  ctx.tools.register(defineTool({
    name: "android_scroll",
    description:
      "在当前可滚动区域滚动屏幕：direction 为 up（向上滚动看更上面内容）/ down / left / right。" +
      "用于翻页、浏览长列表。需要已开启无障碍服务。",
    parameters: {
      direction: {
        type: "string", required: true, enum: ["up", "down", "left", "right"],
        description: "滚动方向"
      }
    },
    output: {
      schema: {
        type: "object",
        additionalProperties: false,
        properties: {
          ok: { type: "boolean", required: true },
          error: { type: "string" },
          method: { type: "string" }
        }
      },
      render: (_a, v) => renderResult(v)
    },
    async execute(args, exec) {
      const raw = await a11yRequest("/scroll", { direction: String(args.direction || "down") }, 8000);
      const v = parseResult(raw);
      if (!v.ok) return { ok: false, error: v.error || GUIDE_TEXT };
      return {
        ok: v.ok !== false,
        ...(v.method ? { method: String(v.method) } : {}),
        ...(v.error ? { error: v.error } : {})
      };
    }
  }));

  // 截图（需要 attachments 服务 + 视觉模型）
  ctx.inject(["attachments"], (imageCtx) => {
    imageCtx.tools.register(defineTool({
      name: "android_see",
      description:
        "截取当前屏幕（无障碍截图，无需 MediaProjection 弹窗）并把截图作为图片发送给模型查看。" +
        "适合需要看图理解布局/图片内容、或控件树（android_screen）信息不足时（尤其 Unity/游戏等无控件界面）。" +
        "**截图会被模型查看器缩放，绝对像素坐标会点偏——请优先用分数坐标（fx/fy，0~1）配合 android_tap/android_swipe/android_hold/android_gesture 操作**。" +
        "换算：截图上量到的像素 (px,py) → 屏幕坐标 = (px×scaleX, py×scaleY)（scaleX=screenW/imageW，截图原生分辨率≈屏幕，通常≈1）。" +
        "游戏/无控件界面建议 grid:true 叠加 4×4 网格，按「第几行第几列」定位更准。" +
        "需要当前模型支持图片输入；模型不支持图片时请改用 android_screen 读控件文字。" +
        "需要已开启无障碍服务且设备 Android 11+（截图能力），低版本可用 android_screen。",
      parameters: {
        grid: { type: "boolean", description: "true 时在截图上叠加 4×4 网格线，方便按行列定位（游戏/无控件界面推荐）" }
      },
      output: {
        schema: {
          type: "object",
          additionalProperties: false,
          properties: {
            ok: { type: "boolean", required: true },
            error: { type: "string" },
            path: { type: "string" },
            screenW: { type: "number" },
            screenH: { type: "number" },
            imageW: { type: "number" },
            imageH: { type: "number" },
            scaleX: { type: "number" },
            scaleY: { type: "number" },
            grid: { type: "number" },
            hint: { type: "string" },
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
          if (!value.ok) return renderResult(value);
          const meta = [
            `${value.image.mediaType} 屏幕截图, ${value.image.width}x${value.image.height} px, ${value.image.bytes} bytes`,
            `屏幕尺寸 ${value.screenW}x${value.screenH}, 截图尺寸 ${value.imageW}x${value.imageH}, 换算系数 scaleX=${value.scaleX} scaleY=${value.scaleY}${value.grid ? `, 已叠加 ${value.grid}x${value.grid} 网格` : ""}`,
            "操作优先用分数坐标 fx/fy（0~1）：图中位置 (ix,iy) → fx=ix/imageW, fy=iy/imageH；用绝对像素 = 图中像素 × scaleX/Y"
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
      async execute(args, exec) {
        const attachments = imageCtx.get("attachments");
        if (attachments === void 0) {
          return { ok: false, error: "cannot screenshot: no attachment service is mounted" };
        }
        const params = args.grid === true ? { grid: "4" } : undefined;
        const raw = await a11yRequest("/screenshot", params, 20000);
        const v = parseResult(raw);
        if (!v.ok || !v.path) {
          return { ok: false, error: v.error || "截图失败（可能设备低于 Android 11，或当前页面禁止截图）" };
        }
        let data;
        try {
          data = await readFile(v.path);
        } catch (e) {
          return { ok: false, error: "读取截图失败: " + String(e && e.message || e) };
        }
        try {
          const ref = await attachments.saveImage({
            data,
            mediaType: "image/png",
            name: "screen.png"
          });
          return {
            ok: true,
            path: v.path,
            screenW: typeof v.screenW === "number" ? v.screenW : 0,
            screenH: typeof v.screenH === "number" ? v.screenH : 0,
            imageW: typeof v.imageW === "number" ? v.imageW : 0,
            imageH: typeof v.imageH === "number" ? v.imageH : 0,
            scaleX: typeof v.scaleX === "number" ? v.scaleX : 1,
            scaleY: typeof v.scaleY === "number" ? v.scaleY : 1,
            grid: typeof v.grid === "number" ? v.grid : 0,
            ...(typeof v.hint === "string" && v.hint ? { hint: v.hint } : {}),
            image: {
              attachmentId: ref.attachmentId,
              mediaType: ref.mediaType,
              bytes: ref.bytes,
              width: ref.width,
              height: ref.height,
              name: ref.name
            }
          };
        } catch (e) {
          return { ok: false, error: "截图保存为附件失败: " + String(e && e.message || e) };
        }
      }
    }));
  });

  // ===================== v1.7.3 通用触摸手势工具 =====================
  // 底层：无障碍 dispatchGesture 多笔时间轴（真多指）+ willContinue 按住保持。
  // 一套原语覆盖所有触摸操作（点击/滑动/长按/按住拖动/多指同时），不绑定任何具体 App/游戏。
  // 坐标统一支持 x/y（屏幕绝对像素）或 fx/fy（0~1 分数，推荐——截图会被查看器缩放）。

  const heldSchema = {
    type: "object",
    additionalProperties: false,
    properties: {
      finger: { type: "number" },
      x: { type: "number" },
      y: { type: "number" },
      fx: { type: "number" },
      fy: { type: "number" }
    }
  };
  const touchOutput = {
    schema: {
      type: "object",
      additionalProperties: false,
      properties: {
        ok: { type: "boolean", required: true },
        error: { type: "string" },
        durationMs: { type: "number" },
        held: { type: "array", items: heldSchema }
      }
    },
    render: (_a, v) => renderResult(v)
  };

  // 滑动
  ctx.tools.register(defineTool({
    name: "android_swipe",
    description:
      "在屏幕上从起点滑动到终点（按下→移动→抬起，单指）。" +
      "参数可用屏幕绝对像素（x1/y1→x2/y2）或分数坐标（fx1/fy1→fx2/fy2，0~1，推荐）。" +
      "durationMs 控制滑动时长（默认 300ms；慢速拖动可加大到 800~1500ms）。" +
      "适合翻页、划动列表、游戏内转向/拖动。需要已开启无障碍服务。",
    parameters: {
      x1: { type: "number", description: "起点 x（像素）" },
      y1: { type: "number", description: "起点 y（像素）" },
      x2: { type: "number", description: "终点 x（像素）" },
      y2: { type: "number", description: "终点 y（像素）" },
      fx1: { type: "number", description: "起点分数 x（0~1，推荐）" },
      fy1: { type: "number", description: "起点分数 y（0~1，推荐）" },
      fx2: { type: "number", description: "终点分数 x（0~1，推荐）" },
      fy2: { type: "number", description: "终点分数 y（0~1，推荐）" },
      durationMs: { type: "number", description: "滑动时长毫秒（默认 300）" },
      finger: { type: "number", description: "可选：指定手指（0~7）；若该手指正按住则从当前位置滑到终点并抬起" }
    },
    output: touchOutput,
    async execute(args, exec) {
      const params = {};
      for (const k of ["x1", "y1", "x2", "y2", "fx1", "fy1", "fx2", "fy2"]) {
        if (args[k] !== undefined) params[k] = String(Number(args[k]));
      }
      if (args.durationMs !== undefined) params.duration = String(Number(args.durationMs));
      if (args.finger !== undefined) params.finger = String(Number(args.finger));
      if (!(("x1" in params || "fx1" in params) && ("y1" in params || "fy1" in params) &&
            ("x2" in params || "fx2" in params) && ("y2" in params || "fy2" in params))) {
        return { ok: false, error: "android_swipe 需要起点(x1/y1 或 fx1/fy1)和终点(x2/y2 或 fx2/fy2)" };
      }
      const raw = await a11yRequest("/swipe", params, 12000);
      const v = parseResult(raw);
      if (!v.ok) return { ok: false, error: v.error || GUIDE_TEXT };
      return {
        ok: true,
        ...(typeof v.durationMs === "number" ? { durationMs: v.durationMs } : {}),
        ...(Array.isArray(v.held) ? { held: v.held } : {})
      };
    }
  }));

  // 长按 / 按住指定时长后自动抬起
  ctx.tools.register(defineTool({
    name: "android_hold",
    description:
      "在指定位置按住（长按）durationMs 毫秒后自动抬起，也可用 finger 指定手指。" +
      "**需要一直按住不放（延续到后续操作）时，不要用本工具，改用 android_touch action=down**（down 后手指保持按住，可跨调用延续）。" +
      "适合长按图标、游戏蓄力、按住等待等。需要已开启无障碍服务。",
    parameters: {
      x: { type: "number", description: "按住 x（像素）" },
      y: { type: "number", description: "按住 y（像素）" },
      fx: { type: "number", description: "分数 x（0~1，推荐）" },
      fy: { type: "number", description: "分数 y（0~1，推荐）" },
      durationMs: { type: "number", description: "按住时长毫秒（默认 500）" },
      finger: { type: "number", description: "可选：指定手指（0~7）" }
    },
    output: touchOutput,
    async execute(args, exec) {
      const params = {};
      for (const k of ["x", "y", "fx", "fy"]) {
        if (args[k] !== undefined) params[k] = String(Number(args[k]));
      }
      if (args.durationMs !== undefined) params.duration = String(Number(args.durationMs));
      if (args.finger !== undefined) params.finger = String(Number(args.finger));
      if (!(("x" in params || "fx" in params) && ("y" in params || "fy" in params))) {
        return { ok: false, error: "android_hold 需要 x/y 或 fx/fy" };
      }
      const raw = await a11yRequest("/hold", params, 12000);
      const v = parseResult(raw);
      if (!v.ok) return { ok: false, error: v.error || GUIDE_TEXT };
      return { ok: true, ...(Array.isArray(v.held) ? { held: v.held } : {}) };
    }
  }));

  // 状态式虚拟触摸屏（多指核心）
  ctx.tools.register(defineTool({
    name: "android_touch",
    description:
      "虚拟触摸屏状态式控制：action=down（按下并保持）/ move（按住的手指滑到新位置）/ up（抬起）。" +
      "每根手指用 finger 编号（0~7）区分，多根手指可同时按住——多指操作的基础。" +
      "**典型用法：按住摇杆 = down(0) 在摇杆位置，然后 move(0) 拖动控制方向，松开 = up(0)**。" +
      "down 之后手指一直按住，直到你 up / android_touch_status 确认 / 超时（30s）自动抬起。" +
      "跨调用延续：down(0) 后可直接调 android_tap/android_gesture 等，按住的手指不会被松开（自动并入后续手势）。" +
      "坐标支持 x/y 或 fx/fy（0~1，推荐）。需要已开启无障碍服务。",
    parameters: {
      action: { type: "string", required: true, enum: ["down", "move", "up"], description: "down=按下保持 / move=按住移动 / up=抬起" },
      finger: { type: "number", required: true, description: "手指编号 0~7（多指的关键：每根手指一个编号）" },
      x: { type: "number", description: "目标 x（像素）" },
      y: { type: "number", description: "目标 y（像素）" },
      fx: { type: "number", description: "分数 x（0~1，推荐）" },
      fy: { type: "number", description: "分数 y（0~1，推荐）" }
    },
    output: touchOutput,
    async execute(args, exec) {
      if (!args.action) return { ok: false, error: "android_touch 需要 action=down|move|up" };
      if (args.finger === undefined) return { ok: false, error: "android_touch 需要 finger=0~7" };
      const params = { action: String(args.action), finger: String(Number(args.finger)) };
      for (const k of ["x", "y", "fx", "fy"]) {
        if (args[k] !== undefined) params[k] = String(Number(args[k]));
      }
      const raw = await a11yRequest("/touch", params, 12000);
      const v = parseResult(raw);
      if (!v.ok) return { ok: false, error: v.error || GUIDE_TEXT };
      return { ok: true, ...(Array.isArray(v.held) ? { held: v.held } : {}) };
    }
  }));

  // 组合手势（多笔时间轴，一次全部注入）
  ctx.tools.register(defineTool({
    name: "android_gesture",
    description:
      "一次执行一组多指手势（底层多笔时间轴，全部同时注入，真多指）。strokes 数组按顺序在时间轴上执行，支持：\n" +
      "- down: 按下并保持 {kind:'down', finger, x/y 或 fx/fy}\n" +
      "- move: 按住的手指滑到新位置 {kind:'move', finger, x/y 或 fx/fy}\n" +
      "- up: 抬起 {kind:'up', finger}\n" +
      "- tap: 点按 {kind:'tap', x/y 或 fx/fy, [finger], [durationMs]}\n" +
      "- swipe: 滑动 {kind:'swipe', x/y 或 fx/fy → x2/y2 或 fx2/fy2, [durationMs]}\n" +
      "- hold: 按下→保持 durationMs→抬起 {kind:'hold', x/y 或 fx/fy, [durationMs], [finger]}\n" +
      "- wait: 等待 {kind:'wait', ms}\n" +
      "**典型游戏场景：左手按住摇杆同时右手点击 = [down(0, 摇杆), tap(1, 按钮)]**；按住摇杆拖动 = [down(0, 摇杆中心), move(0, 目标方向)]。" +
      "down 的手指在请求结束后继续保持（可跨请求延续），直到 up / 超时自动抬起。" +
      "坐标全部支持 fx/fy（0~1，推荐）。需要已开启无障碍服务。",
    parameters: {
      strokes: {
        type: "array",
        required: true,
        items: {
          type: "object",
          additionalProperties: false,
          properties: {
            kind: { type: "string", required: true, enum: ["down", "move", "up", "tap", "swipe", "hold", "wait"], description: "笔类型" },
            finger: { type: "number", description: "手指编号 0~7" },
            x: { type: "number", description: "目标 x（像素）" },
            y: { type: "number", description: "目标 y（像素）" },
            fx: { type: "number", description: "分数 x（0~1，推荐）" },
            fy: { type: "number", description: "分数 y（0~1，推荐）" },
            x2: { type: "number", description: "swipe 终点 x（像素）" },
            y2: { type: "number", description: "swipe 终点 y（像素）" },
            fx2: { type: "number", description: "swipe 终点分数 x（0~1）" },
            fy2: { type: "number", description: "swipe 终点分数 y（0~1）" },
            durationMs: { type: "number", description: "时长（wait=等待毫秒；tap 默认60；swipe 默认300；hold 默认500；move/up 默认100）" },
            ms: { type: "number", description: "wait 的等待毫秒" }
          }
        },
        description: "手势笔列表（按顺序在时间轴上执行）"
      }
    },
    output: touchOutput,
    async execute(args, exec) {
      const strokes = args.strokes;
      if (!Array.isArray(strokes) || strokes.length === 0) {
        return { ok: false, error: "android_gesture 需要 strokes 数组" };
      }
      const ALLOWED = ["kind", "finger", "x", "y", "fx", "fy", "x2", "y2", "fx2", "fy2", "durationMs", "ms"];
      const clean = [];
      let total = 0;
      for (const s of strokes) {
        if (!s || typeof s !== "object") return { ok: false, error: "strokes 元素必须是对象" };
        const kind = s.kind;
        const dur = typeof s.durationMs === "number" ? s.durationMs : 0;
        if (kind === "wait") total += typeof s.ms === "number" && s.ms > 0 ? s.ms : 0;
        else if (kind === "tap") total += dur > 0 ? dur : 60;
        else if (kind === "swipe") total += dur > 0 ? dur : 300;
        else if (kind === "hold") total += dur > 0 ? dur : 500;
        else if (kind === "move") total += dur > 0 ? dur : 100;
        else if (kind === "up") total += dur > 0 ? dur : 100;
        else if (kind !== "down") return { ok: false, error: "未知 kind: " + String(kind) };
        const o = {};
        for (const k of Object.keys(s)) {
          if (ALLOWED.includes(k)) o[k] = s[k];
        }
        clean.push(o);
      }
      const raw = await a11yPost("/gesture", clean, total + 15000);
      const v = parseResult(raw);
      if (!v.ok) return { ok: false, error: v.error || GUIDE_TEXT };
      return {
        ok: true,
        ...(typeof v.durationMs === "number" ? { durationMs: v.durationMs } : {}),
        ...(Array.isArray(v.held) ? { held: v.held } : {})
      };
    }
  }));

  // 触摸状态查询
  ctx.tools.register(defineTool({
    name: "android_touch_status",
    description:
      "查询当前按住的手指（虚拟触摸屏状态）。用于确认之前 down 的手指是否还在按住、坐标在哪、按住多久。" +
      "手指按住超过 30 秒会被自动抬起（安全机制）。需要已开启无障碍服务。",
    parameters: {},
    output: {
      schema: {
        type: "object",
        additionalProperties: false,
        properties: {
          ok: { type: "boolean", required: true },
          error: { type: "string" },
          screenW: { type: "number" },
          screenH: { type: "number" },
          maxFingers: { type: "number" },
          holdTimeoutMs: { type: "number" },
          held: { type: "array", items: heldSchema }
        }
      },
      render: (_a, v) => renderResult(v)
    },
    async execute(args, exec) {
      const raw = await a11yRequest("/touch-status", undefined, 6000);
      const v = parseResult(raw);
      if (!v.ok) return { ok: false, error: v.error || GUIDE_TEXT };
      return {
        ok: true,
        screenW: typeof v.screenW === "number" ? v.screenW : 0,
        screenH: typeof v.screenH === "number" ? v.screenH : 0,
        maxFingers: typeof v.maxFingers === "number" ? v.maxFingers : 0,
        holdTimeoutMs: typeof v.holdTimeoutMs === "number" ? v.holdTimeoutMs : 0,
        held: Array.isArray(v.held) ? v.held : []
      };
    }
  }));
}

export { apply, inject, name };
