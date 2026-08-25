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
    private final Handler handler = new Handler(Looper.getMainLooper());
    private int enginePort = 3080;

    private float touchX, touchY, startX, startY;
    private boolean dragging = false;
    private boolean panelVisible = false;
    /** 探测计数：每 PROBE_MS 探测一次引擎；每 3 次（约 6 秒）顺带拉一次会话信息 */
    private int probeCount = 0;
    private volatile boolean lastSessionRunning = false;

    public static int enginePort(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, MODE_PRIVATE);
        return sp.getInt(KEY_PORT, 3080);
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
        applyVisible(!MainActivity.overlayForeground);
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
        pbg.setColor(0xE61A2140);              // 深蓝半透明
        pbg.setCornerRadius(dp(16));
        panelView.setBackground(pbg);

        statusText = new TextView(this);
        statusText.setText("状态：检测中…");
        statusText.setTextColor(0xFFE8EDFF);
        statusText.setTextSize(12);
        panelView.addView(statusText);

        // AI 回复状态（session.list 的 running 字段，每 ~6 秒刷新）
        aiText = new TextView(this);
        aiText.setText("AI：—");
        aiText.setTextColor(0xFF9DB4FF);
        aiText.setTextSize(12);
        panelView.addView(aiText);

        portText = new TextView(this);
        portText.setText("端口：: " + enginePort);
        portText.setTextColor(0xFF9DB4FF);
        portText.setTextSize(12);
        panelView.addView(portText);

        // 按钮行
        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams brp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        brp.topMargin = dp(6);
        btnRow.setLayoutParams(brp);

        Button openBtn = smallButton("打开应用");
        openBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Intent i = new Intent(OverlayService.this, MainActivity.class);
                i.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
                try { startActivity(i); } catch (Throwable ignored) {}
            }
        });
        btnRow.addView(openBtn);

        Button collapseBtn = smallButton("收起");
        collapseBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { setPanelVisible(false); }
        });
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cp.leftMargin = dp(6);
        collapseBtn.setLayoutParams(cp);
        btnRow.addView(collapseBtn);

        Button closeBtn = smallButton("关闭");
        closeBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { stopSelf(); }
        });
        LinearLayout.LayoutParams xp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        xp.leftMargin = dp(6);
        closeBtn.setLayoutParams(xp);
        btnRow.addView(closeBtn);

        panelView.addView(btnRow);
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
                        if (!dragging && System.currentTimeMillis() - downAt < 400) {
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

    private Button smallButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(12);
        b.setTextColor(0xFF4D6BFE);
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
        }
    }

    /** 悬浮窗整体可见性（App 前台隐藏、退后台显示；服务常驻只切视图）。 */
    public static void setOverlayVisible(boolean show) {
        OverlayService s = instance;
        if (s != null) s.applyVisible(show);
    }

    private void applyVisible(boolean show) {
        try {
            if (rootView != null) rootView.setVisibility(show ? View.VISIBLE : View.GONE);
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

    /** 探测引擎：请求首页并读完整页（≤256KB），含 <title>DeepSeek Harness</title> 才算运行中。
     *  与 MainActivity.isDshEngine 同款检测，避免首页较大时旧 Socket 16KB 探测误判「未启动」。 */
    private boolean engineAlive(int port) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/").openConnection();
            c.setConnectTimeout(1200);
            c.setReadTimeout(1500);
            c.setRequestProperty("User-Agent", "dsh-overlay-probe");
            int code = c.getResponseCode();
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

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
