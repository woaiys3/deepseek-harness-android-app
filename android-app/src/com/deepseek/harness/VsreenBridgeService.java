package com.deepseek.harness;

import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.GradientDrawable;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.Base64;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.URLConnection;

import moe.shizuku.server.IRemoteProcess;
import moe.shizuku.server.IShizukuService;
import rikka.shizuku.Shizuku;

/**
 * 虚拟屏【接入桥】。
 *
 * 分工（v1.11 起）：
 *  - 真正的能力由**特权服务端**提供：{@code com.deepseek.harness.vscreen.Main} 以 shell 身份
 *    （Shizuku app_process）运行，在 127.0.0.1:8998 上提供 create/launch/see/preview/tap/swipe/key/close。
 *  - 本服务只做两件事：① 拉起并守护那个特权进程；② 在 8999 上把它**原样代理**给插件（插件协议不变）。
 *
 * 为什么不能在 App 进程里建屏（旧实现的方向性错误）：
 *  - App 身份建的虚拟屏是主屏镜像（MediaProjection），既弹授权框，又只有 mDisplayIdToMirror=0；
 *  - App 身份把外部 App 启动到虚拟屏会被 SafeActivityOptions.checkPermissions 拒绝
 *    （logcat 实测：Permission Denial ... with launchDisplayId=N）。
 *  shell 身份两件事都成立，这是本文件存在的唯一理由。
 */
public class VsreenBridgeService extends Service {

    private static final String TAG = "VsreenBridge";

    /** 插件使用的对外端口（本服务的代理端口）。 */
    private static final int PORT = 8999;
    /** 特权服务端端口。 */
    private static final int CORE_PORT = 8998;
    /** 特权服务端主类。 */
    private static final String CORE_MAIN = "com.deepseek.harness.vscreen.Main";

    /**
     * 期望的服务端构建指纹（必须与 vscreen/Main.java 的 BUILD 一致）。
     * 不匹配 → 杀掉旧 core 重新拉起。旧进程偷生过很多次，
     * 表现为“服务在跑但新路由/新参数静默失效”（如 create 的 width/height 被完全忽略）。
     * v1.13.12：core 加了心跳看门狗（App 死了 → 20 秒后自动销毁虚拟屏并退出）。
     */
    private static final String EXPECTED_CORE_BUILD = "vs113-20260916";

    /** 持有 Shizuku 拉起的进程引用：被 GC 回收会连带清理子进程。 */
    private static volatile IRemoteProcess sCoreProc;
    private static volatile boolean sCoreStarting;

    private volatile ServerSocket serverSocket;
    private volatile boolean running;

    // ==================== 预览浮窗状态 ====================
    private android.widget.FrameLayout previewRootView = null;
    private ImageView previewImageView = null;
    private WindowManager previewWm = null;
    private WindowManager.LayoutParams previewLp = null;
    private final Handler previewHandler = new Handler(Looper.getMainLooper());
    private android.view.ScaleGestureDetector scaleDetector = null;
    private float downX, downY, startLpX, startLpY;
    private volatile boolean previewWindowVisible = false;
    private volatile boolean previewPolling = false;
    private Thread previewPollThread = null;
    private Thread coreWatcher = null;
    private Bitmap lastPreviewBitmap = null;
    private volatile int vdW = 0, vdH = 0;
    private volatile int vdDisplayId = -1;
    /** 已应用的宽高比，用于只在变屏/旋转时重算窗口高度，不干扰用户手动拖动/缩放。 */
    private volatile float lastAspect = 0f;
    /** 用户点了 ✕ 关掉的虚拟屏 displayId —— 轮询别再自动把它弹回来。 */
    private volatile int previewDismissedDisplayId = Integer.MIN_VALUE;
    /**
     * v1.13.11：预览窗被「收起到小鲸鱼」（▾）—— 轮询同样不要自动弹回来，
     * 但语义与 ✕ 不同：虚拟屏仍在跑，用户可从桌面小鲸鱼面板把预览窗叫回来。
     */
    private volatile boolean previewCollapsedToWhale = false;
    /** v1.13.11：服务实例（供小鲸鱼面板回调「重新打开预览窗」）。 */
    private static volatile VsreenBridgeService instance = null;
    /**
     * v1.13.12：虚拟屏当前是否在跑（预览轮询每 ~750ms 刷新）。
     * 小鲸鱼面板据此决定要不要显示「销毁屏」按钮。
     */
    public static volatile boolean sVscreenRunning = false;

