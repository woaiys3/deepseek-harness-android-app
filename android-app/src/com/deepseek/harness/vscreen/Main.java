package com.deepseek.harness.vscreen;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Looper;
import android.os.Process;
import android.view.Surface;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 虚拟屏特权服务端（app_process / shell 身份，由 Shizuku 或 root 拉起）。
 *
 * 为什么必须是特权进程：App 身份建出来的虚拟屏既不能承载外部 App
 * （startActivityAsUser 会因 launchDisplayId 被 SafeActivityOptions.checkPermissions 拒绝），
 * 也无法把外部 App 画面渲染进自己的 display。shell 身份没有这两个限制。
 *
 * 对外只提供 HTTP（默认 8998），由 App 内 VsreenBridgeService 代理到插件用的 8999。
 * 所有能力都来自公开 API + 反射常量，不使用 MediaProjection（那只是主屏镜像，且需要用户授权弹窗）。
 */
public class Main {

    private static final String TAG = "ShowerMain";

    /**
     * 服务端构建指纹。
     * App 侧 VsreenBridgeService.EXPECTED_CORE_BUILD 必须与此一致：
     * 不一致就会杀掉旧 core 重新拉起（旧进程偷生会让新路由/新参数静默失效）。
     */
    // ⚠ 改过任何影响对外行为的核心代码（路由 / 参数 / 尺寸归一化等）都必须同时升这个值：
    // 只改代码不升指纹，App 就判不出"跑的是旧 core"，改动会静默失效。
    static final String BUILD = "vs113-20260916";

    /**
     * 心跳看门狗（v1.13.12）。App 进程内的桥服务每 ~750ms 就来拉一次 /vscreen/status，
     * 插件请求也走桥 —— "20 秒没有任何请求" ⟺ App 已经不在了（强制关闭/被系统杀）。
     * core 是 shell 身份的独立进程，App 死了它不会跟着死，虚拟屏会变成一块
     * 没人管的孤儿屏；看门狗负责销毁虚拟屏并退出进程（用户问过"强制关闭会不会
     * 销毁虚拟桌面"，现在答案是：会，20 秒内自动收掉）。
     */
    // 60s（原 20s 偏紧）：桥的轮询虽然约 750ms 一次，但单次请求超时是 status 3s /
    // preview 5s，一旦开始超时轮询会被拖长，20s 的余量可能被吃穿 → 误杀虚拟屏。
    private static final long HEARTBEAT_TIMEOUT_MS = 60000L;
    private static volatile long sLastRequestAt = System.currentTimeMillis();

    private static final int DEFAULT_PORT = 8998;
    private static final String DEFAULT_EXTERNAL_ROOT = "/sdcard/DeepSeekHarness";
    private static final String LOG_PATH = "/data/local/tmp/vscreen.log";

    /** 预览帧最大边（服务端缩放后再 JPEG，避免把 720x1520 原图传给 App）。 */
    private static final int PREVIEW_MAX_WIDTH = 360;
    private static final int PREVIEW_JPEG_QUALITY = 70;

    /** 帧泵节流：屏幕静止时不空转烧 CPU，动起来时最多约 20fps 刷新。 */
    private static final long PUMP_IDLE_SLEEP_MS = 30;

    private static Context sContext;
    private static int sPort = DEFAULT_PORT;
    private static String sExternalRoot = DEFAULT_EXTERNAL_ROOT;

    private static final ConcurrentHashMap<Integer, Session> sSessions = new ConcurrentHashMap<>();
    private static final AtomicInteger sDisplaySeq = new AtomicInteger(0);
    private static volatile int sCurrentDisplayId = -1;

    public static void main(String[] args) {
        Looper.prepareMainLooper();
        parseArgs(args);
        log("server starting uid=" + Process.myUid() + " sdk=" + Build.VERSION.SDK_INT
                + " brand=" + Build.BRAND + " port=" + sPort + " dir=" + sExternalRoot, null);

        try {
            sContext = FakeContext.get();
            log("FakeContext ready (package=" + sContext.getPackageName() + ")", null);
        } catch (Throwable t) {
            log("FakeContext init failed, 虚拟屏不可用", t);
        }

        try {
            startHttpServer();
        } catch (Throwable t) {
            log("HTTP 服务启动失败", t);
            return;
        }

        startHeartbeatWatchdog();

        try {
            Looper.loop();
        } catch (Throwable t) {
            log("Looper.loop 退出", t);
        }
    }

