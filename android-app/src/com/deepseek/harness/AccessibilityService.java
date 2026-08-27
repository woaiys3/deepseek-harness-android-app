package com.deepseek.harness;

import android.accessibilityservice.GestureDescription;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Display;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * 无障碍服务（v1.7）：给 AI 提供「看屏幕 + 操作屏幕」能力。
 * 与 node 插件 dsh-tool-accessibility 通过本地 HTTP（127.0.0.1:<a11yPort>）通信。
 * 端口 = 引擎端口 + 101（正式版 3181 / Lite 3183 / 兼容版 3185），三版本共存不冲突。
 * 注意：javac -bootclasspath android.jar 下不能用 lambda/方法引用，全部用显式匿名类。
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

    private ServerSocket serverSocket;

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
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        isRunning = false;
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
                    String path = parsePath(head.toString());
                    String respBody;
                    try {
                        respBody = route(path);
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

    private String route(String path) throws Exception {
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
        if (base.equals("/screenshot")) return handleScreenshot();
        return jsonError("未知路由: " + base);
    }

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

    /** /tap：text/desc 按文本查找点击；x/y 按坐标点击（优先节点 ACTION_CLICK，失败手势）。 */
    private String handleTap(String path) {
        try {
            String text = queryParam(path, "text");
            String desc = queryParam(path, "desc");
            String xStr = queryParam(path, "x");
            String yStr = queryParam(path, "y");
            JSONObject o = new JSONObject();
            o.put("ok", true);

            AccessibilityNodeInfo target = null;
            String method = "";
            if (!text.isEmpty() || !desc.isEmpty()) {
                target = findNodeByText(!text.isEmpty() ? text : desc);
                method = "node-text";
            } else if (!xStr.isEmpty() && !yStr.isEmpty()) {
                int x = (int) Double.parseDouble(xStr);
                int y = (int) Double.parseDouble(yStr);
                target = findNodeByPoint(x, y);
                method = "node-coord";
            } else {
                return jsonError("tap 需要 text/desc 或 x/y 参数");
            }

            if (target != null && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                o.put("found", true);
                o.put("method", method);
                target.recycle();
                return o.toString();
            }
            if (!xStr.isEmpty() && !yStr.isEmpty()) {
                boolean gestureOk = gestureTap((int) Double.parseDouble(xStr), (int) Double.parseDouble(yStr));
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

    /** /screenshot：Android 11+ 无障碍截图，存 PNG 到 filesDir/screenshots/，返回路径。 */
    private String handleScreenshot() {
        if (Build.VERSION.SDK_INT < 30) {
            return jsonError("截图需要 Android 11+（当前 API " + Build.VERSION.SDK_INT + "）；低版本请用 /dump 读屏幕文本");
        }
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
                            Bitmap soft = bmp.copy(Bitmap.Config.ARGB_8888, false);
                            bmp.recycle();
                            if (hb != null) hb.close();
                            File dir = new File(getFilesDir(), "screenshots");
                            if (!dir.exists()) dir.mkdirs();
                            File out = new File(dir, "screen-" + System.currentTimeMillis() + ".png");
                            FileOutputStream fos = new FileOutputStream(out);
                            soft.compress(Bitmap.CompressFormat.PNG, 100, fos);
                            fos.flush();
                            fos.close();
                            JSONObject o = new JSONObject();
                            o.put("ok", true);
                            o.put("path", out.getAbsolutePath());
                            o.put("width", soft.getWidth());
                            o.put("height", soft.getHeight());
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
