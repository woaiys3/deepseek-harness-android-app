package com.deepseek.harness;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.AppOpsManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.DocumentsContract;
import android.provider.Settings;
import android.system.ErrnoException;
import android.system.Os;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import rikka.shizuku.Shizuku;

public class MainActivity extends Activity {
    private static final String TAG = "DeepSeekHarness";
    // 引擎端口：固定默认端口（v1.5.4 起移除「端口冲突自动换端口」功能，用于排查慢启动是否与其相关）。
    // 共存版（Lite/抢先版）各用独立默认端口，靠包名隔离，不依赖动态切换。
    private int enginePort = 3080;
    private String homeUrl() { return "http://127.0.0.1:" + enginePort; }
    // bin.js 相对 dshroot 目录的路径（dshroot 可能位于外部公共目录或内部 fallback）
    private static final String REL_BINJS = "lib/node_modules/@deepseek-ai/dsh/lib/bin.js";
    // 外部 dshroot 公共目录名（挂在 /sdcard 下，卸载不丢；node 二进制/凭证仍留内部）
    private static final String EXT_DSHROOT_ROOT = "DeepSeekHarness";
    // 官方维护、需随 APK 更新的路径前缀：即使外部 dshroot 已有同名文件也强制覆盖
    // （避免"保留 AI 修改"策略挡住官方修复，例如 shizuku 插件的三层补丁）。
    private static final String[] FORCE_OVERWRITE_PREFIXES = {
        "dshroot/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-tool-shizuku/",
        "dshroot/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-tool-android/",
        // v1.3.x 核心 UI 改动（侧栏改造/插件按钮）必须随 APK 覆盖：
        // 否则旧版升级用户的外部 dshroot 保留旧 client.js → 页面仍是旧 UI（无竖屏适配）
        "dshroot/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-client-ui-layout/lib/client.js",
        "dshroot/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-client-ui-cordis/lib/client.js",
        "dshroot/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-web-frontend/dist/mobile.css",
        "dshroot/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-web-frontend/dist/mobile.js",
        "dshroot/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-web-frontend/dist/index.html",
        "dshroot/lib/node_modules/@deepseek-ai/dsh/package.json"
    };
    // 外部 dshroot 解压完成标记（App 在 dshroot 补齐后写入；清空/重置时随目录删除）。
    // 用于识别「解压中途被打断」：即使 REVISION 一致也强制补齐缺失文件。
    private static final String DSHROOT_COMPLETE = ".complete";
    private static final String PREFS = "dsh_setup";
    private static final int REQ_STORAGE = 200;
    private static final int REQ_NOTIFICATION = 201;
    private static final int REQ_SHIZUKU = 300;
    private static final int REQ_WORKSPACE_TREE = 400;

    // 悬浮窗前后台联动：App 在前台时隐藏悬浮窗（不挡界面），退后台时显示（随时可查引擎状态）。
    // 由 onStart/onStop 维护；OverlayService 启动时按此标志决定初始可见性。
    public static volatile boolean overlayForeground = true;

    private WebView webView;
    private TextView statusView;
    private ProgressBar progressBar;
    private ImageView splashLogo;
    private TextView splashBrand;
    private final Handler ui = new Handler(Looper.getMainLooper());
    // 运行时确定的 dshroot 目录（外部公共目录优先，失败回退内部 files/payload/dshroot）
    private File dshrootDir = null;
    private boolean watchdogStarted = false;
    private long lastRespawnAt = 0L;
    // 引擎 node 进程
    private Process nodeProcess = null;
    // v1.5.2 慢启动修复：本次启动走了「快速同步」（同内核升级，只补白名单+REVISION）。
    // 若引擎启动超时，用它触发一次全量补齐（防止快速路径漏掉缺失文件）。
    private volatile boolean fastSyncedThisBoot = false;

    // 权限界面
    private final List<PermRow> permRows = new ArrayList<>();
    private File rishDex;
    // AI 工作区（可选）：外部共享存储目录，传给引擎作为 bash/文件工具的工作根目录
    private TextView workspaceDescView;


    private interface StatusProvider { boolean granted(); }
    private static class PermRow {
        TextView status;
        StatusProvider provider;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        installCrashHandler();
        checkAbiCompat(); // ② ABI 检测：非 arm64 设备引擎可能无法运行，弹提示
        checkBatteryOptimization(); // ④ 电池优化引导：被限制时提示（挂后台可能被杀）
        checkForUpdate(); // ⑧ 更新提示：GitHub 有新版时提示（后台线程，不阻塞启动）

        webView = new WebView(this);
        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setAllowFileAccess(true);
        ws.setDatabaseEnabled(true);
        ws.setUseWideViewPort(true);
        ws.setLoadWithOverviewMode(true);
        ws.setSupportZoom(false);
        ws.setBuiltInZoomControls(false);
        ws.setDisplayZoomControls(false);
        ws.setTextZoom(100);
        webView.setBackgroundColor(Color.parseColor("#0b0f1a"));
        checkWebViewCompat(); // WebView 兼容检测：老内核提示引导（DSH 前端需 Chromium 80+）
        webView.setWebViewClient(new android.webkit.WebViewClient() {
            private int errorRetries = 0;

            @Override
            public void onReceivedError(WebView view, android.webkit.WebResourceRequest request,
                                         android.webkit.WebResourceError error) {
                // 主框架加载失败（如 ERR_CONNECTION_REFUSED）时自动重试，直到服务器就绪
                if (request != null && request.isForMainFrame() && errorRetries < 120) {
                    errorRetries++;
                    final WebView wv = view;
                    view.postDelayed(new Runnable() {
                        @Override public void run() { wv.loadUrl(homeUrl()); }
                    }, 2500L);
                }
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                errorRetries = 0;
            }
        });

        statusView = new TextView(this);
        statusView.setText("正在启动 DeepSeek Harness…");
        statusView.setTextColor(Color.parseColor("#e6edf3"));
        statusView.setTextSize(15);
        statusView.setGravity(Gravity.CENTER);
        statusView.setPadding(dp(24), dp(12), dp(24), dp(12));

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setProgress(0);
        progressBar.setVisibility(View.GONE);

        // 提取 rish dex（DSH 的 shizuku_shell 插件执行命令用，与 payload 解压解耦）
        rishDex = extractRishDex();

        // Shizuku API：监听 binder 与授权结果（实现授权弹窗）
        try {
            Shizuku.addBinderReceivedListenerSticky(new Shizuku.OnBinderReceivedListener() {
                @Override public void onBinderReceived() { probeShizuku(); }
            });
            Shizuku.addBinderDeadListener(new Shizuku.OnBinderDeadListener() {
                @Override public void onBinderDead() { shizukuOk = false; refreshAllStatuses(); }
            });
            Shizuku.addRequestPermissionResultListener(new Shizuku.OnRequestPermissionResultListener() {
                @Override public void onRequestPermissionResult(int requestCode, int grantResult) {
                    if (grantResult == PackageManager.PERMISSION_GRANTED) {
                        shizukuOk = true;
                    }
                    refreshAllStatuses();
                }
            });
        } catch (Throwable t) {
            Log.w(TAG, "Shizuku listener init failed", t);
        }

        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (prefs.getBoolean("setup_done", false)) {
            // 定时任务自动执行：闹钟到点可能带着 scheduledTask extra 启动本 Activity
            Intent in = getIntent();
            if (in != null) {
                String task = in.getStringExtra("scheduledTask");
                if (task != null && !task.isEmpty()) pendingScheduledTask = task;
            }
            showEngineScreen();
            startEngine();
        } else {
            showPermissionScreen();
        }
    }

    // 定时任务自动执行：闹钟到点带来的任务文本（引擎就绪后自动 prompt 执行）
    private String pendingScheduledTask = null;

    // ============ WebView 兼容检测（老安卓 WebView 缺失/过旧） ============
    /** DSH 前端是 Vite 构建的现代应用（<script type="module"> + 可选链/nullish），
     *  需要 Chromium 80+ 才能渲染；Android 7/8 出厂 WebView（Chromium 51/59）或长期未更新的
     *  系统 WebView 会白屏，用户误以为「引擎启动失败」。检测到过旧版本时弹提示引导，
     *  不阻断启动（引擎本身与 WebView 无关，node 进程照常拉起）。 */
    private void checkWebViewCompat() {
        try {
            int chrome = parseChromeMajor(webView.getSettings().getUserAgentString());
            // UA 无 Chrome 标记时（部分 ROM 魔改 UA），API 26+ 用 WebView 包版本兜底
            if (chrome <= 0 && Build.VERSION.SDK_INT >= 26) {
                try {
                    android.content.pm.PackageInfo pi = WebView.getCurrentWebViewPackage();
                    if (pi != null && pi.versionName != null) {
                        chrome = parseChromeMajor(pi.versionName);
                    }
                } catch (Throwable ignored) {}
            }
            if (chrome <= 0 || chrome >= 80) return; // 拿不到版本或够新 → 不打扰
            final int ver = chrome;
            ui.post(new Runnable() {
                @Override public void run() {
                    try {
                        new AlertDialog.Builder(MainActivity.this)
                                .setTitle("系统 WebView 版本过旧")
                                .setMessage("检测到系统 WebView 内核为 Chromium " + ver
                                        + "（DSH 界面需要 80 以上）。\n\n"
                                        + "界面可能无法正常显示（白屏/无法交互），引擎本身不受影响。\n\n"
                                        + "建议：① 更新\"Android System WebView\"后重试；"
                                        + "② 安装「DeepSeek Harness 兼容版」（专为老设备优化）。")
                                .setPositiveButton("去更新", new DialogInterface.OnClickListener() {
                                    @Override public void onClick(DialogInterface d, int w) { openWebViewUpdate(); }
                                })
                                .setNegativeButton("继续尝试", null)
                                .show();
                    } catch (Throwable ignored) {}
                }
            });
        } catch (Throwable ignored) {}
    }

    /** 从 UA（"... Chrome/51.0.2704.81 ..."）或版本名（"80.0.3987.149"）解析主版本号。 */
    private int parseChromeMajor(String s) {
        if (s == null) return -1;
        int i = s.indexOf("Chrome/");
        int base = 0;
        if (i < 0) { i = s.indexOf("Chrome "); if (i < 0) return -1; base = "Chrome ".length(); }
        else { base = "Chrome/".length(); }
        int start = i + base;
        int e = start;
        while (e < s.length() && Character.isDigit(s.charAt(e))) e++;
        if (e == start) return -1;
        try { return Integer.parseInt(s.substring(start, e)); } catch (Throwable t) { return -1; }
    }

