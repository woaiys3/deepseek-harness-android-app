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
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.Socket;
import java.net.URL;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 小鲸鱼悬浮窗服务：
 *  - 常驻悬浮小鲸鱼图标（可拖动）
 *  - 点击展开状态面板：引擎运行状态 / 端口 / 打开应用 / 收起
 *  - 每 2 秒探测引擎端口，实时刷新状态
 *  - 需要 SYSTEM_ALERT_WINDOW（悬浮窗）权限；前台服务保活
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
    private TextView portText;
    // v1.9 虚拟屏预览：悬浮窗实时显示虚拟屏画面（用户可看 AI 操作）
    private ImageView vscreenImageView = null;
    private volatile boolean vscreenPreviewRunning = false;
    private final Handler vscreenHandler = new Handler(Looper.getMainLooper());
    private final Handler handler = new Handler(Looper.getMainLooper());
    private int enginePort = 3080;

    private float touchX, touchY, startX, startY;
    private boolean dragging = false;
    private boolean panelVisible = false;
    /** v1.13.11：悬浮图标当前吸附在右边？由拖动松手时的 snapToEdge() 决定。 */
    private boolean snappedRight = false;
    /** v1.13.11：贴边时与屏幕边缘留的间隙（dp），避免被圆角/曲面边缘裁掉。 */
    private static final int EDGE_MARGIN = 4;
    /** v1.13.11：App 在前台 → 悬浮窗应隐藏（由 MainActivity.onStart/onStop 维护）。 */
    private boolean foregroundWantsHidden = true;
    /** v1.13.11：被虚拟屏预览「收起到小鲸鱼」钉住 —— 优先于前台隐藏。 */
    private volatile boolean vscreenPinned = false;
    /** 探测计数：每 PROBE_MS 探测一次引擎；每 3 次（约 6 秒）顺带拉一次会话信息 */
    private int probeCount = 0;
    private volatile boolean lastSessionRunning = false;

    public static int enginePort(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, MODE_PRIVATE);
        return sp.getInt(KEY_PORT, defaultEnginePort(ctx));
    }

    private final Runnable probeRunnable = new Runnable() {
        @Override public void run() {
            if (!isRunning) return;
            probeCount++;
            // 探测放后台线程：HttpURLConnection 在主线程会抛 NetworkOnMainThreadException
            new Thread(new Runnable() {
                @Override public void run() {
                    final boolean up = engineAlive(enginePort);
                    // 引擎在线时每 3 次探测拉一次会话信息（会话标题/AI 状态）
                    if (up && probeCount % 3 == 0) {
                        SessionInfo si = fetchSessionInfo();
                        if (si != null) {
                            lastSessionRunning = si.running;
                        }
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
        // 允许通过 intent 指定端口（如换端口后重启）
        if (intent != null && intent.hasExtra("port")) {
            enginePort = intent.getIntExtra("port", enginePort);
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt(KEY_PORT, enginePort).apply();
            if (portText != null) portText.setText("端口：: " + enginePort);
        }
        return START_STICKY;
    }

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
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "黑鲸鱼悬浮窗",
                    NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("黑鲸鱼悬浮窗运行中（引擎状态指示）");
            nm.createNotificationChannel(ch);
        }
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
        Notification n = b.setContentTitle("🐋 黑鲸鱼悬浮窗运行中")
                .setContentText("引擎状态：" + (engineUp ? "运行中 :" + enginePort : "未运行"))
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentIntent(pi)
                .build();
        startForeground(NOTIF_ID, n);
    }

    private void buildOverlay() {
        // ===== 根布局（竖排：图标行 + 状态面板）=====
        rootView = new LinearLayout(this);
        rootView.setOrientation(LinearLayout.VERTICAL);
        rootView.setPadding(dp(10), dp(8), dp(10), dp(8));
        // 收起态无背景（只留小鲸鱼图标）；背景移到展开面板 panelView 上

        // ===== 图标行（小鲸鱼 + 标题）=====
        LinearLayout iconRow = new LinearLayout(this);
        iconRow.setOrientation(LinearLayout.HORIZONTAL);
        iconRow.setGravity(Gravity.CENTER_VERTICAL);
        iconRow.setPadding(dp(4), dp(2), dp(4), dp(2));

        iconView = new ImageView(this);
        iconView.setImageResource(R.drawable.ic_whale_black); // DSH 官方黑鲸鱼（无背景）
        iconView.setLayoutParams(new LinearLayout.LayoutParams(dp(40), dp(40)));
        iconRow.addView(iconView);
        rootView.addView(iconRow);

        // ===== 状态面板（默认隐藏）=====
        panelView = new LinearLayout(this);
        panelView.setOrientation(LinearLayout.VERTICAL);
        panelView.setPadding(dp(4), dp(4), dp(4), dp(2));
        GradientDrawable pbg = new GradientDrawable();
        pbg.setColor(getColor(R.color.panel_bg));   // 深蓝半透明（colors.xml 统一管理）
        pbg.setCornerRadius(dp(16));
        panelView.setBackground(pbg);

        statusText = new TextView(this);
        statusText.setText("状态：检测中…");
        statusText.setTextColor(getColor(R.color.panel_text_bright));
        statusText.setTextSize(TypedValue.COMPLEX_UNIT_PX, getResources().getDimension(R.dimen.text_caption));
        panelView.addView(statusText);

        // AI 回复状态（session.list 的 running 字段，每 ~6 秒刷新）
        aiText = new TextView(this);
        aiText.setText("AI：—");
        aiText.setTextColor(getColor(R.color.panel_text_dim));
        aiText.setTextSize(TypedValue.COMPLEX_UNIT_PX, getResources().getDimension(R.dimen.text_caption));
        panelView.addView(aiText);

        // v1.9 虚拟屏预览（默认隐藏）：悬浮窗实时显示虚拟屏画面
        vscreenImageView = new ImageView(this);
        LinearLayout.LayoutParams vsp = new LinearLayout.LayoutParams(dp(240), dp(400));
        vsp.topMargin = dp(6);
        vscreenImageView.setLayoutParams(vsp);
        vscreenImageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        vscreenImageView.setVisibility(View.GONE);
        vscreenImageView.setBackgroundColor(0x88000000);
        vscreenImageView.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { /* 点一下无操作，可后续做全屏 */ }
        });
        panelView.addView(vscreenImageView);

        portText = new TextView(this);
        portText.setText("端口：" + enginePort);
        portText.setTextColor(getColor(R.color.panel_text_dim));
        portText.setTextSize(TypedValue.COMPLEX_UNIT_PX, getResources().getDimension(R.dimen.text_caption));
        panelView.addView(portText);

        // 按钮区：2×2 两行网格（weight 均分宽度）。原来是 4 个按钮挤一行，
        // 加「虚拟屏」后 1080px 宽的屏上「关闭」被挤出屏幕外（实测截图 p4_panel.png）。
        LinearLayout btnGrid = new LinearLayout(this);
        btnGrid.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams bgp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        bgp.topMargin = dp(6);
        btnGrid.setLayoutParams(bgp);

        LinearLayout btnRow1 = new LinearLayout(this);
        btnRow1.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout btnRow2 = new LinearLayout(this);
        btnRow2.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);

        Button openBtn = smallButton("打开应用");
        openBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Intent i = new Intent(OverlayService.this, MainActivity.class);
                i.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
                try { startActivity(i); } catch (Throwable ignored) {}
            }
        });
        btnRow1.addView(openBtn, gridCellLp());

        // v1.13.11：虚拟屏预览窗被「收起到小鲸鱼」后的回程入口 ——
        // 不看这个出口的话「收起」就等于把预览永久关掉（用户会以为功能坏了）。
        Button vsBtn = smallButton("虚拟屏");
        vsBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                try { VsreenBridgeService.showPreviewFromWhale(); } catch (Throwable ignored) {}
                setPanelVisible(false);
            }
        });
        btnRow1.addView(vsBtn, gridCellLp());

        Button collapseBtn = smallButton("收起");
        collapseBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { setPanelVisible(false); }
        });
        btnRow2.addView(collapseBtn, gridCellLp());

        Button closeBtn = smallButton("关闭");
        closeBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { stopSelf(); }
        });
        btnRow2.addView(closeBtn, gridCellLp());

        btnGrid.addView(btnRow1, rowLp);
        btnGrid.addView(btnRow2, rowLp);
        panelView.addView(btnGrid);
        rootView.addView(panelView);
        setPanelVisible(false);

        // ===== 拖动 + 点击 =====
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
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (dragging) {
                            snapToEdge();   // v1.13.11：拖完自动吸到最近的左右边缘
                        } else if (System.currentTimeMillis() - downAt < 400) {
                            setPanelVisible(!panelVisible);
                        }
                        return true;
                    case MotionEvent.ACTION_OUTSIDE:
                        // 点击悬浮窗外区域：收回面板
                        setPanelVisible(false);
                        return true;
                }
                return false;
            }
        });
    }

    /**
     * v1.13.11：悬浮小鲸鱼自动贴边 —— 拖动松手后吸到最近的屏幕左/右边缘，
     * 不再停在屏幕中间挡内容（用户报「图标要手动摆，摆完还挡着字」）。
     * 纵向只做越界夹取，不吸附，保留用户选的高度。
     */
    private void snapToEdge() {
        try {
            if (lp == null || rootView == null) return;
            int screenH = getResources().getDisplayMetrics().heightPixels;
            int w = rootView.getWidth() > 0 ? rootView.getWidth() : dp(40);
            int h = rootView.getHeight() > 0 ? rootView.getHeight() : dp(40);
            snappedRight = (lp.x + w / 2) > getResources().getDisplayMetrics().widthPixels / 2;
            lp.x = edgeXFor(w);
            if (lp.y < 0) lp.y = 0;
            if (lp.y > screenH - h) lp.y = Math.max(0, screenH - h);
            wm.updateViewLayout(rootView, lp);
        } catch (Throwable ignored) {}
    }

    /**
     * v1.13.11：展开面板后控件变宽 —— 贴右边缘时会把面板推出屏幕外。
     * 展开时夹回可见范围，收起时还原到贴边位置。（与悬浮预览窗的 clampPreviewBounds 同一思路。）
     */
    private void clampPanelOnScreen() {
        try {
            if (lp == null || rootView == null) return;
            int w = rootView.getWidth();
            if (w <= 0) return;
            if (!panelVisible) {
                lp.x = edgeXFor(w);
            } else {
                int maxX = getResources().getDisplayMetrics().widthPixels - w - dp(EDGE_MARGIN);
                if (lp.x > maxX) lp.x = Math.max(dp(EDGE_MARGIN), maxX);
            }
            wm.updateViewLayout(rootView, lp);
        } catch (Throwable ignored) {}
    }

    /** 贴边坐标：snappedRight 决定靠哪边。 */
    private int edgeXFor(int viewWidth) {
        int screenW = getResources().getDisplayMetrics().widthPixels;
        return snappedRight ? Math.max(dp(EDGE_MARGIN), screenW - viewWidth - dp(EDGE_MARGIN))
                            : dp(EDGE_MARGIN);
    }

    /** 2×2 按钮网格的单格参数：weight 均分剩余宽度，格间留 6dp 间距。 */
    private LinearLayout.LayoutParams gridCellLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp.leftMargin = dp(3);
        lp.rightMargin = dp(3);
        return lp;
    }

    private Button smallButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(TypedValue.COMPLEX_UNIT_PX, getResources().getDimension(R.dimen.text_caption));
        b.setTextColor(getColor(R.color.accent_brand));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFFFFFFFF);
        bg.setCornerRadius(dp(12));
        b.setBackground(bg);
        b.setPadding(dp(10), dp(4), dp(10), dp(4));
        b.setMinHeight(0);
        b.setMinWidth(0);
        b.setAllCaps(false);
        return b;
    }

    private void setPanelVisible(boolean show) {
        panelVisible = show;
        if (panelView != null) panelView.setVisibility(show ? View.VISIBLE : View.GONE);
        // 注意：buildOverlay() 在 addToWindow() 之前调用（此时 lp 可能为 null），必须判空
        if (lp != null) {
            lp.width = WindowManager.LayoutParams.WRAP_CONTENT;
            lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
            try { wm.updateViewLayout(rootView, lp); } catch (Throwable ignored) {}
            // v1.13.11：布局落定后再夹一次（展开后面板更宽，贴右边缘会被推出屏幕）
            if (rootView != null) {
                rootView.post(new Runnable() { @Override public void run() { clampPanelOnScreen(); } });
            }
        }
    }

    /** 悬浮窗整体可见性（App 前台隐藏、退后台显示；服务常驻只切视图）。 */
    public static void setOverlayVisible(boolean show) {
        OverlayService s = instance;
        if (s != null) { s.foregroundWantsHidden = !show; s.applyVisibleNow(); }
    }

    /**
     * v1.13.11：把桌面小鲸鱼钉住可见（虚拟屏预览「收起到小鲸鱼」用）。
     * App 在前台时小鲸鱼默认是隐藏的；预览收起后画面改由小鲸鱼承载，
     * 这时必须强制可见，否则用户点完「收起」什么都看不到。
     * @param pin true=钉住可见并开始拉虚拟屏画面；false=解除，恢复前后台联动
     */
    public static void pinForVscreen(boolean pin) {
        OverlayService s = instance;
        if (s != null) s.applyVscreenPin(pin);
    }

    private void applyVscreenPin(boolean pin) {
        try {
            vscreenPinned = pin;
            applyVisibleNow();
            if (pin) startVscreenPreview();
            else stopVscreenPreview();
        } catch (Throwable ignored) {}
    }

    /** 实际可见性 = 钉住 or 未被前台隐藏。 */
    private void applyVisibleNow() {
        try {
            if (rootView != null) {
                rootView.setVisibility((vscreenPinned || !foregroundWantsHidden) ? View.VISIBLE : View.GONE);
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
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = dp(12);
        lp.y = dp(160);
        try { wm.addView(rootView, lp); } catch (Throwable t) {
            stopSelf();
        }
    }

    /** 探测引擎是否在跑。
     *  v1.13：0.1.5 起首页需要一次性 token —— 不带 token 返回 401 + 纯文本
     *  “dsh web authentication required…”，带有效 token 返回 303 跳转；两种都说明“引擎在跑”。
     *  旧实现只认首页 HTML 里的 <title>DeepSeek Harness</title>，于是引擎明明在跑（401）
     *  也被判成“未运行” → 悬浮窗与常驻通知一直显示“未启动”（用户实测）。 */
    private boolean engineAlive(int port) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/").openConnection();
            c.setConnectTimeout(1200);
            c.setReadTimeout(1500);
            c.setRequestProperty("User-Agent", "dsh-overlay-probe");
            // 不跟随重定向：303 就是“token 有效”，跟随反而会把 token 浪费掉
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

    /** 会话信息（仅取 AI 回复状态 running） */
    private static class SessionInfo {
        boolean running;
    }

    /** 拉取最近会话信息：POST /api/session.list（标准 RPC 协议）。
     *  响应结构实测：{"type":"server-response","result":{"ok":true,"value":{"items":[...]}}}
     *  items[0] 字段：sessionId/updatedAt/running/blank/cwd/agentPreset（新会话无 title，blank=true）。
     *  无会话/失败返回 null，不抛异常（悬浮窗探测线程静默）。 */
    private SessionInfo fetchSessionInfo() {
        HttpURLConnection c = null;
        try {
            String rpcId = "ov-" + System.currentTimeMillis();
            String body = "{\"type\":\"client-request\",\"rpcId\":\"" + rpcId
                    + "\",\"method\":\"session.list\",\"payload\":{}}";
            c = (HttpURLConnection) new URL("http://127.0.0.1:" + enginePort + "/api/session.list").openConnection();
            c.setRequestMethod("POST");
            c.setRequestProperty("Content-Type", "application/json");
            c.setDoOutput(true);
            c.setConnectTimeout(1200);
            c.setReadTimeout(1500);
            c.getOutputStream().write(body.getBytes("UTF-8"));
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) return null;
            InputStream in = c.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] b = new byte[4096];
            int n;
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
            try { in.close(); } catch (Throwable ignored) {}
            return findSessionInfo(new String(out.toByteArray(), "UTF-8"));
        } catch (Throwable t) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** 解析 session.list 响应：result.value.items[0]，取 title + running。
     *  新会话（blank）无 title → 显示"新会话"。 */
    private SessionInfo findSessionInfo(String json) {
        try {
            JSONObject o = new JSONObject(json);
            JSONObject result = o.optJSONObject("result");
            if (result == null) result = o;
            JSONObject value = result.optJSONObject("value");
            if (value != null) result = value;
            JSONArray items = result.optJSONArray("items");
            if (items == null || items.length() == 0) return null;
            JSONObject s = items.optJSONObject(0);
            if (s == null) return null;
            SessionInfo info = new SessionInfo();
            info.running = s.optBoolean("running", false);
            return info;
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
            if (engineUp) {
                aiText.setText(lastSessionRunning ? "AI：回复中…" : "AI：空闲");
            } else {
                aiText.setText("AI：—");
            }
        }
        if (portText != null) {
            portText.setText("端口：: " + enginePort);
        }
        // 更新常驻通知
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) {
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
                Notification n = b.setContentTitle("🐋 黑鲸鱼悬浮窗运行中")
                        .setContentText("引擎状态：" + (engineUp ? "运行中 :" + enginePort : "未运行"))
                        .setSmallIcon(R.drawable.ic_launcher)
                        .setContentIntent(pi)
                        .build();
                nm.notify(NOTIF_ID, n);
            }
        } catch (Throwable ignored) {}
    }

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
