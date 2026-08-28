package com.deepseek.harness;

import android.accessibilityservice.GestureDescription;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * 无障碍服务（v1.7.3）：给 AI 提供「看屏幕 + 操作屏幕」能力。
 * 与 node 插件 dsh-tool-accessibility 通过本地 HTTP（127.0.0.1:<a11yPort>）通信。
 * 端口 = 引擎端口 + 101（正式版 3181 / Lite 3183 / 兼容版 3185），三版本共存不冲突。
 * 注意：javac -bootclasspath android.jar 下不能用 lambda/方法引用，全部用显式匿名类。
 *
 * v1.7.3 新增「通用触摸手势引擎」（/gesture /touch /swipe /hold /touch-release /touch-status）：
 *  - 多笔时间轴：一次请求可含 down/move/up/tap/swipe/hold/wait，全部同时注入（真多指）；
 *  - 按住保持：down 后不 up 的手指会一直按住（willContinue），可跨请求延续——"左手按住摇杆，
 *    右手同时点技能"就是 down(0) 之后再来 tap(1)；
 *  - 分数坐标：所有坐标支持 fx/fy（0~1 相对屏幕比例），彻底消除截图缩放误差；
 *  - 安全网：手指按住超时（30s）自动抬起、同 finger 覆盖、release_all 一键复位、
 *    手势失败/服务断开全部复位。
 */
public class AccessibilityService extends android.accessibilityservice.AccessibilityService {

    private static final String TAG = "dsh-a11y";
    private static final String PREFS = "dsh_prefs";
    private static final String KEY_A11Y_PORT = "a11y_port";

    /** 服务是否已连接（用户已在系统设置开启无障碍）。 */
    public static volatile boolean isRunning = false;
    /** 当前活跃窗口的包名。 */
    public static volatile String activePackage = "";

    private static final int MAX_NODES = 250;
    private static final int MAX_DEPTH = 40;
    private static final int MAX_TEXT_LEN = 120;
    private static final int SOCKET_TIMEOUT_MS = 8000;

    /** 手势引擎：最大手指数（多数设备 getMaxStrokeCount() ≥ 10）。 */
    private static final int MAX_FINGERS = 8;
    /** 手指按住保持超时（毫秒），超过自动抬起，防止 AI 失控后手指一直压着屏幕。 */
    private static final long HOLD_TIMEOUT_MS = 30000;
    /** 单次手势时间轴总长上限（毫秒）。 */
    private static final long MAX_GESTURE_TOTAL_MS = 120000;

    private final Object gestureLock = new Object();
    private final boolean[] fingerDown = new boolean[MAX_FINGERS];
    private final float[] fingerX = new float[MAX_FINGERS];
    private final float[] fingerY = new float[MAX_FINGERS];
    private final long[] fingerDownAt = new long[MAX_FINGERS];
    private int screenW = 0;
    private int screenH = 0;

    private ServerSocket serverSocket;

    private static class StrokeDesc {
        Path path;
        long startTime;
        long duration;
        boolean willContinue;
        StrokeDesc(Path p, long s, long d, boolean wc) {
            path = p;
            startTime = s;
            duration = d;
            willContinue = wc;
        }
    }