    /** 引导更新系统 WebView：优先系统 WebView 设置页，失败兜底应用商店。 */
    private void openWebViewUpdate() {
        try {
            Intent i = new Intent("android.settings.WEBVIEW_SETTINGS");
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            return;
        } catch (Throwable ignored) {}
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.google.android.webview"));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Throwable ignored) {}
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private int sp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().scaledDensity);
    }

    // ============ 权限引导界面 ============
    private void detachView(View v) {
        if (v != null && v.getParent() != null) {
            ((ViewGroup) v.getParent()).removeView(v);
        }
    }

    private void showEngineScreen() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.parseColor("#0b0f1a"));
        // 成员视图（webView/statusView/progressBar）可能已挂在旧容器上，先全部摘下，避免重复挂载崩溃。
        detachView(webView);
        detachView(statusView);
        detachView(progressBar);
        root.addView(webView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);

        // 鲸鱼 logo
        splashLogo = new ImageView(this);
        splashLogo.setImageResource(R.drawable.ic_launcher);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(dp(92), dp(92));
        llp.gravity = Gravity.CENTER_HORIZONTAL;
        llp.bottomMargin = dp(22);
        box.addView(splashLogo, llp);

        // 品牌名
        splashBrand = new TextView(this);
        splashBrand.setText("DeepSeek Harness");
        splashBrand.setTextColor(Color.parseColor("#f0f6fc"));
        splashBrand.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        splashBrand.setTypeface(null, android.graphics.Typeface.BOLD);
        splashBrand.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        blp.gravity = Gravity.CENTER_HORIZONTAL;
        blp.bottomMargin = dp(26);
        box.addView(splashBrand, blp);

        // 状态文字
        box.addView(statusView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // 进度条（深色主题：亮蓝进度 + 暗灰轨道）
        android.content.res.ColorStateList tint = android.content.res.ColorStateList.valueOf(Color.parseColor("#4d6bfe"));
        progressBar.setProgressTintList(tint);
        progressBar.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#1f2733")));
        LinearLayout.LayoutParams pbp = new LinearLayout.LayoutParams(dp(260), dp(6));
        pbp.topMargin = dp(18);
        pbp.gravity = Gravity.CENTER_HORIZONTAL;
        box.addView(progressBar, pbp);

        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        bp.gravity = Gravity.CENTER;
        root.addView(box, bp);

        // 浮动退出按钮（右上角）：点击确认后退出 deepdive
        Button exitBtn = new Button(this);
        exitBtn.setText("退出");
        exitBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        exitBtn.setTextColor(Color.WHITE);
        exitBtn.setAllCaps(false);
        exitBtn.setBackgroundColor(Color.parseColor("#66000000"));
        exitBtn.setPadding(dp(12), dp(4), dp(12), dp(4));
        FrameLayout.LayoutParams ebp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        ebp.gravity = Gravity.TOP | Gravity.END;
        ebp.topMargin = dp(28);
        ebp.rightMargin = dp(12);
        exitBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { confirmExit(); }
        });
        root.addView(exitBtn, ebp);

        setContentView(root);
    }

    /** 退出确认对话框（浮动按钮与系统返回键共用） */
    private void confirmExit() {
        new AlertDialog.Builder(this)
                .setTitle("退出 deepdive")
                .setMessage("确定要退出吗？服务器将停止运行。")
                .setPositiveButton("退出", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        stopKeepAliveService(); // 用户主动退出：停止保活服务
                        finish();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ============ 界面主题色（跟随系统深/浅色，权限页与加载页共用）============
    private boolean isDark() {
        int m = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return m == Configuration.UI_MODE_NIGHT_YES;
    }
    private int cBg() { return Color.parseColor(isDark() ? "#0b0f1a" : "#f7f8fb"); }
    private int cCard() { return Color.parseColor(isDark() ? "#161c2a" : "#ffffff"); }
    private int cText() { return Color.parseColor(isDark() ? "#e6edf3" : "#1f2328"); }
    private int cSub() { return Color.parseColor(isDark() ? "#8b98a9" : "#6b7280"); }
    private int cGreen() { return Color.parseColor("#1f9d6b"); }
    private int cRed() { return Color.parseColor("#d9503f"); }

    private long deleteRecursive(File f) {
        if (f == null || !f.exists()) return 0;
        long total = 0;
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) for (File c : children) total += deleteRecursive(c);
        }
        total += f.length();
        if (!f.delete()) {
            // 删除失败（通常是目录仍非空，因子项删除失败）。再递归扫一遍重试。
            if (f.isDirectory()) {
                File[] children = f.listFiles();
                if (children != null) for (File c : children) total += deleteRecursive(c);
            }
            f.delete();
        }
        return total;
    }

    // ② ABI 检测：node 引擎仅 arm64，非 arm64 设备会启动失败——尽早提示用户
    private void checkAbiCompat() {
        try {
            if (Build.SUPPORTED_ABIS == null || Build.SUPPORTED_ABIS.length == 0) return;
            String abi = Build.SUPPORTED_ABIS[0];
            boolean arm64 = abi.startsWith("arm64") || abi.contains("arm64-v8a");
            if (arm64) return; // 支持，正常继续
            // 32 位设备：引擎（node arm64 二进制）无法运行，提示但不阻止（用户可能知道自己在做什么）
            ui.post(new Runnable() {
                @Override public void run() {
                    try {
                        new AlertDialog.Builder(MainActivity.this)
                                .setTitle("设备架构不受支持")
                                .setMessage("当前设备为 32 位（" + abi + "），而 DSH 引擎仅支持 64 位（arm64）。\n\nAI 引擎可能无法启动，建议更换 64 位设备使用。")
                                .setNegativeButton("知道了", null)
                                .show();
                    } catch (Throwable ignored) {}
                }
            });
        } catch (Throwable t) {
            Log.w(TAG, "checkAbiCompat error", t);
        }
    }

    // ④ 电池优化引导：App 被系统限制后台时，引擎挂后台可能被杀——提示用户设"不限制"
    private void checkBatteryOptimization() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm == null) return;
            if (pm.isIgnoringBatteryOptimizations(getPackageName())) return; // 已"不限制"，正常
            ui.post(new Runnable() {
                @Override public void run() {
                    try {
                        new AlertDialog.Builder(MainActivity.this)
                                .setTitle("建议：允许后台运行")
                                .setMessage("当前应用被系统限制后台活动，AI 执行任务时挂后台可能被系统杀掉。\n\n建议将本应用设为「不限制」电池优化，确保任务持续运行。")
                                .setPositiveButton("去设置", new DialogInterface.OnClickListener() {
                                    @Override public void onClick(DialogInterface d, int w) {
                                        openSystemSetting(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                                    }
                                })
                                .setNegativeButton("暂不", null)
                                .show();
                    } catch (Throwable ignored) {}
                }
            });
        } catch (Throwable t) {
            Log.w(TAG, "checkBatteryOptimization error", t);
        }
    }

    // ⑧ 更新提示：后台查 GitHub Releases 最新 tag，与本地 versionName 比对，有新版弹提示
    private void checkForUpdate() {
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    URL url = new URL("https://api.github.com/repos/woaiys3/deepseek-harness-android-app/releases/latest");
                    HttpURLConnection c = (HttpURLConnection) url.openConnection();
                    c.setConnectTimeout(5000);
                    c.setReadTimeout(5000);
                    c.setRequestProperty("User-Agent", "dsh-android");
                    int code = c.getResponseCode();
                    if (code != 200) { c.disconnect(); return; }
                    InputStream in = c.getInputStream();
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] b = new byte[4096];
                    int n;
                    while ((n = in.read(b)) > 0) out.write(b, 0, n);
                    in.close();
                    c.disconnect();
                    String json = new String(out.toByteArray(), "UTF-8");
                    // 解析 "tag_name":"vX.Y.Z"
                    String tag = null;
                    int ti = json.indexOf("\"tag_name\"");
                    if (ti >= 0) {
                        int q1 = json.indexOf('"', ti + 10);
                        int q2 = q1 >= 0 ? json.indexOf('"', q1 + 1) : -1;
                        if (q1 >= 0 && q2 > q1) tag = json.substring(q1 + 1, q2);
                    }
                    if (tag == null || tag.isEmpty()) return;
                    String latest = tag.replace("v", "").replace("-lite", "").replace("-beta", "");
                    String local = "";
                    try { local = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Throwable ignored) {}
                    // 只比较主版本号（数字部分），忽略后缀
                    final String fLocal = local;
                    if (isNewerVersion(latest, fLocal)) {
                        final String ftag = tag;
                        ui.post(new Runnable() {
                            @Override public void run() {
                                try {
                                    new AlertDialog.Builder(MainActivity.this)
                                            .setTitle("发现新版本 " + ftag)
                                            .setMessage("当前版本 " + fLocal + "，最新 " + ftag + "。\n\n前往 GitHub Releases 下载更新（正式版 / Lite 共存版可选）。")
                                            .setPositiveButton("去下载", new DialogInterface.OnClickListener() {
                                                @Override public void onClick(DialogInterface d, int w) {
                                                    try {
                                                        startActivity(new Intent(Intent.ACTION_VIEW,
                                                                Uri.parse("https://github.com/woaiys3/deepseek-harness-android-app/releases")));
                                                    } catch (Throwable ignored) {}
                                                }
                                            })
                                            .setNegativeButton("稍后", null)
                                            .show();
                                } catch (Throwable ignored) {}
                            }
                        });
                    }
                } catch (Throwable t) {
                    // 网络失败/离线时静默跳过（不打扰用户）
                }
            }
        }, "update-check").start();
    }

    /** 简单版本号比较："1.4.0" vs "1.3.3" → true（1.4.0 更新）。 */
    private boolean isNewerVersion(String latest, String local) {
        try {
            String[] a = latest.split("\\.");
            String[] b = (local == null ? "" : local).split("\\.");
            for (int i = 0; i < Math.max(a.length, b.length); i++) {
                int x = i < a.length ? parseIntSafe(a[i]) : 0;
                int y = i < b.length ? parseIntSafe(b[i]) : 0;
                if (x != y) return x > y;
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    private int parseIntSafe(String s) {
        // 版本段可能带后缀（如 "5-test"/"5-lite"）：提取前导数字，避免 1.6.5-test 被误判为低于 1.6.1
        if (s == null) return 0;
        int i = 0;
        String t = s.trim();
        while (i < t.length() && Character.isDigit(t.charAt(i))) i++;
        if (i == 0) return 0;
        try { return Integer.parseInt(t.substring(0, i)); } catch (Throwable ex) { return 0; }
    }

    // 捕获未处理异常，写到外部崩溃日志（便于无 adb 时排查闪退）
    private void installCrashHandler() {
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override public void uncaughtException(Thread t, Throwable e) {
                try {
                    File dir = new File(Environment.getExternalStorageDirectory(), EXT_DSHROOT_ROOT);
                    if (!dir.exists()) dir.mkdirs();
                    File f = new File(dir, "crash.log");
                    FileOutputStream fos = new FileOutputStream(f, true);
                    String s = "\n==== " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date())
                            + " thread=" + t.getName() + " ====\n";
                    fos.write(s.getBytes("UTF-8"));
                    java.io.StringWriter sw = new java.io.StringWriter();
                    e.printStackTrace(new java.io.PrintWriter(sw));
                    fos.write(sw.toString().getBytes("UTF-8"));
                    fos.close();
                } catch (Throwable ignored) {}
                if (prev != null) prev.uncaughtException(t, e);
                else android.os.Process.killProcess(android.os.Process.myPid());
            }
        });
    }

    // 清理外部公共目录下遗留的 .trash-* 垃圾目录（清空数据 rename 后后台删除未完成）。
    private void cleanupTrashDirs(File externalRoot) {
        File[] children = externalRoot.listFiles();
        if (children == null) return;
        for (File c : children) {
            if (c.isDirectory() && c.getName().startsWith(".trash-")) {
                deleteRecursive(c);
            }
        }
    }

    private void showPermissionScreen() {
        permRows.clear();

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(cBg());

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(24), dp(20), dp(24), dp(24));
        scroll.addView(col, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));

        // 标题
        TextView title = new TextView(this);
        title.setText("首次使用 · 配置手机权限");
        title.setTextColor(cText());
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        col.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("在进入 DeepSeek Harness 之前，请先授权以下能力。\n配好后点底部「开始使用」才会解压运行时。");
        subtitle.setTextColor(cSub());
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        subtitle.setPadding(0, dp(8), 0, dp(16));
        col.addView(subtitle);

        // 权限项
        addPermRow(col, "存储权限", "读写手机文件、导入导出内容。",
                new StatusProvider() {
                    @Override public boolean granted() {
                        return checkSelfPermission("android.permission.READ_EXTERNAL_STORAGE") == PackageManager.PERMISSION_GRANTED
                                && checkSelfPermission("android.permission.WRITE_EXTERNAL_STORAGE") == PackageManager.PERMISSION_GRANTED;
                    }
                },
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        requestPermissions(new String[]{
                                "android.permission.READ_EXTERNAL_STORAGE",
                                "android.permission.WRITE_EXTERNAL_STORAGE"}, REQ_STORAGE);
                    }
                });

        addPermRow(col, "所有文件访问", "访问手机所有文件（Android 11 及以上需单独授权，11 以下由存储权限覆盖）。",
                new StatusProvider() {
                    @Override public boolean granted() {
                        if (Build.VERSION.SDK_INT >= 30) {
                            return Environment.isExternalStorageManager();
                        } else {
                            return checkSelfPermission("android.permission.WRITE_EXTERNAL_STORAGE") == PackageManager.PERMISSION_GRANTED;
                        }
                    }
                },
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        if (Build.VERSION.SDK_INT >= 30) {
                            try {
                                Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                                i.setData(Uri.parse("package:" + getPackageName()));
                                startActivity(i);
                            } catch (Exception e) {
                                try {
                                    startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                                } catch (Exception e2) {
                                    Log.w(TAG, "无法打开所有文件访问设置", e2);
                                }
                            }
                        } else {
                            requestPermissions(new String[]{
                                    "android.permission.READ_EXTERNAL_STORAGE",
                                    "android.permission.WRITE_EXTERNAL_STORAGE"}, REQ_STORAGE);
                        }
                    }
                });

        addPermRow(col, "悬浮窗", "让 AI 和工具能在其它应用之上显示内容。",
                new StatusProvider() {
                    @Override public boolean granted() { return Settings.canDrawOverlays(MainActivity.this); }
                },
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        openSystemSetting(Settings.ACTION_MANAGE_OVERLAY_PERMISSION);
                    }
                });

        addPermRow(col, "修改系统设置", "允许读写系统设置（亮度、音量、常亮等）。",
                new StatusProvider() {
                    @Override public boolean granted() { return Settings.System.canWrite(MainActivity.this); }
                },
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        openSystemSetting(Settings.ACTION_MANAGE_WRITE_SETTINGS);
                    }
                });

        addPermRow(col, "使用情况访问", "查看应用使用时长与统计信息。",
                new StatusProvider() {
                    @Override public boolean granted() {
                        AppOpsManager ops = (AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
                        int mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), getPackageName());
                        return mode == AppOpsManager.MODE_ALLOWED;
                    }
                },
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        openSystemSetting(Settings.ACTION_USAGE_ACCESS_SETTINGS);
                    }
                });

        addPermRow(col, "安装未知来源应用", "允许安装 APK（侧载、AI 帮你装应用）。",
                new StatusProvider() {
                    @Override public boolean granted() {
                        if (Build.VERSION.SDK_INT < 26) return true;
                        return getPackageManager().canRequestPackageInstalls();
                    }
                },
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        openSystemSetting(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES);
                    }
                });

        addPermRow(col, "忽略电池优化", "后台常驻不被系统杀掉（保持服务在线）。",
                new StatusProvider() {
                    @Override public boolean granted() {
                        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                        return pm.isIgnoringBatteryOptimizations(getPackageName());
                    }
                },
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        openSystemSetting(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                    }
                });

        addPermRow(col, "通知权限", "接收 AI 完成、提醒等通知。",
                new StatusProvider() {
                    @Override public boolean granted() {
                        if (Build.VERSION.SDK_INT < 33) return true;
                        return checkSelfPermission("android.permission.POST_NOTIFICATIONS") == PackageManager.PERMISSION_GRANTED;
                    }
                },
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        if (Build.VERSION.SDK_INT >= 33) {
                            if (checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
                                requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, REQ_NOTIFICATION);
                            } else {
                                // 已授权，跳到应用通知设置
                                try {
                                    Intent i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
                                    i.putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
                                    startActivity(i);
                                } catch (Exception e) {
                                    openSystemSetting(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
                                }
                            }
                        } else {
                            openSystemSetting(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
                        }
                    }
                });

        addPermRow(col, "Shizuku / Root 特权（可选）", "不授权也能正常使用：文件读写、预览、编辑只需「所有文件访问」权限。授权后可让 AI 执行系统级操作（安装/卸载应用、改系统设置、模拟点击等）。",
                new StatusProvider() {
                    @Override public boolean granted() {
                        // 只读缓存：root 探测在后台线程执行（probeShizuku），不在主线程跑 su
                        return (shizukuOk != null && shizukuOk) || (rootOk != null && rootOk);
                    }
                },
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        showShizukuDialog();
                    }
                });

        // ===== AI 工作区（可选）：选择外部共享存储文件夹作为 AI 文件操作的工作根目录 =====
        LinearLayout wsRow = new LinearLayout(this);
        wsRow.setOrientation(LinearLayout.HORIZONTAL);
        wsRow.setGravity(Gravity.CENTER_VERTICAL);
        wsRow.setPadding(dp(16), dp(14), dp(16), dp(14));
        wsRow.setBackgroundColor(cCard());
        LinearLayout.LayoutParams wslp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        wslp.bottomMargin = dp(10);
        wsRow.setLayoutParams(wslp);

        LinearLayout wsLeft = new LinearLayout(this);
        wsLeft.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams wsllp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        wsLeft.setLayoutParams(wsllp);

        TextView wsTitle = new TextView(this);
        wsTitle.setText("AI 工作区（可选）");
        wsTitle.setTextColor(cText());
        wsTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        wsTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        wsLeft.addView(wsTitle);

        workspaceDescView = new TextView(this);
        workspaceDescView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        workspaceDescView.setTextColor(cSub());
        workspaceDescView.setPadding(0, dp(3), 0, 0);
        wsLeft.addView(workspaceDescView);

        wsRow.addView(wsLeft);
        wsRow.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { onWorkspaceRowClick(); }
        });
        col.addView(wsRow);

        // 开始使用按钮
        Button start = new Button(this);
        start.setText("开始使用");
        start.setTextColor(Color.WHITE);
        start.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        start.setBackgroundColor(Color.parseColor("#4d6bfe"));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(52));
        lp.topMargin = dp(20);
        start.setLayoutParams(lp);
        start.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("setup_done", true).apply();
                showEngineScreen();
                startEngine();
            }
        });
        col.addView(start);

        TextView skip = new TextView(this);
        skip.setText("部分权限可稍后在系统设置中开启");
        skip.setTextColor(cSub());
        skip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        skip.setGravity(Gravity.CENTER);
        skip.setPadding(0, dp(10), 0, 0);
        col.addView(skip);

        setContentView(scroll);
        refreshAllStatuses();
        probeShizuku();
    }

    private void addPermRow(LinearLayout parent, String title, String desc,
                            final StatusProvider provider, final View.OnClickListener click) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(14), dp(16), dp(14));
        row.setBackgroundColor(cCard());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(10);
        row.setLayoutParams(lp);

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        left.setLayoutParams(llp);

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(cText());
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        t.setTypeface(null, android.graphics.Typeface.BOLD);
        left.addView(t);

        TextView d = new TextView(this);
        d.setText(desc);
        d.setTextColor(cSub());
        d.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        d.setPadding(0, dp(3), 0, 0);
        left.addView(d);

        TextView status = new TextView(this);
        status.setText("检测中…");
        status.setTextColor(cSub());
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        status.setGravity(Gravity.CENTER_VERTICAL);
        status.setPadding(dp(10), 0, dp(4), 0);

        row.addView(left);
        row.addView(status);
        row.setOnClickListener(click);
        parent.addView(row);

        PermRow pr = new PermRow();
        pr.status = status;
        pr.provider = provider;
        permRows.add(pr);
    }

    private void refreshAllStatuses() {
        // 线程安全：Shizuku binder 回调、后台线程都可能调用；setText 必须在 UI 线程。
        if (Looper.myLooper() != Looper.getMainLooper()) {
            ui.post(new Runnable() {
                @Override public void run() { refreshAllStatuses(); }
            });
            return;
        }
        for (PermRow pr : permRows) {
            boolean g = false;
            try { g = pr.provider.granted(); } catch (Throwable ignored) {}
            pr.status.setText(g ? "已授权" : "未授权");
            pr.status.setTextColor(g ? cGreen() : cRed());
        }
        // 工作区行状态（非权限，显示已设置/未设置）
        if (workspaceDescView != null) {
            String p = workspacePath();
            if (p == null || p.isEmpty()) {
                workspaceDescView.setText("未设置：AI 文件操作在内部目录。点此选择外部文件夹（如 /sdcard/Documents）。");
            } else {
                workspaceDescView.setText("已设置：" + p + "（点此更改或恢复默认）");
            }
        }
    }

    // ============ AI 工作区（可选） ============
    private static final String KEY_WORKSPACE = "workspace_path";

    /** 当前配置的工作区路径（外部共享存储目录），未设置返回 null。 */
    private String workspacePath() {
        return getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_WORKSPACE, null);
    }

    /** 工作区行点击：未设置直接选目录；已设置弹菜单（重新选择/恢复默认）。 */
    private void onWorkspaceRowClick() {
        final String cur = workspacePath();
        if (cur == null || cur.isEmpty()) { openWorkspacePicker(); return; }
        try {
            new AlertDialog.Builder(this)
                    .setTitle("AI 工作区")
                    .setMessage("当前工作区：\n" + cur + "\n\n选择其他文件夹，或恢复默认？")
                    .setPositiveButton("重新选择", new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface d, int w) { openWorkspacePicker(); }
                    })
                    .setNegativeButton("恢复默认", new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface d, int w) {
                            getSharedPreferences(PREFS, MODE_PRIVATE).edit().remove(KEY_WORKSPACE).apply();
                            refreshAllStatuses();
                        }
                    })
                    .setNeutralButton("取消", null)
                    .show();
        } catch (Throwable ignored) {}
    }

    /** 打开系统文件夹选择器（SAF），选中的目录持久化为工作区。 */
    private void openWorkspacePicker() {
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
            startActivityForResult(i, REQ_WORKSPACE_TREE);
        } catch (Throwable t) {
            Log.w(TAG, "open document tree failed", t);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_WORKSPACE_TREE && resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri tree = data.getData();
            // 持久化 SAF 授权（重启后仍可访问该目录）
            try {
                getContentResolver().takePersistableUriPermission(tree,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            } catch (Throwable ignored) {}
            String path = treeUriToPath(tree);
            if (path != null && !path.isEmpty()) {
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_WORKSPACE, path).apply();
                refreshAllStatuses();
            } else {
                try {
                    new AlertDialog.Builder(this)
                            .setTitle("无法使用该目录")
                            .setMessage("无法解析所选文件夹的真实路径，请选择手机存储（内部存储或 SD 卡）内的文件夹。")
                            .setPositiveButton("知道了", null)
                            .show();
                } catch (Throwable ignored) {}
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    /** SAF 树 URI → 真实路径。
     *  关键：SAF 的 docId 与真实挂载路径不是简单字符串拼接。
     *  - "primary:*"（内部存储）→ /storage/emulated/0/*（Environment.getExternalStorageDirectory 基准）
     *  - "downloads:*"（Downloads 卷）→ /storage/emulated/0/Download/*
     *  - "home:*" → 内部存储根
     *  - "XXXX-XXXX:*"（SD 卡卷）→ 无法可靠映射，回退 /storage/<volume>/*
     *  - "raw:/..."（部分 ROM）→ 直接用 raw: 后的真实路径
     *  解析失败返回 null（调用方提示用户重新选择）。 */
    private String treeUriToPath(Uri uri) {
        try {
            String docId = DocumentsContract.getTreeDocumentId(uri);
            if (docId == null || docId.isEmpty()) return null;
            Log.i(TAG, "SAF docId=" + docId);
            if (docId.startsWith("raw:")) {
                String raw = docId.substring(4);
                return raw.isEmpty() ? null : raw;
            }
            int colon = docId.indexOf(':');
            String volume = colon > 0 ? docId.substring(0, colon) : docId;
            String rest = colon > 0 ? docId.substring(colon + 1) : "";
            File base;
            if ("primary".equals(volume)) {
                base = Environment.getExternalStorageDirectory();
            } else if ("downloads".equals(volume)) {
                // Downloads 卷实际位于内部存储的 Download 目录
                base = new File(Environment.getExternalStorageDirectory(), "Download");
            } else if ("home".equals(volume)) {
                base = Environment.getExternalStorageDirectory();
            } else {
                // 其它卷（如 SD 卡 XXXX-XXXX）：返回 /storage/<volume>/<rest>（可能不准，但极少用）
                String p = "/storage/" + volume + (rest.isEmpty() ? "" : "/" + rest);
                Log.w(TAG, "SAF 非标准卷 -> " + p);
                return p;
            }
            File out;
            if (rest.isEmpty()) out = base;
            else out = new File(base, rest.replace('\\', '/'));
            Log.i(TAG, "SAF 路径 -> " + out.getAbsolutePath());
            return out.getAbsolutePath();
        } catch (Throwable t) {
            Log.w(TAG, "treeUriToPath error", t);
            return null;
        }
    }

    private void openSystemSetting(String action) {
        try {
            Intent i = new Intent(action);
            i.setData(Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Exception e) {
            try {
                Intent i = new Intent(action);
                startActivity(i);
            } catch (Exception e2) {
                Log.w(TAG, "无法打开设置: " + action, e2);
            }
        }
    }

    private void openShizukuApp() {
        String[] pkgs = {"moe.shizuku.privileged.api", "rikka.shizuku"};
        for (String p : pkgs) {
            Intent i = getPackageManager().getLaunchIntentForPackage(p);
            if (i != null) {
                try { startActivity(i); return; } catch (Exception ignored) {}
            }
        }
        Log.w(TAG, "未找到 Shizuku 应用，请手动打开并授权");
    }

    private void showShizukuDialog() {
        boolean installed = false;
        for (String p : new String[]{"moe.shizuku.privileged.api", "rikka.shizuku"}) {
            try { getPackageManager().getPackageInfo(p, 0); installed = true; break; } catch (Exception ignored) {}
        }
        boolean binderOk = false;
        try { binderOk = Shizuku.pingBinder(); } catch (Throwable ignored) {}
        boolean rootOkNow = rootOk != null && rootOk;

        if (rootOkNow || (shizukuOk != null && shizukuOk)) {
            AlertDialog.Builder b = new AlertDialog.Builder(this);
            b.setTitle("系统特权（可选）");
            b.setMessage((rootOkNow ? "已检测到 Root（su）可用，AI 可以执行系统级操作。\n" : "") +
                    ((shizukuOk != null && shizukuOk) ? "Shizuku 已授权，AI 可以执行系统级操作。\n" : "") +
                    "\n不授予特权也能正常使用：文件读写、预览、编辑只需「所有文件访问」权限。");
            b.setNegativeButton("关闭", null);
            b.show();
        } else if (binderOk) {
            // 服务在运行但未授权 → 直接弹 Shizuku 授权对话框
            try {
                Shizuku.requestPermission(REQ_SHIZUKU);
            } catch (Throwable t) {
                Log.w(TAG, "Shizuku requestPermission failed", t);
                fallbackShizukuDialog(installed);
            }
        } else {
            fallbackShizukuDialog(installed);
        }
    }

    private void fallbackShizukuDialog(boolean installed) {
        String msg;
        if (installed) {
            msg = "Shizuku 服务未运行。\n\n请先打开 Shizuku 应用并启动服务，然后回来点击「重新检测」；服务启动后本应用会自动弹出授权对话框。";
        } else {
            msg = "未检测到 Shizuku 应用。请先安装 Shizuku（官方版），再回来授权。";
        }
        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle("Shizuku 特权");
        b.setMessage(msg);
        if (installed) {
            b.setPositiveButton("去启动 Shizuku", new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int w) { openShizukuApp(); }
            });
        }
        b.setNeutralButton("重新检测", new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface d, int w) { probeShizuku(); }
        });
        b.setNegativeButton("关闭", null);
        b.show();
    }

    // Shizuku 检测（Shizuku API，异步）
    private volatile Boolean shizukuOk = null;

    private void probeShizuku() {
        new Thread(new Runnable() {
            @Override public void run() {
                final boolean ok = shizukuAvailable();
                shizukuOk = ok;
                // 顺带在后台探测 root（避免在主线程执行 su）
                try { rootAvailable(); } catch (Throwable ignored) {}
                ui.post(new Runnable() { @Override public void run() { refreshAllStatuses(); } });
            }
        }, "shizuku-probe").start();
    }

    private boolean shizukuAvailable() {
        try {
            if (!Shizuku.pingBinder()) return false;
            return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 探测 root（su）是否可用：执行 `su -c id`，输出含 uid=0 即视为可用。结果缓存，onResume 时重置。 */
    private volatile Boolean rootOk = null;
    private boolean rootAvailable() {
        Boolean cached = rootOk;
        if (cached != null) return cached;
        boolean ok = probeRoot();
        rootOk = ok;
        return ok;
    }

    private boolean probeRoot() {
        Process p = null;
        try {
            p = Runtime.getRuntime().exec(new String[]{"su", "-c", "id"});
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line = r.readLine();
            // 等待进程退出，避免僵尸；API 26+ 支持超时，低版本直接等待（su -c id 很快返回）
            try {
                if (Build.VERSION.SDK_INT >= 26) {
                    if (!p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) p.destroy();
                } else {
                    p.waitFor();
                }
            } catch (Throwable ignored) {}
            return line != null && line.contains("uid=0");
        } catch (Throwable t) {
            return false;
        } finally {
            try { if (p != null) p.destroy(); } catch (Throwable ignored) {}
        }
    }

    private File extractRishDex() {
        try {
            File dir = new File(getFilesDir(), "rish");
            if (!dir.exists()) dir.mkdirs();
            File dex = new File(dir, "rish_shizuku.dex");
            if (dex.exists() && dex.length() > 0) return dex;
            InputStream in = getAssets().open("rish_shizuku.dex");
            FileOutputStream out = new FileOutputStream(dex);
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
            out.close();
            in.close();
            return dex;
        } catch (Exception e) {
            Log.w(TAG, "extract rish dex failed", e);
            return null;
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        refreshAllStatuses();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) webView.onResume();
        rootOk = null; // 从设置页/Shizuku 返回时重新探测 root
        refreshAllStatuses();
        // 从 Shizuku/设置页返回时重新检测
        if (permRows != null && !permRows.isEmpty()) probeShizuku();
    }

    @Override
    protected void onStart() {
        super.onStart();
        overlayForeground = true;
        OverlayService.setOverlayVisible(false); // 回到前台：隐藏悬浮窗
    }

    @Override
    protected void onStop() {
        overlayForeground = false;
        OverlayService.setOverlayVisible(true);  // 退后台：显示悬浮窗
        super.onStop();
    }

    // ============ 引擎启动（原逻辑）============
    private void startEngine() {
        startKeepAliveService();   // 前台保活：挂后台不被杀（引擎持续运行）
        // 引擎端口持久化（供 OverlayService/其他组件读取）；已授权悬浮窗时自动拉起小鲸鱼
        try {
            getSharedPreferences("dsh_prefs", MODE_PRIVATE)
                    .edit().putInt("engine_port", enginePort).apply();
        } catch (Throwable ignored) {}
        if (Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this)) {
            startOverlayService();
        }
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    // v1.5.4：已移除「端口冲突自动换端口」（resolveEnginePort/portInUse/saveEnginePort/engine_port 持久化），
                    // 引擎固定默认端口启动，用于排查慢启动是否与端口探测相关。
                    // 通知通道在后台线程启动（端口 = enginePort+1）。
                    startNotifyServer();

                    File files = getFilesDir();
                    File payload = new File(files, "payload");
                    File done = new File(payload, ".extracted");

                    // v1.5.3 慢启动根因修复：内核目录改为【内部存储优先】。
                    // 现象：v1.5.x 真机启动 50-60s（v1.4 的 10s），payload/node/启动参数逐字节对比无差异，
                    // 模拟器正常（4s）→ 根因是 node 每次启动从【外部 /sdcard（FUSE）】读取 2 万+ 内核文件，
                    // require() 解析时海量 stat/read 过 FUSE 极慢（真机如此，模拟器宿主机磁盘快测不出）。
                    // 修复：node 恒从内部存储（files/payload/dshroot）读内核（快、可靠）；
                    // 外部目录仅作【内部空间不足】时的回退，以及保留 .nomedia/相册保护等兼容逻辑。
                    File externalRoot = new File(Environment.getExternalStorageDirectory(), EXT_DSHROOT_ROOT);
                    boolean useExternal = externalDshrootWritable(externalRoot);

                    // 后台清理上次「清空」遗留的 .trash-* 目录（rename 后后台删除未完成），不阻塞启动。
                    if (useExternal) {
                        final File extCleanup = externalRoot;
                        new Thread(new Runnable() {
                            @Override public void run() { cleanupTrashDirs(extCleanup); }
                        }, "trash-cleanup").start();
                        // 相册保护：外部 dshroot（历史版本遗留）里 2 万+ 文件会被 MediaStore
                        // 内容嗅探误判为视频。.nomedia 让 MediaStore 忽略整个目录。幂等。
                        File nomedia = new File(externalRoot, ".nomedia");
                        if (!nomedia.exists()) {
                            try { nomedia.createNewFile(); } catch (Throwable ignored) {}
                        }
                    }

                    if (!done.exists()) {
                        // 关键：先解压内部关键运行时（node/.so/dshhome/bin/rish），再解压 dshroot。
                        // 解压中途被打断时，只要内部已就位引擎仍能启动；缺的文件由 dshrootNeedsSync 幂等补齐。
                        extractPayload(payload, null, "internal");
                        done.createNewFile();
                    } else {
                        // 覆盖升级：补齐内部运行时缺失的新增文件（如 runtime/bin/rg），已有文件不动
                        try { extractPayload(payload, null, "internal-patch"); } catch (Throwable ignored) {}
                    }

                    // 内部 dshroot 同步（node 从此处读内核）：
                    // REVISION 不匹配（重装）或 .complete 缺失（中断）都补。
                    // v1.5.2：REVISION 是构建时间戳每次构建都变——同内核升级走「快速同步」
                    //（只更新 REVISION+白名单文件，秒级）；.complete 缺失或内核版本变化才全量补齐。
                    File internalBase = payload; // 内部 dshroot 位于 payload/dshroot
                    File internalDshroot = new File(payload, "dshroot");
                    boolean kernelOnExternal = false;
                    try {
                        if (dshrootNeedsSync(internalBase)) {
                            boolean revisionChanged = dshrootRevisionChanged(internalBase);
                            boolean full = dshrootNeedsFullSync(internalBase);
                            fastSyncedThisBoot = !full;
                            extractPayload(payload, null, full ? "dshroot" : "dshroot-fast");
                            writeDshrootComplete(internalBase);
                            if (revisionChanged) refreshInternalConfig(payload);
                        }
                        dshrootDir = internalDshroot;
                    } catch (Throwable t) {
                        // 内部解压失败（通常为内部存储空间不足）→ 回退外部（慢但可用）
                        Log.w(TAG, "internal dshroot sync failed, fallback to external", t);
                        // 清理不完整的内部 dshroot，避免双重占空间
                        try { deleteRecursive(internalDshroot); } catch (Throwable ignored) {}
                        if (useExternal) {
                            if (dshrootNeedsSync(externalRoot)) {
                                boolean full = dshrootNeedsFullSync(externalRoot);
                                extractPayload(payload, externalRoot, full ? "dshroot" : "dshroot-fast");
                                writeDshrootComplete(externalRoot);
                            }
                            dshrootDir = new File(externalRoot, "dshroot");
                            kernelOnExternal = true;
                        } else {
                            throw t;
                        }
                    }

                    // 兜底：确保 dshroot 确实就位（例如首次内部解压被系统打断）。
                    if (!new File(dshrootDir, REL_BINJS).exists()) {
                        Log.w(TAG, "dshroot missing at " + dshrootDir + ", repopulating");
                        extractPayload(payload, kernelOnExternal ? externalRoot : null, "dshroot");
                        if (kernelOnExternal) writeDshrootComplete(externalRoot);
                    }

                    applyLinks(payload);
                    setExecutables(payload);
                    ensurePatchConfig(payload); // ③ 补丁启动自检：cordis.patch.yml 缺失/被改则自动补齐
                    if (healthOk()) { loadHome(); return; }
                    showIndeterminate("正在启动 DeepSeek Harness…");
                    spawnNode(payload);
                    waitForServer();
                } catch (Throwable t) {
                    Log.e(TAG, "engine error", t);
                    String msg = String.valueOf(t.getMessage());
                    setStatus("引擎启动失败：" + msg);
                    writeStartupDiag(msg);
                }
            }
        }, "engine-boot").start();
    }

    /** v1.7：启动失败时把引擎日志尾部与状态写进外部目录（多位置，保证至少一处成功），用户无需 adb 即可反馈排查。 */
    private void writeStartupDiag(String errorMsg) {
        try {
            String sub = getPackageName().contains("beta") ? "DeepSeekHarnessLite"
                    : getPackageName().contains("compat") ? "DeepSeekHarnessCompat" : "DeepSeekHarness";
            StringBuilder sb = new StringBuilder();
            sb.append("时间: ").append(new java.util.Date()).append('\n');
            sb.append("错误: ").append(errorMsg).append('\n');
            sb.append("enginePort=").append(enginePort).append(" notifyPort=").append(notifyPort()).append('\n');
            sb.append("node存活=").append(nodeProcess != null && nodeProcess.isAlive()).append('\n');
            File log = new File(getFilesDir(), "dsh-web.log");
            if (log.exists()) {
                java.io.RandomAccessFile raf = new java.io.RandomAccessFile(log, "r");
                long len = raf.length();
                long start = Math.max(0, len - 65536);
                raf.seek(start);
                byte[] buf = new byte[(int) (len - start)];
                raf.readFully(buf);
                raf.close();
                sb.append("--- dsh-web.log 尾部 ---\n").append(new String(buf, "UTF-8"));
            }
            byte[] content = sb.toString().getBytes("UTF-8");
            // 多位置都尝试：外部目录（需存储权限）、App 专属外部目录（无需权限）、Download（最易找）
            String[] paths = new String[]{
                    new File(android.os.Environment.getExternalStorageDirectory(), sub + "/startup-diag.txt").getAbsolutePath(),
                    new File(getExternalFilesDir(null), "startup-diag.txt").getAbsolutePath(),
                    new File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "DeepSeekHarness-startup-diag.txt").getAbsolutePath()
            };
            String written = "";
            for (String p : paths) {
                try {
                    File f = new File(p);
                    File parent = f.getParentFile();
                    if (parent != null && !parent.exists()) parent.mkdirs();
                    FileOutputStream fos = new FileOutputStream(f);
                    fos.write(content);
                    fos.close();
                    written += "\n" + p;
                } catch (Throwable ignored) {
                }
            }
            Log.i(TAG, "启动诊断已写入:" + written);
        } catch (Throwable ignored) {
        }
    }

    // ============ ③ 补丁启动自检 ============
    /** 检查内部 dshhome/cordis.patch.yml 是否完整（含禁用的三个插件），
     *  缺失/被外部改动破坏则从 payload.zip 重新提取官方配置（幂等）。
     *  背景：补丁配置被改/删会导致 llm-pi-ai/sandbox/bash-sandbox 启用失败 → 启动崩溃。 */
    private void ensurePatchConfig(File payload) {
        try {
            File patch = new File(payload, "dshhome/cordis.patch.yml");
            boolean need = !patch.exists();
            if (!need) {
                String content = readFileText(patch);
                // 关键禁用项缺任一 → 视为损坏，重新提取
                need = !(content.contains("llm-pi-ai") && content.contains("sandbox")
                        && content.contains("bash-sandbox") && content.contains("disabled: true"));
            }
            if (need) {
                Log.w(TAG, "cordis.patch.yml missing or incomplete, restoring from payload.zip");
                refreshInternalConfig(payload); // 重新覆盖 dshhome 官方配置（凭证/会话保留）
            }
        } catch (Throwable t) {
            Log.w(TAG, "ensurePatchConfig error", t);
        }
    }

    // ============ ① 端口冲突处理 ============
    /** 判断端口上是否真的是 DSH 引擎（而非任意 HTTP 服务/占位页）。
     *  强特征：首页 HTML 含 <title>DeepSeek Harness</title>（占位服务/Termux busy 页不会恰好相同）。
     *  v1.5.1 修复：旧 healthOk() 只认"任意 HTTP 响应(200-499)"，占位服务返回 200 时被误判为
     *  引擎健康 → 不换端口、node 不启动、WebView 显示占位内容。 */
    private boolean isDshEngine(int port) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/").openConnection();
            c.setConnectTimeout(1200);
            c.setReadTimeout(1500);
            c.setRequestProperty("User-Agent", "dsh-probe");
            int code = c.getResponseCode();
            if (code < 200 || code >= 500) return false;
            InputStream in = c.getInputStream();
            // v1.5.5 修复：首页实际约 14KB（13KB 内联脚本在前，<title> 位于页面末尾第 13.4KB 处），
            // 旧实现只读前 4096 字节 → 永远匹配不到 → waitForServer 干等 90s 超时（慢启动根因）。
            // 改为读完整页（上限 256KB，本地读取 <50ms）。
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

    // ============ 前台保活服务 ============
    /** 启动前台服务（带常驻通知），引擎运行期间挂后台不被系统杀掉。 */
    private void startKeepAliveService() {
        try {
            Intent i = new Intent(this, EngineService.class);
            if (Build.VERSION.SDK_INT >= 26) {
                startForegroundService(i);
            } else {
                startService(i);
            }
            Log.i(TAG, "keep-alive service started");
        } catch (Throwable t) {
            Log.w(TAG, "keep-alive service start failed", t);
        }
    }

    /** 停止前台服务（用户主动退出时调用）。 */
    private void stopKeepAliveService() {
        try {
            stopService(new Intent(this, EngineService.class));
        } catch (Throwable ignored) {}
    }

    // ============ AI 发通知通道（本地端口，只需通知权限） ============
    /** 通知渠道（App 内发通知用，与保活服务的渠道分开）。 */
    private static final String NOTIFY_CHANNEL_ID = "dsh_ai_notify";
    private static final String NOTIFY_CHANNEL_NAME = "AI 通知";
    // 通知端口动态跟随引擎端口（enginePort+1），保证两个 App 共存时不冲突
    private int notifyPort() { return enginePort + 1; }

    /** 启动本地通知监听：AI 通过插件请求 http://127.0.0.1:<notifyPort> 发通知（仅需通知权限）。 */
    private void startNotifyServer() {
        final int port = notifyPort();
        new Thread(new Runnable() {
            @Override public void run() {
                ServerSocket ss = null;
                try {
                    ss = new ServerSocket();
                    ss.setReuseAddress(true);
                    ss.bind(new InetSocketAddress("127.0.0.1", port));
                    Log.i(TAG, "notify server listening on " + port);
                    while (!Thread.currentThread().isInterrupted()) {
                        try {
                            final Socket s = ss.accept();
                            handleNotifyConnection(s);
                        } catch (Throwable t) {
                            // accept 异常（连接被重置/中断）不退出监听循环，短暂等待后继续
                            try { Thread.sleep(100); } catch (Throwable ignored) {}
                        }
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "notify server stopped", t);
                } finally {
                    try { if (ss != null) ss.close(); } catch (Throwable ignored) {}
                }
            }
        }, "notify-server").start();
    }

    /** 处理一条本地请求：按 HTTP 路径分发（/notify 通知、/setting 系统设置、/clipboard 剪贴板）。 */
    private void handleNotifyConnection(final Socket s) {
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    s.setSoTimeout(5000);
                    InputStream in = s.getInputStream();
                    // 1) 读请求行 + 请求头，解析路径和 Content-Length
                    int contentLength = 0;
                    StringBuilder head = new StringBuilder();
                    int c;
                    while ((c = in.read()) != -1) {
                        head.append((char) c);
                        if (head.length() >= 4 && head.substring(head.length() - 4).equals("\r\n\r\n")) break;
                        if (head.length() > 8192) break; // 防异常大头部
                    }
                    String h = head.toString();
                    // 请求行形如: POST /notify HTTP/1.1
                    String path = "/notify";
                    int sp1 = h.indexOf(' ');
                    int sp2 = sp1 >= 0 ? h.indexOf(' ', sp1 + 1) : -1;
                    if (sp1 >= 0 && sp2 > sp1) path = h.substring(sp1 + 1, sp2);
                    int qIdx = path.indexOf('?');
                    if (qIdx >= 0) path = path.substring(0, qIdx);
                    int clIdx = h.toLowerCase().indexOf("content-length:");
                    if (clIdx >= 0) {
                        int eol = h.indexOf('\r', clIdx);
                        if (eol < 0) eol = h.indexOf('\n', clIdx);
                        if (eol < 0) eol = h.length();
                        try {
                            contentLength = Integer.parseInt(h.substring(clIdx + 15, eol).trim());
                        } catch (Exception ignored) {}
                    }
                    // 2) 读取正文（JSON body）
                    StringBuilder body = new StringBuilder();
                    if (contentLength > 0 && contentLength < 65536) {
                        byte[] buf = new byte[contentLength];
                        int off = 0;
                        while (off < contentLength) {
                            int n = in.read(buf, off, contentLength - off);
                            if (n < 0) break;
                            off += n;
                        }
                        body.append(new String(buf, 0, off, "UTF-8"));
                    } else {
                        // contentLength==0（如 GET 请求 /usage?days=N /overlay /status）：不读 body，
                        // 否则阻塞等 EOF 会 5s 读超时（SocketTimeoutException），所有 GET 路由卡死。
                    }
                    // 3) 分发处理
                    String respBody;
                    if (path.startsWith("/setting")) {
                        respBody = handleSettingRequest(body.toString());
                    } else if (path.startsWith("/clipboard")) {
                        respBody = handleClipboardRequest(body.toString());
                    } else if (path.startsWith("/schedule")) {
                        respBody = handleScheduleRequest(body.toString());
                    } else if (path.startsWith("/usage")) {
                        respBody = handleUsageRequest(body.toString());
                    } else if (path.startsWith("/overlay")) {
                        respBody = handleOverlayRequest(body.toString());
                    } else if (path.startsWith("/status")) {
                        respBody = handleStatusRequest();
                    } else {
                        respBody = handleNotifyRequest(body.toString());
                    }
                    BufferedWriter w = new BufferedWriter(new OutputStreamWriter(s.getOutputStream(), "UTF-8"));
                    w.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: "
                            + respBody.getBytes("UTF-8").length + "\r\nConnection: close\r\n\r\n" + respBody);
                    w.flush();
                    s.close();
                } catch (Throwable t) {
                    Log.w(TAG, "local server connection error", t);
                    try { s.close(); } catch (Throwable ignored) {}
                }
            }
        }, "local-conn").start();
    }

    /** 处理 /usage：查询应用使用时长（UsageStats）。参数 days=N（默认 1，上限 30）。 */
    private String handleUsageRequest(String raw) {
        try {
            String days = jsonField(raw, "days");
            if (days.isEmpty()) days = queryField(raw, "days");
            int d = 1;
            try { if (!days.isEmpty()) d = Integer.parseInt(days.trim()); } catch (Exception ignored) {}
            return UsageStatsHelper.queryUsageJson(this, d);
        } catch (Throwable t) {
            return "{\"ok\":false,\"error\":\"" + String.valueOf(t.getMessage()).replace("\"", "'") + "\"}";
        }
    }

    /** 处理 /status：引擎/服务运行状态（供悬浮窗与 AI 查询）。 */
    private String handleStatusRequest() {
        boolean engineOk = isDshEngine(enginePort);
        StringBuilder sb = new StringBuilder("{\"ok\":true");
        sb.append(",\"enginePort\":").append(enginePort);
        sb.append(",\"engineReady\":").append(engineOk);
        sb.append(",\"nodeAlive\":").append(nodeProcess != null && nodeProcess.isAlive());
        sb.append(",\"overlay\":").append(OverlayService.isRunning);
        sb.append(",\"overlayEngineUp\":").append(OverlayService.engineUp);
        sb.append(",\"usageGranted\":").append(UsageStatsHelper.permissionGranted(this));
        sb.append(",\"overlayGranted\":").append(Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this));
        sb.append('}');
        return sb.toString();
    }

    /** 处理 /overlay：控制小鲸鱼悬浮窗。action=show|hide|toggle|status。 */
    private String handleOverlayRequest(String raw) {
        try {
            String action = jsonField(raw, "action");
            if (action.isEmpty()) action = queryField(raw, "action");
            if (action.isEmpty()) action = "status";
            if (action.equals("status")) {
                return "{\"ok\":true,\"running\":" + OverlayService.isRunning
                        + ",\"engineUp\":" + OverlayService.engineUp
                        + ",\"granted\":" + (Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this)) + "}";
            }
            if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
                return "{\"ok\":false,\"error\":\"未授予悬浮窗权限，请在权限引导页/系统设置里开启\"}";
            }
            if (action.equals("show") || action.equals("toggle")) {
                if (OverlayService.isRunning) {
                    if (action.equals("toggle")) { stopOverlayService(); return "{\"ok\":true,\"running\":false}"; }
                    return "{\"ok\":true,\"running\":true,\"msg\":\"已在运行\"}";
                }
                startOverlayService();
                return "{\"ok\":true,\"running\":true}";
            }
            if (action.equals("hide")) {
                stopOverlayService();
                return "{\"ok\":true,\"running\":false}";
            }
            return "{\"ok\":false,\"error\":\"未知 action（show/hide/toggle/status）\"}";
        } catch (Throwable t) {
            return "{\"ok\":false,\"error\":\"" + String.valueOf(t.getMessage()).replace("\"", "'") + "\"}";
        }
    }

    /** 启动小鲸鱼悬浮窗（需已授予悬浮窗权限；权限引导页里会调用）。 */
    private void startOverlayService() {
        try {
            Intent i = new Intent(this, OverlayService.class);
            if (Build.VERSION.SDK_INT >= 26) {
                startForegroundService(i);
            } else {
                startService(i);
            }
            Log.i(TAG, "overlay service starting");
        } catch (Throwable t) {
            Log.w(TAG, "overlay start failed", t);
        }
    }

    /** 停止小鲸鱼悬浮窗。 */
    private void stopOverlayService() {
        try {
            stopService(new Intent(this, OverlayService.class));
        } catch (Throwable t) {
            Log.w(TAG, "overlay stop failed", t);
        }
    }

    /** 处理 /notify：发系统通知（仅需通知权限）。 */
    private String handleNotifyRequest(String raw) {
        String title = "", text = "";
        int ti = raw.indexOf("\"title\"");
        int tx = raw.indexOf("\"text\"");
        if (ti >= 0 || tx >= 0) {
            title = jsonField(raw, "title");
            text = jsonField(raw, "text");
        } else {
            title = queryField(raw, "title");
            text = queryField(raw, "text");
        }
        if (title.isEmpty()) title = "DeepSeek Harness";
        if (text.isEmpty()) text = "(空消息)";
        boolean granted = checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                == PackageManager.PERMISSION_GRANTED;
        if (granted) {
            postNotification(title, text);
            return "{\"ok\":true}";
        }
        return "{\"ok\":false,\"error\":\"通知权限未授予，无法发送通知\"}";
    }

    /** 处理 /setting：改系统设置（⑤，走 App 的 WRITE_SETTINGS 权限，仅限 System 命名空间，免 Shizuku）。
     *  音量类 key 必须走 AudioManager.setStreamVolume（Settings.System 的记录不生效）；
     *  其余 System 项走 Settings.System.put。 */
    private String handleSettingRequest(String raw) {
        try {
            String key = jsonField(raw, "key");
            String value = jsonField(raw, "value");
            if (key.isEmpty()) {
                key = queryField(raw, "key");
                value = queryField(raw, "value");
            }
            if (key.isEmpty()) return "{\"ok\":false,\"error\":\"缺少 key 参数\"}";
            // 音量：走 AudioManager（真实生效，无需 WRITE_SETTINGS）
            if (key.startsWith("volume_")) {
                return handleVolumeRequest(key, value);
            }
            // 其余 System 设置：需要 WRITE_SETTINGS 权限
            if (Build.VERSION.SDK_INT < 23 || !Settings.System.canWrite(this)) {
                return "{\"ok\":false,\"error\":\"未授予「修改系统设置」权限（WRITE_SETTINGS），无法修改；请先在权限引导页/系统设置里开启\"}";
            }
            boolean ok;
            if (isNumeric(value)) {
                ok = Settings.System.putInt(getContentResolver(), key, Integer.parseInt(value));
            } else {
                ok = Settings.System.putString(getContentResolver(), key, value);
            }
            return ok ? "{\"ok\":true}" : "{\"ok\":false,\"error\":\"写入失败（key 可能不存在或不允许修改）\"}";
        } catch (Throwable t) {
            return "{\"ok\":false,\"error\":\"" + String.valueOf(t.getMessage()).replace("\"", "'") + "\"}";
        }
    }

    /** 音量调节：走 AudioManager.setStreamVolume（真实改变音量）。 */
    private String handleVolumeRequest(String key, String value) {
        try {
            android.media.AudioManager am = (android.media.AudioManager) getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return "{\"ok\":false,\"error\":\"音频服务不可用\"}";
            int stream;
            switch (key) {
                case "volume_music": stream = android.media.AudioManager.STREAM_MUSIC; break;
                case "volume_ring": stream = android.media.AudioManager.STREAM_RING; break;
                case "volume_alarm": stream = android.media.AudioManager.STREAM_ALARM; break;
                case "volume_notification": stream = android.media.AudioManager.STREAM_NOTIFICATION; break;
                case "volume_system": stream = android.media.AudioManager.STREAM_SYSTEM; break;
                case "volume_voice_call": stream = android.media.AudioManager.STREAM_VOICE_CALL; break;
                default: return "{\"ok\":false,\"error\":\"不支持的音量类型: " + key + "\"}";
            }
            int max = am.getStreamMaxVolume(stream);
            int val;
            if (value.endsWith("%")) {
                // 支持百分比：如 "50%"
                val = (int) Math.round(max * Integer.parseInt(value.replace("%", "").trim()) / 100.0);
            } else {
                val = Integer.parseInt(value.trim());
            }
            if (val < 0) val = 0;
            if (val > max) val = max;
            // flags=0：不显示音量条、不播放提示音（静默调整，避免打扰）
            am.setStreamVolume(stream, val, 0);
            return "{\"ok\":true,\"stream\":\"" + key + "\",\"level\":" + val + ",\"max\":" + max + "}";
        } catch (Throwable t) {
            return "{\"ok\":false,\"error\":\"" + String.valueOf(t.getMessage()).replace("\"", "'") + "\"}";
        }
    }

    /** 处理 /clipboard：读写剪贴板（⑦，无需任何特殊权限）。 */
    private String handleClipboardRequest(String raw) {
        try {
            String action = jsonField(raw, "action");
            if (action.isEmpty()) action = queryField(raw, "action");
            if (action.isEmpty()) action = "read";
            if (action.equals("write")) {
                String content = jsonField(raw, "content");
                if (content.isEmpty()) content = queryField(raw, "content");
                if (content.isEmpty()) return "{\"ok\":false,\"error\":\"缺少 content 参数\"}";
                android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(android.content.ClipData.newPlainText("dsh", content));
                return "{\"ok\":true}";
            }
            // read
            android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null || !cm.hasPrimaryClip()) return "{\"ok\":true,\"content\":\"\"}";
            CharSequence cs = cm.getPrimaryClip().getItemAt(0).coerceToText(this);
            String content = cs == null ? "" : cs.toString();
            // JSON 转义
            content = content.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
            return "{\"ok\":true,\"content\":\"" + content + "\"}";
        } catch (Throwable t) {
            return "{\"ok\":false,\"error\":\"" + String.valueOf(t.getMessage()).replace("\"", "'") + "\"}";
        }
    }

    private boolean isNumeric(String s) {
        if (s == null || s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch < '0' || ch > '9') return false;
        }
        return true;
    }

    // ============ ⑥ 定时任务（半自动版）============
    /** 处理 /schedule：AI 设置定时提醒 → AlarmManager 注册系统闹钟。
     *  到点系统唤醒 AlarmReceiver（即使 App 被杀也能触发）→ 推送通知提醒。
     *  若 App 仍在后台（保活生效），点通知可回 App 继续执行。 */
    private String handleScheduleRequest(String raw) {
        try {
            String text = jsonField(raw, "text");
            if (text.isEmpty()) text = queryField(raw, "text");
            String when = jsonField(raw, "when");
            if (when.isEmpty()) when = queryField(raw, "when");
            // 重复模式（Kun 式调度）：once 一次性（默认）| daily 每天 | interval 每隔 N 分钟
            String repeat = jsonField(raw, "repeat");
            if (repeat.isEmpty()) repeat = queryField(raw, "repeat");
            if (repeat.isEmpty()) repeat = "once";
            int intervalMin = 0;
            String im = jsonField(raw, "intervalMin");
            if (im.isEmpty()) im = queryField(raw, "intervalMin");
            if (!im.isEmpty()) { try { intervalMin = Math.max(1, Integer.parseInt(im.trim())); } catch (Exception ignored) {} }
            if (!repeat.equals("once") && !repeat.equals("daily") && !repeat.equals("interval")) {
                return "{\"ok\":false,\"error\":\"repeat 仅支持 once/daily/interval\"}";
            }
            if (repeat.equals("interval") && intervalMin <= 0) {
                return "{\"ok\":false,\"error\":\"interval 模式需要 intervalMin（分钟）参数\"}";
            }
            if (text.isEmpty()) return "{\"ok\":false,\"error\":\"缺少 text 参数\"}";
            if (when.isEmpty()) {
                // interval 模式允许省略 when（默认 1 分钟后首次触发）
                if (repeat.equals("interval")) when = "60";
                else return "{\"ok\":false,\"error\":\"缺少 when 参数（ISO 时间或相对秒数）\"}";
            }

            long triggerAt;
            // 支持两种格式：纯数字 = 相对秒数；否则按 ISO 时间解析
            if (isNumeric(when)) {
                triggerAt = System.currentTimeMillis() + Long.parseLong(when) * 1000L;
            } else {
                // 兼容 "2026-08-21 08:00:00" / "2026-08-21T08:00:00" / "08:00"（今天）
                String w = when.trim().replace("T", " ").replace("Z", "");
                java.text.SimpleDateFormat fmt;
                long at;
                if (w.length() <= 5) {
                    fmt = new java.text.SimpleDateFormat("HH:mm", Locale.US);
                    java.util.Date d = fmt.parse(w);
                    java.util.Calendar cal = java.util.Calendar.getInstance();
                    cal.set(java.util.Calendar.HOUR_OF_DAY, d.getHours());
                    cal.set(java.util.Calendar.MINUTE, d.getMinutes());
                    cal.set(java.util.Calendar.SECOND, 0);
                    at = cal.getTimeInMillis();
                    if (at <= System.currentTimeMillis()) at += 24 * 3600 * 1000L; // 已过 → 明天
                } else if (w.length() <= 16) {
                    fmt = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US);
                    at = fmt.parse(w).getTime();
                } else {
                    fmt = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
                    at = fmt.parse(w).getTime();
                }
                triggerAt = at;
            }
            if (triggerAt <= System.currentTimeMillis()) {
                // interval 模式：when 已过则从 1 分钟后起算（避免报错打断循环任务）
                if (repeat.equals("interval")) {
                    triggerAt = System.currentTimeMillis() + 60 * 1000L;
                } else {
                    return "{\"ok\":false,\"error\":\"触发时间已过，请设置未来的时间\"}";
                }
            }

            android.app.AlarmManager am = (android.app.AlarmManager) getSystemService(Context.ALARM_SERVICE);
            // 存任务到文件（AlarmReceiver 到点时读取并自动执行）
            String taskId = "task-" + System.currentTimeMillis();
            saveScheduledTask(taskId, text, triggerAt, repeat, intervalMin);
            Intent i = new Intent(this, AlarmReceiver.class);
            i.putExtra("task", text);
            i.putExtra("taskId", taskId);
            i.putExtra("repeatType", repeat);
            i.putExtra("intervalMin", intervalMin);
            i.putExtra("triggerAt", triggerAt);
            android.app.PendingIntent pi = android.app.PendingIntent.getBroadcast(this, 0, i,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE);
            // 用 setAlarmClock（系统最高优先级闹钟，无需特殊权限、Doze 也触发）最可靠；
            // 失败则降级 setExactAndAllowWhileIdle / set
            try {
                if (Build.VERSION.SDK_INT >= 21) {
                    Intent show = new Intent(this, MainActivity.class);
                    show.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    android.app.PendingIntent showPi = android.app.PendingIntent.getActivity(this, 1, show,
                            android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE);
                    am.setAlarmClock(new android.app.AlarmManager.AlarmClockInfo(triggerAt, showPi), pi);
                } else {
                    am.setExact(android.app.AlarmManager.RTC_WAKEUP, triggerAt, pi);
                }
            } catch (Throwable t) {
                try {
                    if (Build.VERSION.SDK_INT >= 23) {
                        am.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, triggerAt, pi);
                    } else {
                        am.setExact(android.app.AlarmManager.RTC_WAKEUP, triggerAt, pi);
                    }
                } catch (Throwable t2) {
                    am.set(android.app.AlarmManager.RTC_WAKEUP, triggerAt, pi);
                }
            }
            long secs = (triggerAt - System.currentTimeMillis()) / 1000;
            String whenStr = secs >= 3600
                    ? (secs / 3600) + "小时" + ((secs % 3600) / 60) + "分钟后"
                    : (secs / 60) + "分钟后";
            String repStr;
            if (repeat.equals("daily")) repStr = "每天";
            else if (repeat.equals("interval")) repStr = "每" + intervalMin + "分钟";
            else repStr = "一次性";
            return "{\"ok\":true,\"at\":\"" + whenStr + "\",\"repeat\":\"" + repStr
                    + "\",\"hint\":\"到点会自动拉起引擎执行任务（无需操作），完成后推送通知；重复任务到点后自动安排下一次；若 App 被杀，闹钟仍会触发并自动启动\"}";
        } catch (Throwable t) {
            return "{\"ok\":false,\"error\":\"" + String.valueOf(t.getMessage()).replace("\"", "'") + "\"}";
        }
    }

    // ============ 定时任务持久化 ============
    /** 任务文件：内部私有目录（AlarmReceiver 与 MainActivity 都能读） */
    private File scheduledTasksFile() { return new File(getFilesDir(), "scheduled-tasks.json"); }
    /** 执行记录日志：任务到点/执行/通知都追加，防止丢失 */
    private File scheduledLogFile() { return new File(getFilesDir(), "scheduled-log.txt"); }

    /** 保存一条定时任务到文件（jsonl 格式：taskId|triggerAt|repeatType|intervalMin|text）。
     *  repeatType: once=一次性 daily=每天 interval=每隔 N 分钟（intervalMin>0）。 */
    private void saveScheduledTask(String taskId, String text, long triggerAt, String repeatType, int intervalMin) {
        try {
            File f = scheduledTasksFile();
            String line = taskId + "|" + triggerAt + "|" + repeatType + "|" + intervalMin + "|"
                    + text.replace("|", " ").replace("\n", " ") + "\n";
            FileOutputStream fos = new FileOutputStream(f, true);
            fos.write(line.getBytes("UTF-8"));
            fos.close();
            logSchedule("任务已设置: " + text + " @ " + new java.text.SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(new Date(triggerAt)));
        } catch (Throwable t) {
            Log.w(TAG, "saveScheduledTask error", t);
        }
    }

    /** 读取所有已到点的任务（triggerAt <= now），并从未到点列表中删除它们（标记已处理）。 */
    private List<String[]> takeDueScheduledTasks() {
        List<String[]> due = new ArrayList<>();
        try {
            File f = scheduledTasksFile();
            if (!f.exists()) return due;
            long now = System.currentTimeMillis();
            StringBuilder keep = new StringBuilder();
            BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
            String line;
            while ((line = r.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                String[] parts = line.split("\\|", 3);
                if (parts.length < 3) continue;
                try {
                    long at = Long.parseLong(parts[1]);
                    if (at <= now) {
                        due.add(parts); // 到点：取走
                    } else {
                        keep.append(line).append("\n"); // 未到点：保留
                    }
                } catch (Exception ignored) {}
            }
            r.close();
            FileOutputStream fos = new FileOutputStream(f, false);
            fos.write(keep.toString().getBytes("UTF-8"));
            fos.close();
        } catch (Throwable t) {
            Log.w(TAG, "takeDueScheduledTasks error", t);
        }
        return due;
    }

    /** 追加一条执行记录日志（防丢失）。 */
    private void logSchedule(String msg) {
        try {
            FileOutputStream fos = new FileOutputStream(scheduledLogFile(), true);
            String line = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()) + " " + msg + "\n";
            fos.write(line.getBytes("UTF-8"));
            fos.close();
        } catch (Throwable ignored) {}
    }

    /** 从 JSON 里取字符串字段值（简易解析，不引第三方库）。 */
    private String jsonField(String json, String key) {
        try {
            String k = "\"" + key + "\"";
            int i = json.indexOf(k);
            if (i < 0) return "";
            int c = json.indexOf(':', i + k.length());
            if (c < 0) return "";
            int q1 = json.indexOf('"', c + 1);
            if (q1 < 0) return "";
            int q2 = json.indexOf('"', q1 + 1);
            if (q2 < 0) return "";
            return json.substring(q1 + 1, q2).replace("\\\"", "\"");
        } catch (Throwable t) {
            return "";
        }
    }

    /** 从 query 字符串里取字段值（title=..&text=..）。 */
    private String queryField(String q, String key) {
        try {
            String k = key + "=";
            int i = q.indexOf(k);
            if (i < 0) return "";
            int e = q.indexOf('&', i + k.length());
            if (e < 0) e = q.length();
            return q.substring(i + k.length(), e).replace("+", " ");
        } catch (Throwable t) {
            return "";
        }
    }

    /** 发一条 AI 通知（仅需 POST_NOTIFICATIONS，无需 Shizuku/root）。 */
    private void postNotification(String title, String text) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel ch = new NotificationChannel(NOTIFY_CHANNEL_ID, NOTIFY_CHANNEL_NAME,
                        NotificationManager.IMPORTANCE_DEFAULT);
                ch.setDescription("AI 任务完成/需要你关注时推送");
                nm.createNotificationChannel(ch);
            }
            Intent i = new Intent(this, MainActivity.class);
            i.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
            android.app.PendingIntent pi = android.app.PendingIntent.getActivity(this, 1, i,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE);
            Notification.Builder b;
            if (Build.VERSION.SDK_INT >= 26) {
                b = new Notification.Builder(this, NOTIFY_CHANNEL_ID);
            } else {
                b = new Notification.Builder(this);
            }
            Notification n = b.setContentTitle(title)
                    .setContentText(text)
                    .setSmallIcon(R.drawable.ic_launcher)
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .build();
            int id = (int) (System.currentTimeMillis() & 0x7fffffff);
            nm.notify(id, n);
            Log.i(TAG, "AI notification sent: " + title);
        } catch (Throwable t) {
            Log.w(TAG, "post notification failed", t);
        }
    }

    // 探测外部公共目录是否可写（不需要"所有文件访问"时也能降级内部）
    private boolean externalDshrootWritable(File externalRoot) {
        try {
            if (!externalRoot.exists() && !externalRoot.mkdirs()) return false;
            File probe = new File(externalRoot, ".probe");
            if (!probe.createNewFile()) return false;
            probe.delete();
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "external dshroot not writable, fallback to internal", t);
            return false;
        }
    }

    private String readAssetText(String asset) throws IOException {
        InputStream in = getAssets().open(asset);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] b = new byte[4096];
        int n;
        while ((n = in.read(b)) > 0) out.write(b, 0, n);
        in.close();
        return new String(out.toByteArray(), "UTF-8");
    }

    private String readFileText(File f) throws IOException {
        FileInputStream in = new FileInputStream(f);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] b = new byte[4096];
        int n;
        while ((n = in.read(b)) > 0) out.write(b, 0, n);
        in.close();
        return new String(out.toByteArray(), "UTF-8");
    }

    private String builtinDshrootRevision() {
        try {
            return readAssetText("dshroot_revision.txt").trim();
        } catch (Throwable t) {
            Log.w(TAG, "read dshroot revision failed", t);
            return "";
        }
    }

    // v1.5.3：dshroot 版本检查统一以「基目录」为单位——外部模式传 /sdcard/DeepSeekHarness，
    // 内部模式传 files/payload（内部 dshroot 位于 payload/dshroot，路径拼接一致）。
    private String dshrootRevisionAt(File dshrootBase) {
        File revFile = new File(dshrootBase, "dshroot/REVISION");
        try {
            return revFile.exists() ? readFileText(revFile).trim() : "";
        } catch (Throwable t) {
            return "";
        }
    }

    private boolean dshrootRevisionChanged(File dshrootBase) {
        String builtin = builtinDshrootRevision();
        String external = dshrootRevisionAt(dshrootBase);
        return !builtin.isEmpty() && !builtin.equals(external);
    }

    // dshroot 是否需要补齐：REVISION 不匹配（重装）或缺完成标记（解压被打断）。
    private boolean dshrootNeedsSync(File dshrootBase) {
        if (dshrootRevisionChanged(dshrootBase)) return true;
        File complete = new File(dshrootBase, "dshroot/" + DSHROOT_COMPLETE);
        return !complete.exists();
    }

    // v1.5.2 慢启动修复：是否必须走全量补齐（扫描 2 万+ 文件）。
    // 仅两种情况需要：① .complete 缺失（上次解压被打断，缺文件）② 内核版本变化（新内核新增包文件）。
    // 同内核升级（REVISION 变化但内容几乎不变）→ false → 走快速同步，避免真机外部存储 FUSE 上
    // 2 万+ 次 stat 造成的 50-60s 慢启动（模拟器宿主机磁盘快，测不出）。
    private boolean dshrootNeedsFullSync(File dshrootBase) {
        File complete = new File(dshrootBase, "dshroot/" + DSHROOT_COMPLETE);
        if (!complete.exists()) return true;
        return dshKernelChanged(dshrootBase);
    }

    // 对比 dshroot 的 DSH 内核版本与 APK 内置版本（build.sh 写入 dshroot_kernel_version.txt）。
    // 版本不同 → 内核升级（如 rc.6 → rc.2）→ 新增包文件必须补齐，否则引擎起不来。
    private boolean dshKernelChanged(File dshrootBase) {
        try {
            String builtin = readAssetText("dshroot_kernel_version.txt").trim();
            if (builtin.isEmpty()) return true; // 无版本标记（旧 APK）→ 保守走全量
            File pkg = new File(dshrootBase, "dshroot/lib/node_modules/@deepseek-ai/dsh/package.json");
            if (!pkg.exists()) return true;
            return !readFileText(pkg).contains("\"version\":\"" + builtin + "\"");
        } catch (Throwable t) {
            return true; // 读不到 → 保守全量
        }
    }

    private void writeDshrootComplete(File dshrootBase) {
        File complete = new File(dshrootBase, "dshroot/" + DSHROOT_COMPLETE);
        try {
            FileOutputStream fos = new FileOutputStream(complete);
            fos.write(builtinDshrootRevision().getBytes("UTF-8"));
            fos.close();
        } catch (Throwable t) {
            Log.w(TAG, "write dshroot complete marker failed", t);
        }
    }

    private void extractPayload(File destInternal, File externalRoot, String mode) throws IOException {
        // mode: "internal" = 只解压内部条目（runtime/bin/dshhome/rish，不含 dshroot）；
        //       "dshroot"  = 只解压 dshroot 条目（外部优先，回退内部）；
        //       "dshroot-fast" = 快速同步：只更新 REVISION + 官方白名单文件，不 stat 已有文件
        //                        （同内核升级用，避免真机 FUSE 2 万+ 次 stat 造成慢启动）。
        final boolean fast = "dshroot-fast".equals(mode);
        final boolean internalPatch = "internal-patch".equals(mode);
        final boolean internalOnly = "internal".equals(mode) || internalPatch;
        final boolean dshrootOnly = "dshroot".equals(mode) || fast;
        if (!destInternal.exists() && !destInternal.mkdirs()) throw new IOException("mkdir failed: " + destInternal);
        final int total = fast ? 0 : countPayloadEntries(mode); // 快速同步无进度条（更新极少文件）
        if (total > 0) setProgress(0, "首次启动 · 正在解压运行时 0/" + total + " 个文件…");
        byte[] buf = new byte[128 * 1024];
        InputStream in = getAssets().open("payload.zip");
        ZipInputStream zis = new ZipInputStream(in);
        ZipEntry e;
        int processed = 0;
        int written = 0;
        while ((e = zis.getNextEntry()) != null) {
            String name = e.getName();
            if (e.isDirectory() || name.startsWith("__MACOSX/") || name.startsWith("META-INF/")) { zis.closeEntry(); continue; }
            boolean isDshroot = name.startsWith("dshroot/");
            if (dshrootOnly && !isDshroot) { zis.closeEntry(); continue; }
            if (internalOnly && isDshroot) { zis.closeEntry(); continue; }
            processed++;

            File target;
            boolean skipIfExists = false;
            if (isDshroot && externalRoot != null) {
                target = new File(externalRoot, name);
                // 外部 dshroot：REVISION 与官方白名单路径总是覆盖；其他已有文件跳过（保留 AI 运行时修改）。
                if (fast) {
                    // 快速同步（内外通用）：只处理 REVISION + 白名单文件，其余条目直接跳过（不做 exists() stat）
                    if (!name.equals("dshroot/REVISION") && !isForceOverwrite(name)) { zis.closeEntry(); continue; }
                    skipIfExists = false;
                } else {
                    skipIfExists = !name.equals("dshroot/REVISION") && !isForceOverwrite(name) && target.exists();
                }
            } else {
                target = new File(destInternal, name);
                if (fast) {
                    // 快速同步：内部 dshroot 也只更新 REVISION + 白名单文件（同内核升级，避免全量重写）
                    if (!name.equals("dshroot/REVISION") && !isForceOverwrite(name)) { zis.closeEntry(); continue; }
                    skipIfExists = false;
                } else if (internalPatch) {
                    // 覆盖升级补齐：内部运行时只写缺失文件（新增文件如 runtime/bin/rg），白名单路径总是覆盖
                    skipIfExists = !isForceOverwrite(name) && target.exists();
                }
            }

            if (skipIfExists) {
                zis.closeEntry();
                updateProgress(processed, total, written);
                continue;
            }

            File parent = target.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) throw new IOException("mkdir failed: " + parent);
            FileOutputStream fos = new FileOutputStream(target);
            int n;
            while ((n = zis.read(buf)) > 0) fos.write(buf, 0, n);
            fos.close();
            zis.closeEntry();
            written++;
            updateProgress(processed, total, written);
        }
        zis.close();
        Log.i(TAG, "extracted " + written + " entries (external=" + (externalRoot != null) + ", mode=" + mode + ")");
    }

    // 判断某条目是否属于官方强制覆盖白名单（外部 dshroot 也随 APK 更新）。
    private boolean isForceOverwrite(String name) {
        for (String p : FORCE_OVERWRITE_PREFIXES) {
            if (name.startsWith(p)) return true;
        }
        return false;
    }

    // dshhome 里随 APK 更新的官方配置文件（凭证 .credentials.yaml、会话数据 storages/ 等不在内）。
    private static final String[] DSHHOME_CONFIG_PATHS = {
        "dshhome/cordis.patch.yml",
        "dshhome/settings.yaml",
        "dshhome/profiles/web/cordis.patch.yml",
        "dshhome/profiles/web/cordis.yml",
        "dshhome/profiles/web/package.json",
        "dshhome/profiles/web/pnpm-workspace.yaml"
    };

    // 重装后把 dshhome 的官方配置文件从 payload.zip 覆盖到内部（凭证/会话保留）。
    private void refreshInternalConfig(File payload) throws IOException {
        byte[] buf = new byte[128 * 1024];
        InputStream in = getAssets().open("payload.zip");
        ZipInputStream zis = new ZipInputStream(in);
        ZipEntry e;
        int updated = 0;
        while ((e = zis.getNextEntry()) != null) {
            String name = e.getName();
            boolean isConfig = false;
            for (String p : DSHHOME_CONFIG_PATHS) {
                if (name.equals(p)) { isConfig = true; break; }
            }
            if (!isConfig) { zis.closeEntry(); continue; }
            File target = new File(payload, name);
            File parent = target.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) throw new IOException("mkdir failed: " + parent);
            FileOutputStream fos = new FileOutputStream(target);
            int n;
            while ((n = zis.read(buf)) > 0) fos.write(buf, 0, n);
            fos.close();
            zis.closeEntry();
            updated++;
        }
        zis.close();
        Log.i(TAG, "refreshed " + updated + " dshhome config files");
    }

    // 预扫 payload.zip 统计要处理的条目数（只读 entry 头，不写盘），供进度条使用。
    private int countPayloadEntries(String mode) throws IOException {
        final boolean internalOnly = "internal".equals(mode) || "internal-patch".equals(mode);
        final boolean dshrootOnly = "dshroot".equals(mode);
        InputStream in = getAssets().open("payload.zip");
        ZipInputStream zis = new ZipInputStream(in);
        ZipEntry e;
        int n = 0;
        while ((e = zis.getNextEntry()) != null) {
            String name = e.getName();
            if (e.isDirectory() || name.startsWith("__MACOSX/") || name.startsWith("META-INF/")) { zis.closeEntry(); continue; }
            boolean isDshroot = name.startsWith("dshroot/");
            if (dshrootOnly && !isDshroot) { zis.closeEntry(); continue; }
            if (internalOnly && isDshroot) { zis.closeEntry(); continue; }
            n++;
            zis.closeEntry();
        }
        zis.close();
        return n;
    }

    private void updateProgress(int processed, int total, int written) {
        if (total <= 0) return;
        if (processed != total && processed % 200 != 0) return;
        int pct = (int)(processed * 100L / total);
        setProgress(pct, "首次启动 · 正在解压运行时 " + processed + "/" + total + " 个文件…");
    }

    private void applyLinks(File payload) throws IOException {
        File lib = new File(payload, "runtime/lib");
        File linksFile = new File(lib, "LINKS.txt");
        if (!linksFile.exists()) return;
        BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(linksFile), "UTF-8"));
        String line;
        int n = 0;
        while ((line = r.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] parts = line.split("\\t+");
            if (parts.length < 2) continue;
            String linkName = parts[0].trim();
            String target = parts[1].trim();
            File link = new File(lib, linkName);
            File src = new File(lib, target);
            if (!link.exists() && src.exists()) {
                try {
                    Os.link(src.getAbsolutePath(), link.getAbsolutePath());
                    n++;
                } catch (ErrnoException e1) {
                    try {
                        Os.symlink(target, link.getAbsolutePath());
                        n++;
                    } catch (ErrnoException e2) {
                        try { copyFile(src, link); n++; } catch (IOException e3) {
                            Log.w(TAG, "link failed " + linkName, e3);
                        }
                    }
                }
            }
        }
        r.close();
    }

    private void copyFile(File src, File dst) throws IOException {
        FileInputStream in = new FileInputStream(src);
        FileOutputStream out = new FileOutputStream(dst);
        byte[] b = new byte[128 * 1024];
        int n;
        while ((n = in.read(b)) > 0) out.write(b, 0, n);
        out.close();
        in.close();
    }

    private void setExecutables(File payload) {
        String[] execs = {"runtime/bin/node", "bin/bash", "runtime/bin/rg", "runtime/bin/curl"};
        for (String p : execs) {
            File f = new File(payload, p);
            if (f.exists()) f.setExecutable(true, false);
        }
    }

    private void spawnNode(File payload) throws IOException {
        File node = new File(payload, "runtime/bin/node");
        File binjs = new File(dshrootDir, REL_BINJS);
        File lib = new File(payload, "runtime/lib");
        File home = new File(payload, "dshhome");
        File bin = new File(payload, "bin");
        File tmp = new File(getCacheDir(), "tmp");
        if (!tmp.exists()) tmp.mkdirs();

        if (!node.exists()) throw new IOException("node binary missing");
        if (!binjs.exists()) throw new IOException("dsh bin.js missing");
        if (!node.canExecute()) node.setExecutable(true, false);

        // 注意：Android 兼容补丁（禁用 llm-pi-ai/sandbox/bash-sandbox 的 cordis.patch.yml）
        // 位于 $DSH_HOME/cordis.patch.yml，由 dsh profile-boot 的 homePatches 自动加载，
        // 无需 --patch 参数（重复传入会导致 duplicate loader entry 崩溃）。
        ProcessBuilder pb = new ProcessBuilder(
                node.getAbsolutePath(), "--expose-internals", binjs.getAbsolutePath(),
                "web", "--host", "127.0.0.1", "--port", String.valueOf(enginePort));
        java.util.Map<String, String> env = pb.environment();
        env.put("LD_LIBRARY_PATH", lib.getAbsolutePath());
        env.put("PATH", bin.getAbsolutePath() + ":" +
                new File(payload, "runtime/bin").getAbsolutePath() + ":/system/bin:/system/xbin");
        env.put("HOME", getFilesDir().getAbsolutePath());
        env.put("DSH_HOME", home.getAbsolutePath());
        env.put("TMPDIR", tmp.getAbsolutePath());
        env.put("TERM", "xterm");
        env.put("SHIZUKU_DEX", rishDex != null ? rishDex.getAbsolutePath() : "");
        env.put("SHIZUKU_APP_ID", "com.deepseek.harness");
        // 特权通道可用性：root(su) 或 Shizuku。两者都未授予时，DSH 插件不注册特权工具，
        // AI 不会反复尝试系统操作；文件读写仍可用 DSH 自带的 fs/bash 工具（只需存储权限）。
        env.put("SHIZUKU_AVAILABLE", shizukuAvailable() ? "1" : "0");
        env.put("ROOT_AVAILABLE", rootAvailable() ? "1" : "0");
        env.put("APP_NOTIFY_PORT", String.valueOf(notifyPort()));
        // v1.7 无障碍服务端口（通知端口 + 100，三版本共存不冲突）：插件 dsh-tool-accessibility 经此端口
        // 调用 App 的无障碍服务（读屏/点击/输入/截图）。端口同时写入 dsh_prefs，供无障碍服务读取。
        final int a11yPort = notifyPort() + 100;
        env.put("APP_A11Y_PORT", String.valueOf(a11yPort));
        getSharedPreferences("dsh_prefs", MODE_PRIVATE).edit().putInt("a11y_port", a11yPort).apply();
        // AI 工作区（可选）：用户选择的外部共享存储目录，作为 bash/文件工具的工作根目录
        String ws = workspacePath();
        if (ws != null && !ws.isEmpty()) env.put("DSH_WORKSPACE", ws);
        pb.redirectErrorStream(true);

        final Process proc = pb.start();
        nodeProcess = proc;
        final File logFile = new File(getFilesDir(), "dsh-web.log");
        // v1.7.1：同时镜像一份引擎日志到外部目录（无需 root/adb 可读），
        // 覆盖「node 反复崩溃但 waitForServer 未抛异常」时不产生 startup-diag.txt 的场景。
        final File extLogFile = new File(android.os.Environment.getExternalStorageDirectory(),
                (getPackageName().contains("beta") ? "DeepSeekHarnessLite"
                        : getPackageName().contains("compat") ? "DeepSeekHarnessCompat" : "DeepSeekHarness")
                        + "/dsh-web.log");
        new Thread(new Runnable() {
            @Override public void run() {
                FileOutputStream fos = null;
                FileOutputStream extFos = null;
                try {
                    fos = new FileOutputStream(logFile, true);
                    try {
                        File extParent = extLogFile.getParentFile();
                        if (extParent != null && !extParent.exists()) extParent.mkdirs();
                        extFos = new FileOutputStream(extLogFile, true);
                    } catch (Throwable ignored) {
                    }
                    InputStream is = proc.getInputStream();
                    byte[] b = new byte[4096];
                    int n;
                    while ((n = is.read(b)) > 0) {
                        fos.write(b, 0, n);
                        fos.flush();
                        if (extFos != null) {
                            try { extFos.write(b, 0, n); extFos.flush(); } catch (Throwable ignored) {}
                        }
                        String s = new String(b, 0, n, "UTF-8");
                        for (String line : s.split("\n")) {
                            String t = line.trim();
                            if (!t.isEmpty()) Log.i(TAG, "node: " + t);
                        }
                    }
                } catch (IOException e) {
                    Log.w(TAG, "log reader error", e);
                } finally {
                    try { if (fos != null) fos.close(); } catch (IOException ignored) {}
                    try { if (extFos != null) extFos.close(); } catch (IOException ignored) {}
                }
            }
        }, "node-log").start();
    }

    private boolean healthOk() {
        return isDshEngine(enginePort);
    }

    private void waitForServer() {
        long start = System.currentTimeMillis();
        long deadline = start + 90000;
        while (System.currentTimeMillis() < deadline) {
            if (healthOk()) { loadHome(); executePendingScheduledTask(); return; }
            long waited = (System.currentTimeMillis() - start) / 1000;
            setStatus("正在启动 DeepSeek Harness…（已等待 " + waited + " 秒）");
            try { Thread.sleep(1000); } catch (InterruptedException e) { return; }
        }
        // 超时：带端口提示便于排查（node 日志已写入 files/dsh-web.log）
        Log.e(TAG, "engine start timeout on port " + enginePort + ", check dsh-web.log");
        // v1.5.2 慢启动修复兜底：本次走了「快速同步」（同内核升级），若引擎仍起不来，
        // 可能外部 dshroot 有缺失文件（快速路径不 stat 已有文件）→ 全量补齐后重启引擎再等一轮。
        if (fastSyncedThisBoot) {
            fastSyncedThisBoot = false;
            Log.w(TAG, "fast sync may have missed files, forcing full dshroot repair");
            setStatus("引擎启动超时，正在补齐引擎文件后重试…");
            try {
                File externalRoot = new File(Environment.getExternalStorageDirectory(), EXT_DSHROOT_ROOT);
                extractPayload(new File(getFilesDir(), "payload"), externalRoot, "dshroot");
                writeDshrootComplete(externalRoot);
            } catch (Throwable t) {
                Log.w(TAG, "full repair failed", t);
            }
            try {
                spawnNode(new File(getFilesDir(), "payload"));
            } catch (Throwable t) {
                Log.e(TAG, "respawn after repair failed", t);
            }
            waitForServer();
            return;
        }
        setStatus("引擎启动超时（端口 " + enginePort + "），请重启应用");
        loadHome();
    }

    /** 定时任务自动执行：闹钟到点后引擎就绪，把任务文本作为消息自动发送给 AI（无需用户操作）。 */
    private void executePendingScheduledTask() {
        final String task = pendingScheduledTask;
        pendingScheduledTask = null; // 只执行一次
        if (task == null || task.isEmpty()) return;
        logSchedule("开始自动执行任务: " + task);
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    // 等引擎完全就绪（HTTP 200 后 API 可能还需一点时间）
                    for (int i = 0; i < 20; i++) {
                        if (healthOk()) break;
                        Thread.sleep(1000);
                    }
                    // 调 DSH API：建会话 + 发消息（AI 自动执行任务）
                    String sessionId = createSession();
                    if (sessionId == null) {
                        logSchedule("自动执行失败：无法创建会话（引擎未就绪或无 API Key？）");
                        return;
                    }
                    boolean sent = sendPrompt(sessionId, task);
                    logSchedule(sent ? "任务已发送给 AI 执行: " + task : "任务发送失败: " + task);
                } catch (Throwable t) {
                    logSchedule("自动执行异常: " + t.getMessage());
                }
            }
        }, "scheduled-exec").start();
    }

    /** 调 DSH API 创建会话，返回 sessionId（失败返回 null）。 */
    private String createSession() {
        String json = rpcCall("session.create", "{}");
        if (json == null) return null;
        int i = json.indexOf("\"sessionId\":\"");
        if (i >= 0) {
            int q1 = i + "\"sessionId\":\"".length();
            int q2 = json.indexOf('"', q1);
            if (q2 > q1) return json.substring(q1, q2);
        }
        return null;
    }

    /** 调 DSH API 发送消息（AI 开始执行任务）。 */
    private boolean sendPrompt(String sessionId, String text) {
        String payload = "{\"sessionId\":\"" + sessionId + "\",\"mode\":\"queue\",\"content\":[{\"type\":\"text\",\"text\":\"" + escapeJson(text) + "\"}]}";
        String json = rpcCall("session.prompt", payload);
        return json != null && json.contains("\"ok\":true");
    }

    /** DSH RPC 调用：标准协议 {"type":"client-request","rpcId":"...","method":"...","payload":{...}} */
    private String rpcCall(String method, String payloadJson) {
        try {
            URL url = new URL(homeUrl() + "/api/" + method);
            HttpURLConnection c = (HttpURLConnection) url.openConnection();
            c.setRequestMethod("POST");
            c.setRequestProperty("Content-Type", "application/json");
            c.setDoOutput(true);
            c.setConnectTimeout(3000);
            c.setReadTimeout(5000);
            String rpcId = "sched-" + System.currentTimeMillis();
            String body = "{\"type\":\"client-request\",\"rpcId\":\"" + rpcId + "\",\"method\":\"" + method
                    + "\",\"payload\":" + (payloadJson == null || payloadJson.isEmpty() ? "{}" : payloadJson) + "}";
            c.getOutputStream().write(body.getBytes("UTF-8"));
            int code = c.getResponseCode();
            if (code >= 200 && code < 300) {
                InputStream in = c.getInputStream();
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] b = new byte[4096];
                int n;
                while ((n = in.read(b)) > 0) out.write(b, 0, n);
                in.close();
                c.disconnect();
                return new String(out.toByteArray(), "UTF-8");
            }
            c.disconnect();
        } catch (Throwable t) {
            Log.w(TAG, "rpc " + method + " error", t);
        }
        return null;
    }

    private String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }

    /** node 看门狗：node 进程死亡且服务不可用时自动重启引擎并刷新页面 */
    private void startWatchdog() {
        if (watchdogStarted) return;
        watchdogStarted = true;
        new Thread(new Runnable() {
            @Override public void run() {
                while (!Thread.currentThread().isInterrupted()) {
                    try { Thread.sleep(5000); } catch (InterruptedException e) { return; }
                    try {
                        if (nodeProcess == null) continue;
                        boolean serverUp = healthOk();
                        boolean nodeAlive = nodeProcess.isAlive();
                        if (!serverUp && !nodeAlive) {
                            long now = System.currentTimeMillis();
                            if (now - lastRespawnAt < 20000) continue; // 避免风车重启
                            lastRespawnAt = now;
                            Log.w(TAG, "node died, respawning engine");
                            spawnNode(new File(getFilesDir(), "payload"));
                            final WebView wv = webView;
                            ui.post(new Runnable() {
                                @Override public void run() { wv.loadUrl(homeUrl()); }
                            });
                        }
                    } catch (Throwable t) {
                        Log.w(TAG, "watchdog error", t);
                    }
                }
            }
        }, "node-watchdog").start();
    }

    private void loadHome() {
        startWatchdog();
        ui.post(new Runnable() {
            @Override public void run() {
                statusView.setVisibility(View.GONE);
                if (splashLogo != null) splashLogo.setVisibility(View.GONE);
                if (splashBrand != null) splashBrand.setVisibility(View.GONE);
                if (progressBar != null) {
                    progressBar.setIndeterminate(false);
                    progressBar.setVisibility(View.GONE);
                }
                webView.loadUrl(homeUrl());
            }
        });
    }

    private void setStatus(final String s) {
        ui.post(new Runnable() {
            @Override public void run() { statusView.setText(s); }
        });
    }

    private void setProgress(final int percent, final String s) {
        ui.post(new Runnable() {
            @Override public void run() {
                if (progressBar != null) {
                    progressBar.setIndeterminate(false);
                    progressBar.setVisibility(View.VISIBLE);
                    progressBar.setProgress(percent);
                }
                if (s != null) statusView.setText(s);
            }
        });
    }

    private void showIndeterminate(final String s) {
        ui.post(new Runnable() {
            @Override public void run() {
                if (progressBar != null) {
                    progressBar.setIndeterminate(true);
                    progressBar.setVisibility(View.VISIBLE);
                }
                if (s != null) statusView.setText(s);
            }
        });
    }

    private void hideProgress() {
        ui.post(new Runnable() {
            @Override public void run() {
                if (progressBar != null) {
                    progressBar.setIndeterminate(false);
                    progressBar.setVisibility(View.GONE);
                }
            }
        });
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (webView != null) webView.onPause();
    }

    @Override
    protected void onDestroy() {
        if (webView != null) webView.destroy();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        // 有历史先回退（可关掉侧边栏/返回上一页）；没有历史则询问是否退出
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
            return;
        }
        confirmExit();
    }
}
