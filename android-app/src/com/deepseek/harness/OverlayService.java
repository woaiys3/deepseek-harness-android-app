package com.deepseek.harness;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.OvershootInterpolator;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

import java.util.HashSet;

import org.json.JSONObject;

/**
 * 小鲸鱼悬浮窗服务：
 *  - 常驻悬浮小鲸鱼图标（可拖动；松手自动贴边，静置时半藏在屏幕边缘）
 *  - 点击展开紧凑状态面板：引擎状态 / AI 会话状态 / 打开应用 / 虚拟屏 / 销毁屏 / 收起
 *  - 拖到屏幕底部区域松手 = 隐藏小鲸鱼（通知栏「显示小鲸鱼」可恢复）
 *  - 每 2 秒探测引擎端口；每 6 秒扫一次 /proc 统计"正在写入的会话"刷新 AI 状态
 *  - 需要 SYSTEM_ALERT_WINDOW（悬浮窗）权限；前台服务保活
 *
 * v1.13.12 大改（用户 15 条反馈里的悬浮窗部分）：
 *  ① 前台隐藏优先级最高 —— 之前虚拟屏预览「收起到小鲸鱼」的钉住状态会盖过前台隐藏，
 *     导致 App 里偶尔冒出小鲸鱼；现在 App 前台一律隐藏（预览仍由钉住状态保持拉帧）。
 *  ② 静置半藏：面板收起时小鲸鱼滑到屏幕边缘、只露一半（FLAG_LAYOUT_NO_LIMITS 允许越界）；
 *     点开面板/拖动时完整露出。收起与唤出都带旋转抖动动画。
 *  ③ 拖到底部隐藏：悬浮窗没有系统级的"拖底消失"（那是通知气泡的特权），
 *     这里自实现同款手势 + 通知栏恢复入口。
 *  ④ AI 状态不再走 HTTP：0.1.5 起接口要认证 cookie，悬浮窗拿不到（401 → 永远"空闲"）。
 *     改为扫 /proc/<同uid进程>/fd 里持着 dshhome/sessions/**​/session.lock 的文件描述符 ——
 *     内核的会话写入器持锁多久，fd 就开多久（dsh-session-persistence-jsonl 的 SessionWriteLease），
 *     这是"会话正在工作"的一手证据，无需任何认证。
 */
public class OverlayService extends Service {
    /**
     * 三版本共存的默认引擎端口，按包名区分（与 AccessibilityService 的口径一致）：
     * 正式版 3080 / Lite 3082 / 兼容版 3084。通知端口 = 引擎端口 + 1，无障碍端口 = +101。
     * 否则三套 App 同时安装会抢同一个 3080（表现为 EADDRINUSE、工具连到别的版本的服务）。
     */
    private static int defaultEnginePort(Context ctx) {
        String p = ctx != null ? ctx.getPackageName() : "";
        if (p.contains("beta")) return 3082;
        if (p.contains("compat")) return 3084;
        return 3080;
    }

    private static final String PREFS = "dsh_prefs";
    private static final String KEY_PORT = "engine_port";
    private static final String CHANNEL_ID = "dsh_overlay";
    private static final int NOTIF_ID = 9002;
    private static final long PROBE_MS = 2000L;
    /** 静置半藏时露在外面的比例（另一半越界到屏幕外）。 */
    private static final float TUCK_VISIBLE_FRACTION = 0.45f;
    /** 拖到距底部多少 dp 内松手 = 隐藏。 */
    private static final int DISMISS_ZONE_DP = 84;
    /** AI "已完成"提示在状态切换后保留的时长（毫秒）。 */
    private static final long FINISHED_TTL_MS = 60000L;

    /** 当前运行的 OverlayService 实例（供 MainActivity 前后台联动控制视图可见性）。 */
    private static OverlayService instance = null;

    /** 是否正在运行（供 MainActivity / HTTP 端点查询） */
    public static volatile boolean isRunning = false;
    /** 最近一次引擎探测结果 */
    public static volatile boolean engineUp = false;
    public static volatile long lastProbeAt = 0L;

    private WindowManager wm;
    private WindowManager.LayoutParams lp;
    private LinearLayout rootView;
    private ImageView iconView;
    private LinearLayout panelView;
    private TextView statusText;
    private TextView aiText;
    private Button destroyBtn;
    // v1.9 虚拟屏预览：悬浮窗实时显示虚拟屏画面（用户可看 AI 操作）
    private ImageView vscreenImageView = null;
    private volatile boolean vscreenPreviewRunning = false;
    private final Handler vscreenHandler = new Handler(Looper.getMainLooper());
    private final Handler handler = new Handler(Looper.getMainLooper());
    private int enginePort = 3080;