    /** 心跳看门狗：App 消失（20 秒无请求）→ 销毁虚拟屏 + 退出进程。 */
    private static void startHeartbeatWatchdog() {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                while (true) {
                    try {
                        Thread.sleep(5000);
                    } catch (InterruptedException e) {
                        return;
                    }
                    long idle = System.currentTimeMillis() - sLastRequestAt;
                    if (idle <= HEARTBEAT_TIMEOUT_MS) continue;
                    // App 没了：先显式销毁虚拟屏（哪怕进程退出系统也会回收，显式关闭更干净）
                    log("心跳超时 " + idle + "ms 无请求，判定宿主 App 已退出：销毁虚拟屏并退出", null);
                    try {
                        closeDisplay();
                    } catch (Throwable t2) {
                        log("看门狗销毁虚拟屏失败", t2);
                    }
                    try {
                        Thread.sleep(500);   // 给日志一点落盘时间
                    } catch (InterruptedException ignored) {
                    }
                    System.exit(0);
                }
            }
        }, "vscreen-heartbeat");
        t.setDaemon(true);
        t.start();
    }

    private static void parseArgs(String[] args) {
        if (args == null) {
            return;
        }
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if ("--port".equals(a) && i + 1 < args.length) {
                try {
                    sPort = Integer.parseInt(args[++i].trim());
                } catch (Throwable ignored) {
                }
            } else if ("--dir".equals(a) && i + 1 < args.length) {
                sExternalRoot = args[++i].trim();
            }
        }
    }

    // ==================== 会话（一个虚拟屏 = 一个 ImageReader + 帧泵） ====================

    private static final class Session {
        final int displayId;
        final int width;
        final int height;
        final VirtualDisplay virtualDisplay;
        final ImageReader reader;

        final Object frameLock = new Object();
        Bitmap frame;             // 最新一帧（复用，读取方必须持锁拷贝）
        long frameTs;
        volatile boolean pumping = true;
        volatile boolean sawFrame = false;

        Session(int displayId, int width, int height, VirtualDisplay vd, ImageReader reader) {
            this.displayId = displayId;
            this.width = width;
            this.height = height;
            this.virtualDisplay = vd;
            this.reader = reader;
        }

        void release() {
            pumping = false;
            synchronized (frameLock) {
                if (frame != null) {
                    frame.recycle();
                    frame = null;
                }
            }
            try {
                virtualDisplay.release();
            } catch (Throwable t) {
                log("virtualDisplay.release 失败: " + t.getMessage(), null);
            }
            try {
                reader.close();
            } catch (Throwable t) {
                log("reader.close 失败: " + t.getMessage(), null);
            }
        }
    }

    /**
     * 帧泵：持续 acquireLatestImage 并保存最新帧。
     * ImageReader 只有在被 acquire 后才会继续交付新帧，所以必须常驻消费；
     * 屏幕静止时没有新帧，靠缓存的最后一帧对外服务。
     */
    private static void startFramePump(final Session s) {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                int consecutiveNull = 0;
                while (s.pumping) {
                    Image img = null;
                    try {
                        img = s.reader.acquireLatestImage();
                    } catch (Throwable t2) {
                        log("acquireLatestImage 异常: " + t2.getMessage(), null);
                    }
                    if (img == null) {
                        consecutiveNull++;
                        try {
                            Thread.sleep(consecutiveNull > 20 ? 60 : PUMP_IDLE_SLEEP_MS);
                        } catch (InterruptedException e) {
                            return;
                        }
                        continue;
                    }
                    consecutiveNull = 0;
                    try {
                        copyImageToSession(s, img);
                        s.sawFrame = true;
                        s.frameTs = System.currentTimeMillis();
                    } catch (Throwable t2) {
                        log("copyImageToSession 异常: " + t2.getMessage(), null);
                    } finally {
                        try {
                            img.close();
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }
        }, "vscreen-pump-" + s.displayId);
        t.setDaemon(true);
        t.start();
    }

    private static void copyImageToSession(Session s, Image img) {
        int w = img.getWidth();
        int h = img.getHeight();
        Image.Plane plane = img.getPlanes()[0];
        ByteBuffer buf = plane.getBuffer();
        int pixelStride = plane.getPixelStride();
        int rowStride = plane.getRowStride();
        int rowPadding = rowStride - pixelStride * w;
        buf.rewind();

        synchronized (s.frameLock) {
            // rowStride == pixelStride * width 时（本机实测 2880 == 4*720）可直接复用同一张 Bitmap，
            // 否则带 padding 的帧复制进复用的 Bitmap 会整体错位，只能另建带 padding 宽度的 Bitmap。
            if (rowPadding == 0 && pixelStride == 4) {
                if (s.frame == null || s.frame.getWidth() != w || s.frame.getHeight() != h
                        || s.frame.isRecycled()) {
                    if (s.frame != null) {
                        s.frame.recycle();
                    }
                    s.frame = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                }
                s.frame.copyPixelsFromBuffer(buf);
            } else {
                Bitmap padded = Bitmap.createBitmap(w + rowPadding / Math.max(1, pixelStride), h,
                        Bitmap.Config.ARGB_8888);
                padded.copyPixelsFromBuffer(buf);
                Bitmap cropped = Bitmap.createBitmap(padded, 0, 0, w, h);
                padded.recycle();
                if (s.frame != null) {
                    s.frame.recycle();
                }
                s.frame = cropped;
            }
        }
    }

    private static Bitmap snapshotFrame(Session s) {
        synchronized (s.frameLock) {
            if (s.frame == null || s.frame.isRecycled()) {
                return null;
            }
            return s.frame.copy(Bitmap.Config.ARGB_8888, false);
        }
    }

    // ==================== 建屏 ====================

    private static int flag(String name) {
        try {
            Field f = DisplayManager.class.getField(name);
            return f.getInt(null);
        } catch (Throwable t) {
            return 0;
        }
    }

    // ==================== 尺寸归一化：只允许 9:16（竖）/ 16:9（横）====================
    /** 最短边取 144(=9×16) 的倍数：长边 = 短边×16/9 时两个方向都自然 16 对齐，比例精确。 */
    private static int normalizeShortEdge(int requested) {
        int v = requested > 0 ? requested : 1008; // 默认 1008×1792（≈FHD 竖屏）
        int units = Math.round(v / 144f);
        if (units < 1) units = 1;
        if (units > 10) units = 10; // 上限 1440 → 1440×2560
        return units * 144;
    }

    /**
     * 把请求宽高归一成手机比例：宽>高 → 16:9 横屏，否则 9:16 竖屏。
     * 这样预览小窗（按虚拟屏宽高比自适应高度）始终是正常手机的竖屏/横屏比例，
     * 不会出现 1520×720 这类 19:9 的怪比例。
     * @param w - 请求宽度（≤0 → 用默认竖屏短边）
     * @param h - 请求高度
     * @returns {宽, 高}
     */
    private static int[] toPhoneSize(int w, int h) {
        boolean hasRequest = w > 0 || h > 0;
        boolean landscape = hasRequest && w > h;
        int shortReq = 0;
        if (hasRequest) {
            int ww = w > 0 ? w : h;
            int hh = h > 0 ? h : w;
            shortReq = Math.min(ww, hh);
        }
        int shortEdge = normalizeShortEdge(shortReq);
        int longEdge = shortEdge * 16 / 9;
        return landscape ? new int[]{longEdge, shortEdge} : new int[]{shortEdge, longEdge};
    }

    private static synchronized String createDisplay(int reqW, int reqH, int reqDpi) {
        if (sContext == null) {
            return err("特权进程 Context 初始化失败（Shizuku/root 通道不可用）");
        }
        int[] phone = toPhoneSize(reqW, reqH);
        reqW = phone[0];
        reqH = phone[1];
        if (sCurrentDisplayId >= 0 && sSessions.containsKey(sCurrentDisplayId)) {
            Session cur = sSessions.get(sCurrentDisplayId);
            boolean sameSize = (reqW == cur.width) && (reqH == cur.height);
            if (sameSize) {
                return ok("{\"ok\":true,\"displayId\":" + sCurrentDisplayId
                        + ",\"width\":" + cur.width
                        + ",\"height\":" + cur.height
                        + ",\"dpi\":" + reqDpi + ",\"reused\":true}");
            }
            // 尺寸/朝向变了：必须重建，否则横竖屏切换会被静默忽略（曾表现为“尺寸永远 720x1520”）
            log("create 请求 " + reqW + "x" + reqH + " 与现有 " + cur.width + "x" + cur.height
                    + " 不同 → 重建", null);
            sCurrentDisplayId = -1;
            Session old = sSessions.remove(cur.displayId);
            if (old != null) {
                old.release();
            }
        }

        int w = reqW;
        int h = reqH;
        int d = reqDpi > 0 ? reqDpi : 320;

        int flags = flag("VIRTUAL_DISPLAY_FLAG_PUBLIC")
                | flag("VIRTUAL_DISPLAY_FLAG_PRESENTATION")
                | flag("VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY")
                | flag("VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH")
                | flag("VIRTUAL_DISPLAY_FLAG_ROTATES_WITH_CONTENT")
                | flag("VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL");
        if (Build.VERSION.SDK_INT >= 33) {
            flags |= flag("VIRTUAL_DISPLAY_FLAG_TRUSTED")
                    | flag("VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP")
                    | flag("VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED")
                    | flag("VIRTUAL_DISPLAY_FLAG_TOUCH_FEEDBACK_DISABLED");
        }
        if (Build.VERSION.SDK_INT >= 34) {
            flags |= flag("VIRTUAL_DISPLAY_FLAG_OWN_FOCUS")
                    | flag("VIRTUAL_DISPLAY_FLAG_DEVICE_DISPLAY_GROUP");
        }

        ImageReader reader = null;
        try {
            reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2);
            Surface surface = reader.getSurface();

            Constructor<DisplayManager> ctor =
                    DisplayManager.class.getDeclaredConstructor(Context.class);
            ctor.setAccessible(true);
            DisplayManager dm = ctor.newInstance(sContext);

            String name = "DSHVscreen-" + sDisplaySeq.incrementAndGet();
            VirtualDisplay vd = dm.createVirtualDisplay(name, w, h, d, surface, flags);
            if (vd == null || vd.getDisplay() == null) {
                reader.close();
                return err("createVirtualDisplay 返回空 display");
            }
            int id = vd.getDisplay().getDisplayId();

            Session s = new Session(id, w, h, vd, reader);
            sSessions.put(id, s);
            sCurrentDisplayId = id;
            startFramePump(s);
            log("建屏成功 displayId=" + id + " " + w + "x" + h + " dpi=" + d
                    + " flags=0x" + Integer.toHexString(flags), null);
            return "{\"ok\":true,\"displayId\":" + id + ",\"width\":" + w
                    + ",\"height\":" + h + ",\"dpi\":" + d + "}";
        } catch (Throwable t) {
            if (reader != null) {
                try {
                    reader.close();
                } catch (Throwable ignored) {
                }
            }
            log("建屏失败", t);
            return err("建屏失败: " + t);
        }
    }

    private static synchronized String closeDisplay() {
        int id = sCurrentDisplayId;
        sCurrentDisplayId = -1;
        Session s = id >= 0 ? sSessions.remove(id) : null;
        if (s != null) {
            s.release();
            log("已释放 displayId=" + id, null);
        }
        return "{\"ok\":true,\"closed\":" + id + "}";
    }

    private static Session currentSession() {
        int id = sCurrentDisplayId;
        return id >= 0 ? sSessions.get(id) : null;
    }

    // ==================== 启动 App 到虚拟屏 ====================

    private static String execCapture(String... cmd) {
        try {
            java.lang.Process p = Runtime.getRuntime().exec(cmd);
            StringBuilder sb = new StringBuilder();
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line).append('\n');
            }
            r = new BufferedReader(new InputStreamReader(p.getErrorStream()));
            while ((line = r.readLine()) != null) {
                sb.append(line).append('\n');
            }
            int code = p.waitFor();
            return "exit=" + code + " " + sb.toString().trim();
        } catch (Throwable t) {
            return "exec失败: " + t;
        }
    }

    /** 按关键字匹配已安装第三方应用包名（取最短命中，避免匹配到同名子包）。 */
    private static String matchPackage(String keyword) {
        String out = execCapture("/system/bin/cmd", "package", "list", "packages", "-3");
        String lower = keyword.toLowerCase(Locale.US);
        String best = null;
        for (String line : out.split("\n")) {
            int i = line.indexOf("package:");
            if (i < 0) {
                continue;
            }
            String p = line.substring(i + 8).trim();
            if (p.isEmpty()) {
                continue;
            }
            if (p.toLowerCase(Locale.US).contains(lower)
                    && (best == null || p.length() < best.length())) {
                best = p;
            }
        }
        return best;
    }

    private static String launchApp(String pkg) {
        Session s = currentSession();
        if (s == null) {
            return err("虚拟屏未创建，请先调用 /vscreen/create");
        }
        if (pkg == null || pkg.trim().isEmpty()) {
            return err("缺少 pkg 参数");
        }
        pkg = pkg.trim();

        // AI 常直接说应用名（如"微信"）：不是完整包名时先用已安装应用列表做唯一/最短匹配。
        if (!pkg.contains(".")) {
            String matched = matchPackage(pkg);
            if (matched != null) {
                log("launch 包名解析: " + pkg + " -> " + matched, null);
                pkg = matched;
            }
        }

        // am start 只能用组件名（-p 在 Android 15 上解析失败，实测），先解析启动组件。
        String resolved = execCapture("/system/bin/cmd", "package", "resolve-activity", "--brief", pkg);
        String component = null;
        for (String line : resolved.split("\n")) {
            line = line.trim();
            if (line.contains("/") && line.startsWith(pkg)) {
                component = line;
            }
        }
        if (component == null) {
            log("launch 解析组件失败: " + resolved, null);
            return err("无法解析启动组件：" + pkg + "（" + resolved.replace("\n", " ") + "）");
        }

        String out = execCapture("/system/bin/am", "start", "--display", String.valueOf(s.displayId),
                "-n", component);
        log("launch " + pkg + " -> " + component + " on " + s.displayId + " : " + out, null);
        if (out.contains("Error:") || out.startsWith("exec失败")) {
            return err("启动失败：" + out.replace("\n", " "));
        }
        return "{\"ok\":true,\"package\":\"" + pkg + "\",\"component\":\"" + component
                + "\",\"displayId\":" + s.displayId + "}";
    }

    // ==================== 截图 / 预览 ====================

    private static String screenshotsDir() {
        return sExternalRoot + "/vscreen";
    }

    private static String see() {
        Session s = currentSession();
        if (s == null) {
            return err("虚拟屏未创建，请先调用 /vscreen/create");
        }
        Bitmap bmp = snapshotFrame(s);
        if (bmp == null) {
            return err("虚拟屏暂无画面（App 尚未渲染或屏幕刚创建）");
        }
        File dir = new File(screenshotsDir());
        if (!dir.exists() && !dir.mkdirs()) {
            bmp.recycle();
            return err("截图目录不可写：" + dir.getAbsolutePath());
        }
        File out = new File(dir, "vscreen-" + System.currentTimeMillis() + ".png");
        try {
            FileOutputStream fos = new FileOutputStream(out);
            bmp.compress(Bitmap.CompressFormat.PNG, 100, fos);
            fos.flush();
            fos.close();
            int iw = bmp.getWidth();
            int ih = bmp.getHeight();
            bmp.recycle();
            return "{\"ok\":true,\"path\":\"" + out.getAbsolutePath() + "\",\"displayId\":" + s.displayId
                    + ",\"screenW\":" + s.width + ",\"screenH\":" + s.height
                    + ",\"imageW\":" + iw + ",\"imageH\":" + ih
                    + ",\"scaleX\":1.0,\"scaleY\":1.0}";
        } catch (Throwable t) {
            bmp.recycle();
            log("写 PNG 失败: " + out.getAbsolutePath(), t);
            return err("写截图失败: " + t);
        }
    }

    private static String preview() {
        Session s = currentSession();
        if (s == null) {
            return err("虚拟屏未创建");
        }
        Bitmap bmp = snapshotFrame(s);
        if (bmp == null) {
            return err("虚拟屏暂无画面");
        }
        Bitmap scaled = null;
        try {
            int pw = Math.min(PREVIEW_MAX_WIDTH, bmp.getWidth());
            int ph = Math.max(1, Math.round(bmp.getHeight() * (pw / (float) bmp.getWidth())));
            scaled = Bitmap.createScaledBitmap(bmp, pw, ph, true);
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            scaled.compress(Bitmap.CompressFormat.JPEG, PREVIEW_JPEG_QUALITY, bos);
            String b64 = android.util.Base64.encodeToString(bos.toByteArray(), android.util.Base64.NO_WRAP);
            return "{\"ok\":true,\"previewB64\":\"" + b64 + "\",\"width\":" + pw + ",\"height\":" + ph
                    + ",\"screenW\":" + s.width + ",\"screenH\":" + s.height + "}";
        } catch (Throwable t) {
            return err("预览编码失败: " + t);
        } finally {
            bmp.recycle();
            if (scaled != null) {
                scaled.recycle();
            }
        }
    }

    // ==================== 输入注入 ====================

    private static String input(String action, String... args) {
        Session s = currentSession();
        if (s == null) {
            return err("虚拟屏未创建，请先调用 /vscreen/create");
        }
        String[] cmd = new String[4 + args.length];
        cmd[0] = "/system/bin/input";
        cmd[1] = "-d";
        cmd[2] = String.valueOf(s.displayId);
        cmd[3] = action;
        System.arraycopy(args, 0, cmd, 4, args.length);
        String out = execCapture(cmd);
        if (!out.startsWith("exit=0")) {
            log("input " + action + " 失败: " + out, null);
            return err("注入失败：" + out.replace("\n", " "));
        }
        return "{\"ok\":true}";
    }

    private static String status() {
        Session s = currentSession();
        if (s == null) {
            return "{\"ok\":true,\"displayId\":-1,\"running\":false,\"server\":true,\"width\":0,\"height\":0}";
        }
        return "{\"ok\":true,\"displayId\":" + s.displayId + ",\"running\":true,\"server\":true"
                + ",\"build\":\"" + BUILD + "\""
                + ",\"width\":" + s.width + ",\"height\":" + s.height
                + ",\"hasFrame\":" + s.sawFrame + "}";
    }

    // ==================== HTTP ====================

    private static void startHttpServer() throws IOException {
        final ServerSocket ss = new ServerSocket(sPort, 16, InetAddress.getByName("127.0.0.1"));
        log("HTTP listening on 127.0.0.1:" + sPort, null);
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                while (true) {
                    try {
                        final Socket sock = ss.accept();
                        // 具名类而非嵌套匿名类：d8 8.2 在 "匿名类内再套匿名类" 的 class 上会内部 NPE。
                        new Thread(new ConnRunner(sock), "vscreen-http").start();
                    } catch (Throwable e) {
                        log("accept 失败: " + e.getMessage(), null);
                    }
                }
            }
        }, "vscreen-http-accept");
        t.setDaemon(true);
        t.start();
    }

    /** 单连接处理线程（具名类，见 startHttpServer 注释）。 */
    private static final class ConnRunner implements Runnable {
        private final Socket sock;

        ConnRunner(Socket sock) {
            this.sock = sock;
        }

        @Override
        public void run() {
            handle(sock);
        }
    }

    private static void handle(Socket sock) {
        try {
            sLastRequestAt = System.currentTimeMillis();   // 心跳：任何请求都算 App 活着
            sock.setSoTimeout(15000);
            BufferedReader in = new BufferedReader(new InputStreamReader(sock.getInputStream()));
            String requestLine = in.readLine();
            if (requestLine == null) {
                return;
            }
            String line;
            while ((line = in.readLine()) != null && !line.isEmpty()) {
                // 忽略请求头
            }

            String path = "";
            String query = "";
            String[] parts = requestLine.split(" ");
            if (parts.length >= 2) {
                String target = parts[1];
                int q = target.indexOf('?');
                path = q >= 0 ? target.substring(0, q) : target;
                query = q >= 0 ? target.substring(q + 1) : "";
            }

            String body;
            long started = System.currentTimeMillis();
            if ("/vscreen/ping".equals(path)) {
                // App 端 coreAlive() 的健康检查；必须存在，否则会误判成 core 未启动而反复重启
                body = "{\"ok\":true,\"server\":true,\"build\":\"" + BUILD + "\"}";
            } else if ("/vscreen/status".equals(path)) {
                body = status();
            } else if ("/vscreen/create".equals(path)) {
                // 参数名两种都收：插件用 width/height/dpi，内部与旧调用用 w/h/d
                body = createDisplay(
                        qIntEither(query, "w", "width", 0),
                        qIntEither(query, "h", "height", 0),
                        qIntEither(query, "d", "dpi", 0));
            } else if ("/vscreen/launch".equals(path)) {
                body = launchApp(qStr(query, "pkg"));
            } else if ("/vscreen/see".equals(path)) {
                body = see();
            } else if ("/vscreen/preview".equals(path)) {
                body = preview();
            } else if ("/vscreen/tap".equals(path)) {
                body = input("tap", fmt(qFloat(query, "x", 0)), fmt(qFloat(query, "y", 0)));
            } else if ("/vscreen/swipe".equals(path)) {
                body = input("swipe", fmt(qFloat(query, "x1", 0)), fmt(qFloat(query, "y1", 0)),
                        fmt(qFloat(query, "x2", 0)), fmt(qFloat(query, "y2", 0)),
                        String.valueOf(qInt(query, "dur", 300)));
            } else if ("/vscreen/key".equals(path)) {
                body = input("keyevent", String.valueOf(qInt(query, "keycode", 0)));
            } else if ("/vscreen/close".equals(path)) {
                body = closeDisplay();
            } else {
                body = "{\"ok\":false,\"error\":\"未知路径 " + path + "\"}";
            }

            byte[] bytes = body.getBytes("UTF-8");
            OutputStream os = sock.getOutputStream();
            os.write(("HTTP/1.1 200 OK\r\n"
                    + "Content-Type: application/json; charset=utf-8\r\n"
                    + "Content-Length: " + bytes.length + "\r\n"
                    + "Connection: close\r\n\r\n").getBytes("UTF-8"));
            os.write(bytes);
            os.flush();

            long cost = System.currentTimeMillis() - started;
            if (cost > 1500 || !path.equals("/vscreen/preview")) {
                log("HTTP " + path + " " + query + " -> " + summarize(body) + " (" + cost + "ms)", null);
            }
        } catch (Throwable t) {
            log("handle 异常: " + t.getMessage(), null);
        } finally {
            try {
                sock.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private static String summarize(String body) {
        return body.length() > 160 ? body.substring(0, 160) + "..." : body;
    }

    private static String fmt(float f) {
        if (f == Math.rint(f)) {
            return String.valueOf((long) f);
        }
        return String.valueOf(f);
    }

    private static String qStr(String query, String key) {
        for (String kv : query.split("&")) {
            int eq = kv.indexOf('=');
            if (eq > 0 && kv.substring(0, eq).equals(key)) {
                return urlDecode(kv.substring(eq + 1));
            }
        }
        return null;
    }

    /** 同一含义的多个参数名都接受（插件用 width/height/dpi，内部/旧调用用 w/h/d）。 */
    private static int qIntEither(String query, String k1, String k2, int def) {
        int v = qInt(query, k1, Integer.MIN_VALUE);
        return v != Integer.MIN_VALUE ? v : qInt(query, k2, def);
    }

    private static int qInt(String query, String key, int def) {
        try {
            String v = qStr(query, key);
            return v == null ? def : (int) Double.parseDouble(v);
        } catch (Throwable t) {
            return def;
        }
    }

    private static float qFloat(String query, String key, float def) {
        try {
            String v = qStr(query, key);
            return v == null ? def : Float.parseFloat(v);
        } catch (Throwable t) {
            return def;
        }
    }

    private static String urlDecode(String s) {
        try {
            return java.net.URLDecoder.decode(s, "UTF-8");
        } catch (Throwable t) {
            return s;
        }
    }

    private static String err(String msg) {
        return "{\"ok\":false,\"error\":\"" + escape(msg) + "\"}";
    }

    private static String ok(String json) {
        return json;
    }

    private static String escape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ");
    }

    // ==================== 日志 ====================

    /** 兼容旧调用点（DisplayCapture 等）保留的别名。 */
    static void logToFile(String msg, Throwable t) {
        log(msg, t);
    }

    static void log(String msg, Throwable t) {
        String stamp = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
        StringBuilder sb = new StringBuilder();
        sb.append(stamp).append(' ').append(msg);
        if (t != null) {
            sb.append(" | ").append(t.getClass().getSimpleName()).append(": ").append(t.getMessage());
            StackTraceElement[] st = t.getStackTrace();
            for (int i = 0; i < Math.min(6, st.length); i++) {
                sb.append("\n    at ").append(st[i]);
            }
        }
        System.out.println(TAG + ": " + sb);
        try {
            FileOutputStream fos = new FileOutputStream(LOG_PATH, true);
            fos.write((TAG + ": " + sb + "\n").getBytes("UTF-8"));
            fos.flush();
            fos.close();
        } catch (Throwable ignored) {
        }
    }
}