    /** 小鲸鱼面板「虚拟屏」按钮：重新打开预览窗（配合「收起到小鲸鱼」）。 */
    public static void showPreviewFromWhale() {
        VsreenBridgeService s = instance;
        if (s != null) s.doShowPreviewFromWhale();
    }

    /** 小鲸鱼面板「销毁屏」按钮：销毁虚拟屏并收掉预览窗（替代旧预览窗上的 ✕）。 */
    public static void destroyVscreenFromWhale() {
        VsreenBridgeService s = instance;
        if (s != null) s.destroyVscreen();
    }

    /** 控制台「停止引擎」联动：引擎停了虚拟屏一起销毁（用户确认的行为）。 */
    public static void requestDestroyVscreen() {
        VsreenBridgeService s = instance;
        if (s != null) s.destroyVscreen();
    }

    private void showPreviewWindow() {
        try {
            previewHandler.post(new Runnable() {
                @Override public void run() { doShowPreviewWindow(); }
            });
        } catch (Throwable ignored) {}
    }

    private void doShowPreviewWindow() {
        try {
            if (!Settings.canDrawOverlays(this)) {
                Log.w(TAG, "预览窗需要悬浮窗权限（设置→应用→显示在其他应用上层）");
                return;
            }
            if (previewRootView != null) return;
            previewWm = (WindowManager) getSystemService(WINDOW_SERVICE);
            int w = dp(260), h = dp(430);
            previewLp = new WindowManager.LayoutParams(
                    w, h,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    android.graphics.PixelFormat.TRANSLUCENT);
            // 用 LEFT 绝对坐标（不用 END：END 的 x 是"距右边缘"，左右拖动会异常）
            previewLp.gravity = Gravity.TOP | Gravity.START;
            // 初始位置：右上角（留边距，不贴边）；用户可自由拖动到任意位置
            previewLp.x = getResources().getDisplayMetrics().widthPixels - dp(260) - dp(24);
            previewLp.y = dp(120);

            // v1.13.12 重做（用户要求）：控件全部挪到**显示区域外面**的边框上，
            // 形态就是用户截图里的"小条"——一条居中的小圆角短横。
            // 点小条 = 收起到小鲸鱼；销毁功能移进小鲸鱼面板（预览窗上不再放 ✕）。
            LinearLayout shell = new LinearLayout(this);
            shell.setOrientation(LinearLayout.VERTICAL);
            GradientDrawable shellBg = new GradientDrawable();
            shellBg.setColor(0xCC000000);
            shellBg.setCornerRadius(dp(10));
            shell.setBackground(shellBg);
            try { shell.setClipToOutline(true); } catch (Throwable ignored) {}

            // --- 边框小条（显示区域外的顶部）：视觉是 64×6dp 的圆角短横，点整条区域收起 ---
            FrameLayout barZone = new FrameLayout(this);
            barZone.setClickable(true);
            View bar = new View(this);
            GradientDrawable barBg = new GradientDrawable();
            barBg.setColor(getColor(R.color.accent_brand));
            barBg.setCornerRadius(dp(3));
            bar.setBackground(barBg);
            FrameLayout.LayoutParams barLp = new FrameLayout.LayoutParams(dp(64), dp(6));
            barLp.gravity = Gravity.CENTER_HORIZONTAL | Gravity.CENTER_VERTICAL;
            barZone.addView(bar, barLp);
            barZone.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
                try { collapsePreviewToWhale(); } catch (Throwable ignored) {}
            }});
            shell.addView(barZone, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(24)));

            // --- 显示区域（虚拟屏画面）---
            previewImageView = new ImageView(this);
            previewImageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
            shell.addView(previewImageView, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

            previewRootView = new FrameLayout(this);
            previewRootView.addView(shell, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));

            // 建窗即按当前虚拟屏比例算尺寸（否则要等下一次比例“变化”才生效）
            lastAspect = 0f;
            if (vdW > 0 && vdH > 0) {
                final int fw = previewLp.width;
                final float ratio = vdH / (float) vdW;
                int fh = Math.min(Math.max(Math.round(fw * ratio), dp(110)),
                        Math.round(getResources().getDisplayMetrics().heightPixels * 0.8f));
                previewLp.height = fh + dp(24); // 补上顶部小条的高度
            }

            scaleDetector = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override public boolean onScale(ScaleGestureDetector detector) {
                    // 最小化时不响应双指缩放；缩放后统一走 clampPreviewBounds()
                    //（旧实现只在一处夹边界，双指放大就能把窗口撑到屏幕外，
                    //  于是右上角的按钮条被推出屏幕 → 用户看到的就是“控制条没有出现”）
                    if (previewLp == null || previewCollapsedToWhale) return true;
                    float f = detector.getScaleFactor();
                    previewLp.width = Math.max(dp(120), Math.round(previewLp.width * f));
                    previewLp.height = Math.max(barHeightPx(), Math.round(previewLp.height * f));
                    clampPreviewBounds();
                    updatePreviewLayout();
                    return true;
                }
            });
            previewRootView.setOnTouchListener(new View.OnTouchListener() {
                @Override public boolean onTouch(View v, MotionEvent e) {
                    // 小条区域自己消费点击（收起）；拖动/缩放在画面区域做
                    scaleDetector.onTouchEvent(e);
                    switch (e.getActionMasked()) {
                        case MotionEvent.ACTION_DOWN:
                            downX = e.getRawX();
                            downY = e.getRawY();
                            startLpX = previewLp.x;
                            startLpY = previewLp.y;
                            return true;
                        case MotionEvent.ACTION_MOVE:
                            if (!scaleDetector.isInProgress()) {
                                float dx = e.getRawX() - downX;
                                float dy = e.getRawY() - downY;
                                previewLp.x = Math.round(startLpX + dx);
                                previewLp.y = Math.round(startLpY + dy);
                                // 统一夹边界（含状态栏让位后的可用高度），保证小条永远可点
                                clampPreviewBounds();
                                updatePreviewLayout();
                            }
                            return true;
                    }
                    return false;
                }
            });

            clampPreviewBounds();          // 建窗即夹，避免初始就超出屏幕
            previewWm.addView(previewRootView, previewLp);
            previewWindowVisible = true;
            Log.i(TAG, "虚拟屏预览窗已显示（画面区拖动/缩放，顶部小条点击收起到小鲸鱼）");
        } catch (Throwable t) {
            Log.w(TAG, "showPreviewWindow failed: " + t.getMessage());
        }
    }

    /** 安全更新预览窗布局：View 已 detach（窗口被移除）时静默跳过 —— 防止 updateViewLayout 崩溃。 */
    private void updatePreviewLayout() {
        try {
            if (previewRootView == null || previewWm == null) return;
            if (!previewRootView.isAttachedToWindow()) return;
            previewWm.updateViewLayout(previewRootView, previewLp);
        } catch (Throwable ignored) {
            // 生命周期竞态：窗口已移除时忽略（历史闪退根因）
        }
    }

    /**
     * 预览窗尺寸与位置的**统一**夹回可见范围。
     * 任何会改 previewLp 的路径（建窗 / 双指缩放 / 拖动 / 折叠）都必须调它，
     * 否则窗口能超出屏幕 —— 按钮条被推出屏幕，用户看到的就是「控制条没出现」。
     */
    private void clampPreviewBounds() {
        try {
            if (previewLp == null) return;
            final int screenW = getResources().getDisplayMetrics().widthPixels;
            final int usableH = usableHeight();
            // 最大只占屏幕 70%：留出位置让下面的聊天/主屏还能操作（旧实现能被撑到满屏）
            final int maxW = Math.max(dp(120), Math.round(screenW * 0.7f));
            final int maxH = Math.max(barHeightPx(), Math.round(usableH * 0.7f));
            if (previewLp.width > maxW) previewLp.width = maxW;
            if (previewLp.width < dp(120)) previewLp.width = dp(120);
            int minH = dp(110);
            if (previewLp.height > maxH) previewLp.height = maxH;
            if (previewLp.height < minH) previewLp.height = minH;
            if (previewLp.x > screenW - previewLp.width) previewLp.x = screenW - previewLp.width;
            if (previewLp.y > usableH - previewLp.height) previewLp.y = usableH - previewLp.height;
            if (previewLp.x < 0) previewLp.x = 0;
            if (previewLp.y < 0) previewLp.y = 0;
        } catch (Throwable ignored) {}
    }

    /** 去掉状态栏占位后的可用高度：窗口坐标是从状态栏下方开始算的（真机实测偏移 133px）。 */
    private int usableHeight() {
        int h = getResources().getDisplayMetrics().heightPixels - statusBarInset();
        return h > 0 ? h : getResources().getDisplayMetrics().heightPixels;
    }

    private int statusBarInset() {
        try {
            int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
            if (id > 0) return getResources().getDimensionPixelSize(id);
        } catch (Throwable ignored) {}
        return 0;
    }

    /** 最小化时保留的高度：一条按钮栏 + 上下留白。 */
    private int barHeightPx() { return dp(40); }

    /**
     * v1.13.11：预览窗 ✕ = **销毁/停止虚拟屏**。
     * 旧实现只是「关窗」—— 虚拟屏还继续跑着，想停只能让 AI 调 android_vscreen_close，
     * 与按钮语义不符（用户明确要求改成销毁/停止）。现在先请求特权服务端 /vscreen/close，
     * 再收掉预览窗；请求失败如实提示，不假装成功。
     */
    private void destroyVscreen() {
        // 没有虚拟屏在跑：静默收掉窗与钉住即可，不发请求也不弹"已关闭"（避免假反馈）
        if (!sVscreenRunning && vdDisplayId < 0) {
            previewHandler.post(new Runnable() { @Override public void run() {
                try {
                    hidePreviewWindow();
                    OverlayService.pinForVscreen(false);
                } catch (Throwable ignored) {}
            }});
            return;
        }
        final int closingId = vdDisplayId;
        sVscreenRunning = false;
        new Thread(new Runnable() { @Override public void run() {
            final String r = coreGet("/vscreen/close", 8000);
            previewHandler.post(new Runnable() { @Override public void run() {
                try {
                    // 记下「这块屏是用户主动销毁的」：轮询看到旧 id 时不要再弹回来
                    previewDismissedDisplayId = closingId;
                    previewCollapsedToWhale = false;
                    vdDisplayId = -1;
                    hidePreviewWindow();
                    OverlayService.pinForVscreen(false);
                    toast(r == null ? "关闭虚拟屏失败（特权服务无响应）" : "虚拟屏已关闭");
                } catch (Throwable ignored) {}
            }});
        }}, "vscreen-close").start();
    }

    /**
     * v1.13.11：预览窗 ▾ = **收起到小鲸鱼**。
     * 旧实现是缩成一条按钮栏（仍占着屏幕）；现在直接收掉预览悬浮窗，改由桌面小鲸鱼承载画面
     * （小鲸鱼面板里本来就有虚拟屏画面区），并把它**钉住可见** —— App 在前台时小鲸鱼默认隐藏
     * （MainActivity.onStart → setOverlayVisible(false)），不钉住的话「收起」之后什么都看不到。
     */
    private void collapsePreviewToWhale() {
        previewHandler.post(new Runnable() { @Override public void run() {
            try {
                previewCollapsedToWhale = true;
                previewDismissedDisplayId = vdDisplayId;   // 别让预览轮询又把它弹回来
                hidePreviewWindow();
                OverlayService.pinForVscreen(true);
                toast("预览已收到小鲸鱼，点小鲸鱼可再打开");
            } catch (Throwable ignored) {}
        }});
    }

    private void doShowPreviewFromWhale() {
        try {
            previewDismissedDisplayId = Integer.MIN_VALUE;
            previewCollapsedToWhale = false;
            OverlayService.pinForVscreen(false);
            showPreviewWindow();
        } catch (Throwable ignored) {}
    }

    /** 浮层上的短提示（预览窗是 FLAG_NOT_FOCUSABLE，弹不出对话框）。 */
    private void toast(String msg) {
        try {
            android.widget.Toast.makeText(getApplicationContext(), msg, android.widget.Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {}
    }

    private void hidePreviewWindow() {
        try {
            previewHandler.post(new Runnable() {
                @Override public void run() {
                    if (previewRootView != null && previewWm != null) {
                        try { previewWm.removeView(previewRootView); } catch (Throwable ignored) {}
                    }
                    previewRootView = null;
                    previewImageView = null;
                    previewWm = null;
                    previewWindowVisible = false;
                    if (lastPreviewBitmap != null) {
                        lastPreviewBitmap.recycle();
                        lastPreviewBitmap = null;
                    }
                }
            });
        } catch (Throwable ignored) {}
    }

    // ==================== 预览轮询（从特权服务端拉 JPEG 帧） ====================

    private void startPreviewPolling() {
        if (previewPolling) return;
        previewPolling = true;
        previewPollThread = new Thread(new Runnable() {
            @Override public void run() {
                int sinceStatus = 0;
                while (previewPolling) {
                    try {
                        if (sinceStatus <= 0) {
                            String st = coreGet("/vscreen/status", 3000);
                            sinceStatus = 5;
                            int id = jsonInt(st, "displayId", -1);
                            vdDisplayId = id;
                            vdW = jsonInt(st, "width", 0);
                            vdH = jsonInt(st, "height", 0);
                            boolean running = id >= 0 && jsonBool(st, "running");
                            sVscreenRunning = running;   // 小鲸鱼面板「销毁屏」按钮的显示依据
                            if (id >= 0 && vdW > 0 && vdH > 0) applyAspect(vdW, vdH);
                            if (running) {
                                // 用户手动 ✕ 关掉的那块虚拟屏不再自动弹回来
                                // （虚拟屏被关掉/换新的一块时下面会清掉这个标记）
                                if (!previewWindowVisible && id != previewDismissedDisplayId) showPreviewWindow();
                            } else {
                                if (id < 0) previewDismissedDisplayId = Integer.MIN_VALUE;   // 虚拟屏已销毁 → 下次重建照常弹预览
                                if (previewWindowVisible) hidePreviewWindow();
                            }
                        }
                        sinceStatus--;
                        if (previewWindowVisible && previewImageView != null) {
                            String pv = coreGet("/vscreen/preview", 5000);
                            String b64 = jsonStr(pv, "previewB64");
                            if (b64 != null && b64.length() > 0) {
                                byte[] jpg = Base64.decode(b64, Base64.DEFAULT);
                                final Bitmap bmp = BitmapFactory.decodeByteArray(jpg, 0, jpg.length);
                                if (bmp != null) {
                                    previewHandler.post(new Runnable() {
                                        @Override public void run() {
                                            if (previewImageView == null) { bmp.recycle(); return; }
                                            if (lastPreviewBitmap != null && lastPreviewBitmap != bmp) {
                                                lastPreviewBitmap.recycle();
                                            }
                                            lastPreviewBitmap = bmp;
                                            previewImageView.setImageBitmap(bmp);
                                        }
                                    });
                                }
                            }
                        }
                    } catch (Throwable ignored) {
                        // 服务端未就绪/虚拟屏未创建：静默，下一轮再试
                    }
                    try { Thread.sleep(150); } catch (InterruptedException e) { break; }
                }
            }
        }, "vscreen-preview-poll");
        previewPollThread.setDaemon(true);
        previewPollThread.start();
    }

    private void stopPreviewPolling() {
        previewPolling = false;
        if (previewPollThread != null) {
            previewPollThread.interrupt();
            previewPollThread = null;
        }
    }

    /** 预览窗宽度不变，高度按虚拟屏宽高比自适应（竖屏高瘦 / 横屏矮宽），避免 FIT_CENTER 留黑边。 */
    private void applyAspect(final int vw, final int vh) {
        final float aspect = vh / (float) vw;
        if (Math.abs(aspect - lastAspect) < 0.02f) return;
        lastAspect = aspect;
        try {
            previewHandler.post(new Runnable() {
                @Override public void run() {
                    // 窗口还没创建：撤销标记，等建窗后按当前虚拟屏比例重新算
                    // （否则标记被提前消费，之后每轮都因差值<0.02 提前返回 → 形状永远不变）
                    if (previewLp == null) { lastAspect = 0f; return; }
                    int maxH = Math.round(getResources().getDisplayMetrics().heightPixels * 0.8f);
                    int minH = dp(110);
                    // v1.13.12：窗口 = 顶部小条(dp 24) + 画面区；按画面比例算完要补小条高度
                    int h = Math.min(Math.max(Math.round(previewLp.width * aspect), minH), maxH - dp(24)) + dp(24);
                    if (previewLp.height != h) {
                        previewLp.height = h;
                        updatePreviewLayout();
                    }
                }
            });
        } catch (Throwable ignored) {
        }
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private String extRoot() {
        // 必须与 MainActivity.pkgRoot() 完全一致：桥接从这里取 jar 交给特权进程，
        // 用错目录会拿到另一个根目录下的旧 jar（8998 跑旧版服务端 → 缺新路由 → 工具报错）。
        String p = getPackageName();
        return p.contains("beta") ? "DeepSeekHarnessLite"
                : p.contains("compat") ? "DeepSeekHarnessCompat" : "DeepSeekHarness";
    }

    // ==================== 生命周期 ====================

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;   // v1.13.11：供小鲸鱼面板回调「重新打开预览窗」
        running = true;
        new Thread(new Runnable() {
            @Override public void run() { ensureCoreServer(); }
        }, "vscreen-core-start").start();
        startCoreWatcher();
        startProxyServer();
        // 预览轮询必须常驻启动：它负责「发现虚拟屏→拉起预览窗」，不能在窗口显示后才启动（会互等死锁）
        startPreviewPolling();
        Log.i(TAG, "VsreenBridgeService started (proxy 8999 -> core 8998)");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        running = false;
        instance = null;
        stopPreviewPolling();
        hidePreviewWindow();
        if (serverSocket != null) { try { serverSocket.close(); } catch (Throwable ignored) {} }
        super.onDestroy();
    }

    // ==================== 拉起特权服务端（Shizuku app_process） ====================

    private boolean coreAlive() {
        String st = coreGet("/vscreen/ping", 1200);
        // 必须判 "ok":true：旧版/异常响应是 {"ok":false,...}，用 contains("ok") 会把错误响应误判成健康，
        // 导致 App 不再拉起新 core（旧进程占着 8998，缺新路由）。
        if (st == null || !st.contains("\"ok\":true")) return false;
        // 构建指纹也必須一致，否则说明 8998 上跑的是旧版服务端
        return st.contains("\"build\":\"" + EXPECTED_CORE_BUILD + "\"");
    }

    private void ensureCoreServer() {
        if (coreAlive()) { Log.i(TAG, "core server already alive"); return; }
        if (sCoreStarting) return;
        sCoreStarting = true;
        try {
            // Shizuku binder 是异步到达的（App 冷启动时通常晚几秒），必须等，否则会误判成无授权。
            if (!waitShizuku(20000)) {
                Log.w(TAG, "无 Shizuku 授权（binder 未就绪），稍后自动重试——请确认 Shizuku 服务在运行且已授权本应用");
                return;
            }
            String dir = new File(Environment.getExternalStorageDirectory(), extRoot() + "/vscreen").getAbsolutePath();
            String rootDir = new File(Environment.getExternalStorageDirectory(), extRoot()).getAbsolutePath();
            File jar = new File(dir, "vscreen_shizuku.jar");
            if (!jar.exists()) {
                Log.w(TAG, "vscreen jar 不存在: " + jar.getAbsolutePath() + "（需要存储权限后由 MainActivity 提取）");
                return;
            }
            // jar 先由 shell 拷到 /data/local/tmp 再加载：/storage 对 Shizuku shell 进程不一定可见，
            // 且 /data/local/tmp 下 app_process 加载 dex 最稳（Operit/旧插件同做法）。
            String remoteJar = "/data/local/tmp/vscreen_shizuku.jar";
            // 启动前清掉占着 CORE_PORT 的旧 core（旧版进程不会自行退出；卸载/重装也不杀它）。
            // 用正则（不能加 -F）+ [x] 括号技巧：既能匹配 Main，又不会匹配到这条命令自身
            String killOld = "PID=$(ps -A -o PID,ARGS | grep 'com.deepseek.harness.vscreen.Mai[n]' "
                    + "| grep -v grep | awk '{print $1}'); "
                    + "if [ -n \"$PID\" ]; then kill -9 $PID 2>/dev/null; sleep 1; fi; ";
            String cmd = "echo \"--- core start $(date)\" >> /data/local/tmp/vscreen.log 2>&1; "
                    + killOld
                    + "id >> /data/local/tmp/vscreen.log 2>&1; "
                    + "cp -f \"" + jar.getAbsolutePath() + "\" " + remoteJar + " >> /data/local/tmp/vscreen.log 2>&1; "
                    + "chmod 644 " + remoteJar + " 2>/dev/null; "
                    + "CLASSPATH=" + remoteJar
                    + " /system/bin/app_process /system/bin " + CORE_MAIN
                    + " --port " + CORE_PORT + " --dir \"" + rootDir + "\""
                    + " >> /data/local/tmp/vscreen.log 2>&1";
            Log.i(TAG, "starting core server: " + cmd);
            IShizukuService svc = IShizukuService.Stub.asInterface(Shizuku.getBinder());
            IRemoteProcess p = svc.newProcess(
                    new String[]{"/system/bin/sh", "-c", cmd},
                    // env 必须传 null（继承 shell 环境）：传 {"PATH=..."} 会把 ANDROID_ROOT/BOOTCLASSPATH
                    // 等一起覆盖掉，app_process 起不了 ART 虚拟机，表现为静默无输出。
                    null, null);
            sCoreProc = p;
            for (int i = 0; i < 40; i++) {
                Thread.sleep(250);
                if (coreAlive()) { Log.i(TAG, "core server ready after " + (i + 1) * 250 + "ms"); return; }
            }
            Log.w(TAG, "core server 启动超时");
        } catch (Throwable t) {
            Log.w(TAG, "ensureCoreServer failed: " + t.getMessage());
        } finally {
            sCoreStarting = false;
        }
    }

    /** 等 Shizuku binder 就绪（冷启动异步到达）。 */
    private boolean waitShizuku(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (hasShizukuPermission()) return true;
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                return false;
            }
        }
        return hasShizukuPermission();
    }

    /** 后台守护：core 未就绪就重试（Shizuku 授权/binder 晚到、core 进程被杀都能自愈）。 */
    private void startCoreWatcher() {
        if (coreWatcher != null) return;
        coreWatcher = new Thread(new Runnable() {
            @Override
            public void run() {
                while (running) {
                    try {
                        if (!coreAlive()) ensureCoreServer();
                    } catch (Throwable t) {
                        Log.w(TAG, "core watcher: " + t.getMessage());
                    }
                    try {
                        Thread.sleep(5000);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }
        }, "vscreen-core-watch");
        coreWatcher.setDaemon(true);
        coreWatcher.start();
    }

    private boolean hasShizukuPermission() {
        try {
            if (!Shizuku.pingBinder()) return false;
            return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    // ==================== HTTP 代理（插件 8999 → 服务端 8998，原样透传） ====================

    private void startProxyServer() {
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    serverSocket = new ServerSocket(PORT, 8, InetAddress.getByName("127.0.0.1"));
                    Log.i(TAG, "proxy listening 127.0.0.1:" + PORT);
                    while (running && !serverSocket.isClosed()) {
                        final Socket s = serverSocket.accept();
                        new Thread(new Runnable() {
                            @Override public void run() { proxy(s); }
                        }, "vscreen-proxy-conn").start();
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "proxy server exited: " + t.getMessage());
                }
            }
        }, "vscreen-proxy").start();
    }

    private void proxy(Socket s) {
        OutputStream out = null;
        try {
            s.setSoTimeout(20000);
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), "UTF-8"));
            String requestLine = in.readLine();
            if (requestLine == null || requestLine.length() == 0) return;
            String path = "/vscreen/status";
            int sp = requestLine.indexOf(' ');
            if (sp > 0) {
                int sp2 = requestLine.indexOf(' ', sp + 1);
                path = sp2 > sp ? requestLine.substring(sp + 1, sp2) : requestLine.substring(sp + 1);
            }
            out = s.getOutputStream();
            Socket up = null;
            try {
                up = new Socket("127.0.0.1", CORE_PORT);
                up.setSoTimeout(20000);
                OutputStream uo = up.getOutputStream();
                uo.write(("GET " + path + " HTTP/1.0\r\nHost: 127.0.0.1\r\n\r\n").getBytes("UTF-8"));
                uo.flush();
                InputStream is = up.getInputStream();
                byte[] buf = new byte[32768];
                int n;
                while ((n = is.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
                out.flush();
            } catch (Throwable t) {
                // 服务端没起来：拉一次，再明确报错（避免插件只看到"未知错误"）
                if (path.startsWith("/vscreen/create") || path.startsWith("/vscreen/status")) {
                    new Thread(new Runnable() {
                        @Override public void run() { ensureCoreServer(); }
                    }, "vscreen-core-retry").start();
                }
                byte[] body = ("{\"ok\":false,\"error\":\"虚拟屏服务未就绪（特权进程未启动）："
                        + safe(t.getMessage()) + "\"}").getBytes("UTF-8");
                out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json; charset=utf-8\r\n"
                        + "Content-Length: " + body.length + "\r\nConnection: close\r\n\r\n").getBytes("UTF-8"));
                out.write(body);
                out.flush();
            } finally {
                if (up != null) { try { up.close(); } catch (Throwable ignored) {} }
            }
        } catch (Throwable ignored) {
        } finally {
            try { if (out != null) out.flush(); } catch (Throwable ignored) {}
            try { s.close(); } catch (Throwable ignored) {}
        }
    }

    // ==================== 与服务端交互的小工具 ====================

    /** 直接请求特权服务端（预览轮询用；插件请求走 proxy）。 */
    private String coreGet(String path, int timeoutMs) {
        InputStream is = null;
        try {
            URL u = new URL("http://127.0.0.1:" + CORE_PORT + path);
            URLConnection c = u.openConnection();
            c.setConnectTimeout(timeoutMs);
            c.setReadTimeout(timeoutMs);
            is = c.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            String all = new String(bos.toByteArray(), "UTF-8");
            int idx = all.indexOf("\r\n\r\n");
            return idx >= 0 ? all.substring(idx + 4) : all;
        } catch (Throwable t) {
            return null;
        } finally {
            if (is != null) { try { is.close(); } catch (Throwable ignored) {} }
        }
    }

    private static String jsonStr(String json, String key) {
        if (json == null) return null;
        String k = "\"" + key + "\"";
        int i = json.indexOf(k);
        if (i < 0) return null;
        i = json.indexOf(':', i + k.length());
        if (i < 0) return null;
        int q1 = json.indexOf('"', i + 1);
        if (q1 < 0) return null;
        int q2 = json.indexOf('"', q1 + 1);
        if (q2 < 0) return null;
        return json.substring(q1 + 1, q2);
    }

    private static int jsonInt(String json, String key, int def) {
        if (json == null) return def;
        String k = "\"" + key + "\"";
        int i = json.indexOf(k);
        if (i < 0) return def;
        i = json.indexOf(':', i + k.length());
        if (i < 0) return def;
        int j = i + 1;
        while (j < json.length() && (json.charAt(j) == ' ' || json.charAt(j) == '"')) j++;
        int e = j;
        while (e < json.length() && (Character.isDigit(json.charAt(e)) || json.charAt(e) == '-')) e++;
        try { return Integer.parseInt(json.substring(j, e)); } catch (Throwable t) { return def; }
    }

    private static boolean jsonBool(String json, String key) {
        if (json == null) return false;
        String k = "\"" + key + "\"";
        int i = json.indexOf(k);
        if (i < 0) return false;
        i = json.indexOf(':', i + k.length());
        return i > 0 && json.startsWith("true", i + 1 + (json.charAt(i + 1) == ' ' ? 1 : 0));
    }

    private static String safe(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ");
    }
}