    private float touchX, touchY, startX, startY;
    private boolean dragging = false;
    private boolean panelVisible = false;
    /** 拖动中进入"拖底删除"暗示区（图标缩小变淡提示松手即隐藏）。 */
    private boolean dismissHint = false;
    /** 用户拖底主动隐藏后为 true；从通知栏「显示小鲸鱼」恢复。 */
    private volatile boolean userHidden = false;
    /** v1.13.11：悬浮图标当前吸附在右边？由拖动松手时的 snapToEdge() 决定。 */
    private boolean snappedRight = false;
    /** v1.13.11：App 在前台 → 悬浮窗应隐藏（由 MainActivity.onStart/onStop 维护）。 */
    private volatile boolean foregroundWantsHidden = true;
    /** v1.13.11：被虚拟屏预览「收起到小鲸鱼」钉住 —— 只负责持续拉预览帧，不再影响可见性。 */
    private volatile boolean vscreenPinned = false;
    /** 探测计数：每 PROBE_MS 探测一次引擎；每 3 次（约 6 秒）顺带扫一次会话写入器 */
    private int probeCount = 0;
    /** 最近一次扫到的"持锁会话"目录集合；null 表示还扫过。 */
    private volatile HashSet<String> activeSessions = new HashSet<String>();
    private volatile long finishedAt = 0L;