    @Override
    public void onServiceConnected() {
        isRunning = true;
        Log.i(TAG, "accessibility service connected");
        // 端口：MainActivity 启动时写入 dsh_prefs（a11y_port）；读不到时按包名推导默认引擎端口 + 101
        int port = 3181;
        try {
            SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
            String pkg = getPackageName();
            int defaultEngine = pkg.contains("beta") ? 3082 : pkg.contains("compat") ? 3084 : 3080;
            port = prefs.getInt(KEY_A11Y_PORT, defaultEngine + 101);
        } catch (Throwable t) {
            Log.w(TAG, "read a11y port failed", t);
        }
        startServer(port);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event != null && event.getPackageName() != null) {
            activePackage = event.getPackageName().toString();
        }
    }

    @Override
    public void onInterrupt() {
        Log.w(TAG, "accessibility service interrupted");
        releaseAllFingers();
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        isRunning = false;
        releaseAllFingers();
        stopServer();
        return super.onUnbind(intent);
    }

    private void stopServer() {
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (Throwable ignored) {
        }
        serverSocket = null;
    }

    private void startServer(final int port) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                ServerSocket ss = null;
                try {
                    ss = new ServerSocket();
                    ss.setReuseAddress(true);
                    ss.bind(new InetSocketAddress("127.0.0.1", port));
                    serverSocket = ss;
                    Log.i(TAG, "a11y server listening on " + port);
                    while (!Thread.currentThread().isInterrupted()) {
                        try {
                            final Socket s = ss.accept();
                            handleConnection(s);
                        } catch (Throwable t) {
                            try { Thread.sleep(100); } catch (Throwable ignored) {}
                        }
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "a11y server stopped", t);
                } finally {
                    try { if (ss != null) ss.close(); } catch (Throwable ignored) {}
                }
            }
        }, "a11y-server").start();
    }

    private String parsePath(String head) {
        int sp1 = head.indexOf(' ');
        int sp2 = sp1 >= 0 ? head.indexOf(' ', sp1 + 1) : -1;
        if (sp1 >= 0 && sp2 > sp1) return head.substring(sp1 + 1, sp2);
        return "/";
    }

    private String queryParam(String path, String key) {
        int q = path.indexOf('?');
        if (q < 0) return "";
        String query = path.substring(q + 1);
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                String k = pair.substring(0, eq);
                if (k.equals(key)) {
                    try {
                        return java.net.URLDecoder.decode(pair.substring(eq + 1), "UTF-8");
                    } catch (Exception ignored) {
                        return pair.substring(eq + 1);
                    }
                }
            }
        }
        return "";
    }

    private double parseDoubleSafe(String s, double def) {
        try {
            return Double.parseDouble(s);
        } catch (Throwable t) {
            return def;
        }
    }

    private void handleConnection(final Socket s) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    s.setSoTimeout(SOCKET_TIMEOUT_MS);
                    InputStream in = s.getInputStream();
                    StringBuilder head = new StringBuilder();
                    int c;
                    while ((c = in.read()) != -1) {
                        head.append((char) c);
                        if (head.length() >= 4 && head.substring(head.length() - 4).equals("\r\n\r\n")) break;
                        if (head.length() > 8192) break;
                    }
                    String headStr = head.toString();
                    String path = parsePath(headStr);
                    // 读取 POST body（/gesture 传 JSON）
                    String body = "";
                    int ci = headStr.toLowerCase().indexOf("content-length:");
                    if (ci >= 0) {
                        int eol = headStr.indexOf("\r\n", ci);
                        if (eol > ci) {
                            String v = headStr.substring(ci + 15, eol).trim();
                            try {
                                int len = Integer.parseInt(v);
                                if (len > 0 && len < 262144) {
                                    byte[] buf = new byte[len];
                                    int off = 0;
                                    while (off < len) {
                                        int n = in.read(buf, off, len - off);
                                        if (n < 0) break;
                                        off += n;
                                    }
                                    body = new String(buf, 0, off, "UTF-8");
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                    }
                    String respBody;
                    try {
                        respBody = route(path, body);
                    } catch (Throwable t) {
                        respBody = jsonError("内部错误: " + t.getMessage());
                    }
                    BufferedWriter w = new BufferedWriter(new OutputStreamWriter(s.getOutputStream(), "UTF-8"));
                    w.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: "
                            + respBody.getBytes("UTF-8").length + "\r\nConnection: close\r\n\r\n" + respBody);
                    w.flush();
                    s.close();
                } catch (Throwable t) {
                    Log.w(TAG, "a11y connection error", t);
                    try { s.close(); } catch (Throwable ignored) {}
                }
            }
        }, "a11y-conn").start();
    }

    private String jsonError(String msg) {
        try {
            JSONObject o = new JSONObject();
            o.put("ok", false);
            o.put("error", msg == null ? "未知错误" : msg);
            return o.toString();
        } catch (Throwable t) {
            return "{\"ok\":false,\"error\":\"json error\"}";
        }
    }

    private String route(String path, String body) throws Exception {
        String base = path;
        int q = path.indexOf('?');
        if (q >= 0) base = path.substring(0, q);

        if (base.equals("/status")) return handleStatus();
        if (base.equals("/dump")) return handleDump();
        if (base.equals("/tap")) return handleTap(path);
        if (base.equals("/input")) return handleInput(path);
        if (base.equals("/back")) return handleGlobalAction(GLOBAL_ACTION_BACK);
        if (base.equals("/home")) return handleGlobalAction(GLOBAL_ACTION_HOME);
        if (base.equals("/scroll")) return handleScroll(path);
        if (base.equals("/screenshot")) return handleScreenshot(path);
        if (base.equals("/swipe")) return handleSwipe(path);
        if (base.equals("/hold")) return handleHold(path);
        if (base.equals("/touch")) return handleTouch(path);
        if (base.equals("/touch-release")) return handleTouchRelease();
        if (base.equals("/touch-status")) return handleTouchStatus();
        if (base.equals("/gesture")) return handleGesture(body);
        return jsonError("未知路由: " + base);
    }

    // ===================== 屏幕尺寸 / 坐标 =====================

    /** 当前真实屏幕尺寸（物理像素，与无障碍截图同一坐标系）。 */
    private int[] screenSize() {
        try {
            WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
            DisplayMetrics dm = new DisplayMetrics();
            wm.getDefaultDisplay().getRealMetrics(dm);
            return new int[]{dm.widthPixels, dm.heightPixels};
        } catch (Throwable t) {
            try {
                DisplayMetrics dm = getResources().getDisplayMetrics();
                return new int[]{dm.widthPixels, dm.heightPixels};
            } catch (Throwable t2) {
                return new int[]{0, 0};
            }
        }
    }

    /** JSON 坐标解析：fx/fy（0~1 分数）优先，x/y（像素）兜底。 */
    private int resolveInt(JSONObject op, String fracKey, String pxKey, int screenLen, int def) {
        try {
            if (op.has(fracKey)) {
                double f = op.optDouble(fracKey, -1);
                if (f >= 0 && f <= 1) return (int) Math.round(f * screenLen);
            }
            if (op.has(pxKey)) return op.optInt(pxKey, -1);
        } catch (Throwable ignored) {
        }
        return def;
    }

    /** GET 查询参数坐标解析：fx/fy 优先，x/y 兜底。 */
    private int coordFromParams(String path, String pxKey, String fKey, int screenLen) {
        String p = queryParam(path, pxKey);
        String f = queryParam(path, fKey);
        if (!f.isEmpty()) {
            double d = parseDoubleSafe(f, -1);
            if (d >= 0 && d <= 1) return (int) Math.round(d * screenLen);
        }
        if (!p.isEmpty()) return (int) parseDoubleSafe(p, -1);
        return -1;
    }

    // ===================== 通用触摸手势引擎 =====================

    /** 把单个 op 的坐标（x/y 或 fx/fy）解析为 JSONObject，供合成单步手势用。 */
    private void putCoord(JSONObject op, String pxX, String pxY, String fX, String fY, int sx, int sy) {
        try {
            if (sx >= 0) op.put(pxX, sx);
            if (sy >= 0) op.put(pxY, sy);
        } catch (Throwable ignored) {
        }
    }

    /** 抬起全部按住中的手指（加锁入口，供服务生命周期调用）。 */
    private void releaseAllFingers() {
        synchronized (gestureLock) {
            releaseAllFingersUnlocked();
        }
    }

    /** 抬起全部按住中的手指（调用方必须已持有 gestureLock）。 */
    private void releaseAllFingersUnlocked() {
        boolean any = false;
        for (int f = 0; f < MAX_FINGERS; f++) {
            if (fingerDown[f]) {
                any = true;
                fingerDown[f] = false;
            }
        }
        if (!any) return;
        try {
            GestureDescription.Builder gb = new GestureDescription.Builder();
            for (int f = 0; f < MAX_FINGERS; f++) {
                if (fingerX[f] >= 0 && fingerY[f] >= 0) {
                    // 注意：上面已把 fingerDown 全部置 false，这里用坐标数组判断即可
                    Path p = new Path();
                    p.moveTo(fingerX[f], fingerY[f]);
                    gb.addStroke(new GestureDescription.StrokeDescription(p, 0, 80, false));
                }
            }
            dispatchGesture(gb.build(), null, null);
        } catch (Throwable t) {
            Log.w(TAG, "release all failed", t);
        }
    }

    /**
     * 执行一组手势笔（时间轴编排，全部同时注入）。op 结构：
     *   { "kind": "down"|"move"|"up"|"tap"|"swipe"|"hold"|"wait",
     *     "finger": 0..7,
     *     "x"/"y" 或 "fx"/"fy"（0~1 分数）,
     *     "x2"/"y2" 或 "fx2"/"fy2"（swipe 终点）,
     *     "durationMs": 笔时长（wait=等待毫秒；tap 默认 60；swipe 默认 300；
     *                    hold 默认 500；move 默认 100；up 默认 100） }
     * down 后未 up 的手指保持按住，可跨请求延续；下次请求自动带上延续笔。
     */
    private String executeGesture(JSONArray ops) {
        synchronized (gestureLock) {
            try {
                int[] size = screenSize();
                if (size[0] <= 0 || size[1] <= 0) return jsonError("无法获取屏幕尺寸");
                // 屏幕尺寸变化（旋转/分辨率切换）→ 旧手指坐标失效，全部复位
                if (screenW != 0 && (size[0] != screenW || size[1] != screenH)) {
                    releaseAllFingersUnlocked();
                }
                screenW = size[0];
                screenH = size[1];

                int n = ops == null ? 0 : ops.length();
                if (n == 0) return jsonError("手势列表为空");

                // ===== 第一遍：解析 + 校验 + 时间轴（此时不修改任何真实状态）=====
                // 虚拟手指状态：模拟本请求内 down/move/up 的演化，用于校验（真实状态不动）
                boolean[] virtualDown = new boolean[MAX_FINGERS];
                System.arraycopy(fingerDown, 0, virtualDown, 0, MAX_FINGERS);
                long now = System.currentTimeMillis();
                boolean[] expired = new boolean[MAX_FINGERS];
                for (int f = 0; f < MAX_FINGERS; f++) {
                    if (fingerDown[f] && now - fingerDownAt[f] > HOLD_TIMEOUT_MS) expired[f] = true;
                }
                String[] kinds = new String[n];
                int[] fings = new int[n];
                int[] xs = new int[n];
                int[] ys = new int[n];
                int[] x2s = new int[n];
                int[] y2s = new int[n];
                long[] starts = new long[n];
                long[] ends = new long[n];
                // 真实按住且被本请求接管的手指 → 其首个 op 开始时刻（补「保持到 op 开始」的延续笔）
                long[] firstOpStart = new long[MAX_FINGERS];
                for (int f = 0; f < MAX_FINGERS; f++) firstOpStart[f] = -1;
                long t = 0;
                for (int i = 0; i < n; i++) {
                    JSONObject op = ops.getJSONObject(i);
                    String kind = op.optString("kind", "");
                    int finger = op.has("finger") ? op.optInt("finger", -1) : -1;
                    int x = resolveInt(op, "fx", "x", screenW, -1);
                    int y = resolveInt(op, "fy", "y", screenH, -1);
                    int x2 = resolveInt(op, "fx2", "x2", screenW, -1);
                    int y2 = resolveInt(op, "fy2", "y2", screenH, -1);
                    long dur = op.optLong("durationMs", -1);
                    long seg;
                    boolean held = finger >= 0 && finger < MAX_FINGERS && virtualDown[finger];
                    if ("wait".equals(kind)) {
                        seg = Math.max(0, op.optLong("ms", 0));
                    } else if ("down".equals(kind)) {
                        seg = 40;
                        if (finger < 0 || finger >= MAX_FINGERS) return jsonError("down 的 finger 越界（0~7）");
                        if (x < 0 || y < 0) return jsonError("down 需要 x/y 或 fx/fy");
                        if (expired[finger]) return jsonError("down 的手指 " + finger + " 已超时自动抬起，请重新 down");
                        if (held && firstOpStart[finger] < 0) firstOpStart[finger] = t;
                        virtualDown[finger] = true;
                    } else if ("move".equals(kind)) {
                        seg = dur >= 0 ? dur : 100;
                        if (finger < 0 || finger >= MAX_FINGERS) return jsonError("move 的 finger 越界（0~7）");
                        if (!held) return jsonError("move 的手指 " + finger + " 未按住（先 down）");
                        if (expired[finger]) return jsonError("move 的手指 " + finger + " 已超时自动抬起，请重新 down");
                        if (x < 0 || y < 0) return jsonError("move 需要 x/y 或 fx/fy");
                        if (firstOpStart[finger] < 0) firstOpStart[finger] = t;
                    } else if ("up".equals(kind)) {
                        seg = dur >= 0 ? dur : 100;
                        if (finger < 0 || finger >= MAX_FINGERS) return jsonError("up 的 finger 越界（0~7）");
                        if (!held) return jsonError("up 的手指 " + finger + " 未按住（先 down）");
                        if (expired[finger]) return jsonError("up 的手指 " + finger + " 已超时自动抬起，请重新 down");
                        if (firstOpStart[finger] < 0) firstOpStart[finger] = t;
                        virtualDown[finger] = false;
                    } else if ("tap".equals(kind)) {
                        seg = dur >= 0 ? dur : 60;
                        if (x < 0 || y < 0) return jsonError("tap 需要 x/y 或 fx/fy");
                        if (held) {
                            if (expired[finger]) return jsonError("tap 的手指 " + finger + " 已超时自动抬起，请重新 down");
                            if (firstOpStart[finger] < 0) firstOpStart[finger] = t;
                            virtualDown[finger] = false;
                        }
                    } else if ("swipe".equals(kind)) {
                        seg = dur >= 0 ? dur : 300;
                        if (x < 0 || y < 0 || x2 < 0 || y2 < 0) {
                            return jsonError("swipe 需要起点(x/y 或 fx/fy)和终点(x2/y2 或 fx2/fy2)");
                        }
                        if (held) {
                            if (expired[finger]) return jsonError("swipe 的手指 " + finger + " 已超时自动抬起，请重新 down");
                            if (firstOpStart[finger] < 0) firstOpStart[finger] = t;
                            virtualDown[finger] = false;
                        }
                    } else if ("hold".equals(kind)) {
                        seg = dur >= 0 ? dur : 500;
                        if (x < 0 || y < 0) return jsonError("hold 需要 x/y 或 fx/fy");
                        if (held) {
                            if (expired[finger]) return jsonError("hold 的手指 " + finger + " 已超时自动抬起，请重新 down");
                            if (firstOpStart[finger] < 0) firstOpStart[finger] = t;
                            virtualDown[finger] = false;
                        }
                    } else {
                        return jsonError("未知手势 kind: " + kind);
                    }
                    kinds[i] = kind;
                    fings[i] = finger;
                    xs[i] = x;
                    ys[i] = y;
                    x2s[i] = x2;
                    y2s[i] = y2;
                    starts[i] = t;
                    ends[i] = t + seg;
                    t += seg;
                }
                long totalT = t;
                if (totalT <= 0) return jsonError("手势时间轴为空");
                if (totalT > MAX_GESTURE_TOTAL_MS) {
                    return jsonError("手势总时长超限（>" + (MAX_GESTURE_TOTAL_MS / 1000) + "s）；长按请用 down 保持，不要用长 wait");
                }

                // ===== 第二遍准备：生成笔（所有校验已通过，不会中途返回）=====
                GestureDescription.Builder gb = new GestureDescription.Builder();
                List<StrokeDesc> strokes = new ArrayList<StrokeDesc>();

                // 保持中的手指：
                //  - 未接管 → 全程延续笔（保持到手势结束）
                //  - 被接管 → 保持到其首个 op 开始（op 笔接同一指针，避免等待期被系统误抬起）
                //  - 已过期 → 原地抬起笔
                for (int f = 0; f < MAX_FINGERS; f++) {
                    if (!fingerDown[f]) continue;
                    if (expired[f]) {
                        Path p = new Path();
                        p.moveTo(fingerX[f], fingerY[f]);
                        strokes.add(new StrokeDesc(p, 0, 80, false));
                    } else if (firstOpStart[f] < 0) {
                        Path p = new Path();
                        p.moveTo(fingerX[f], fingerY[f]);
                        strokes.add(new StrokeDesc(p, 0, totalT, true));
                    } else if (firstOpStart[f] > 0) {
                        Path p = new Path();
                        p.moveTo(fingerX[f], fingerY[f]);
                        strokes.add(new StrokeDesc(p, 0, firstOpStart[f], true));
                    }
                    // firstOpStart==0：op 笔从 0 开始，无需保持笔
                }

                // 第二遍：逐 op 生成笔（cur 为手指当前坐标；所有校验已通过，不会中途返回）
                float[] curX = new float[MAX_FINGERS];
                float[] curY = new float[MAX_FINGERS];
                System.arraycopy(fingerX, 0, curX, 0, MAX_FINGERS);
                System.arraycopy(fingerY, 0, curY, 0, MAX_FINGERS);

                for (int i = 0; i < n; i++) {
                    String kind = kinds[i];
                    int finger = fings[i];
                    int x = xs[i];
                    int y = ys[i];
                    int x2 = x2s[i];
                    int y2 = y2s[i];
                    long start = starts[i];
                    long end = ends[i];
                    long seg = end - start;
                    boolean held = finger >= 0 && finger < MAX_FINGERS && fingerDown[finger];

                    if ("wait".equals(kind)) {
                        // 无笔，仅占时间轴
                    } else if ("down".equals(kind)) {
                        if (held) {
                            // 已按住 → 视为滑到新位置并继续保持
                            Path path = new Path();
                            path.moveTo(curX[finger], curY[finger]);
                            path.lineTo(x, y);
                            strokes.add(new StrokeDesc(path, start, seg, true));
                        } else {
                            Path path = new Path();
                            path.moveTo(x, y);
                            strokes.add(new StrokeDesc(path, start, totalT - start, true));
                            fingerDownAt[finger] = now;
                        }
                        fingerDown[finger] = true;
                        fingerX[finger] = x;
                        fingerY[finger] = y;
                        curX[finger] = x;
                        curY[finger] = y;
                    } else if ("move".equals(kind)) {
                        Path path = new Path();
                        path.moveTo(curX[finger], curY[finger]);
                        path.lineTo(x, y);
                        strokes.add(new StrokeDesc(path, start, seg, true));
                        fingerX[finger] = x;
                        fingerY[finger] = y;
                        curX[finger] = x;
                        curY[finger] = y;
                    } else if ("up".equals(kind)) {
                        Path path = new Path();
                        path.moveTo(curX[finger], curY[finger]);
                        if (x >= 0 && y >= 0) {
                            path.lineTo(x, y); // 先滑到目标位置再抬起
                            fingerX[finger] = x;
                            fingerY[finger] = y;
                        }
                        strokes.add(new StrokeDesc(path, start, seg, false));
                        fingerDown[finger] = false;
                    } else if ("tap".equals(kind)) {
                        if (held) {
                            // 已按住的手指 tap → 滑到目标并抬起
                            Path path = new Path();
                            path.moveTo(curX[finger], curY[finger]);
                            path.lineTo(x, y);
                            strokes.add(new StrokeDesc(path, start, seg, false));
                            fingerDown[finger] = false;
                            fingerX[finger] = x;
                            fingerY[finger] = y;
                        } else {
                            // 新手指点按（按下即抬起）
                            Path path = new Path();
                            path.moveTo(x, y);
                            strokes.add(new StrokeDesc(path, start, seg, false));
                        }
                    } else if ("swipe".equals(kind)) {
                        if (held) {
                            // 已按住的手指 swipe → 从当前位置滑到终点并抬起
                            Path path = new Path();
                            path.moveTo(curX[finger], curY[finger]);
                            path.lineTo(x2, y2);
                            strokes.add(new StrokeDesc(path, start, seg, false));
                            fingerDown[finger] = false;
                            fingerX[finger] = x2;
                            fingerY[finger] = y2;
                        } else {
                            Path path = new Path();
                            path.moveTo(x, y);
                            path.lineTo(x2, y2);
                            strokes.add(new StrokeDesc(path, start, seg, false));
                        }
                    } else if ("hold".equals(kind)) {
                        if (held) {
                            // 已按住的手指 hold → 滑到目标位置按住到结束再抬起
                            Path path = new Path();
                            path.moveTo(curX[finger], curY[finger]);
                            path.lineTo(x, y);
                            strokes.add(new StrokeDesc(path, start, seg, false));
                            fingerDown[finger] = false;
                            fingerX[finger] = x;
                            fingerY[finger] = y;
                        } else {
                            // 新手指：按下 → 保持 duration → 抬起
                            Path path = new Path();
                            path.moveTo(x, y);
                            strokes.add(new StrokeDesc(path, start, seg, false));
                        }
                    }
                }

                if (strokes.isEmpty()) return jsonError("手势没有可执行的笔");
                // 注：getMaxStrokeCount() 是 @hide API，public android.jar 没有；固定 8 指远低于设备上限（通常 ≥10）
                if (strokes.size() > MAX_FINGERS + 2) {
                    releaseAllFingersUnlocked();
                    return jsonError("笔画数超限（单次最多 " + (MAX_FINGERS + 2) + " 笔）");
                }
                for (StrokeDesc sd : strokes) {
                    gb.addStroke(new GestureDescription.StrokeDescription(sd.path, sd.startTime, sd.duration, sd.willContinue));
                }
                final GestureDescription gesture = gb.build();

                final CountDownLatch latch = new CountDownLatch(1);
                final boolean[] success = {false};
                dispatchGesture(gesture, new GestureResultCallback() {
                    @Override
                    public void onCompleted(GestureDescription g) {
                        success[0] = true;
                        latch.countDown();
                    }

                    @Override
                    public void onCancelled(GestureDescription g) {
                        latch.countDown();
                    }
                }, null);
                boolean done = latch.await(totalT + 5000, TimeUnit.MILLISECONDS);
                if (!done) {
                    releaseAllFingersUnlocked();
                    return jsonError("手势执行超时，已复位所有手指");
                }
                if (!success[0]) {
                    releaseAllFingersUnlocked();
                    return jsonError("手势被系统取消（可能被新手势或手动触摸打断），已复位所有手指");
                }

                // 返回当前按住的手指
                JSONArray held = new JSONArray();
                for (int f = 0; f < MAX_FINGERS; f++) {
                    if (fingerDown[f]) {
                        JSONObject h = new JSONObject();
                        h.put("finger", f);
                        h.put("x", (int) fingerX[f]);
                        h.put("y", (int) fingerY[f]);
                        h.put("fx", fingerX[f] / screenW);
                        h.put("fy", fingerY[f] / screenH);
                        held.put(h);
                    }
                }
                JSONObject o = new JSONObject();
                o.put("ok", true);
                o.put("durationMs", totalT);
                o.put("held", held);
                return o.toString();
            } catch (Throwable t) {
                releaseAllFingersUnlocked();
                return jsonError("gesture error: " + t.getMessage());
            }
        }
    }

    /** /gesture：POST JSON 数组（或 {"strokes": [...]}），执行一组手势笔。 */
    private String handleGesture(String body) {
        try {
            if (body == null || body.trim().isEmpty()) return jsonError("gesture 需要 POST JSON body");
            String s = body.trim();
            JSONArray ops;
            if (s.startsWith("[")) {
                ops = new JSONArray(s);
            } else {
                JSONObject o = new JSONObject(s);
                ops = o.optJSONArray("strokes");
                if (ops == null) return jsonError("gesture body 需要是数组或 {\"strokes\":[...]}");
            }
            return executeGesture(ops);
        } catch (Throwable t) {
            return jsonError("gesture 参数解析失败: " + t.getMessage());
        }
    }

    /** /touch：action=down|move|up|release&finger=N&x/y 或 fx/fy（状态式单指操作）。 */
    private String handleTouch(String path) {
        try {
            String action = queryParam(path, "action");
            String fStr = queryParam(path, "finger");
            if (action.isEmpty()) return jsonError("touch 需要 action=down|move|up|release");
            if (fStr.isEmpty()) return jsonError("touch 需要 finger=0~7");
            int finger = (int) parseDoubleSafe(fStr, -1);
            if (finger < 0 || finger >= MAX_FINGERS) return jsonError("finger 越界（0~7）");
            JSONObject op = new JSONObject();
            op.put("kind", "release".equals(action) ? "up" : action);
            op.put("finger", finger);
            if (!"up".equals(action) && !"release".equals(action)) {
                int x = coordFromParams(path, "x", "fx", screenW == 0 ? screenSize()[0] : screenW);
                int y = coordFromParams(path, "y", "fy", screenH == 0 ? screenSize()[1] : screenH);
                if (x < 0 || y < 0) return jsonError("touch 需要 x/y 或 fx/fy");
                op.put("x", x);
                op.put("y", y);
            }
            JSONArray ops = new JSONArray();
            ops.put(op);
            return executeGesture(ops);
        } catch (Throwable t) {
            return jsonError("touch error: " + t.getMessage());
        }
    }

    /** /touch-release：抬起全部按住的手指。 */
    private String handleTouchRelease() {
        synchronized (gestureLock) {
            try {
                releaseAllFingersUnlocked();
                JSONObject o = new JSONObject();
                o.put("ok", true);
                o.put("held", new JSONArray());
                return o.toString();
            } catch (Throwable t) {
                return jsonError("touch-release error: " + t.getMessage());
            }
        }
    }

    /** /touch-status：查询当前按住的手指。 */
    private String handleTouchStatus() {
        try {
            int[] size = screenSize();
            JSONObject o = new JSONObject();
            o.put("ok", true);
            o.put("screenW", size[0]);
            o.put("screenH", size[1]);
            o.put("maxFingers", MAX_FINGERS);
            o.put("holdTimeoutMs", HOLD_TIMEOUT_MS);
            JSONArray held = new JSONArray();
            long now = System.currentTimeMillis();
            for (int f = 0; f < MAX_FINGERS; f++) {
                if (fingerDown[f]) {
                    JSONObject h = new JSONObject();
                    h.put("finger", f);
                    h.put("x", (int) fingerX[f]);
                    h.put("y", (int) fingerY[f]);
                    h.put("fx", screenW > 0 ? fingerX[f] / screenW : 0);
                    h.put("fy", screenH > 0 ? fingerY[f] / screenH : 0);
                    h.put("elapsedMs", now - fingerDownAt[f]);
                    held.put(h);
                }
            }
            o.put("held", held);
            return o.toString();
        } catch (Throwable t) {
            return jsonError("touch-status error: " + t.getMessage());
        }
    }

    /** /swipe：x1/y1→x2/y2（或 fx1/fy1→fx2/fy2），duration 毫秒。 */
    private String handleSwipe(String path) {
        try {
            int[] size = screenSize();
            if (screenW == 0) {
                screenW = size[0];
                screenH = size[1];
            }
            int x1 = coordFromParams(path, "x1", "fx1", screenW);
            int y1 = coordFromParams(path, "y1", "fy1", screenH);
            int x2 = coordFromParams(path, "x2", "fx2", screenW);
            int y2 = coordFromParams(path, "y2", "fy2", screenH);
            if (x1 < 0 || y1 < 0 || x2 < 0 || y2 < 0) {
                return jsonError("swipe 需要起点(x1/y1 或 fx1/fy1)和终点(x2/y2 或 fx2/fy2)");
            }
            long dur = (long) parseDoubleSafe(queryParam(path, "duration"), 300);
            JSONObject op = new JSONObject();
            op.put("kind", "swipe");
            op.put("x", x1);
            op.put("y", y1);
            op.put("x2", x2);
            op.put("y2", y2);
            op.put("durationMs", dur);
            if (!queryParam(path, "finger").isEmpty()) {
                op.put("finger", (int) parseDoubleSafe(queryParam(path, "finger"), 0));
            }
            JSONArray ops = new JSONArray();
            ops.put(op);
            return executeGesture(ops);
        } catch (Throwable t) {
            return jsonError("swipe error: " + t.getMessage());
        }
    }

    /** /hold：x/y（或 fx/fy）按住 duration 毫秒后自动抬起。 */
    private String handleHold(String path) {
        try {
            int[] size = screenSize();
            if (screenW == 0) {
                screenW = size[0];
                screenH = size[1];
            }
            int x = coordFromParams(path, "x", "fx", screenW);
            int y = coordFromParams(path, "y", "fy", screenH);
            if (x < 0 || y < 0) return jsonError("hold 需要 x/y 或 fx/fy");
            long dur = (long) parseDoubleSafe(queryParam(path, "duration"), 500);
            JSONObject op = new JSONObject();
            op.put("kind", "hold");
            op.put("x", x);
            op.put("y", y);
            op.put("durationMs", dur);
            if (!queryParam(path, "finger").isEmpty()) {
                op.put("finger", (int) parseDoubleSafe(queryParam(path, "finger"), 0));
            }
            JSONArray ops = new JSONArray();
            ops.put(op);
            return executeGesture(ops);
        } catch (Throwable t) {
            return jsonError("hold error: " + t.getMessage());
        }
    }

    // ===================== 原有路由（保留 + 增强） =====================

    private String handleStatus() {
        try {
            JSONObject o = new JSONObject();
            o.put("ok", true);
            o.put("running", isRunning);
            o.put("package", activePackage);
            AccessibilityNodeInfo root = getRootInActiveWindow();
            o.put("nodeCount", root == null ? 0 : countNodes(root));
            o.put("apiLevel", Build.VERSION.SDK_INT);
            o.put("canScreenshot", Build.VERSION.SDK_INT >= 30);
            return o.toString();
        } catch (Throwable t) {
            return jsonError("status error: " + t.getMessage());
        }
    }

    private int countNodes(AccessibilityNodeInfo root) {
        final int[] count = {0};
        walk(root, new NodeVisitor() {
            @Override
            public void visit(AccessibilityNodeInfo node, int depth) {
                count[0]++;
            }
        }, 0);
        return count[0];
    }

    private interface NodeVisitor {
        void visit(AccessibilityNodeInfo node, int depth);
    }

    private void walk(AccessibilityNodeInfo node, NodeVisitor visitor, int depth) {
        if (node == null || depth > MAX_DEPTH) return;
        visitor.visit(node, depth);
        for (int i = 0; i < node.getChildCount(); i++) {
            try {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) {
                    walk(child, visitor, depth + 1);
                    child.recycle();
                }
            } catch (Throwable ignored) {
            }
        }
    }

    /** 读取屏幕节点树，转 JSON（过滤：只保留有文本/描述/可点击/可输入/可滚动节点）。 */
    private String handleDump() {
        try {
            final AccessibilityNodeInfo root = getRootInActiveWindow();
            JSONObject o = new JSONObject();
            o.put("ok", true);
            if (root == null) {
                o.put("package", activePackage);
                o.put("count", 0);
                o.put("truncated", false);
                o.put("nodes", new JSONArray());
                o.put("note", "当前没有可读取的活动窗口（可能处于锁屏或安全页面）");
                return o.toString();
            }
            final JSONArray nodes = new JSONArray();
            final int[] emitted = {0};
            final boolean[] truncated = {false};
            walk(root, new NodeVisitor() {
                @Override
                public void visit(AccessibilityNodeInfo node, int depth) {
                    if (emitted[0] >= MAX_NODES) {
                        truncated[0] = true;
                        return;
                    }
                    if (node == null) return;
                    try {
                        CharSequence textCs = node.getText();
                        CharSequence descCs = node.getContentDescription();
                        String text = textCs == null ? "" : textCs.toString().trim();
                        String desc = descCs == null ? "" : descCs.toString().trim();
                        boolean clickable = node.isClickable();
                        boolean input = node.isEditable() || "android.widget.EditText".equals(node.getClassName() != null ? node.getClassName().toString() : "");
                        boolean scrollable = node.isScrollable();
                        if (text.isEmpty() && desc.isEmpty() && !clickable && !input && !scrollable) return;
                        Rect bounds = new Rect();
                        node.getBoundsInScreen(bounds);
                        if (bounds.width() <= 0 || bounds.height() <= 0) return;
                        if (text.length() > MAX_TEXT_LEN) text = text.substring(0, MAX_TEXT_LEN) + "…";
                        if (desc.length() > MAX_TEXT_LEN) desc = desc.substring(0, MAX_TEXT_LEN) + "…";
                        JSONObject n = new JSONObject();
                        n.put("text", text);
                        n.put("desc", desc);
                        n.put("cls", node.getClassName() == null ? "" : node.getClassName().toString());
                        n.put("x", bounds.left);
                        n.put("y", bounds.top);
                        n.put("w", bounds.width());
                        n.put("h", bounds.height());
                        n.put("clickable", clickable);
                        n.put("input", input);
                        n.put("checked", node.isChecked());
                        n.put("selected", node.isSelected());
                        n.put("scrollable", scrollable);
                        n.put("depth", depth);
                        nodes.put(n);
                        emitted[0]++;
                    } catch (Throwable ignored) {
                    }
                }
            }, 0);
            o.put("package", root.getPackageName() == null ? "" : root.getPackageName().toString());
            o.put("count", emitted[0]);
            o.put("truncated", truncated[0]);
            o.put("nodes", nodes);
            // v1.7.3：无节点界面（Unity 游戏/自绘 UI）引导 AI 改用截图 + 分数坐标
            if (emitted[0] == 0) {
                o.put("hint", "当前界面没有可读控件（常见于 Unity/游戏/自绘界面）。请改用 android_see 截屏看图，" +
                        "并优先用分数坐标（fx/fy，0~1）点击/滑动——截图会被模型查看器缩放，绝对像素坐标会点偏。");
            }
            return o.toString();
        } catch (Throwable t) {
            return jsonError("dump error: " + t.getMessage());
        }
    }

    private AccessibilityNodeInfo findNodeByText(String needle) {
        if (needle == null || needle.isEmpty()) return null;
        final AccessibilityNodeInfo[] found = {null};
        final String target = needle.trim().toLowerCase();
        final AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return null;
        walk(root, new NodeVisitor() {
            @Override
            public void visit(AccessibilityNodeInfo node, int depth) {
                if (found[0] != null) return;
                if (node == null) return;
                CharSequence textCs = node.getText();
                CharSequence descCs = node.getContentDescription();
                String text = textCs == null ? "" : textCs.toString().toLowerCase();
                String desc = descCs == null ? "" : descCs.toString().toLowerCase();
                if ((!text.isEmpty() && text.contains(target)) || (!desc.isEmpty() && desc.contains(target))) {
                    found[0] = node;
                }
            }
        }, 0);
        return found[0];
    }

    private AccessibilityNodeInfo findNodeByPoint(final int x, final int y) {
        final AccessibilityNodeInfo[] found = {null};
        final AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return null;
        walk(root, new NodeVisitor() {
            @Override
            public void visit(AccessibilityNodeInfo node, int depth) {
                if (found[0] != null) return;
                if (node == null) return;
                Rect bounds = new Rect();
                node.getBoundsInScreen(bounds);
                if (bounds.contains(x, y)) found[0] = node;
            }
        }, 0);
        return found[0];
    }

    /** /tap：text/desc 按文本查找点击；x/y 或 fx/fy 按坐标点击（优先节点 ACTION_CLICK，失败手势）。 */
    private String handleTap(String path) {
        try {
            String text = queryParam(path, "text");
            String desc = queryParam(path, "desc");
            String xStr = queryParam(path, "x");
            String yStr = queryParam(path, "y");
            int[] size = screenSize();
            if (screenW == 0) {
                screenW = size[0];
                screenH = size[1];
            }
            int tapX = coordFromParams(path, "x", "fx", screenW);
            int tapY = coordFromParams(path, "y", "fy", screenH);
            JSONObject o = new JSONObject();
            o.put("ok", true);

            AccessibilityNodeInfo target = null;
            String method = "";
            if (!text.isEmpty() || !desc.isEmpty()) {
                target = findNodeByText(!text.isEmpty() ? text : desc);
                method = "node-text";
            } else if (tapX >= 0 && tapY >= 0) {
                target = findNodeByPoint(tapX, tapY);
                method = "node-coord";
            } else {
                return jsonError("tap 需要 text/desc 或 x/y（或 fx/fy）参数");
            }

            if (target != null && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                o.put("found", true);
                o.put("method", method);
                target.recycle();
                return o.toString();
            }
            if (tapX >= 0 && tapY >= 0) {
                boolean gestureOk = gestureTap(tapX, tapY);
                o.put("found", gestureOk);
                o.put("method", gestureOk ? "gesture" : "none");
                return o.toString();
            }
            o.put("found", false);
            o.put("error", "未找到可点击的目标元素（text/desc 未匹配，或元素不可点击）");
            return o.toString();
        } catch (Throwable t) {
            return jsonError("tap error: " + t.getMessage());
        }
    }

    private boolean gestureTap(final int x, final int y) {
        if (Build.VERSION.SDK_INT < 24) return false;
        try {
            Path path = new Path();
            path.moveTo(x, y);
            GestureDescription.StrokeDescription stroke =
                    new GestureDescription.StrokeDescription(path, 0, 60);
            GestureDescription gesture = new GestureDescription.Builder()
                    .addStroke(stroke)
                    .build();
            return dispatchGesture(gesture, null, null);
        } catch (Throwable t) {
            Log.w(TAG, "gesture tap failed", t);
            return false;
        }
    }

    /** /input：text= 输入到当前聚焦输入框。mode=set（默认，ACTION_SET_TEXT）或 mode=paste（剪贴板粘贴，
     * 适合 WebView/contenteditable 输入框——setText 只改无障碍节点、不触发前端 input 事件，界面不刷新）。 */
    private String handleInput(String path) {
        try {
            String text = queryParam(path, "text");
            String mode = queryParam(path, "mode");
            JSONObject o = new JSONObject();
            o.put("ok", true);
            AccessibilityNodeInfo target = null;
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) return jsonError("当前没有活动窗口");
            target = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            if (target == null) {
                final AccessibilityNodeInfo[] editable = {null};
                walk(root, new NodeVisitor() {
                    @Override
                    public void visit(AccessibilityNodeInfo node, int depth) {
                        if (editable[0] != null) return;
                        if (node != null && (node.isEditable() || node.isFocusable())) editable[0] = node;
                    }
                }, 0);
                target = editable[0];
            }
            if (target == null) return jsonError("未找到可输入的文本框");
            boolean ok;
            if (!target.isFocused()) {
                target.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
            }
            if ("paste".equals(mode)) {
                // WebView/contenteditable：setText 不触发前端 input 事件 → 用剪贴板粘贴
                android.content.ClipboardManager cm =
                        (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                cm.setPrimaryClip(android.content.ClipData.newPlainText("dsh-input", text));
                ok = target.performAction(AccessibilityNodeInfo.ACTION_PASTE);
                o.put("method", "paste");
            } else {
                android.os.Bundle args = new android.os.Bundle();
                args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
                ok = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
                o.put("method", "set");
            }
            o.put("focused", target.isFocused());
            o.put("error", ok ? "" : ("输入失败（" + ("paste".equals(mode) ? "粘贴未执行" : "setText 未执行") + "）"));
            return o.toString();
        } catch (Throwable t) {
            return jsonError("input error: " + t.getMessage());
        }
    }

    /** /scroll：direction=up/down/left/right（优先节点滚动）。 */
    private String handleScroll(String path) {
        try {
            String direction = queryParam(path, "direction");
            JSONObject o = new JSONObject();
            o.put("ok", true);
            o.put("direction", direction);
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) return jsonError("当前没有活动窗口");
            final int[] action = {AccessibilityNodeInfo.ACTION_SCROLL_FORWARD};
            if ("up".equals(direction)) action[0] = AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD;
            else if ("down".equals(direction)) action[0] = AccessibilityNodeInfo.ACTION_SCROLL_FORWARD;
            else if ("left".equals(direction)) action[0] = AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD;
            else if ("right".equals(direction)) action[0] = AccessibilityNodeInfo.ACTION_SCROLL_FORWARD;
            else return jsonError("direction 需要 up/down/left/right");
            final AccessibilityNodeInfo[] scroller = {null};
            walk(root, new NodeVisitor() {
                @Override
                public void visit(AccessibilityNodeInfo node, int depth) {
                    if (scroller[0] != null) return;
                    if (node != null && node.isScrollable()) scroller[0] = node;
                }
            }, 0);
            if (scroller[0] != null && scroller[0].performAction(action[0])) {
                o.put("method", "node");
                return o.toString();
            }
            o.put("method", "none");
            o.put("error", "未找到可滚动的区域");
            return o.toString();
        } catch (Throwable t) {
            return jsonError("scroll error: " + t.getMessage());
        }
    }

    private String handleGlobalAction(int action) {
        try {
            JSONObject o = new JSONObject();
            o.put("ok", performGlobalAction(action));
            return o.toString();
        } catch (Throwable t) {
            return jsonError("global action error: " + t.getMessage());
        }
    }

    /** /screenshot：Android 11+ 无障碍截图，存 PNG 到 filesDir/screenshots/，返回路径。
     *  grid=4/8 叠加网格线（帮模型按行列定位）；返回屏幕/图片尺寸与换算系数。 */
    private String handleScreenshot(String path) {
        if (Build.VERSION.SDK_INT < 30) {
            return jsonError("截图需要 Android 11+（当前 API " + Build.VERSION.SDK_INT + "）；低版本请用 /dump 读屏幕文本");
        }
        final String gridStr = queryParam(path, "grid");
        final int grid = "8".equals(gridStr) ? 8 : ("4".equals(gridStr) || "true".equals(gridStr) ? 4 : 0);
        final CountDownLatch latch = new CountDownLatch(1);
        final String[] result = {null};
        Executor executor = new Executor() {
            @Override
            public void execute(Runnable r) {
                new Handler(Looper.getMainLooper()).post(r);
            }
        };
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, executor, new TakeScreenshotCallback() {
                @Override
                public void onSuccess(ScreenshotResult screenshotResult) {
                    try {
                        // API 30-33：ScreenshotResult 提供 HardwareBuffer（+ColorSpace），包成 Bitmap 再转软件位图
                        android.hardware.HardwareBuffer hb = screenshotResult.getHardwareBuffer();
                        Bitmap bmp = Bitmap.wrapHardwareBuffer(hb, screenshotResult.getColorSpace());
                        if (bmp == null) {
                            result[0] = jsonError("截图位图为空");
                        } else {
                            Bitmap soft = bmp.copy(Bitmap.Config.ARGB_8888, true); // isMutable=true：后面要叠网格（new Canvas 需要可变位图）
                            bmp.recycle();
                            if (hb != null) hb.close();
                            // 网格叠加（半透明细线，帮模型按行列定位）
                            if (grid > 0 && soft.getWidth() > 0) {
                                Canvas cv = new Canvas(soft);
                                Paint paint = new Paint();
                                paint.setColor(0x66FFFFFF);
                                paint.setStrokeWidth(2f);
                                for (int i = 1; i < grid; i++) {
                                    float gx = soft.getWidth() * i / (float) grid;
                                    cv.drawLine(gx, 0, gx, soft.getHeight(), paint);
                                    float gy = soft.getHeight() * i / (float) grid;
                                    cv.drawLine(0, gy, soft.getWidth(), gy, paint);
                                }
                            }
                            File dir = new File(getFilesDir(), "screenshots");
                            if (!dir.exists()) dir.mkdirs();
                            File out = new File(dir, "screen-" + System.currentTimeMillis() + ".png");
                            FileOutputStream fos = new FileOutputStream(out);
                            soft.compress(Bitmap.CompressFormat.PNG, 100, fos);
                            fos.flush();
                            fos.close();
                            int[] size = screenSize();
                            JSONObject o = new JSONObject();
                            o.put("ok", true);
                            o.put("path", out.getAbsolutePath());
                            o.put("width", soft.getWidth());
                            o.put("height", soft.getHeight());
                            // 坐标系对齐：屏幕物理尺寸 / 截图尺寸 / 换算系数
                            o.put("screenW", size[0]);
                            o.put("screenH", size[1]);
                            o.put("imageW", soft.getWidth());
                            o.put("imageH", soft.getHeight());
                            o.put("scaleX", soft.getWidth() > 0 ? (double) size[0] / soft.getWidth() : 1.0);
                            o.put("scaleY", soft.getHeight() > 0 ? (double) size[1] / soft.getHeight() : 1.0);
                            o.put("grid", grid);
                            o.put("bytes", out.length());
                            result[0] = o.toString();
                        }
                    } catch (Throwable t) {
                        result[0] = jsonError("截图保存失败: " + t.getMessage());
                    } finally {
                        latch.countDown();
                    }
                }

                @Override
                public void onFailure(int errorCode) {
                    result[0] = jsonError("截图失败（错误码 " + errorCode + "，安全页面/未授权截图时常见）");
                    latch.countDown();
                }
            });
        } catch (Throwable t) {
            return jsonError("截图调用失败: " + t.getMessage());
        }
        try {
            if (!latch.await(6000, TimeUnit.MILLISECONDS)) {
                return jsonError("截图超时");
            }
        } catch (InterruptedException e) {
            return jsonError("截图被中断");
        }
        return result[0] == null ? jsonError("截图无结果") : result[0];
    }
}