    public static int enginePort(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, MODE_PRIVATE);
        return sp.getInt(KEY_PORT, defaultEnginePort(ctx));
    }

    private final Runnable probeRunnable = new Runnable() {
        @Override public void run() {
            if (!isRunning) return;
            probeCount++;
            final boolean scanSessions = probeCount % 3 == 0;
            // 探测放后台线程：HttpURLConnection / /proc 扫描在主线程会卡界面
            new Thread(new Runnable() {
                @Override public void run() {
                    final boolean up = engineAlive(enginePort);
                    if (up && scanSessions) {
                        activeSessions = scanActiveSessions();
                        lastProbeAt = System.currentTimeMillis();
                    }
                    handler.post(new Runnable() {
                        @Override public void run() {
                            if (!isRunning) return;
                            engineUp = up;
                            updateEngineStatusUi();
                        }
                    });
                }
            }, "overlay-probe").start();
            handler.postDelayed(this, PROBE_MS);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        isRunning = true;
        instance = this;
        ShellLocale.init(this); // язык панели китёнка и уведомлений
        enginePort = enginePort(this);
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        startForegroundCompat();
        buildOverlay();
        addToWindow();
        // 前后台联动：App 前台时隐藏悬浮窗（不挡界面），退后台时显示
        foregroundWantsHidden = MainActivity.overlayForeground;   // v1.13.11：改为记状态再统一应用
        applyVisibleNow();
        handler.postDelayed(probeRunnable, 200);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 通知栏「显示小鲸鱼」：解除用户隐藏并抖一下示意
        if (intent != null && ACTION_SHOW.equals(intent.getAction())) {
            userHidden = false;
            applyVisibleNow();
            wiggle();
        }
        // 允许通过 intent 指定端口（如换端口后重启）
        if (intent != null && intent.hasExtra("port")) {
            enginePort = intent.getIntExtra("port", enginePort);
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt(KEY_PORT, enginePort).apply();
        }
        return START_STICKY;
    }

    private static final String ACTION_SHOW = "com.deepseek.harness.overlay.SHOW";

    @Override
    public void onDestroy() {
        isRunning = false;
        if (instance == this) instance = null;
        stopVscreenPreview();
        handler.removeCallbacksAndMessages(null);
        if (rootView != null && wm != null) {
            try { wm.removeView(rootView); } catch (Throwable ignored) {}
        }
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    /** 前台服务保活（引擎运行期间悬浮窗不被系统回收） */
    private void startForegroundCompat() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, ShellLocale.t("黑鲸鱼悬浮窗"),
                    NotificationManager.IMPORTANCE_LOW);
            ch.setDescription(ShellLocale.t("黑鲸鱼悬浮窗运行中（引擎状态指示）"));
            nm.createNotificationChannel(ch);
        }
        startForeground(NOTIF_ID, buildNotification());
    }

    /** 常驻通知（内容随引擎状态更新；常驻「显示小鲸鱼」动作，拖底隐藏后靠它找回）。 */
    private Notification buildNotification() {
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            b = new Notification.Builder(this, CHANNEL_ID);
        } else {
            b = new Notification.Builder(this);
        }
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent show = new Intent(this, OverlayService.class);
        show.setAction(ACTION_SHOW);
        PendingIntent showPi = PendingIntent.getService(this, 1, show,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        try { b.addAction(new Notification.Action.Builder(null, ShellLocale.t("显示小鲸鱼"), showPi).build()); }
        catch (Throwable ignored) {
            // 老系统 Action.Builder(null, ...) 不吃图标时退化：不显示动作也不影响主流程
        }
        return b.setContentTitle(ShellLocale.t("🐋 DeepSeek Harness 运行中"))
                .setContentText(ShellLocale.t("引擎状态：" + (engineUp ? "运行中（端口 " + enginePort + "）" : "未运行")))
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    private void buildOverlay() {
        // ===== 根布局（竖排：图标行 + 状态面板）=====
        rootView = new LinearLayout(this);
        rootView.setOrientation(LinearLayout.VERTICAL);
        rootView.setPadding(dp(10), dp(8), dp(10), dp(8));
        // 收起态无背景（只留小鲸鱼图标）；背景移到展开面板 panelView 上

        // ===== 图标行（小鲸鱼）=====
        LinearLayout iconRow = new LinearLayout(this);
        iconRow.setOrientation(LinearLayout.HORIZONTAL);
        iconRow.setGravity(Gravity.CENTER_VERTICAL);
        iconRow.setPadding(dp(4), dp(2), dp(4), dp(2));

        iconView = new ImageView(this);
        iconView.setImageResource(R.drawable.ic_whale_black); // DSH 鲸鱼（品牌蓝+白描边）
        iconView.setLayoutParams(new LinearLayout.LayoutParams(dp(40), dp(40)));
        iconRow.addView(iconView);
        rootView.addView(iconRow);

        // ===== 状态面板（紧凑版，默认隐藏）=====
        panelView = new LinearLayout(this);
        panelView.setOrientation(LinearLayout.VERTICAL);
        panelView.setPadding(dp(10), dp(8), dp(10), dp(8));
        GradientDrawable pbg = new GradientDrawable();
        pbg.setColor(getColor(R.color.panel_bg));              // 深蓝半透明（统一配色资源）
        pbg.setCornerRadius(dp(12));
        panelView.setBackground(pbg);

        statusText = new TextView(this);
        statusText.setText("状态：检测中…");
        statusText.setTextColor(getColor(R.color.panel_text_bright));
        statusText.setTextSize(TypedValue.COMPLEX_UNIT_PX, getResources().getDimension(R.dimen.text_caption));
        panelView.addView(statusText);

        // AI 会话状态（/proc 会话写入器扫描，每 ~6 秒刷新；v1.13.12 起不再是永远"空闲"）
        aiText = new TextView(this);
        aiText.setText("AI：—");
        aiText.setTextColor(getColor(R.color.panel_text_dim));
        aiText.setTextSize(TypedValue.COMPLEX_UNIT_PX, getResources().getDimension(R.dimen.text_caption));
        panelView.addView(aiText);

        // v1.9 虚拟屏预览（默认隐藏）：悬浮窗实时显示虚拟屏画面
        vscreenImageView = new ImageView(this);
        LinearLayout.LayoutParams vsp = new LinearLayout.LayoutParams(dp(176), dp(298));
        vsp.topMargin = dp(6);
        vscreenImageView.setLayoutParams(vsp);
        vscreenImageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        vscreenImageView.setVisibility(View.GONE);
        vscreenImageView.setBackgroundColor(0x88000000);
        panelView.addView(vscreenImageView);

        // v1.13.12：按钮重做 —— 面板不需要大按钮，两行小胶囊足够；端口行整体移除
        // （端口在控制台/通知里都有，天天显示在悬浮窗上没有信息量）。
        LinearLayout btnRow1 = new LinearLayout(this);
        btnRow1.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams r1p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        r1p.topMargin = dp(7);
        btnRow1.setLayoutParams(r1p);
        btnRow1.addView(pillButton("打开应用", new Runnable() { @Override public void run() {
            Intent i = new Intent(OverlayService.this, MainActivity.class);
            i.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
            try { startActivity(i); } catch (Throwable ignored) {}
            setPanelVisible(false, true);
        }}));
        btnRow1.addView(pillButton("虚拟屏", new Runnable() { @Override public void run() {
            // 预览窗被「收起到小鲸鱼」后的回程入口；没有虚拟屏时桥服务会静默忽略
            try { VsreenBridgeService.showPreviewFromWhale(); } catch (Throwable ignored) {}
            setPanelVisible(false, true);
        }}));

        LinearLayout btnRow2 = new LinearLayout(this);
        btnRow2.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams r2p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        r2p.topMargin = dp(5);
        btnRow2.setLayoutParams(r2p);
        // 销毁屏：替代旧预览窗上的 ✕（用户要求销毁功能收进小鲸鱼面板）
        destroyBtn = pillButton("销毁屏", new Runnable() { @Override public void run() {
            try { VsreenBridgeService.destroyVscreenFromWhale(); } catch (Throwable ignored) {}
            setPanelVisible(false, true);
        }});
        destroyBtn.setVisibility(View.GONE);
        btnRow2.addView(destroyBtn);
        btnRow2.addView(pillButton("收起", new Runnable() { @Override public void run() {
            setPanelVisible(false, true);
        }}));

        panelView.addView(btnRow1);
        panelView.addView(btnRow2);
        rootView.addView(panelView);
        ShellLocale.apply(rootView); // 面板标签/按钮（打开应用、虚拟屏、销毁屏、收起）
        setPanelVisible(false, false);

        // ===== 拖动 + 点击 + 拖底隐藏 =====
        rootView.setOnTouchListener(new View.OnTouchListener() {
            private long downAt = 0;
            @Override public boolean onTouch(View v, MotionEvent ev) {
                switch (ev.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        downAt = System.currentTimeMillis();
                        touchX = ev.getRawX(); touchY = ev.getRawY();
                        startX = lp.x; startY = lp.y;
                        dragging = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        if (Math.abs(ev.getRawX() - touchX) > dp(8) || Math.abs(ev.getRawY() - touchY) > dp(8)) {
                            dragging = true;
                        }
                        if (dragging) {
                            lp.x = (int) (startX + (ev.getRawX() - touchX));
                            lp.y = (int) (startY + (ev.getRawY() - touchY));
                            try { wm.updateViewLayout(rootView, lp); } catch (Throwable ignored) {}
                            updateDismissHint(ev.getRawY());
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (dragging && dismissHint) {
                            hideByDragToBottom();
                        } else if (dragging) {
                            snapToEdge();      // 拖完自动吸到最近的左右边缘（静置态=半藏）
                            if (panelVisible) setPanelVisible(true, false);
                        } else if (System.currentTimeMillis() - downAt < 400) {
                            setPanelVisible(!panelVisible, true);
                        }
                        setDismissHintInternal(false);
                        return true;
                    case MotionEvent.ACTION_OUTSIDE:
                        // 点击悬浮窗外区域：收回面板（回到半藏态）
                        if (panelVisible) setPanelVisible(false, true);
                        return true;
                }
                return false;
            }
        });
    }

    /** 面板里的小胶囊按钮（v1.13.12 重做：小、轻、不抢眼）。 */
    private Button pillButton(String text, final Runnable action) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_PX, getResources().getDimension(R.dimen.text_caption));
        b.setTextColor(getColor(R.color.accent_brand));
        b.setTypeface(null, Typeface.BOLD);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFFFFFFFF);
        bg.setCornerRadius(dp(11));
        b.setBackground(bg);
        b.setSingleLine(true);
        b.setPadding(dp(4), 0, dp(4), 0);
        b.setMinHeight(0);
        b.setMinWidth(0);
        b.setMinimumHeight(0);
        b.setMinimumWidth(0);
        b.setHeight(dp(25));
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(0, dp(25), 1f);
        lp2.leftMargin = dp(3);
        lp2.rightMargin = dp(3);
        b.setLayoutParams(lp2);
        b.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
            try { action.run(); } catch (Throwable ignored) {}
        }});
        return b;
    }

    // ==================== 可见性 / 贴边 / 动画 ====================

    /** 悬浮窗整体可见性（App 前台隐藏、退后台显示；服务常驻只切视图）。 */
    public static void setOverlayVisible(boolean show) {
        OverlayService s = instance;
        if (s != null) { s.foregroundWantsHidden = !show; s.applyVisibleNow(); }
    }

    /**
     * v1.13.11：虚拟屏预览「收起到小鲸鱼」。
     * v1.13.12 语义收窄：pin 只代表"预览帧继续在小鲸鱼面板里拉"，**不再强制可见** ——
     * 之前它会盖过前台隐藏，用户在 App 里也会看到小鲸鱼（报过"偶尔在 dsh 中也显示小鲸鱼"）。
     * @param pin true=开始拉虚拟屏画面到面板；false=停止
     */
    public static void pinForVscreen(boolean pin) {
        OverlayService s = instance;
        if (s != null) s.applyVscreenPin(pin);
    }

    private void applyVscreenPin(boolean pin) {
        try {
            vscreenPinned = pin;
            if (pin) startVscreenPreview();
            else stopVscreenPreview();
        } catch (Throwable ignored) {}
    }

    /**
     * 实际可见性。v1.13.12 起规则只有两条：
     * ① App 在前台（foregroundWantsHidden）→ 隐藏，无论虚拟屏是否钉住；
     * ② 用户拖底主动隐藏（userHidden）→ 隐藏，直到通知栏「显示小鲸鱼」。
     */
    private void applyVisibleNow() {
        try {
            if (rootView != null) {
                boolean show = !foregroundWantsHidden && !userHidden;
                rootView.setVisibility(show ? View.VISIBLE : View.GONE);
            }
        } catch (Throwable ignored) {}
    }

    private void addToWindow() {
        int type = Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        // FLAG_LAYOUT_NO_LIMITS：允许窗口越出屏幕边界 —— 静置"半藏"就靠它
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = dp(12);
        lp.y = dp(160);
        try {
            wm.addView(rootView, lp);
            // 只做一次"布局落定后摆正"。
            // ⚠ 不要在这里挂长期的 OnLayoutChangeListener：拖动窗口、以及"半藏"时
            // 系统重测宽度都会触发 layoutChange，长期监听会把 x 每帧拽回贴边位 ——
            // 表现就是"小鲸鱼横向拖不动、半藏也站不住"。而这里要修的只是"面板
            // 展开/收起瞬间 getWidth() 还是旧值"，一次性摆正就够。
            settleAfterLayout();
        } catch (Throwable t) {
            stopSelf();
        }
    }

    /**
     * v1.13.12：贴边 = 面板收起时把小鲸鱼**半藏**到屏幕边缘（露出约一半），
     * 面板展开时完整贴边（否则面板会被截掉）。
     */
    private void snapToEdge() {
        try {
            if (lp == null || rootView == null) return;
            int screenH = getResources().getDisplayMetrics().heightPixels;
            int w = rootView.getWidth() > 0 ? rootView.getWidth() : dp(60);
            int h = rootView.getHeight() > 0 ? rootView.getHeight() : dp(56);
            snappedRight = (lp.x + w / 2) > getResources().getDisplayMetrics().widthPixels / 2;
            lp.x = edgeXFor(w);
            if (lp.y < 0) lp.y = 0;
            if (lp.y > screenH - h) lp.y = Math.max(0, screenH - h);
            wm.updateViewLayout(rootView, lp);
        } catch (Throwable ignored) {}
    }

    /**
     * 贴边坐标：snappedRight 决定靠哪边；tucked 决定是否半藏。
     * 面板收起（静置）→ 半藏：x 让窗口越出屏幕 (1-露出的比例)；展开 → 完整可见 + 留 4dp 边距。
     */
    private int edgeXFor(int viewWidth) {
        int screenW = getResources().getDisplayMetrics().widthPixels;
        boolean tucked = !panelVisible;
        if (tucked) {
            int off = Math.round(viewWidth * (1f - TUCK_VISIBLE_FRACTION));
            return snappedRight ? screenW - viewWidth + off : -off;
        }
        return snappedRight ? Math.max(dp(4), screenW - viewWidth - dp(4)) : dp(4);
    }

    /** 面板显示/隐藏；animate=true 时带旋转抖动 + 位置过渡（唤出、收起共用）。 */
    private void setPanelVisible(boolean show, boolean animate) {
        panelVisible = show;
        if (panelView != null) panelView.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) refreshPanelDynamicRows();
        if (lp == null) return;
        lp.width = WindowManager.LayoutParams.WRAP_CONTENT;
        lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
        try { wm.updateViewLayout(rootView, lp); } catch (Throwable ignored) {}
        if (!animate) {
            snapToEdge();
        } else {
            wiggle();
        }
        // 展开/收起必然改变窗口尺寸，而 getWidth() 在下一次 layout 之前仍是旧值：
        // 用它算贴边坐标 → 展开时按"图标宽度"摆（面板被推出右边）、收起时按"面板宽度"
        // 算半藏位（整只鲸鱼被推出屏幕）。又因为 FLAG_LAYOUT_NO_LIMITS 系统不夹边界，
        // 算错就真的出屏。原实现用 rootView.post() 补救，但 post 只延后一条消息、
        // 常常仍在下一次 layout 之前，等于没夹。改为等布局真正落定后再算。
        settleAfterLayout();
    }

    /**
     * 布局真正落定后，按**真实尺寸**重算贴边位置。
     * 这是"小鲸鱼/面板跑出屏幕"的根因修法 —— 不能再依赖调用时刻的 getWidth()。
     */
    private void settleAfterLayout() {
        try {
            if (rootView == null) return;
            rootView.getViewTreeObserver().addOnGlobalLayoutListener(
                    new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
                @Override public void onGlobalLayout() {
                    try {
                        android.view.ViewTreeObserver vto = rootView.getViewTreeObserver();
                        if (vto.isAlive()) vto.removeOnGlobalLayoutListener(this);
                    } catch (Throwable ignored) {}
                    applyEdgePos(true);
                }
            });
        } catch (Throwable ignored) {
            applyEdgePos(false);
        }
    }

    /**
     * 按真实尺寸把窗口摆到正确的贴边位：
     * 面板展开 → 完整可见（留 4dp 边距）；面板收起 → 半藏（露出 TUCK_VISIBLE_FRACTION）。
     * @param animate 是否平滑过渡（布局刚落定时用 true，观感更顺）
     */
    private void applyEdgePos(boolean animate) {
        try {
            if (lp == null || rootView == null) return;
            int w = rootView.getWidth() > 0 ? rootView.getWidth() : dp(60);
            int h = rootView.getHeight() > 0 ? rootView.getHeight() : dp(56);
            int targetX = edgeXFor(w);
            int screenH = getResources().getDisplayMetrics().heightPixels;
            if (lp.y < 0) lp.y = 0;
            if (lp.y > screenH - h) lp.y = Math.max(0, screenH - h);
            if (!animate || lp.x == targetX) {
                lp.x = targetX;
                wm.updateViewLayout(rootView, lp);
                return;
            }
            final int fromX = lp.x;
            android.animation.ValueAnimator va =
                    android.animation.ValueAnimator.ofInt(fromX, targetX);
            va.setDuration(220);
            va.setInterpolator(new OvershootInterpolator(0.6f));
            va.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
                @Override public void onAnimationUpdate(android.animation.ValueAnimator a) {
                    if (lp == null || rootView == null) return;
                    lp.x = (Integer) a.getAnimatedValue();
                    try { wm.updateViewLayout(rootView, lp); } catch (Throwable ignored) {}
                }
            });
            va.start();
        } catch (Throwable ignored) {}
    }

    /** 面板每次展开时刷新"看场景才该出现"的行（如销毁屏按钮）。 */
    private void refreshPanelDynamicRows() {
        try {
            if (destroyBtn != null) {
                destroyBtn.setVisibility(VsreenBridgeService.sVscreenRunning
                        ? View.VISIBLE : View.GONE);
            }
        } catch (Throwable ignored) {}
    }

    /** 位置过渡：从当前 x 平滑滑到目标贴边位（半藏/完整随面板状态）。 */
    private void animateToEdge() {
        try {
            if (lp == null || rootView == null) return;
            int w = rootView.getWidth() > 0 ? rootView.getWidth() : dp(60);
            final int targetX = edgeXFor(w);
            final int fromX = lp.x;
            if (fromX == targetX) return;
            android.animation.ValueAnimator va =
                    android.animation.ValueAnimator.ofInt(fromX, targetX);
            va.setDuration(260);
            va.setInterpolator(new OvershootInterpolator(0.6f));
            va.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
                @Override public void onAnimationUpdate(android.animation.ValueAnimator a) {
                    if (lp == null || rootView == null) return;
                    lp.x = (Integer) a.getAnimatedValue();
                    try { wm.updateViewLayout(rootView, lp); } catch (Throwable ignored) {}
                }
            });
            va.start();
        } catch (Throwable ignored) {}
    }

    /** 旋转抖动动画（唤出/收起时的"小鲸鱼摆尾巴"）。只转图标，不转整块面板。 */
    private void wiggle() {
        try {
            if (iconView == null) return;
            android.animation.ObjectAnimator.ofFloat(
                    iconView, View.ROTATION, 0f, -14f, 11f, -8f, 5f, 0f)
                    .setDuration(420)
                    .start();
        } catch (Throwable ignored) {}
    }

    /** 拖动中：接近屏幕底部时给出"松手即隐藏"的暗示（图标缩小变淡）。 */
    private void updateDismissHint(float rawY) {
        int screenH = getResources().getDisplayMetrics().heightPixels;
        boolean inZone = rawY > screenH - dp(DISMISS_ZONE_DP);
        if (inZone != dismissHint) setDismissHintInternal(inZone);
    }

    private void setDismissHintInternal(boolean on) {
        dismissHint = on;
        try {
            if (iconView == null) return;
            iconView.animate().scaleX(on ? 0.62f : 1f).scaleY(on ? 0.62f : 1f)
                    .alpha(on ? 0.7f : 1f).setDuration(140).start();
        } catch (Throwable ignored) {}
    }

    /** 拖到底部松手：隐藏小鲸鱼（通知栏「显示小鲸鱼」可恢复）。 */
    private void hideByDragToBottom() {
        userHidden = true;
        try {
            rootView.animate().alpha(0f).scaleX(0.5f).scaleY(0.5f).setDuration(180)
                    .withEndAction(new Runnable() { @Override public void run() {
                        try {
                            rootView.setAlpha(1f);
                            setDismissHintInternal(false);
                            applyVisibleNow();
                        } catch (Throwable ignored) {}
                    }}).start();
        } catch (Throwable ignored) {
            applyVisibleNow();
        }
        try {
            android.widget.Toast.makeText(getApplicationContext(),
                    ShellLocale.t("小鲸鱼已隐藏，可从通知栏「显示小鲸鱼」恢复"), android.widget.Toast.LENGTH_LONG).show();
        } catch (Throwable ignored) {}
    }

    private void clampPanelOnScreen() {
        try {
            if (lp == null || rootView == null) return;
            int w = rootView.getWidth();
            if (w <= 0) return;
            if (!panelVisible) {
                lp.x = edgeXFor(w);
            } else {
                int maxX = getResources().getDisplayMetrics().widthPixels - w - dp(4);
                if (lp.x > maxX) lp.x = Math.max(dp(4), maxX);
            }
            wm.updateViewLayout(rootView, lp);
        } catch (Throwable ignored) {}
    }

    // ==================== 引擎探测 / AI 会话状态 ====================

    /** 探测引擎是否在跑。
     *  v1.13：0.1.5 起首页需要一次性 token —— 不带 token 返回 401 + 纯文本
     *  “dsh web authentication required…”，带有效 token 返回 303 跳转；两种都说明“引擎在跑”。 */
    private boolean engineAlive(int port) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/").openConnection();
            c.setConnectTimeout(1200);
            c.setReadTimeout(1500);
            c.setRequestProperty("User-Agent", "dsh-overlay-probe");
            c.setInstanceFollowRedirects(false);
            int code = c.getResponseCode();
            if (code == 303 || code == 302) return true;
            if (code == 401) return bodyContains(c, "dsh web authentication required");
            if (code < 200 || code >= 500) return false;
            InputStream in = c.getInputStream();
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int total = 0;
            int r;
            while ((r = in.read(chunk)) > 0 && total < 262144) {
                body.write(chunk, 0, r);
                total += r;
            }
            try { in.close(); } catch (Throwable ignored) {}
            return body.toString("UTF-8").contains("<title>DeepSeek Harness</title>");
        } catch (Throwable t) {
            return false;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** 读一小段响应正文（401 的正文在错误流里）。 */
    private boolean bodyContains(HttpURLConnection c, String needle) {
        try {
            InputStream in = null;
            try { in = c.getInputStream(); } catch (Throwable t) { in = c.getErrorStream(); }
            if (in == null) return false;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[2048];
            int r;
            while ((r = in.read(buf)) > 0 && out.size() < 8192) out.write(buf, 0, r);
            try { in.close(); } catch (Throwable ignored) {}
            return out.toString("UTF-8").contains(needle);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 扫出"正在被引擎写入"的会话集合（session 目录路径）。
     *
     * 机制依据：dsh-session-persistence-jsonl 的 SessionWriteLease 在会话写入器存活期间
     * 持有 <会话目录>/session.lock 的独占锁，释放 = 关闭 fd。所以
     * 「同 uid 进程的 /proc/<pid>/fd 里存在指向 session.lock 的 fd」⟺ 该会话正在工作。
     * node 是本 App 的子进程（同 uid），/proc 对同 uid 可读 —— findEnginePid 已验证过这条路。
     *
     * 旧实现走 POST /api/session.list：0.1.5 起要认证 cookie，悬浮窗拿不到（永远 401），
     * 表现就是"AI：空闲"永远不变。此扫描不需要任何认证。
     *
     * /proc 完全扫不动（被 SELinux 拦等极端情况）返回 null，调用方保持上次结果。
     */
    private HashSet<String> scanActiveSessions() {
        HashSet<String> out = new HashSet<String>();
        try {
            File[] procs = new File("/proc").listFiles();
            if (procs == null) return null;
            for (File d : procs) {
                String name = d.getName();
                if (name == null || name.isEmpty() || !Character.isDigit(name.charAt(0))) continue;
                File fdDir = new File(d, "fd");
                String[] fds;
                try { fds = fdDir.list(); } catch (Throwable t) { fds = null; }
                if (fds == null) continue;   // 别的 uid 的进程：无权读，跳过
                for (String fd : fds) {
                    String target;
                    try { target = new File(fdDir, fd).getCanonicalPath(); } catch (Throwable t) { continue; }
                    int i = target.indexOf("/dshhome/sessions/");
                    if (i < 0) continue;
                    if (!target.endsWith("/session.lock")) continue;
                    // 会话目录 = session.lock 所在目录（…/sessions/<cwd编码>/<session-id>）
                    out.add(target.substring(0, target.lastIndexOf('/')));
                }
            }
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 更新悬浮窗状态文字 + 常驻通知（在主线程调用）。 */
    private void updateEngineStatusUi() {
        if (statusText != null) {
            statusText.setText("状态：" + (engineUp ? "引擎运行中 ✓" : "引擎未运行"));
        }
        if (aiText != null) {
            aiText.setText(ShellLocale.t(aiStatusText()));
        }
        ShellLocale.apply(rootView); // 状态行「状态：… / AI：…」每次刷新后重译
        // 面板开着的话顺带刷新销毁屏按钮的可见性
        if (panelVisible) refreshPanelDynamicRows();
        // 更新常驻通知
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(NOTIF_ID, buildNotification());
        } catch (Throwable ignored) {}
    }

    /**
     * AI 状态行：空闲 / 工作中会话（可多个）/ 已完成。
     *  - 有持锁会话 → "工作中 N 个会话"；
     *  - 上次还工作中、现在没了 → 记一个"刚完成"时间点，60 秒内显示"已完成"；
     *  - 其余 → "空闲"。引擎不在跑时显示"—"。
     */
    private String aiStatusText() {
        if (!engineUp) return "AI：—";
        HashSet<String> cur = activeSessions;
        if (cur == null) return "AI：—";       // 还没扫过 / /proc 扫不了
        int n = cur.size();
        long now = System.currentTimeMillis();
        if (n > 0) {
            lastSessionsHadWork = true;   // 边沿触发源：从"有会话工作"变"没有"时报已完成
            finishedAt = 0L;
            return n == 1 ? "AI：1 个会话工作中…" : "AI：" + n + " 个会话工作中…";
        }
        if (finishedAt == 0L && lastSessionsHadWork) {
            finishedAt = now;
        }
        if (finishedAt > 0L && now - finishedAt < FINISHED_TTL_MS) {
            return "AI：会话已完成 ✓";
        }
        lastSessionsHadWork = false;
        return "AI：空闲";
    }
    /** 上次扫描是否看到过工作中的会话（用于"已完成"的边沿触发）。 */
    private volatile boolean lastSessionsHadWork = false;

    // ==================== v1.9 虚拟屏预览（悬浮窗实时看 AI 操作虚拟屏） ====================

    /** 开始虚拟屏预览：每 ~1s 拉 server /preview（base64 JPEG）并显示到悬浮窗。 */
    public void startVscreenPreview() {
        if (vscreenPreviewRunning) return;
        vscreenPreviewRunning = true;
        vscreenHandler.post(vscreenPreviewRunnable);
    }

    /** 停止虚拟屏预览。 */
    public void stopVscreenPreview() {
        vscreenPreviewRunning = false;
        vscreenHandler.removeCallbacks(vscreenPreviewRunnable);
        if (vscreenImageView != null) {
            vscreenHandler.post(new Runnable() {
                @Override public void run() {
                    vscreenImageView.setVisibility(View.GONE);
                }
            });
        }
    }

    /** 预览帧拉取任务：HTTP GET 127.0.0.1:8999/vscreen/preview → base64 JPEG → ImageView。 */
    private final Runnable vscreenPreviewRunnable = new Runnable() {
        @Override public void run() {
            if (!vscreenPreviewRunning || !isRunning) return;
            try {
                HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:8999/vscreen/preview").openConnection();
                c.setConnectTimeout(2000); c.setReadTimeout(2000);
                String resp = readAll(c.getInputStream());
                c.disconnect();
                JSONObject o = new JSONObject(resp);
                if (o.optBoolean("ok", false)) {
                    String b64 = o.optString("previewB64");
                    if (b64 != null && !b64.isEmpty()) {
                        byte[] bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT);
                        final android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                        if (bmp != null) {
                            vscreenHandler.post(new Runnable() {
                                @Override public void run() {
                                    if (vscreenImageView != null) {
                                        vscreenImageView.setImageBitmap(bmp);
                                        vscreenImageView.setVisibility(View.VISIBLE);
                                    }
                                }
                            });
                        }
                    }
                }
            } catch (Throwable ignored) {
                // server 未跑（虚拟屏未创建）时静默，预览保持隐藏
            }
            vscreenHandler.postDelayed(this, 1000);
        }
    };

    private String readAll(java.io.InputStream in) throws Exception {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] b = new byte[8192]; int n;
        while ((n = in.read(b)) > 0) bos.write(b, 0, n);
        return bos.toString("UTF-8");
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
