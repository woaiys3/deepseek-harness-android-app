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
import android.webkit.WebChromeClient;
import android.webkit.ValueCallback;
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
import moe.shizuku.server.IRemoteProcess;
import moe.shizuku.server.IShizukuService;

public class MainActivity extends Activity {
    private static final String TAG = "DeepSeekHarness";
    // 引擎端口：固定默认端口（v1.5.4 起移除「端口冲突自动换端口」功能，用于排查慢启动是否与其相关）。
    // 共存版（Lite/抢先版）各用独立默认端口，靠包名隔离，不依赖动态切换。
    private int enginePort = 3080;
    private String homeUrl() { return "http://127.0.0.1:" + enginePort; }
    /** 0.1.5 起 web 首页需要一次性 token：引擎启动时会打印带 token 的 URL，
     *  首次必须用它访问（服务器随即下发签名 cookie，后续可回到普通地址），
     *  否则首页返回 401 "authentication required"。这里保存解析到的带 token URL。 */
    private volatile String engineTokenUrl = null;
    /** WebView 与健康探测应使用的地址：拿到 token 就用带 token 的，否则退回普通地址。 */
    private String webHomeUrl() {
        String u = engineTokenUrl;
        return (u != null && !u.isEmpty()) ? u : homeUrl();
    }
    static {
        // 0.1.5 的浏览器认证靠 cookie：带 token 访问首页会下发 dsh-auth-* cookie。
        // HttpURLConnection 默认不保存 cookie，装上全局 CookieManager 后，
        // App 自身的 HTTP 调用（含健康探测）才能像浏览器一样维持会话。
        try { java.net.CookieHandler.setDefault(new java.net.CookieManager()); } catch (Throwable ignored) {}
    }
    // bin.js 相对 dshroot 目录的路径（dshroot 可能位于外部公共目录或内部 fallback）
    private static final String REL_BINJS = "lib/node_modules/@deepseek-ai/dsh/lib/bin.js";
    // 外部 dshroot 公共目录名（挂在 /sdcard 下，卸载不丢；node 二进制/凭证仍留内部）
    // 外部公共根目录不要用常量：它必须按包名派生（正式版 / Lite / 兼容版共存时互不干扰）。
    // 曾硬编码为 "DeepSeekHarness" → Lite/兼容版会写到正式版的外部目录（vscreen jar、外部回退 dshroot
    // 都会串到别的版本上）。统一用 pkgRoot()（见下）。
    // 官方维护、需随 APK 更新的路径前缀：即使外部 dshroot 已有同名文件也强制覆盖
    // （避免"保留 AI 修改"策略挡住官方修复，例如 shizuku 插件的三层补丁）。
    private static final String[] FORCE_OVERWRITE_PREFIXES = {
        "dshroot/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-tool-shizuku/",
        "dshroot/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-tool-android/",
        // v1.9 虚拟屏插件：必须随 APK 覆盖（否则外部/旧 dshroot 里的旧版插件挡住更新 → "未知错误"）
        "dshroot/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-tool-vscreen/",
        // 内核补丁面：以下都是本项目改过或自研的包，必须随 APK 覆盖——
        // 同内核升级走 fast 同步（内核版本号没变）时不会补它们，旧文件会一直挡住修复。
        "dshroot/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-attachment-local/",
        "dshroot/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-subprocess-local/",
        "dshroot/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-bash-local/",
        "dshroot/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-session-persistence-jsonl/",
        "dshroot/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-tool-accessibility/",
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
    /** v1.13：已发出 Shizuku 授权请求、等待结果（用于超时傅底判断）。 */
    private volatile boolean pendingShizukuReq = false;
    private static final int REQ_WORKSPACE_TREE = 400;
    private static final int REQ_FILE_CHOOSER = 500;

    // 悬浮窗前后台联动：App 在前台时隐藏悬浮窗（不挡界面），退后台时显示（随时可查引擎状态）。
    // 由 onStart/onStop 维护；OverlayService 启动时按此标志决定初始可见性。
    public static volatile boolean overlayForeground = true;

    private WebView webView;

    // ---- v1.12 控制台（冷启动首页，原生界面）----
    private FrameLayout engineRoot;                // WebView + 启动浮层 + 控制台的共同根容器
    private ScrollView consoleLayer;               // 控制台覆盖层
    private LinearLayout consoleBody;              // 当前页内容容器
    private int consolePage = 0;                   // 0 控制台 / 1 权限 / 2 插件 / 3 日志
    private boolean consoleVisible = false;
    private boolean consoleDetailOpen = false;      //「已解压」那一行是否展开
    private boolean extracting = false;            // 正在解压（控制台进度）
    private boolean extractOnlyMode = false;       // true：本次只解压，不起引擎
    private boolean filesPreparedThisBoot = false; // 本进程内文件已就绪 → 跳过重复解压
    private volatile boolean starting = false;      // 引擎启动中（跨线程读写：启动流程在后台线程、刷新在主线程）
    private long engineStartTs = 0L;
    private String conExtractMsg = null;
    private String conFilesSummary = null;         //「24,806 个文件 · 214 MB」后台算一次
    private Handler conTick;
    private TextView conExState, conExMeta, conEnState, conEnMeta, conFoot;
    private View conBar, conFill, conSpacer;
    private Button conExBtn, conEnBtn, conEnRestart, conEnStop;
    private View conDetailBox;
    private final Runnable consoleTick = new Runnable() {
        @Override public void run() {
            if (!consoleVisible) return;
            refreshConsole();
            if (conTick != null) conTick.postDelayed(this, 1000);
        }
    };
    // 网页 <input type="file"> 选完文件后的回调（见 onShowFileChooser）
    private ValueCallback<Uri[]> fileChooserCallback;
    private TextView statusView;
    private ProgressBar progressBar;
    private ImageView splashLogo;
    private TextView splashBrand;
    private final Handler ui = new Handler(Looper.getMainLooper());
    // 运行时确定的 dshroot 目录（外部公共目录优先，失败回退内部 files/payload/dshroot）
    private File dshrootDir = null;
    private boolean watchdogStarted = false;
    private long lastRespawnAt = 0L;
    private volatile boolean engineStartAborted = false;   // v1.13：「停止」打断在飞的启动等待（否则状态栏一直计时到 90s 超时）
    private volatile boolean engineStoppedByUser = false;  // v1.13：用户主动停止 → 看门狗不得再自动拉起引擎
    // v1.13：引擎存活探测结果缓存。控制台的每秒刷新在主线程跑，一旦直接做 HTTP 探测就会抛
    // NetworkOnMainThreadException（被 catch 吞掉）→ 引擎明明在跑也永远判“未启动”
    // → 用户再点一次「启动引擎」就又拉起一个 node → EADDRINUSE（用户实测的“启动一会又变回去”）。
    private volatile boolean engineAliveCached = false;
    private volatile long engineProbeAt = 0L;
    private volatile boolean engineProbeBusy = false;
    private volatile boolean notifyServerStarted = false;  // v1.13：通知通道幂等启动标记
    // 引擎 node 进程
    private Process nodeProcess = null;
    // v1.5.2 慢启动修复：本次启动走了「快速同步」（同内核升级，只补白名单+REVISION）。
    // 若引擎启动超时，用它触发一次全量补齐（防止快速路径漏掉缺失文件）。
    private volatile boolean fastSyncedThisBoot = false;

    // 权限界面
    private final List<PermRow> permRows = new ArrayList<>();
    private File rishDex;
    private File vscreenDex;
    private boolean pendingVscreenExtract;
    private static volatile IRemoteProcess vscreenProc;
    private long lastVscreenEnsureTs = 0;
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
        enginePort = defaultEnginePort(this); // 三版本各自独立端口（见 defaultEnginePort）
        installCrashHandler();
        checkAbiCompat(); // ② ABI 检测：非 arm64 设备引擎可能无法运行，弹提示
        checkBatteryOptimization(); // ④ 电池优化引导：被限制时提示（挂后台可能被杀）
        // v1.12：不再在启动时自动检查更新（用户要求）；改为控制台底部的「检查更新」手动触发。

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
                        @Override public void run() { wv.loadUrl(webHomeUrl()); }
                    }, 2500L);
                }
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                errorRetries = 0;
            }
        });

        // 附件/文件选择：官方前端用 <input type="file"> 选文件，Android WebView 必须实现
        // onShowFileChooser 才会弹系统文件选择器，否则点「添加附件」没有任何反应。
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (fileChooserCallback != null) {
                    fileChooserCallback.onReceiveValue(null);
                }
                fileChooserCallback = callback;
                try {
                    Intent intent = params.createIntent();
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    startActivityForResult(intent, REQ_FILE_CHOOSER);
                    return true;
                } catch (Throwable t) {
                    Log.w(TAG, "file chooser failed", t);
                    fileChooserCallback = null;
                    return false;
                }
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
        vscreenDex = extractVscreenDex();
        // 虚拟屏接入桥：启动 Operit server + HTTP->binder 转发（插件走 8999）
        try {
            Class<?> bCls = Class.forName(getPackageName() + ".VsreenBridgeService");
            startService(new Intent(this, bCls));
        } catch (Throwable t) {
            Log.w(TAG, "start VsreenBridgeService failed", t);
        }
        // 存储权限未授予时自动请求（写 /sdcard 提取 vscreen jar 需要；Android 10+ targetSdk28 必须运行时授权），
        // 授权回调里重新提取外部 jar（首次启动提取会 EACCES，不弹窗用户根本不知道要授权）。
        try {
            if (checkSelfPermission("android.permission.WRITE_EXTERNAL_STORAGE") != PackageManager.PERMISSION_GRANTED) {
                pendingVscreenExtract = true;
                requestPermissions(new String[]{
                        "android.permission.READ_EXTERNAL_STORAGE",
                        "android.permission.WRITE_EXTERNAL_STORAGE"}, REQ_STORAGE);
            }
        } catch (Throwable ignored) {}

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
                    pendingShizukuReq = false;   // v1.13：无论结果都结束“等待授权”状态
                    shizukuOk = grantResult == PackageManager.PERMISSION_GRANTED;
                    Log.i(TAG, "shizuku permission result: " + grantResult);
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
            // v1.12：冷启动先停在控制台；切屏回来/任务恢复（savedInstanceState != null）直接进主界面。
            if (savedInstanceState == null) {
                showConsole();
            } else {
                startEngine();
            }
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
        if (engineRoot == null) engineRoot = new FrameLayout(this);
        FrameLayout root = engineRoot;
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

        setContentView(root);
    }

    /** 退出确认对话框（浮动按钮与系统返回键共用） */
    private void confirmExit() {
        conDialog("退出 DeepSeek Harness", "确定要退出吗？服务器将停止运行。", "退出", new Runnable() {
            @Override public void run() {
                stopKeepAliveService(); // 用户主动退出：停止保活服务
                finish();
            }
        }, "取消");
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
                        conDialog("建议：允许后台运行",
                                "当前应用被系统限制后台活动，AI 执行任务时挂后台可能被系统杀掉。\n\n建议把本应用设为「不限制」电池优化，确保任务持续运行。",
                                "去设置", new Runnable() { @Override public void run() {
                                    openSystemSetting(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                                }}, "暂不");
                    } catch (Throwable ignored) {}
                }
            });
        } catch (Throwable t) {
            Log.w(TAG, "checkBatteryOptimization error", t);
        }
    }

    // ⑧ 更新提示：后台查 GitHub Releases 最新 tag，与本地 versionName 比对，有新版弹提示
    private void checkForUpdate(final boolean manual) {
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    URL url = new URL("https://api.github.com/repos/woaiys3/deepseek-harness-android-app/releases/latest");
                    HttpURLConnection c = (HttpURLConnection) url.openConnection();
                    c.setConnectTimeout(5000);
                    c.setReadTimeout(5000);
                    c.setRequestProperty("User-Agent", "dsh-android");
                    int code = c.getResponseCode();
                    if (code != 200) { c.disconnect(); if (manual) ui.post(new Runnable() { @Override public void run() { conToast("检查更新失败（网络）"); } }); return; }
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
                    if (tag == null || tag.isEmpty()) {
                        if (manual) ui.post(new Runnable() { @Override public void run() { conToast("检查更新失败（响应异常）"); } });
                        return;
                    }
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
                                    conDialog("发现新版本 " + ftag,
                                            "当前版本 " + fLocal + "，最新 " + ftag + "。\n\n前往 GitHub Releases 下载更新（正式版 / Lite 共存版可选）。",
                                            "去下载", new Runnable() { @Override public void run() {
                                                try {
                                                    startActivity(new Intent(Intent.ACTION_VIEW,
                                                            Uri.parse("https://github.com/woaiys3/deepseek-harness-android-app/releases")));
                                                } catch (Throwable ignored) {}
                                            }}, "稍后");
                                } catch (Throwable ignored) {}
                            }
                        });
                    } else if (manual) {
                        ui.post(new Runnable() { @Override public void run() { conToast("已是最新版本（" + fLocal + "）"); } });
                    }
                } catch (final Throwable t) {
                    // 网络失败/离线：启动时的自动检查静默跳过；手动检查给反馈
                    if (manual) ui.post(new Runnable() { @Override public void run() { conToast("检查更新失败：" + t.getMessage()); } });
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
                    File dir = new File(Environment.getExternalStorageDirectory(), pkgRoot());
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
        if (requestCode == REQ_FILE_CHOOSER) {
            if (fileChooserCallback != null) {
                Uri[] picked = null;
                if (resultCode == RESULT_OK && data != null) {
                    if (data.getClipData() != null) {
                        int count = data.getClipData().getItemCount();
                        picked = new Uri[count];
                        for (int i = 0; i < count; i++) picked[i] = data.getClipData().getItemAt(i).getUri();
                    } else if (data.getData() != null) {
                        picked = new Uri[]{data.getData()};
                    }
                }
                fileChooserCallback.onReceiveValue(picked);
                fileChooserCallback = null;
            }
            return;
        }
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

        // v1.13：**实时探测**，不信 shizukuOk 缓存。
        // 缓存由 probeShizuku() 异步刷新，而用户“在 Shizuku 里撤销授权 → 回来马上点授权”时缓存
        // 往往还是旧的 true → 会走“已授权”分支而不调 requestPermission → 表现为“点了没任何弹窗”。
        boolean shizukuNow = false;
        int selfPerm = -1, apiVer = -1;
        boolean rationale = false;
        try {
            if (binderOk) {
                selfPerm = Shizuku.checkSelfPermission();
                shizukuNow = selfPerm == PackageManager.PERMISSION_GRANTED;
                try { apiVer = Shizuku.getVersion(); } catch (Throwable ignored) {}
                try { rationale = Shizuku.shouldShowRequestPermissionRationale(); } catch (Throwable ignored) {}
            }
        } catch (Throwable t) { Log.w(TAG, "Shizuku state probe failed", t); }
        if (binderOk) shizukuOk = shizukuNow;   // 用实时值刷新缓存
        Log.i(TAG, "shizuku state: binder=" + binderOk + " selfPerm=" + selfPerm
                + " apiVer=" + apiVer + " rationale=" + rationale);

        if (rootOkNow || shizukuNow) {
            AlertDialog.Builder b = new AlertDialog.Builder(this);
            b.setTitle("系统特权（可选）");
            b.setMessage((rootOkNow ? "已检测到 Root（su）可用，AI 可以执行系统级操作。\n" : "") +
                    (shizukuNow ? "Shizuku 已授权，AI 可以执行系统级操作。\n" : "") +
                    "\n不授予特权也能正常使用：文件读写、预览、编辑只需「所有文件访问」权限。");
            b.setNegativeButton("关闭", null);
            b.show();
        } else if (binderOk) {
            // 服务在运行但未授权 → 请求 Shizuku 弹授权框；若系统/ROM 没弹出来，8 秒后引导手动授权
            requestShizukuPermission(installed);
        } else {
            fallbackShizukuDialog(installed);
        }
    }

    /**
     * 请求 Shizuku 授权 + **超时傅底**。
     * v1.13：授权框由 Shizuku 应用弹出；在部分 ROM（ColorOS 等）上它可能被拦截/不弹，
     * 而 requestPermission 本身不报错也不回调 —— 用户看到的就是“点了没任何反应”。
     * 这里过 8 秒仍未拿到结果，就弹一个“怎么手动授权”的引导框（附当前状态供排查）。
     */
    private void requestShizukuPermission(final boolean installed) {
        pendingShizukuReq = true;
        try {
            Shizuku.requestPermission(REQ_SHIZUKU);
        } catch (Throwable t) {
            Log.w(TAG, "Shizuku requestPermission failed", t);
        }
        // v1.13.3：真机实测本机（Shizuku 13.6.0 + ColorOS 15）上 requestPermission() 不弹框，
        // 而“引擎里 AI 调 rish”能弹 —— 因为 Shizuku 的授权框是 Shizuku 应用在收到**真实请求**时才弹。
        // 所以这里补两条与引擎同款的真实触发；授权框弹出后用户点允许，3 秒后回探一次即可反映到界面。
        triggerShizukuPrompt();
        ui.postDelayed(new Runnable() {
            @Override public void run() { probeShizuku(); }
        }, 3000L);
        ui.postDelayed(new Runnable() {
            @Override public void run() {
                if (!pendingShizukuReq) return;   // 已经回调过（用户处理了）
                pendingShizukuReq = false;
                showShizukuGuideDialog(installed);
            }
        }, 12000L);
    }

    /**
     * 发两条**真实请求**逼 Shizuku 弹授权框（requestPermission 在本机不弹）：
     *   ① 向 Shizuku 应用要 binder（client provider 路径）；
     *   ② 用 App 自己 spawn 一个 rish（与引擎里 rish 完全同款：同样的 dex、同样的 env 清理、同样的广播）。
     * ② 会超时（5 秒广播预算）也没关系——我们要的是它在 Shizuku 应用侧触发的授权框。
     */
    private void triggerShizukuPrompt() {
        new Thread(new Runnable() {
            @Override public void run() {
                try { Shizuku.getBinder(); } catch (Throwable ignored) {}
            }
        }, "shizuku-binder-warm").start();
        new Thread(new Runnable() {
            @Override public void run() {
                Process p = null;
                try {
                    File dex = (rishDex != null && rishDex.exists()) ? rishDex : extractRishDex();
                    if (dex == null || !dex.exists()) {
                        Log.w(TAG, "triggerShizukuPrompt: rish dex 不可用");
                        return;
                    }
                    try { android.system.Os.chmod(dex.getAbsolutePath(), 0444); } catch (Throwable ignored) {}
                    ProcessBuilder pb = new ProcessBuilder(
                            "/system/bin/app_process",
                            "-Djava.class.path=" + dex.getAbsolutePath(),
                            "/system/bin",
                            "--nice-name=rish",
                            "rikka.shizuku.shell.ShizukuShellLoader",
                            "-c", "id");
                    java.util.Map<String, String> env = pb.environment();
                    env.remove("LD_LIBRARY_PATH");
                    env.remove("LD_PRELOAD");
                    env.remove("LD_DEBUG");
                    env.put("RISH_APPLICATION_ID", getPackageName());
                    pb.redirectErrorStream(true);
                    p = pb.start();
                    try { p.waitFor(9, java.util.concurrent.TimeUnit.SECONDS); } catch (Throwable ignored) {}
                } catch (Throwable t) {
                    Log.w(TAG, "triggerShizukuPrompt(rish) failed", t);
                } finally {
                    try { if (p != null) p.destroy(); } catch (Throwable ignored) {}
                }
            }
        }, "shizuku-prompt-rish").start();
    }

    /** 授权框没弹出来时的引导（去 Shizuku 里手动授权），并带上当前状态便于定位。 */
    private void showShizukuGuideDialog(boolean installed) {
        String label = getPackageName();
        try {
            CharSequence l = getApplicationInfo().loadLabel(getPackageManager());
            if (l != null && l.length() > 0) label = l + "（" + getPackageName() + "）";
        } catch (Throwable ignored) {}
        String msg = "Shizuku 的授权框没有出现（常见于 ColorOS/OPPO 等系统拦截了它的弹窗）。"
                + "\n\n请手动授权：\n"
                + "1. 打开 Shizuku 应用\n"
                + "2. 进入「已授权的应用」（或首页的「授权应用」）\n"
                + "3. 找到 " + label + " → 打开开关\n\n"
                + "授权后回到本页会自动刷新。";
        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle("Shizuku 授权（需手动）");
        b.setMessage(msg);
        b.setPositiveButton("打开 Shizuku", new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface d, int w) { openShizukuApp(); }
        });
        b.setNeutralButton("重新检测", new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface d, int w) { probeShizuku(); conToast("已重新检测"); }
        });
        b.setNegativeButton("关闭", null);
        b.show();
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
                // Shizuku 可用时确保 vscreen server 已启动（特权进程由 App 持有 → 不被命令会话清理）
                if (ok) {
                    // v1.10：虚拟屏服务由 App 进程内嵌启动（VsreenBridgeService → Main.start），
                    // 不再用 Shizuku newProcess 启动旧 jar 的 VirtualScreenServer —— 双 server 抢
                    // 8999 会导致 displayId 状态错乱（153/154）且旧 shell server SIGABRT。
                    // Shizuku 仅保留用于 input 注入 / am start --display（vscreen 交互）。
                    // try { ensureVscreenServer(); } catch (Throwable ignored) {}
                }
                ui.post(new Runnable() { @Override public void run() { refreshAllStatuses(); } });
            }
        }, "shizuku-probe").start();
    }

    /**
     * 用 Shizuku newProcess 启动 vscreen server：
     * 之前用 rish -c 后台 & 启动，Shizuku 命令会话结束会杀子进程（用户 Android 15 实测 server 消失）——
     * newProcess 创建的是独立进程（App 持有 IRemoteProcess），不被会话清理，这是 Operit 验证过的存活方案。
     */
    private void ensureVscreenServer() {
        try {
            long now = System.currentTimeMillis();
            if (now - lastVscreenEnsureTs < 5000) return;  // 5 秒频率限制
            lastVscreenEnsureTs = now;
            if (vscreenAlive()) return;  // server 已在监听
            File src = vscreenDex;
            if (src == null || !src.exists()) return;
            if (!shizukuAvailable()) return;
            android.os.IBinder binder = Shizuku.getBinder();
            if (binder == null) return;
            IShizukuService svc = IShizukuService.Stub.asInterface(binder);
            if (svc == null) return;
            // 1) 特权拷贝 jar 到 /data/local/tmp（App uid 写不了该目录；等待完成）
            IRemoteProcess cp = svc.newProcess(new String[]{ "/system/bin/cp", "-f", src.getAbsolutePath(), "/data/local/tmp/vscreen_shizuku.jar" }, null, null);
            if (cp != null) { cp.waitFor(); }
            // 2) chmod 644（保证可读）
            IRemoteProcess ch = svc.newProcess(new String[]{ "/system/bin/chmod", "644", "/data/local/tmp/vscreen_shizuku.jar" }, null, null);
            if (ch != null) { ch.waitFor(); }
            // 3) 启动 server（长驻；保存引用防 GC）—— Operit 同款启动方式：
            //    CLASSPATH=... app_process / <Main>（cmd-dir 用 /，不用 /system/bin；Operit 实测在这类设备可用）
            String[] env = new String[]{ "CLASSPATH=/data/local/tmp/vscreen_shizuku.jar" };
            vscreenProc = svc.newProcess(new String[]{
                    "/system/bin/app_process",
                    "/",
                    "com.deepseek.harness.vscreen.VirtualScreenServer"
            }, env, null);
            Log.i(TAG, "vscreen server start issued via Shizuku newProcess");
        } catch (Throwable t) {
            Log.w(TAG, "ensureVscreenServer failed", t);
        }
    }

    private boolean vscreenAlive() {
        try {
            java.net.Socket s = new java.net.Socket();
            s.connect(new java.net.InetSocketAddress("127.0.0.1", 8999), 500);
            s.close();
            return true;
        } catch (Throwable t) { return false; }
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
            if (dex.exists() && dex.length() > 0) {
                // v1.13.6：升级用户走的就是这一支（旧文件不会被重写），而旧版留下的副本可能是 0666
                // → 同样会被 ART 拒绝加载（静默 SIGABRT），所以“已存在”分支也要收权。
                secureDexPermissions(dex);
                return dex;
            }
            InputStream in = getAssets().open("rish_shizuku.dex");
            FileOutputStream out = new FileOutputStream(dex);
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
            out.close();
            in.close();
            // v1.13.5：刚写出来的是 rw-rw-rw-，而 Android 14+ 拒绝加载可写 dex（SIGABRT）——
            // 插件侧每次调用会 chmod，但“App 自己刚提取、插件还没跑”的窗口期同样会中招，所以这里就收权。
            secureDexPermissions(dex);
            return dex;
        } catch (Exception e) {
            Log.w(TAG, "extract rish dex failed", e);
            return null;
        }
    }

    private String pkgRoot() {
        String p = getPackageName();
        return p.contains("beta") ? "DeepSeekHarnessLite"
                : p.contains("compat") ? "DeepSeekHarnessCompat" : "DeepSeekHarness";
    }

    /**
     * 把 APK 内置 payload.zip 里的自定义引擎插件强制覆盖到内部 payload 目录。
     * 只处理 @deepseek-ai/dsh-tool-{vscreen,android,accessibility,shizuku} 与 dsh-bash-local，
     * 均为官方维护、必须随 APK 更新的插件，体积很小（几十 KB）。
     */
    private void refreshEnginePluginsFromPayload(File payloadDir) {
        final String[] MARKS = {
                "/dsh-tool-vscreen/",
                "/dsh-tool-android/",
                "/dsh-tool-accessibility/",
                "/dsh-tool-shizuku/",
                "/dsh-bash-local/"
        };
        int copied = 0;
        java.util.zip.ZipInputStream zis = null;
        try {
            zis = new java.util.zip.ZipInputStream(
                    new java.io.BufferedInputStream(getAssets().open("payload.zip")));
            java.util.zip.ZipEntry e;
            byte[] buf = new byte[16384];
            while ((e = zis.getNextEntry()) != null) {
                if (e.isDirectory()) {
                    continue;
                }
                String name = e.getName();
                boolean hit = false;
                for (String m : MARKS) {
                    if (name.contains(m)) { hit = true; break; }
                }
                if (!hit) {
                    continue;
                }
                File out = new File(payloadDir, name);
                File parent = out.getParentFile();
                if (parent == null || (!parent.exists() && !parent.mkdirs())) {
                    continue;
                }
                java.io.FileOutputStream fos = new java.io.FileOutputStream(out);
                int n;
                while ((n = zis.read(buf)) > 0) {
                    fos.write(buf, 0, n);
                }
                fos.close();
                copied++;
            }
        } catch (Throwable t) {
            Log.w(TAG, "refreshEnginePluginsFromPayload failed: " + t.getMessage());
        } finally {
            if (zis != null) { try { zis.close(); } catch (Throwable ignored) {} }
        }
        Log.i(TAG, "engine plugins refreshed from payload.zip, files=" + copied);
    }

    private File extractVscreenDex() {
        // 优先提取到外部共享目录（/sdcard/<EXT_DSHROOT_ROOT>/vscreen/）：
        // shell(uid 2000) 读不到 app 私有目录（SELinux + 权限双重拦截），但能读 /sdcard ——
        // 插件需以特权 shell 把 jar 拷到 /data/local/tmp 供 app_process 加载。
        // 每次启动强制覆盖（旧 jar 残留会导致加载旧版崩溃：NoSuchMethodException / ClassNotFoundException）。
        try {
            File extDir = new File(Environment.getExternalStorageDirectory(), pkgRoot() + "/vscreen");
            if (extDir.exists() || extDir.mkdirs()) {
                File out = new File(extDir, "vscreen_shizuku.jar");
                InputStream in = getAssets().open("vscreen_shizuku.jar");
                FileOutputStream fos = new FileOutputStream(out);
                byte[] b = new byte[8192];
                int n;
                while ((n = in.read(b)) > 0) fos.write(b, 0, n);
                fos.close();
                in.close();
                return out;
            }
        } catch (Exception e) {
            Log.w(TAG, "extract vscreen jar to external failed, fallback internal", e);
        }
        // 降级：私有目录（未授予存储权限时；shell 读不到，但至少 App 自身逻辑可用）
        try {
            File dir = new File(getFilesDir(), "vscreen");
            if (!dir.exists()) dir.mkdirs();
            File dex = new File(dir, "vscreen_shizuku.jar");
            InputStream in = getAssets().open("vscreen_shizuku.jar");
            FileOutputStream out = new FileOutputStream(dex);
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
            out.close();
            in.close();
            return dex;
        } catch (Exception e) {
            Log.w(TAG, "extract vscreen jar failed", e);
            return null;
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        // 存储权限授予后重新提取 vscreen jar 到外部目录（首次启动时授权在提取之后才完成）
        if (code == REQ_STORAGE && pendingVscreenExtract) {
            pendingVscreenExtract = false;
            for (int i = 0; i < perms.length; i++) {
                if ("android.permission.WRITE_EXTERNAL_STORAGE".equals(perms[i])
                        && results[i] == PackageManager.PERMISSION_GRANTED) {
                    try {
                        File f = extractVscreenDex();
                        if (f != null) {
                            vscreenDex = f;
                            Log.i(TAG, "vscreen jar re-extracted after permission grant: " + f);
                        }
                    } catch (Throwable t) {
                        Log.w(TAG, "vscreen re-extract failed", t);
                    }
                }
            }
        }
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
        engineStartAborted = false;    // v1.13：重新启动 → 清掉「停止」留下的中止/抑制标记
        engineStoppedByUser = false;
        startKeepAliveService();   // 前台保活：挂后台不被杀（引擎持续运行）
        // 引擎端口持久化（供 OverlayService/其他组件读取）；已授权悬浮窗时自动拉起小鲸鱼
        try {
            getSharedPreferences("dsh_prefs", MODE_PRIVATE)
                    .edit().putInt("engine_port", enginePort).apply();
        } catch (Throwable ignored) {}
        if (Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this)) {
            startOverlayService();
        }
        // v1.10（Operit 方案）：不再请求 MediaProjection 授权（用户反感弹窗；Operit 主 App 也不用）。
        // 虚拟屏承载外部 App 内容由 PUBLIC|PRESENTATION 建屏实现（真机 Android 15 验证）。
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    // v1.5.4：已移除「端口冲突自动换端口」（resolveEnginePort/portInUse/saveEnginePort/engine_port 持久化），
                    // 引擎固定默认端口启动，用于排查慢启动是否与端口探测相关。
                    // 通知通道在后台线程启动（端口 = enginePort+1）。
                    startNotifyServer();

                    File payload = payloadDir();
                    // v1.12：解压与启动拆开。「解压文件」把 extractOnlyMode 置 true，跑到下面
                    // ensurePatchConfig 为止就返回；「启动引擎」复用同一入口，但 filesPreparedThisBoot
                    // 已置位 → 整段文件准备被跳过。
                    if (!filesPreparedThisBoot) {
                    File done = new File(payload, ".extracted");

                    // v1.5.3 慢启动根因修复：内核目录改为【内部存储优先】。
                    // 现象：v1.5.x 真机启动 50-60s（v1.4 的 10s），payload/node/启动参数逐字节对比无差异，
                    // 模拟器正常（4s）→ 根因是 node 每次启动从【外部 /sdcard（FUSE）】读取 2 万+ 内核文件，
                    // require() 解析时海量 stat/read 过 FUSE 极慢（真机如此，模拟器宿主机磁盘快测不出）。
                    // 修复：node 恒从内部存储（files/payload/dshroot）读内核（快、可靠）；
                    // 外部目录仅作【内部空间不足】时的回退，以及保留 .nomedia/相册保护等兼容逻辑。
                    File externalRoot = new File(Environment.getExternalStorageDirectory(), pkgRoot());
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

                    // 引擎插件强制刷新（见 refreshEnginePluginsFromPayload 注释）
                    refreshEnginePluginsFromPayload(payload);

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
                            boolean layoutChanged = dshrootLayoutChanged(internalBase);
                            boolean full = dshrootNeedsFullSync(internalBase);
                            fastSyncedThisBoot = !full;
                            // 布局变更：先整棵清掉旧 dshroot 再落地。全量覆盖只写文件不删多余项，
                            // 旧树的嵌套副本会被 Node 优先解析到（补丁包/依赖树换过就失效）。
                            if (layoutChanged) {
                                try { deleteRecursive(internalDshroot); } catch (Throwable ignored) {}
                            }
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
                                boolean layoutChanged = dshrootLayoutChanged(externalRoot);
                                boolean full = dshrootNeedsFullSync(externalRoot);
                                if (layoutChanged) {
                                    try { deleteRecursive(new File(externalRoot, "dshroot")); } catch (Throwable ignored) {}
                                }
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
                    secureDexFiles(payload); // v1.13.5：兜底把旧树里已有的 dex 也收成 0444（覆盖升级不会被重写）
                    ensurePatchConfig(payload); // ③ 补丁启动自检：cordis.patch.yml 缺失/被改则自动补齐
                    filesPreparedThisBoot = true;
                    conMarkPayloadDone();   // 记录“内部这棵树是本次安装解压的”（供控制台/校验判定）
                    } // end if (!filesPreparedThisBoot)
                    if (extractOnlyMode) {
                        // 控制台「解压文件」：到此为止，不碰引擎
                        extractOnlyMode = false;
                        extracting = false;
                        ui.post(new Runnable() { @Override public void run() { conExtractDone(); } });
                        return;
                    }
                    launchEngine(payload);
                } catch (Throwable t) {
                    Log.e(TAG, "engine error", t);
                    String msg = String.valueOf(t.getMessage());
                    filesPreparedThisBoot = false;
                    final boolean wasExtract = extractOnlyMode;
                    extractOnlyMode = false;
                    extracting = false;
                    starting = false;
                    setStatus("引擎启动失败：" + msg);
                    writeStartupDiag(msg);
                    final String m = msg;
                    ui.post(new Runnable() { @Override public void run() {
                        if (wasExtract) conExtractFailed(m); else conEngineFailed(m);
                    } });
                }
            }
        }, "engine-boot").start();
    }

    /** v1.7：启动失败时把引擎日志尾部与状态写进外部目录，用户无需 adb 即可反馈排查。 */
    private void writeStartupDiag(String errorMsg) {
        try {
            String sub = getPackageName().contains("beta") ? "DeepSeekHarnessLite"
                    : getPackageName().contains("compat") ? "DeepSeekHarnessCompat" : "DeepSeekHarness";
            File dir = new File(android.os.Environment.getExternalStorageDirectory(), sub);
            if (!dir.exists()) dir.mkdirs();
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
            File out = new File(dir, "startup-diag.txt");
            FileOutputStream fos = new FileOutputStream(out);
            fos.write(sb.toString().getBytes("UTF-8"));
            fos.close();
            Log.i(TAG, "启动诊断已写入 " + out.getAbsolutePath());
        } catch (Throwable ignored) {
        }
    }

    // ============ ③ 补丁启动自检 ============
    /** 检查内部 dshhome/cordis.patch.yml 是否完整（含 marker 与禁用的插件），
     *  缺失/被外部改动破坏/版本落后则从 payload.zip 重新提取官方配置（幂等）。
     *  背景：补丁配置被改/删会导致 sandbox/bash-sandbox 启用失败 → 启动崩溃。
     *  v1.12：改用顶部 marker 判定版本。旧实现靠“内容里必须有 llm-pi-ai”判定，
     *        而 v1.12 起 llm-pi-ai 已取消禁用 → 升级用户会被判为“不完整”并自动落地新配置
     *        （正是我们想要的迁移效果）。 */
    private static final String PATCH_CONFIG_MARKER = "dsh-android-patch: v2";

    private void ensurePatchConfig(File payload) {
        try {
            File patch = new File(payload, "dshhome/cordis.patch.yml");
            boolean need = !patch.exists();
            if (!need) {
                String content = readFileText(patch);
                // marker 缺失/落后，或关键禁用项缺失 → 视为需重新落地
                need = !(content.contains(PATCH_CONFIG_MARKER) && content.contains("sandbox")
                        && content.contains("bash-sandbox") && content.contains("disabled: true"));
            }
            if (need) {
                Log.w(TAG, "cordis.patch.yml missing or incomplete, restoring from payload.zip");
                refreshInternalConfig(payload); // 重新覆盖 dshhome 官方配置（凭证/会话保留）
            } else {
                conHealPatchConfig();   // v1.13：结构自愈（控制台旧实现关插件会写坏它，见 conHealPatchConfig）
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
            // 0.1.5：首页需要 token（否则 401 authentication required）。
            // ⚠ 探测**绝不能带 token**：token 是一次性的（用过即废），若被探测吃掉，
            // 随后 WebView 拿同一个 token 加载就会 401（用户看到的白屏/黑字就是这个）。
            // 探测只用不带 token 的 /：401 + DSH 专属正文 也足以证明“是本引擎且在跑”。
            String probe = "http://127.0.0.1:" + port + "/";
            c = (HttpURLConnection) new URL(probe).openConnection();
            c.setConnectTimeout(1200);
            c.setReadTimeout(1500);
            c.setRequestProperty("User-Agent", "dsh-probe");
            // 0.1.5：带 token 访问首页会返回 303 + Set-Cookie（浏览器会话 cookie），
            // 而 HttpURLConnection 默认不保存 cookie → 跟随重定向后又变 401 → 探测永远失败
            // （旧实现表现为干等 90 秒超时才进兜底）。这里不跟随重定向，把 303/302
            // 直接当作“引擎已就绪且 token 有效”，正文标题校验只在普通 200 路径上做。
            c.setInstanceFollowRedirects(false);
            int code = c.getResponseCode();
            if (code == 303 || code == 302) return true;
            if (code == 401) {
                // v1.12：0.1.5 引擎未带 token 时返回 401 + DSH 专属正文。
                // 旧实现直接当“不是引擎” → 引擎明明在跑，控制台与健康探测却永远判未就绪
                // （用户实测：点完「启动引擎」界面又退回「启动引擎」）。
                String b401 = conReadBody(c, 4096);
                return b401 != null && b401.indexOf("dsh web authentication required") >= 0;
            }
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

    private int notifyPort() { return enginePort + 1; }

    /** 启动本地通知监听：AI 通过插件请求 http://127.0.0.1:<notifyPort> 发通知（仅需通知权限）。 */
    private void startNotifyServer() {
        // v1.13：幂等 —— 重复调用（用户连点「启动引擎」、看门狗重试）会对同一端口二次 bind，
        // 真机日志里表现为“notify server stopped + EADDRINUSE”，期间通知通道短暂不可用。
        if (notifyServerStarted) return;
        notifyServerStarted = true;
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
                    notifyServerStarted = false;   // v1.13：bind 失败（端口被占）时允许下次重试
                } finally {
                    try { if (ss != null) ss.close(); } catch (Throwable ignored) {}
                    notifyServerStarted = false;
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
                    if (path.startsWith("/shell")) {
                        // v1.13.1：App 进程内的特权执行（Shizuku API 通道）——见 handleShellRequest
                        respBody = handleShellRequest(body.toString());
                    } else if (path.startsWith("/setting")) {
                        respBody = handleSettingRequest(body.toString());
                    } else if (path.startsWith("/clipboard")) {
                        respBody = handleClipboardRequest(body.toString());
                    } else if (path.startsWith("/schedule")) {
                        respBody = handleScheduleRequest(body.toString());
                    } else if (path.startsWith("/usage")) {
                        respBody = handleUsageRequest(path, body.toString());
                    } else if (path.startsWith("/overlay")) {
                        respBody = handleOverlayRequest(path, body.toString());
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
    private String handleUsageRequest(String path, String raw) {
        try {
            String days = jsonField(raw, "days");
            if (days.isEmpty()) days = queryField(path, "days");
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

    /**
     * POST /shell {"command":"…","timeout_ms":N,"token":"…"} —— 在 **App 进程内**经 Shizuku API
     * 以 shell 身份执行命令并回收 stdout/stderr/退出码。
     *
     * 为什么不继续用引擎里的 rish：Shizuku 服务端校验「某个包是否被授权」时要回头问 Shizuku 应用本体，
     * 而 ColorOS 会冻结/查杀 Shizuku 应用（真机日志实锤：`OplusHansManager … F exit()`、
     * `NativeFreezeManager … mFgAppPkgname moe.shizuku.privileged.api`、
     * `reason=EXCESSIVE CPU USAGE … excessive binder traffic during cached state`），
     * 于是引擎内 spawn 出来的 rish 子进程等不到响应 → Shizuku 客户端库报 “Request timeout…”。
     * App 进程内的 Shizuku API 通道实测稳定（虚拟屏核心就是用它拉起的），因此特权执行改走这里。
     */
    private String handleShellRequest(String raw) {
        try {
            String token = jsonField(raw, "token");
            String mine = localToken();
            if (mine.isEmpty() || !mine.equals(token)) {
                return "{\"ok\":false,\"error\":\"token 校验失败（该接口仅限本应用引擎调用）\"}";
            }
            String command = jsonField(raw, "command");
            if (command == null || command.isEmpty()) {
                return "{\"ok\":false,\"error\":\"缺少 command 参数\"}";
            }
            int timeoutMs = 30000;
            String tm = jsonField(raw, "timeout_ms");
            if (!tm.isEmpty()) {
                try { timeoutMs = Math.max(1000, Math.min(Integer.parseInt(tm.trim()), 120000)); }
                catch (Exception ignored) {}
            }
            return shellViaShizuku(command, timeoutMs);
        } catch (Throwable t) {
            return "{\"ok\":false,\"error\":\"shell 路由异常：" + jesc(String.valueOf(t.getMessage())) + "\"}";
        }
    }

    /** 经 Shizuku（App 进程内）以 shell uid 执行一条命令，回收输出，带超时兜底。 */
    private String shellViaShizuku(String command, final int timeoutMs) {
        if (!shizukuAvailable()) {
            return "{\"ok\":false,\"error\":\"Shizuku 未授权或服务未运行（App 内 pingBinder/checkSelfPermission 失败）\"}";
        }
        final StringBuilder out = new StringBuilder();
        final StringBuilder err = new StringBuilder();
        try {
            IShizukuService svc = IShizukuService.Stub.asInterface(Shizuku.getBinder());
            if (svc == null) return "{\"ok\":false,\"error\":\"Shizuku binder 为空\"}";
            final IRemoteProcess p = svc.newProcess(new String[]{"/system/bin/sh", "-c", command}, null, null);
            if (p == null) return "{\"ok\":false,\"error\":\"Shizuku newProcess 返回空\"}";
            Thread to = pumpStream(new android.os.ParcelFileDescriptor.AutoCloseInputStream(p.getInputStream()), out, "priv-out");
            Thread te = pumpStream(new android.os.ParcelFileDescriptor.AutoCloseInputStream(p.getErrorStream()), err, "priv-err");
            final int[] code = new int[]{-1};
            Thread waiter = new Thread(new Runnable() {
                @Override public void run() {
                    try { code[0] = p.waitFor(); } catch (Throwable ignored) {}
                }
            }, "priv-wait");
            waiter.start();
            waiter.join(timeoutMs);
            if (waiter.isAlive()) {
                try { p.destroy(); } catch (Throwable ignored) {}
                waiter.join(1500);
                joinQuietly(to); joinQuietly(te);
                return "{\"ok\":false,\"exit_code\":-1,\"stdout\":\"" + jesc(clip(out))
                        + "\",\"stderr\":\"" + jesc(clip(err))
                        + "\",\"error\":\"命令超时（" + timeoutMs + "ms）已终止\"}";
            }
            joinQuietly(to); joinQuietly(te);
            return "{\"ok\":" + (code[0] == 0) + ",\"exit_code\":" + code[0]
                    + ",\"stdout\":\"" + jesc(clip(out)) + "\",\"stderr\":\"" + jesc(clip(err)) + "\"}";
        } catch (Throwable t) {
            return "{\"ok\":false,\"exit_code\":-1,\"stdout\":\"" + jesc(clip(out))
                    + "\",\"stderr\":\"" + jesc(clip(err))
                    + "\",\"error\":\"Shizuku 执行失败：" + jesc(String.valueOf(t.getMessage())) + "\"}";
        }
    }

    /** 后台把一条流读到 EOF。 */
    private Thread pumpStream(final InputStream in, final StringBuilder sb, String name) {
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    byte[] buf = new byte[4096];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        if (sb.length() < 65536) sb.append(new String(buf, 0, n, "UTF-8"));
                    }
                } catch (Throwable ignored) {
                } finally {
                    try { in.close(); } catch (Throwable ignored) {}
                }
            }
        }, name);
        t.setDaemon(true);
        t.start();
        return t;
    }

    private static void joinQuietly(Thread t) {
        try { if (t != null) t.join(800); } catch (Throwable ignored) {}
    }

    private static String clip(StringBuilder sb) {
        String s = sb.toString();
        return s.length() > 8000 ? s.substring(0, 8000) : s;
    }

    /** 最小 JSON 字符串转义。 */
    private static String jesc(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default: b.append(c < 0x20 ? ' ' : c);
            }
        }
        return b.toString();
    }

    /**
     * 本地特权路由的鉴权令牌：持久化在 dsh_prefs（App 私有），随 env 交给引擎。
     * 用 prefs 而不是内存字段，是为了「App 重启但引擎还活着」时令牌不变、插件调用不失效。
     */
    private String localToken() {
        try {
            SharedPreferences p = getSharedPreferences("dsh_prefs", MODE_PRIVATE);
            String t = p.getString("local_token", "");
            if (t == null || t.length() < 16) {
                byte[] b = new byte[16];
                new java.security.SecureRandom().nextBytes(b);
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < b.length; i++) sb.append(String.format("%02x", b[i]));
                t = sb.toString();
                p.edit().putString("local_token", t).apply();
            }
            return t;
        } catch (Throwable t) {
            return "";
        }
    }

    /** 处理 /overlay：控制小鲸鱼悬浮窗。action=show|hide|toggle|status。 */
    private String handleOverlayRequest(String path, String raw) {
        try {
            String action = jsonField(raw, "action");
            if (action.isEmpty()) action = queryField(path, "action");
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

    /**
     * 从请求体里取一个字符串字段。
     *
     * v1.13.4：改成**认识转义**的解析。旧实现是“取第一个引号到下一个引号”，不认 `\"` `\\` `\n` 等，
     * 于是命令里带引号/换行会被截断：例如 shizuku_shell 传
     * `pm install -r "/sdcard/Download/my app.apk"` 会被解析成 `pm install -r \`，后半段全丢，
     * 而工具还会“照跑”——表现为莫名其妙的失败。
     */
    private String jsonField(String json, String key) {
        try {
            String k = "\"" + key + "\"";
            int i = json.indexOf(k);
            if (i < 0) return "";
            int c = json.indexOf(':', i + k.length());
            if (c < 0) return "";
            int q1 = json.indexOf('"', c + 1);
            if (q1 < 0) return "";
            StringBuilder sb = new StringBuilder();
            for (int p = q1 + 1; p < json.length(); p++) {
                char ch = json.charAt(p);
                if (ch == '\\') {
                    if (p + 1 >= json.length()) break;
                    char n = json.charAt(p + 1);
                    if (n == 'n') sb.append('\n');
                    else if (n == 'r') sb.append('\r');
                    else if (n == 't') sb.append('\t');
                    else if (n == 'b') sb.append('\b');
                    else if (n == 'f') sb.append('\f');
                    else if (n == 'u') {
                        if (p + 5 >= json.length()) break;
                        try {
                            sb.append((char) Integer.parseInt(json.substring(p + 2, p + 6), 16));
                            p += 4;
                        } catch (Exception ignored) {}
                    } else {
                        sb.append(n);      // \" \\ \/ 等：取字符本身
                    }
                    p++;
                    continue;
                }
                if (ch == '"') break;
                sb.append(ch);
            }
            return sb.toString();
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
        if (dshKernelChanged(dshrootBase)) return true;
        return dshrootLayoutChanged(dshrootBase);
    }

    // 内核树布局标记（build.sh 写入 assets/dshroot_layout.txt）：布局变更时必须全量重推，
    // 否则 fast 同步只覆盖白名单文件，整棵树留着旧结构（补丁包/依赖树换过就再也更新不到）。
    private String builtinDshrootLayout() {
        try {
            return readAssetText("dshroot_layout.txt").trim();
        } catch (Throwable t) {
            return "";
        }
    }

    private boolean dshrootLayoutChanged(File dshrootBase) {
        String builtin = builtinDshrootLayout();
        if (builtin.isEmpty()) return false; // 旧 APK 无标记 → 不额外触发
        File complete = new File(dshrootBase, "dshroot/" + DSHROOT_COMPLETE);
        try {
            String stored = readFileText(complete).trim();
            int bar = stored.lastIndexOf('|');
            String storedLayout = bar < 0 ? "" : stored.substring(bar + 1);
            return !builtin.equals(storedLayout);
        } catch (Throwable t) {
            return true;
        }
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
            // 记录 <构建时间戳>|<布局标记>：布局标记用于判断是否需要全量重推（见 dshrootLayoutChanged）。
            String layout = builtinDshrootLayout();
            String marker = layout.isEmpty() ? builtinDshrootRevision() : builtinDshrootRevision() + "|" + layout;
            fos.write(marker.getBytes("UTF-8"));
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
        int failed = 0;
        String firstFail = null;
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
            prepareTarget(target);
            try {
                FileOutputStream fos = new FileOutputStream(target);
                int n;
                while ((n = zis.read(buf)) > 0) fos.write(buf, 0, n);
                fos.close();
                // v1.13.5：dex 必须**不可写**——Android 14+ 的 ART 拒绝加载可写 dex
                // （logcat: SecurityException: Writable dex file '…' is not allowed → 进程直接
                //  SIGABRT/exit 134，终端上只看到一个 "Aborted"）。payload.zip 内所有条目都不带
                // unix 权限（external_attr=0），文件权限完全由本函数决定，所以这里对 *.dex 收成 0444。
                if (name.endsWith(".dex")) secureDexPermissions(target);
                written++;
            } catch (IOException ioe) {
                // 单个条目失败不中断整体（否则一次 EACCES 就能让整轮解压白干），最后汇总报错
                failed++;
                if (firstFail == null) firstFail = name + "（" + ioe.getMessage() + "）";
                Log.w(TAG, "extract entry failed: " + name, ioe);
            }
            zis.closeEntry();
            updateProgress(processed, total, written);
        }
        zis.close();
        Log.i(TAG, "extracted " + written + " entries (external=" + (externalRoot != null) + ", mode=" + mode + ")");
        if (failed > 0) throw new IOException("有 " + failed + " 个文件写不进去，首个：" + firstFail);
    }

    /**
     * 写入前把目标清干净。覆盖安装时旧版本（v1.10 那棵老树）可能留下**只读文件 / 扭结软链 /
     * 同名目录**，直接 FileOutputStream 会报 EACCES（用户实测：payload/rish/rish_shizuku.dex）；
     * 父目录不可写也要先提权，否则同样是 EACCES。
     */
    private void prepareTarget(File target) {
        try {
            File parent = target.getParentFile();
            if (parent != null && parent.exists() && !parent.canWrite()) parent.setWritable(true, true);
            if (target.isDirectory()) { deleteRecursive(target); return; }
            if (target.exists()) {
                if (target.canWrite()) return;
                // 注意：这里用 owner-only（true）——setWritable(true, false) 会把 group/other 的写位也加上，
                // 0444 的 dex 会因此变成 0666（真机实测的 “Writable dex” 就是这么来的）。
                target.setWritable(true, true);
                if (target.canWrite()) return;
                if (!target.delete()) {
                    // 删不掉就改名避让（改名只需父目录写权限），旧文件内容不再被引用
                    File bak = new File(target.getParentFile(),
                            target.getName() + ".old-" + System.currentTimeMillis());
                    if (target.renameTo(bak)) deleteRecursive(bak);
                }
            } else {
                // File.exists() 对悬空符号链接返回 false，但创建仍会失败 → 用 lstat 判一下再删
                try { if (Os.lstat(target.getAbsolutePath()) != null) target.delete(); } catch (Throwable ignored) {}
            }
        } catch (Throwable t) {
            Log.w(TAG, "prepareTarget failed: " + target, t);
        }
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

    /**
     * 把 payload 里已知的 dex 统一收成 0444（读取不报错，但任何 uid 都写不了）。
     * 用于兜底：覆盖升级时旧树里的文件不会被重写，只能在解压后扫一遍。
     */
    private void secureDexFiles(File payload) {
        String[] dexs = {"rish/rish_shizuku.dex", "vscreen/vscreen_shizuku.dex"};
        for (String p : dexs) {
            File f = new File(payload, p);
            if (f.exists()) secureDexPermissions(f);
        }
    }

    /**
     * dex 文件强制不可写（0444）。
     *
     * 为什么必须这么做：Android 14+ 的 ART **拒绝加载可写 dex**，报
     * `java.lang.SecurityException: Writable dex file '<path>' is not allowed`，
     * 进程直接 SIGABRT（exit=134）——而终端上只看得到一句 “Aborted”，
     * 极易被误判成 “Shizuku 没运行 / 未授权”，把排查方向带偏。
     * payload.zip 内所有条目的 unix 权限都是 0（external_attr=0），权限完全由解压时决定，
     * 而 Java 写文件在本机 umask 下默认就是 rw-rw-rw-（0666）→ 所以必须显式收权。
     */
    private void secureDexPermissions(File f) {
        try { android.system.Os.chmod(f.getAbsolutePath(), 0444); } catch (Throwable ignored) {}
        try { f.setWritable(false, false); } catch (Throwable ignored) {}   // chmod 失败（如 FUSE）时的兜底
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

        // v1.13：起引擎前先自愈 cordis.patch.yml —— 旧版控制台关插件会把它写成非法 YAML，
        // 引擎每次启动都崩在解析、App 反复重拉（表现：界面一直闪、一直计时）。
        conHealPatchConfig();

        // 注意：Android 兼容补丁（禁用 llm-pi-ai/sandbox/bash-sandbox 的 cordis.patch.yml）
        // 位于 $DSH_HOME/cordis.patch.yml，由 dsh profile-boot 的 homePatches 自动加载，
        // 无需 --patch 参数（重复传入会导致 duplicate loader entry 崩溃）。
        ProcessBuilder pb = new ProcessBuilder(
                node.getAbsolutePath(), "--expose-internals", binjs.getAbsolutePath(),
                "web", "--host", "127.0.0.1", "--port", String.valueOf(enginePort));
        java.util.Map<String, String> env = pb.environment();
        env.put("LD_LIBRARY_PATH", lib.getAbsolutePath());
        // Termux 共存修复（v1.7.4）：内置 node 在 Termux 环境编译，OPENSSLDIR 被编译死为
        // /data/data/com.termux/files/usr。装了 Termux 的设备读其 openssl.cnf 触发 EACCES，
        // node 启动即崩；没装 Termux 时靠 ENOENT 静默才碰巧正常。注入 OPENSSL_CONF 指向
        // payload 自带的可读 openssl.cnf（build.sh 生成），有无 Termux 都稳定。
        File osslConf = new File(payload, "runtime/etc/openssl.cnf");
        if (osslConf.exists()) env.put("OPENSSL_CONF", osslConf.getAbsolutePath());
        env.put("PATH", bin.getAbsolutePath() + ":" +
                new File(payload, "runtime/bin").getAbsolutePath() + ":/system/bin:/system/xbin");
        env.put("HOME", getFilesDir().getAbsolutePath());
        env.put("DSH_HOME", home.getAbsolutePath());
        env.put("TMPDIR", tmp.getAbsolutePath());
        env.put("TERM", "xterm");
        env.put("SHIZUKU_DEX", rishDex != null ? rishDex.getAbsolutePath() : "");
        // v1.9 虚拟屏 server dex：app_process 特权加载 VirtualScreenServer
        env.put("VS_DEX", vscreenDex != null ? vscreenDex.getAbsolutePath() : "");
        // v1.13 修正：这里原来**硬编码** "com.deepseek.harness.beta"，而三版共用同一份源码 —— 正式版跑起来
        // 也在自称 beta，而 rish 要拿这个 appId 去 Shizuku 要授权，Shizuku 比对实际调用者的包名/uid
        // （正式版 uid ≠ beta uid）→ 门卫不认（用户回报：“SHIZUKU_APP_ID=…beta，但真正在跑的是 com.deepseek.harness”）。
        // 按实际包名派生，与 ScheduleExecutor 的 ctx.getPackageName() 一致；
        // 并写回 dsh_prefs，供无障碍服务/调度器等其它组件复用（同样不能信旧值）。
        final String selfAppId = getPackageName();
        env.put("SHIZUKU_APP_ID", selfAppId);
        try {
            getSharedPreferences("dsh_prefs", MODE_PRIVATE).edit().putString("shizuku_app_id", selfAppId).apply();
        } catch (Throwable ignored) {}
        // 特权通道可用性：root(su) 或 Shizuku。两者都未授予时，DSH 插件不注册特权工具，
        // AI 不会反复尝试系统操作；文件读写仍可用 DSH 自带的 fs/bash 工具（只需存储权限）。
        env.put("SHIZUKU_AVAILABLE", shizukuAvailable() ? "1" : "0");
        env.put("ROOT_AVAILABLE", rootAvailable() ? "1" : "0");
        // v1.13.1：本地特权路由 /shell 的鉴权令牌——只经 env 交给本应用自己的引擎，本机其它应用猜不到，
        // 避免任意应用通过 127.0.0.1:<notifyPort>/shell 拿到 shell 权限。
        env.put("APP_LOCAL_TOKEN", localToken());
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
                    String carry = "";
                    while ((n = is.read(b)) > 0) {
                        fos.write(b, 0, n);
                        fos.flush();
                        if (extFos != null) {
                            try { extFos.write(b, 0, n); extFos.flush(); } catch (Throwable ignored) {}
                        }
                        // v1.12：一次 read 可能在行中间断开，token URL 会被截成两半 → 拼接后再切行
                        String s = carry + new String(b, 0, n, "UTF-8");
                        int cut = s.lastIndexOf('\n');
                        carry = cut >= 0 ? s.substring(cut + 1) : s;
                        String parse = cut >= 0 ? s.substring(0, cut) : "";
                        for (String line : parse.split("\n")) {
                            String t = line.trim();
                            if (!t.isEmpty()) Log.i(TAG, "node: " + t);
                            // 0.1.5 认证：引擎打印 "dsh web: http://127.0.0.1:3080/?token=..."，
                            // 解析出来供健康探测与 WebView 首次加载使用。
                            int at = t.indexOf("http://127.0.0.1");
                            int tk = t.indexOf("?token=");
                            if (at >= 0 && tk > at) {
                                String u = t.substring(at);
                                int sp = u.indexOf(' ');
                                if (sp > 0) u = u.substring(0, sp);
                                engineTokenUrl = u;
                                Log.i(TAG, "engine token url captured");
                            }
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

    /** 读 HTTP 正文（容忍错误流；仅供探测用，上限 max 字节）。 */
    private String conReadBody(HttpURLConnection c, int max) {
        try {
            InputStream in = null;
            try { in = c.getInputStream(); } catch (Throwable t) { in = c.getErrorStream(); }
            if (in == null) return null;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int total = 0;
            int r;
            while ((r = in.read(chunk)) > 0 && total < max) { out.write(chunk, 0, r); total += r; }
            try { in.close(); } catch (Throwable ignored) {}
            return out.toString("UTF-8");
        } catch (Throwable t) {
            return null;
        }
    }

    private void waitForServer() {
        long start = System.currentTimeMillis();
        long deadline = start + 90000;
        while (System.currentTimeMillis() < deadline) {
            if (engineStartAborted) return;   // v1.13：用户点了「停止」→ 立即收手，别再刷“已等待 N 秒”
            if (healthOk()) { loadHome(); executePendingScheduledTask(); return; }
            long waited = (System.currentTimeMillis() - start) / 1000;
            setStatus("正在启动 DeepSeek Harness…（已等待 " + waited + " 秒）");
            try { Thread.sleep(1000); } catch (InterruptedException e) { return; }
        }
        if (engineStartAborted) return;   // v1.13：停止后不再走超时兜底（否则会重新 spawn + 重新加载页面）
        // 超时：带端口提示便于排查（node 日志已写入 files/dsh-web.log）
        Log.e(TAG, "engine start timeout on port " + enginePort + ", check dsh-web.log");
        // v1.5.2 慢启动修复兜底：本次走了「快速同步」（同内核升级），若引擎仍起不来，
        // 可能外部 dshroot 有缺失文件（快速路径不 stat 已有文件）→ 全量补齐后重启引擎再等一轮。
        if (fastSyncedThisBoot) {
            fastSyncedThisBoot = false;
            Log.w(TAG, "fast sync may have missed files, forcing full dshroot repair");
            setStatus("引擎启动超时，正在补齐引擎文件后重试…");
            try {
                File externalRoot = new File(Environment.getExternalStorageDirectory(), pkgRoot());
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
                        if (engineStoppedByUser) continue;   // v1.13：用户点了「停止」→ 不自动拉起
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
                                @Override public void run() { wv.loadUrl(webHomeUrl()); }
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
        // v1.12：控制台还开着时不抢界面（引擎就绪后由用户点「打开主界面」进来）
        if (consoleVisible) return;
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
                webView.loadUrl(webHomeUrl());
            }
        });
    }

    private void setStatus(final String s) {
        ui.post(new Runnable() {
            @Override public void run() {
                statusView.setText(s);
                if (consoleVisible) conSay(s);
            }
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
                if (consoleVisible) {
                    if (conBar != null) conBar.setVisibility(View.VISIBLE);
                    conSetProgress(percent);
                    conSay(s);
                }
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
                if (consoleVisible) conSay(s);
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

    // ==================== v1.12 控制台（冷启动首页 · 原生界面） ====================
    // 控制台是盖在 WebView 之上的一层原生视图（同一个 FrameLayout 根），冷启动时显示；
    // 切屏回来 / 任务恢复（savedInstanceState != null）不进它，直接回 DSH 主界面。
    // 四块：解压文件（完成后收起成一行）、启动引擎、授予权限（含 root）、插件开关、日志。

    private volatile boolean conRootOk = false;
    private long conRootProbeTs = 0L;

    private int cLine() { return Color.parseColor(isDark() ? "#232a38" : "#e5e7eb"); }
    private int cAccent() { return Color.parseColor("#4d6bfe"); }

    private File payloadDir() { return new File(getFilesDir(), "payload"); }

    /** 当前安装包的 versionCode（用于判断“内部那棵树是不是本次安装解压的”）。 */
    private int conBuildCode() {
        try { return (int) getPackageManager().getPackageInfo(getPackageName(), 0).getLongVersionCode(); }
        catch (Throwable t) { return 0; }
    }

    /** 关键文件缺失检查（只查标记文件不够：解压中途失败、文件被杀都不算就绪）。 */
    private String conMissingKey() {
        File p = payloadDir();
        String[] keys = {".extracted", "dshroot/REVISION",
                "dshroot/lib/node_modules/@deepseek-ai/dsh/lib/bin.js",
                "runtime/bin/node", "rish/rish_shizuku.dex"};
        for (int i = 0; i < keys.length; i++) if (!new File(p, keys[i]).exists()) return keys[i];
        return null;
    }

    /**
     * 运行时与内核树是否已就绪。
     * v1.12：加两道判定 —— ① 关键文件必须在；② 内部那棵树必须是**当前这次安装**解压出来的
     * （payload_build_code == 当前 versionCode）。否则升级安装后拿着旧树（例：v1.10 留下的）
     * 会显示“已解压”、校验也“通过”（用户实测就是这个问题）。
     */
    private boolean conFilesReady() {
        if (conMissingKey() != null) return false;
        int done = 0;
        try { done = getSharedPreferences("dsh_prefs", MODE_PRIVATE).getInt("payload_build_code", 0); } catch (Throwable ignored) {}
        return done != 0 && done == conBuildCode();
    }

    private void conMarkPayloadDone() {
        try {
            getSharedPreferences("dsh_prefs", MODE_PRIVATE).edit()
                    .putInt("payload_build_code", conBuildCode()).apply();
        } catch (Throwable ignored) {}
    }

    private String conKernelVer() {
        try {
            String s = readFileText(new File(payloadDir(), "dshroot_kernel_version.txt"));
            if (s != null && s.trim().length() > 0) return s.trim();
        } catch (Throwable ignored) {}
        return null;
    }

    private String conVersionLabel() {
        String v = "";
        try { v = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Throwable ignored) {}
        String k = conKernelVer();
        return v + (k != null ? " · 内核 " + k : "");
    }

    /**
     * 控制台用的“引擎就绪”判定：**只认真实端口探测**，不再回退看进程句柄。
     * v1.13 修正：真机实测 node 从拉起→开始监听要 17~25 秒，若把“进程活着”当成“已就绪”，
     * 控制台会过早点亮「打开主界面」，用户点进去时 3080 还没监听 → WebView 连不上，
     * 看起来就是“点了没反应”；同时底部还在刷“正在启动…（已等待 N 秒）” → 两套文案交替闪。
     * 现在：就绪=探测通过；进程活着但未就绪 → 由 conEngineBooting() 归入“启动中”。
     */
    private boolean conEngineRunning() {
        long now = System.currentTimeMillis();
        if (now - engineProbeAt > 1500L && !engineProbeBusy) {
            engineProbeBusy = true;
            new Thread(new Runnable() {
                @Override public void run() {
                    conProbeEngineNow();
                    engineProbeBusy = false;
                    if (consoleVisible) ui.post(new Runnable() { @Override public void run() { refreshConsole(); } });
                }
            }, "engine-probe").start();
        }
        return engineAliveCached;
    }

    /** 已拉起但还没监听端口（node 启动中）；控制台据此显示“启动中…”并禁用入口。 */
    private boolean conEngineBooting() {
        if (engineAliveCached) return false;
        Process p = nodeProcess;
        return starting || (p != null && p.isAlive());
    }

    /** 端口是否已被监听（TCP 连接得通即算；**后台线程调用**）。
     *  比 healthOk() 更早为真：node 还在启动时端口已经 listen，用它避免重复拉起引擎。 */
    private boolean portListening(int port) {
        java.net.Socket s = null;
        try {
            s = new java.net.Socket();
            s.connect(new java.net.InetSocketAddress("127.0.0.1", port), 400);
            return true;
        } catch (Throwable t) {
            return false;
        } finally {
            try { if (s != null) s.close(); } catch (Throwable ignored) {}
        }
    }

    /** 真探一次引擎端口（**必须在后台线程调用**：主线程做网络 IO 会被系统直接抛异常）。 */
    private boolean conProbeEngineNow() {
        boolean up = false;
        try { up = healthOk(); } catch (Throwable ignored) {}
        engineAliveCached = up;
        engineProbeAt = System.currentTimeMillis();
        return up;
    }

    /**
     * 从引擎日志尾部把最新一条 token URL 捞回来。
     * 适用：引擎已经在跑、但本次 App 进程没抓到那条启动输出（例：App 被杀后重开、
     * 引擎此前已被拉起）。不捞的话 WebView 只能不带 token 加载 → 401 认证页。
     */
    private String conTokenFromLog() {
        try {
            File f = conLogFile();
            if (!f.exists()) return null;
            String tail = conTailOf(f, 300);
            int at = tail.lastIndexOf("http://127.0.0.1");
            if (at < 0) return null;
            int tk = tail.indexOf("?token=", at);
            if (tk < 0) return null;
            int end = tk + 7;
            while (end < tail.length() && !" \r\n\t".contains(String.valueOf(tail.charAt(end)))) end++;
            return tail.substring(at, end);
        } catch (Throwable t) { return null; }
    }

    private void showConsole() {
        if (consoleLayer == null) buildConsoleLayer();
        if (consoleLayer == null) { startEngine(); return; } // 兜底：控制台建不出来就走老路
        consoleVisible = true;
        consoleLayer.setVisibility(View.VISIBLE);
        if (engineRoot != null) engineRoot.bringChildToFront(consoleLayer);
        renderConsole();
        startConsoleTick();
        computeFilesSummaryAsync();
    }

    private void buildConsoleLayer() {
        if (engineRoot == null) return;
        ScrollView sc = new ScrollView(this);
        sc.setBackgroundColor(cBg());
        sc.setFillViewport(true);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(18), dp(24), dp(18), dp(24));
        sc.addView(col, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        consoleBody = col;
        consoleLayer = sc;
        engineRoot.addView(sc, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
    }

    private void startConsoleTick() {
        if (conTick == null) conTick = new Handler(Looper.getMainLooper());
        conTick.removeCallbacks(consoleTick);
        conTick.postDelayed(consoleTick, 900);
    }

    /** 控制台里显示一句状态：解压阶段进解压块，否则进引擎块。 */
    private void conSay(String s) {
        if (s == null) return;
        if (extracting) { conExtractMsg = s; if (conExMeta != null) conExMeta.setText(s); }
        else if (conEnMeta != null) conEnMeta.setText(s);
    }

    // ---------- 通用控件 ----------
    private TextView cText(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(null, android.graphics.Typeface.BOLD);
        return t;
    }

    /** 圆角形状（替代系统 Button/ProgressBar 自带背景，避免 ColorOS 上灰底、裁字、颜色不对）。 */
    private android.graphics.drawable.GradientDrawable cShape(int fill, int stroke, int strokeW, int radius) {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        g.setColor(fill);
        g.setCornerRadius(dp(radius));
        if (strokeW > 0) g.setStroke(dp(strokeW), stroke);
        return g;
    }

    private int cTrack() { return Color.parseColor(isDark() ? "#141b2b" : "#eef1f6"); }

    /** 自绘按钮：内边距固定、单行、超长省略号，不再出现“文字超出按钮”的情况。 */
    private Button cButton(String label, boolean primary) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(label);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, primary ? 13.5f : 12.5f);
        b.setSingleLine(true);
        b.setEllipsize(android.text.TextUtils.TruncateAt.END);
        b.setMinWidth(dp(primary ? 96 : 68));
        b.setMinimumWidth(dp(primary ? 96 : 68));
        b.setPadding(dp(16), dp(9), dp(16), dp(9));
        b.setIncludeFontPadding(false);
        if (primary) {
            b.setTextColor(Color.WHITE);
            b.setBackground(cShape(cAccent(), cAccent(), 0, 8));
        } else {
            // 次按钮：强调色描边 + 强调色文字（之前用灰底，看着像“禁用”）
            b.setTextColor(cAccent());
            b.setBackground(cShape(isDark() ? 0x1A4D6BFE : 0x144D6BFE, cAccent(), 1, 8));
        }
        return b;
    }

    /** 统一“禁用”外观：自绘背景下系统不会自动变灰，必须手动降透明度。 */
    private void cSetEnabled(Button b, boolean on) {
        if (b == null) return;
        b.setEnabled(on);
        b.setAlpha(on ? 1f : 0.38f);
    }

    /** 进度条（自绘：轨道 + 强调色填充，风格与页面一致）。 */
    private void conSetProgress(int pct) {
        if (conFill == null || conSpacer == null) return;
        int p = Math.max(0, Math.min(100, pct));
        LinearLayout.LayoutParams f = (LinearLayout.LayoutParams) conFill.getLayoutParams();
        LinearLayout.LayoutParams s = (LinearLayout.LayoutParams) conSpacer.getLayoutParams();
        f.weight = Math.max(p, 0.01f);
        s.weight = Math.max(100 - p, 0.01f);
        conFill.setLayoutParams(f);
        conSpacer.setLayoutParams(s);
    }

    /** 插件开关（自绘小胶囊，比系统 Switch 更可控且与页面同风格）。 */
    private TextView cToggle(final String id, boolean on) {
        final TextView t = new TextView(this);
        t.setTag(Boolean.valueOf(on));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        t.setPadding(dp(12), dp(6), dp(12), dp(6));
        t.setSingleLine(true);
        t.setGravity(Gravity.CENTER);
        conTogglePaint(t);
        t.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                boolean now = !((Boolean) t.getTag()).booleanValue();
                t.setTag(Boolean.valueOf(now));
                conTogglePaint(t);
                conSetPluginDisabled(id, !now);
                conToast("dsh-" + id + (now ? " 已启用" : " 已关闭") + "（重启引擎生效）");
            }
        });
        return t;
    }

    private void conTogglePaint(TextView t) {
        boolean on = ((Boolean) t.getTag()).booleanValue();
        t.setText(on ? "已启用" : "已关闭");
        t.setTextColor(on ? cGreen() : cSub());
        t.setBackground(cShape(on ? (isDark() ? 0x241F9D6B : 0x1A1F9D6B) : 0x00000000,
                on ? cGreen() : cLine(), 1, 12));
    }

    private LinearLayout.LayoutParams cTop(int topMargin) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = topMargin;
        return lp;
    }

    private View cSep(int topMargin) {
        View v = new View(this);
        v.setBackgroundColor(cLine());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1)));
        lp.topMargin = topMargin;
        v.setLayoutParams(lp);
        return v;
    }

    private View cNavRow(String title, String sub, String value, final int page) {
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.addView(cText(title, 14f, cText(), false));
        if (sub != null && sub.length() > 0) left.addView(cText(sub, 11f, cSub(), false));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(15), 0, dp(15));
        row.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (value != null && value.length() > 0) row.addView(cText(value, 11f, cSub(), false));
        row.addView(cText("›", 14f, cSub(), false));
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { consolePage = page; renderConsole(); }
        });
        return row;
    }

    private View conBackRow(String title) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        Button back = cButton("‹ 控制台", false);
        back.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        back.setPadding(0, dp(4), dp(8), dp(4));
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { consolePage = 0; renderConsole(); }
        });
        row.addView(back);
        row.addView(cText(title, 12.5f, cSub(), false));
        return row;
    }

    private void conToast(final String s) {
        ui.post(new Runnable() { @Override public void run() {
            try { android.widget.Toast.makeText(MainActivity.this, s, android.widget.Toast.LENGTH_SHORT).show(); } catch (Throwable ignored) {}
        }});
    }

    // ---------- 页面渲染 ----------
    private void renderConsole() {
        if (consoleBody == null) return;
        consoleBody.removeAllViews();
        if (consolePage == 1) { renderConsolePerm(); return; }
        if (consolePage == 2) { renderConsolePlug(); return; }
        if (consolePage == 3) { renderConsoleLog(); return; }
        renderConsoleMain();
    }

    private void renderConsoleMain() {
        LinearLayout col = consoleBody;
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.BOTTOM);
        top.addView(cText("DEEPSEEK HARNESS", 10f, cSub(), false),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        top.addView(cText(conVersionLabel(), 10f, cSub(), false));
        col.addView(top);

        // ① 解压文件
        col.addView(cSep(dp(16)));
        conExState = cText("", 15f, cText(), false);
        col.addView(conExState, cTop(dp(16)));
        conExMeta = cText("", 11f, cSub(), false);
        conExMeta.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (!conFilesReady()) return;
                consoleDetailOpen = !consoleDetailOpen;
                refreshConsole();
            }
        });
        col.addView(conExMeta, cTop(dp(7)));
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackground(cShape(cTrack(), 0, 0, 2));
        conFill = new View(this);
        conFill.setBackgroundColor(cAccent());
        conSpacer = new View(this);
        bar.addView(conFill, new LinearLayout.LayoutParams(0, dp(4), 1f));
        bar.addView(conSpacer, new LinearLayout.LayoutParams(0, dp(4), 0f));
        bar.setVisibility(View.GONE);
        conBar = bar;
        col.addView(bar, cTop(dp(10)));
        conDetailBox = conExtractDetail();
        col.addView(conDetailBox);
        conExBtn = cButton("解压文件", true);
        conExBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { conExtractClick(); }
        });
        col.addView(conExBtn, cTop(dp(12)));

        // ② 启动引擎
        col.addView(cSep(dp(18)));
        conEnState = cText("", 15f, cText(), false);
        col.addView(conEnState, cTop(dp(16)));
        conEnMeta = cText("", 11f, cSub(), false);
        col.addView(conEnMeta, cTop(dp(7)));
        LinearLayout enActs = new LinearLayout(this);
        enActs.setOrientation(LinearLayout.HORIZONTAL);
        conEnBtn = cButton("启动引擎", true);
        conEnBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { conEngineClick(); }
        });
        enActs.addView(conEnBtn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        conEnRestart = cButton("重启", false);
        conEnRestart.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { conRestartEngine(); }
        });
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.leftMargin = dp(8);
        enActs.addView(conEnRestart, rlp);
        conEnStop = cButton("停止", false);
        conEnStop.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { conStopEngine(); }
        });
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.leftMargin = dp(8);
        enActs.addView(conEnStop, slp);
        col.addView(enActs, cTop(dp(12)));

        // ③ 三个入口
        col.addView(cSep(dp(20)));
        col.addView(cNavRow("授予权限", "存储 · 通知 · 悬浮窗 · 电池 · root · Shizuku · 无障碍", conPermSummary(), 1));
        col.addView(cSep(0));
        col.addView(cNavRow("插件", "关掉用不到的，省上下文", conPlugSummary(), 2));
        col.addView(cSep(0));
        LinearLayout logActs = new LinearLayout(this);
        logActs.setOrientation(LinearLayout.HORIZONTAL);
        Button lv = cButton("查看", false);
        lv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        lv.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { conViewLog(); }
        });
        Button le = cButton("分享", false);
        le.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        le.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { conShareLog(); }
        });
        logActs.addView(lv);
        logActs.addView(le);
        LinearLayout logLeft = new LinearLayout(this);
        logLeft.setOrientation(LinearLayout.VERTICAL);
        logLeft.addView(cText("日志", 14f, cText(), false));
        logLeft.addView(cText(conLogSummary(), 11f, cSub(), false));
        LinearLayout logRow = new LinearLayout(this);
        logRow.setOrientation(LinearLayout.HORIZONTAL);
        logRow.setGravity(Gravity.CENTER_VERTICAL);
        logRow.setPadding(0, dp(12), 0, dp(12));
        logRow.addView(logLeft, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        logRow.addView(logActs);
        col.addView(logRow);
        col.addView(cSep(0));

        conFoot = cText("就绪", 11f, cSub(), false);
        col.addView(conFoot, cTop(dp(14)));
        col.addView(cText("换内核版本 / 覆盖安装后需要重新解压；平时只用到「启动引擎」。", 11f, cSub(), false), cTop(dp(6)));
        // 检查更新（v1.12：不再启动时自动检查，改这里手动触发）
        LinearLayout upd = new LinearLayout(this);
        upd.setOrientation(LinearLayout.HORIZONTAL);
        upd.setGravity(Gravity.CENTER_VERTICAL);
        TextView updLink = cText("检查更新", 12f, cSub(), false);
        updLink.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { conToast("正在检查…"); checkForUpdate(true); }
        });
        upd.addView(updLink, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        upd.addView(cText("当前 " + conVersionLabel(), 11f, cSub(), false));
        col.addView(upd, cTop(dp(16)));
        refreshConsole();
    }

    /** 与控制台同一套视觉的弹窗（平色底、同字体、同按钮样式，跟随系统深浅色）。 */
    private void conDialog(String title, String body, String positive, final Runnable onPositive, String negative) {
        TextView t = null;
        if (body != null && body.length() > 0) t = cText(body, 12.5f, cSub(), false);
        conDialogView(title, t, positive, onPositive, negative);
    }

    /** 同风格弹窗的通用版：内容自定（例如带滚动的日志正文）。 */
    private void conDialogView(String title, View content, String positive, final Runnable onPositive, String negative) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        // v1.13：卡片自己画圆角背景（原来用直角色块，系统对话框面板的圆角/描边会露在外面，
        // 看上去就是“弹窗外面还套了一层小白边/小黑边”）。
        box.setBackground(cShape(cBg(), 0, 0, 16));
        box.setPadding(dp(22), dp(22), dp(22), dp(14));
        if (title != null && title.length() > 0) box.addView(cText(title, 16f, cText(), true));
        if (content != null) box.addView(content, cTop(dp(12)));

        final AlertDialog dlg = new AlertDialog.Builder(this).create();
        LinearLayout acts = new LinearLayout(this);
        acts.setOrientation(LinearLayout.HORIZONTAL);
        acts.setGravity(Gravity.RIGHT);
        if (negative != null) {
            Button nb = cButton(negative, false);
            nb.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { dlg.dismiss(); }
            });
            acts.addView(nb);
        }
        if (positive != null) {
            Button pb = cButton(positive, true);
            pb.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    dlg.dismiss();
                    if (onPositive != null) onPositive.run();
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.leftMargin = dp(8);
            acts.addView(pb, lp);
        }
        box.addView(acts, cTop(dp(18)));

        dlg.setView(box);
        dlg.show();
        try {
            if (dlg.getWindow() != null) {
                // 关键：窗口背景**全透明**，让卡片自己的圆角成为唯一轮廓；
                // 再清掉系统对话框默认的内边距/最小宽度，否则那层“外框”又回来了。
                dlg.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
                android.view.View decor = dlg.getWindow().getDecorView();
                if (decor instanceof android.view.ViewGroup) {
                    ((android.view.ViewGroup) decor).setPadding(0, 0, 0, 0);
                }
                dlg.getWindow().setLayout(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            }
        } catch (Throwable ignored) {}
        // 外层留出与屏幕边缘的呼吸距离（真正的对话框边距，不是面板边框）
        try {
            android.view.ViewGroup.LayoutParams lp = box.getLayoutParams();
            if (lp instanceof LinearLayout.LayoutParams) {
                ((LinearLayout.LayoutParams) lp).setMargins(dp(20), 0, dp(20), 0);
                box.setLayoutParams(lp);
            }
        } catch (Throwable ignored) {}
    }

    private View conExtractDetail() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setVisibility(View.GONE);
        box.addView(cText("布局标记 hoisted-1 · 插件与补丁面已验证", 11f, cSub(), false), cTop(dp(8)));
        LinearLayout acts = new LinearLayout(this);
        acts.setOrientation(LinearLayout.HORIZONTAL);
        Button re = cButton("重新解压", false);
        re.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { conReExtract(); }
        });
        Button vf = cButton("校验", false);
        vf.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { conVerifyFiles(); }
        });
        acts.addView(re);
        acts.addView(vf);
        box.addView(acts, cTop(dp(10)));
        return box;
    }

    private void refreshConsole() {
        if (!consoleVisible || consoleBody == null || consolePage != 0) return;
        boolean ready = conFilesReady();
        if (conExState != null) conExState.setText(extracting ? "正在解压…" : (ready ? "已解压" : "未解压"));
        if (conExMeta != null) conExMeta.setText(conExtractMetaText(ready));
        if (conDetailBox != null) conDetailBox.setVisibility(ready && consoleDetailOpen ? View.VISIBLE : View.GONE);
        if (conBar != null) conBar.setVisibility(extracting ? View.VISIBLE : View.GONE);
        if (!extracting) conSetProgress(ready ? 100 : 0);
        if (conExBtn != null) {
            conExBtn.setText(extracting ? "解压中…" : (ready ? "重新解压" : "解压文件"));
            cSetEnabled(conExBtn, !extracting && !starting);
        }
        boolean run = conEngineRunning();          // 真就绪（端口探测通过）
        boolean booting = conEngineBooting();      // 已拉起、还在监听前的空窗期
        if (run && engineStartTs == 0L) engineStartTs = System.currentTimeMillis();
        // v1.13：只在“真就绪”时结束启动中；并且启动中不得点亮入口。
        // （反面教训：把“node 进程活着”当就绪 → 按钮提前亮、点进去 3080 未监听 → “点了没反应”）
        if (run) starting = false;
        else if (starting && engineStartTs > 0
                && System.currentTimeMillis() - engineStartTs > 150000L) starting = false;   // 超时兜底
        boolean busy = !run && (booting || starting);
        if (conEnState != null) conEnState.setText(run ? "引擎运行中" : (busy ? "启动中…" : "未启动"));
        if (conEnMeta != null) conEnMeta.setText(conEngineMetaText(run, ready, busy));
        if (conEnBtn != null) {
            conEnBtn.setText(run ? "打开主界面" : (busy ? "启动中…" : "启动引擎"));
            cSetEnabled(conEnBtn, !busy && (run || ready));
        }
        cSetEnabled(conEnRestart, run || busy);
        cSetEnabled(conEnStop, run || busy);
        if (conFoot != null) conFoot.setText(run ? "本地服务已就绪" : (busy ? "正在启动引擎…" : "就绪"));
    }

    private String conExtractMetaText(boolean ready) {
        if (extracting) return conExtractMsg != null ? conExtractMsg : "正在解压运行时与内核树…";
        if (ready) {
            String s = conFilesSummary != null ? conFilesSummary : "运行环境与内核树已就绪";
            return s + (consoleDetailOpen ? "" : " · 点这一行看详情");
        }
        return "需要解压运行环境与内核（约 2.5 万个文件 / 约 220 MB）；解压完成后才能启动引擎。";
    }

    private String conEngineMetaText(boolean run, boolean ready, boolean busy) {
        if (run) {
            long mins = engineStartTs > 0 ? Math.max(0, (System.currentTimeMillis() - engineStartTs) / 60000) : 0;
            return "端口 " + enginePort + " · 已运行 " + mins + " 分 · 通知 " + notifyPort();
        }
        // v1.13：启动中显示统一的倒计时文案（真机 node 预熟要 17~25 秒，必须给用户一个“在动”的反馈）
        if (busy) {
            long sec = engineStartTs > 0 ? Math.max(0, (System.currentTimeMillis() - engineStartTs) / 1000) : 0;
            return "端口 " + enginePort + " · 启动中… 已等待 " + sec + " 秒";
        }
        // v1.13：原来无论文件是否已解压都写“解压完成后可启动”，已解压时这句误导人。
        return (ready ? "点「启动引擎」开始 · 端口 " : "解压完成后可启动 · 端口 ") + enginePort;
    }

    // ---------- 动作：解压 / 启动 ----------
    private void conExtractClick() {
        if (extracting || starting) return;
        if (conFilesReady()) { conReExtract(); return; }
        conStartExtract();
    }

    private void conStartExtract() {
        if (extracting) return;
        if (!conFilesReady()) {
            // 本次安装还没解压过（升级安装最常见）：清掉旧标记 → 走一次**完整内部解压**
            // （runtime/node/so/dshhome/rish 全部重写），避免“拿着上一版的树”看着像已解压。
            try { new File(payloadDir(), ".extracted").delete(); } catch (Throwable ignored) {}
        }
        extracting = true;
        conExtractMsg = null;
        extractOnlyMode = true;
        filesPreparedThisBoot = false;
        refreshConsole();
        startEngine();   // 复用同一条链路；extractOnlyMode 会在文件准备完后提前返回
    }

    private void conReExtract() {
        if (extracting || starting) { conToast("正在忙，稍后"); return; }
        filesPreparedThisBoot = false;
        extracting = true;
        conExtractMsg = null;
        extractOnlyMode = true;
        refreshConsole();
        new Thread(new Runnable() { @Override public void run() {
            try { new File(payloadDir(), ".extracted").delete(); } catch (Throwable ignored) {}
            try { new File(payloadDir(), "dshroot/.complete").delete(); } catch (Throwable ignored) {}
            ui.post(new Runnable() { @Override public void run() { extracting = false; conStartExtract(); } });
        }}, "extract-reset").start();
    }

    private void conEngineClick() {
        if (starting) return;
        // v1.13：判定“引擎是否已在跑”必须先真正探一次端口，而探测**只能在后台线程做**
        // （主线程做网络 IO → NetworkOnMainThreadException 被吞 → 误判未启动 → 又拉起第二个 node）。
        new Thread(new Runnable() {
            @Override public void run() {
                final boolean running = conProbeEngineNow();
                ui.post(new Runnable() { @Override public void run() { conEngineClickAfterProbe(running); } });
            }
        }, "engine-click-probe").start();
    }

    private void conEngineClickAfterProbe(boolean running) {
        if (starting) return;
        if (running) {
            engineStartAborted = false;
            engineStoppedByUser = false;
            enterMainUi();
            return;
        }
        if (!conFilesReady()) { conToast("先解压文件"); return; }
        extracting = false;
        extractOnlyMode = false;
        starting = true;
        engineStartTs = System.currentTimeMillis();
        refreshConsole();
        startEngine();   // 文件已就绪 → 只起引擎
    }

    /**
     * 「重启」原来只 destroy 内存里的 nodeProcess 句柄 ——
     * Activity 被重建 / 进程被杀后重开时那个句柄是 null，于是点了重启等于什么都没发生
     * （用户反馈原话：“点重启其实没有用，你并没有被重启”）。
     * 现在改成按 PID 真杀（见 killEngineNow），并等端口真正释放后再拉起新引擎。
     */
    private void conRestartEngine() {
        if (starting) return;
        conToast("正在重启引擎…");
        engineStoppedByUser = false;
        engineStartAborted = false;
        new Thread(new Runnable() { @Override public void run() {
            killEngineNow();
            // 等端口释放：否则紧接着的探针会看到旧进程还 listen → 误判“已在运行” → 又不重启
            long deadline = System.currentTimeMillis() + 10000;
            while (System.currentTimeMillis() < deadline && portListening(enginePort)) {
                try { Thread.sleep(200); } catch (InterruptedException ignored) {}
            }
            ui.post(new Runnable() { @Override public void run() { conEngineClick(); } });
        }}, "engine-restart").start();
    }

    private void conStopEngine() {
        // v1.13：旧实现只 destroy 进程，两个后果 —— ① 在飞的 waitForServer 仍每秒刷“已等待 N 秒”（界面一直计时）；
        // ② 看门狗 5 秒后看到 nodeProcess 非空却已死 → 又把引擎拉起来（用户看到的“停不掉”）。
        // 同样不能只 destroy 句柄 —— 句柄丢了就什么都停不掉，改用 killEngineNow（按 PID）。
        engineStoppedByUser = true;
        engineStartAborted = true;
        conToast("正在停止引擎…");
        new Thread(new Runnable() { @Override public void run() {
            killEngineNow();
            starting = false;
            engineStartTs = 0L;
            ui.post(new Runnable() { @Override public void run() {
                setStatus("引擎已停止");
                refreshConsole();
                conToast("引擎已停止");
            }});
        }}, "engine-stop").start();
    }

    /**
     * 找出当前真正在跑的引擎 node 进程 PID（不依赖内存里的 Process 句柄）。
     *
     * 为什么可行：node 是本 App 的子进程、**同一个 uid**，而同 uid 的进程在 /proc 里互相可见
     * （真机实测：App 身份能读到 /proc/&lt;pid&gt;/cmdline）。所以哪怕 Activity 被重建、
     * 句柄丢了，也仍然能定位并终止它。
     * 认人条件：cmdline 同时含 bin.js、web、--port &lt;enginePort&gt;，避免误杀别的 node。
     */
    private int findEnginePid() {
        File[] kids;
        try { kids = new File("/proc").listFiles(); } catch (Throwable t) { return -1; }
        if (kids == null) return -1;
        int self = android.os.Process.myPid();
        for (File d : kids) {
            String name = d.getName();
            if (name == null || name.isEmpty() || !Character.isDigit(name.charAt(0))) continue;
            int pid;
            try { pid = Integer.parseInt(name); } catch (Throwable t) { continue; }
            if (pid == self) continue;
            String cmd = readProcCmdline(pid);
            if (cmd == null || cmd.length() == 0) continue;
            if (cmd.indexOf("bin.js") < 0) continue;
            if (cmd.indexOf(" web") < 0) continue;
            if (cmd.indexOf("--port " + enginePort) < 0) continue;
            return pid;
        }
        return -1;
    }

    /** 读 /proc/&lt;pid&gt;/cmdline（NUL 分隔 → 空格）。读不到返回 null。 */
    private String readProcCmdline(int pid) {
        FileInputStream in = null;
        try {
            in = new FileInputStream("/proc/" + pid + "/cmdline");
            byte[] buf = new byte[1024];
            int n = in.read(buf);
            if (n <= 0) return "";
            for (int i = 0; i < n; i++) if (buf[i] == 0) buf[i] = ' ';
            return new String(buf, 0, n, "UTF-8");
        } catch (Throwable t) {
            return null;
        } finally {
            try { if (in != null) in.close(); } catch (Throwable ignored) {}
        }
    }

    /**
     * 真把引擎进程杀掉（阻塞直到死透或超时）。
     * 先 SIGTERM 让 node 正常退出（会释放端口），6 秒内没死再 SIGKILL 兜底。
     * 调用方必须在后台线程（内部有 sleep / 轮询）。
     */
    private void killEngineNow() {
        int pid = findEnginePid();
        if (pid > 0) {
            try { android.os.Process.sendSignal(pid, 15); } catch (Throwable ignored) {}   // SIGTERM
        }
        try { if (nodeProcess != null && nodeProcess.isAlive()) nodeProcess.destroy(); } catch (Throwable ignored) {}
        long deadline = System.currentTimeMillis() + 6000;
        while (System.currentTimeMillis() < deadline) {
            if (findEnginePid() <= 0) break;
            try { Thread.sleep(200); } catch (InterruptedException e) { break; }
        }
        int still = findEnginePid();
        if (still > 0) {
            Log.w(TAG, "engine pid " + still + " still alive after SIGTERM, SIGKILL");
            try { android.os.Process.killProcess(still); } catch (Throwable ignored) {}     // SIGKILL 兜底
            try { Thread.sleep(600); } catch (InterruptedException ignored) {}
        }
        nodeProcess = null;
    }

    private void enterMainUi() {
        consoleVisible = false;
        if (consoleLayer != null) consoleLayer.setVisibility(View.GONE);
        if (conTick != null) conTick.removeCallbacks(consoleTick);
        // 没有 token 时先从日志里捞回来（否则 WebView 只能 401 认证页 → 白屏）
        if (engineTokenUrl == null) {
            String u = conTokenFromLog();
            if (u != null) engineTokenUrl = u;
        }
        loadHome();
    }

    private void conExtractDone() {
        extracting = false;
        consoleDetailOpen = false;
        computeFilesSummaryAsync();
        refreshConsole();
        conToast("解压完成，可以启动引擎了");
    }

    private void conExtractFailed(String msg) {
        extracting = false;
        refreshConsole();
        conToast("解压失败：" + msg);
    }

    private void conEngineFailed(String msg) {
        starting = false;
        refreshConsole();
        conToast("引擎启动失败：" + msg);
    }

    /** 只负责起引擎（文件已就绪）：健康探测 → spawnNode → 等就绪。v1.12 从 startEngine 拆出。 */
    private void launchEngine(File payload) {
        if (healthOk()) {
            starting = false;
            engineAliveCached = true;                        // v1.13：写回缓存 → 控制台立刻显示“引擎运行中”
            engineProbeAt = System.currentTimeMillis();
            if (engineStartTs == 0L) engineStartTs = System.currentTimeMillis();
            ui.post(new Runnable() { @Override public void run() { refreshConsole(); } });
            if (!consoleVisible) loadHome();
            return;
        }
        showIndeterminate("正在启动 DeepSeek Harness…");
        starting = true;
        if (engineStartTs == 0L) engineStartTs = System.currentTimeMillis();
        // v1.13：已有一个 node 进程在跑（可能只是还没开始监听端口）→ **绝不再 spawn 第二个**。
        // 旧实现只看 healthOk()：node 启动中的那几秒会被误判为“没在跑”→ 重复 spawn。
        // 真机日志实证：同一时刻两个 node 抢 3080，第二次流程的 notify 端口 3081 直接 EADDRINUSE。
        Process alive = nodeProcess;
        if (alive != null && alive.isAlive()) {
            Log.w(TAG, "engine process already alive, wait instead of respawning");
            waitForServer();
            starting = false;
            ui.post(new Runnable() { @Override public void run() { refreshConsole(); } });
            return;
        }
        // v1.13 第二道防线：句柄丢了（App 重启/被系统回收）也不凭 healthOk() 就重建 ——
        // node 启动中虽然不响应 HTTP，但**端口已经 listen**；只要端口被占就不该再拉一个。
        // （引擎日志里 EADDRINUSE 高达 72 次 vs 成功启动 38 次，重复拉起是最高频的浪费。）
        if (portListening(enginePort)) {
            Log.w(TAG, "port " + enginePort + " already listening, wait instead of respawning");
            waitForServer();
            starting = false;
            ui.post(new Runnable() { @Override public void run() { refreshConsole(); } });
            return;
        }
        try {
            spawnNode(payload);
        } catch (Throwable t) {
            starting = false;
            Log.e(TAG, "spawnNode failed", t);
            final String msg = String.valueOf(t.getMessage());
            setStatus("引擎启动失败：" + msg);
            writeStartupDiag(msg);
            ui.post(new Runnable() { @Override public void run() { conEngineFailed(msg); } });
            return;
        }
        waitForServer();
        starting = false;
        ui.post(new Runnable() { @Override public void run() { refreshConsole(); } });
    }

    // ---------- 文件摘要 / 校验 ----------
    private void computeFilesSummaryAsync() {
        if (!conFilesReady()) { conFilesSummary = null; return; }
        new Thread(new Runnable() { @Override public void run() {
            final String s = conComputeFilesSummary();
            ui.post(new Runnable() { @Override public void run() { conFilesSummary = s; refreshConsole(); } });
        }}, "files-summary").start();
    }

    private String conComputeFilesSummary() {
        try {
            java.util.ArrayDeque<File> q = new java.util.ArrayDeque<File>();
            q.add(new File(payloadDir(), "dshroot"));
            long n = 0;
            long bytes = 0;
            while (!q.isEmpty()) {
                File f = q.poll();
                File[] cs = f.listFiles();
                if (cs == null) continue;
                for (int i = 0; i < cs.length; i++) {
                    if (cs[i].isDirectory()) q.add(cs[i]);
                    else { n++; bytes += cs[i].length(); }
                }
            }
            return String.format(java.util.Locale.US, "%,d 个文件 · %d MB", Long.valueOf(n), Long.valueOf(bytes / 1048576L));
        } catch (Throwable t) { return null; }
    }

    private void conVerifyFiles() {
        conToast("校验中…");
        new Thread(new Runnable() { @Override public void run() {
            final String miss = conMissingKey();
            int done = 0;
            try { done = getSharedPreferences("dsh_prefs", MODE_PRIVATE).getInt("payload_build_code", 0); } catch (Throwable ignored) {}
            final int fdone = done;
            final int cur = conBuildCode();
            final String s = conComputeFilesSummary();
            ui.post(new Runnable() { @Override public void run() {
                if (miss != null) conToast("校验失败：缺 " + miss + "，请点「重新解压」");
                else if (fdone != cur) conToast("校验失败：内部文件是旧版本解压的（记录 " + fdone + " / 当前 " + cur + "），请重新解压");
                else conToast("校验通过，" + (s == null ? "文件齐全" : s) + " · 已对应当前安装版本");
            }});
        }}, "files-verify").start();
    }

    // ---------- 权限页 ----------
    private String conPermSummary() {
        String[] ids = {"storage", "notify", "overlay", "battery", "root", "shizuku", "a11y", "install"};
        int ok = 0;
        for (int i = 0; i < ids.length; i++) if (conPermOk(ids[i])) ok++;
        return "已授权 " + ok + " / " + ids.length;
    }

    private boolean conPermOk(String id) {
        try {
            if ("storage".equals(id)) {
                if (Build.VERSION.SDK_INT >= 30) return Environment.isExternalStorageManager();
                return checkSelfPermission("android.permission.WRITE_EXTERNAL_STORAGE") == PackageManager.PERMISSION_GRANTED;
            }
            if ("notify".equals(id)) {
                android.app.NotificationManager nm = (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                return nm != null && nm.areNotificationsEnabled();
            }
            if ("overlay".equals(id)) return Settings.canDrawOverlays(this);
            if ("battery".equals(id)) {
                PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
            }
            if ("root".equals(id)) return conRootOk;
            if ("shizuku".equals(id)) return shizukuOk != null && shizukuOk.booleanValue();
            if ("a11y".equals(id)) return conA11yEnabled();
            if ("install".equals(id)) return Build.VERSION.SDK_INT < 26 || getPackageManager().canRequestPackageInstalls();
        } catch (Throwable t) { return false; }
        return false;
    }

    private boolean conA11yEnabled() {
        try {
            String s = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (s == null) return false;
            return s.toLowerCase().contains(getPackageName().toLowerCase());
        } catch (Throwable t) { return false; }
    }

    private void conRefreshRootAsync() {
        if (System.currentTimeMillis() - conRootProbeTs < 5000) return;
        conRootProbeTs = System.currentTimeMillis();
        new Thread(new Runnable() { @Override public void run() {
            boolean ok = false;
            try { ok = rootAvailable(); } catch (Throwable ignored) {}
            conRootOk = ok;
            ui.post(new Runnable() { @Override public void run() { refreshConsole(); } });
        }}, "root-probe").start();
    }

    private void renderConsolePerm() {
        LinearLayout col = consoleBody;
        col.addView(conBackRow("授予权限"));
        col.addView(cSep(dp(12)));
        addPermRow(col, "所有文件访问", "读写 /sdcard，AI 才能碰你的文件", "storage");
        addPermRow(col, "通知", "AI 发通知、定时任务提醒", "notify");
        addPermRow(col, "悬浮窗", "黑鲸鱼悬浮窗 / 虚拟屏预览", "overlay");
        addPermRow(col, "电池优化", "设为「不限制」，否则切后台引擎会被杀", "battery");
        addPermRow(col, "root（超级用户）", "替代 Shizuku 跑特权命令：装应用 / 改设置 / 虚拟屏点击 / 任意 shell", "root");
        addPermRow(col, "Shizuku（免 root 特权通道）", "有 root 时用 root；没 root 时装 Shizuku 走同一套能力", "shizuku");
        addPermRow(col, "无障碍服务（读屏 / 点屏）", "android_screen / tap / type / see（不需要 root 或 Shizuku）", "a11y");
        addPermRow(col, "安装未知应用", "android_package 装 APK 用", "install");
        col.addView(cText("root / Shizuku 二选一即可（root 优先）。root 只能由你在 root 管理器（Magisk / KernelSU）里授予本应用；设备没 root 时这一项显示「本机无 root」。",
                11f, cSub(), false), cTop(dp(14)));
        conRefreshRootAsync();
    }

    private void addPermRow(LinearLayout col, String title, String desc, final String id) {
        boolean ok = conPermOk(id);
        boolean noRoot = "root".equals(id) && !conRootOk;
        String state = ok ? "已授权" : (noRoot ? "本机无 root" : "未授权");
        int color = ok ? cGreen() : (noRoot ? cSub() : cRed());
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.addView(cText(title, 13.5f, cText(), false));
        left.addView(cText(desc, 11f, cSub(), false));
        Button act = cButton(ok ? "管理" : "去授权", false);
        act.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        if (noRoot) cSetEnabled(act, false);
        act.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { conPermAction(id); }
        });
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(13), 0, dp(13));
        row.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(cText(state, 11f, color, false));
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.leftMargin = dp(8);
        row.addView(act, alp);
        col.addView(row);
        col.addView(cSep(0));
    }

    private void conPermAction(String id) {
        try {
            if ("storage".equals(id)) {
                if (Build.VERSION.SDK_INT >= 30) {
                    Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                    i.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(i);
                } else {
                    requestPermissions(new String[]{"android.permission.WRITE_EXTERNAL_STORAGE"}, REQ_STORAGE);
                }
                return;
            }
            if ("notify".equals(id)) {
                Intent i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
                i.putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
                startActivity(i);
                return;
            }
            if ("overlay".equals(id)) {
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
                return;
            }
            if ("battery".equals(id)) {
                startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName())));
                return;
            }
            if ("root".equals(id)) {
                conToast("请在 root 管理器（Magisk / KernelSU）里给本应用授予 root");
                return;
            }
            if ("shizuku".equals(id)) {
                // v1.13.3：这里原来只调 probeShizuku()（纯探测）——用户点「管理」永远不会弹授权框。
                // 真机实测：引擎里 AI 调 shizuku_status / shizuku_shell（内部走 rish 广播）能弹出 Shizuku 授权框，
                // 控制台却不弹；差别就在“有没有发出真实请求”。改走统一入口：
                // 实时探测 → 未授权就 requestPermission + 两条真实触发（binder / rish）→ 仍未授权给手动引导。
                showShizukuDialog();
                return;
            }
            if ("a11y".equals(id)) {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                return;
            }
            if ("install".equals(id)) {
                if (Build.VERSION.SDK_INT >= 26) {
                    startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())));
                } else {
                    conToast("本机系统无需单独授权");
                }
                return;
            }
        } catch (Throwable t) {
            conToast("打不开系统页：" + t.getMessage());
        }
    }

    // ---------- 插件页 ----------
    private static final String[][] CON_PLUGINS = {
        {"tool-vscreen", "虚拟屏：建屏 / 看图 / 点击 · 8 个工具"},
        {"tool-accessibility", "无障碍读屏 / 手势 / 截图理解"},
        {"tool-android", "用量统计 / 悬浮窗 / 剪贴板 / 定时任务"},
        {"tool-shizuku", "特权 shell（root 或 Shizuku 任一）"},
        {"llm-pi-ai", "第三方供应商适配（关掉则「添加提供方」不可用）"},
        {"session-telemetry-otel", "遥测上报 · Android 上不需要"},
        {"session-log-download", "会话日志导出按钮（右上角）"},
        {"pwsh-sandbox", "PowerShell 沙箱 · Android 无 pwsh"},
        {"bash-sandbox", "bash 沙箱（本项目用 bash-local 替代）"},
        {"sandbox", "沙箱服务"},
    };

    private String conPlugSummary() {
        int on = 0;
        for (int i = 0; i < CON_PLUGINS.length; i++) if (!conPluginDisabled(CON_PLUGINS[i][0])) on++;
        return "已启用 " + on + " / " + CON_PLUGINS.length;
    }

    private String conPatchPath() { return new File(payloadDir(), "dshhome/cordis.patch.yml").getAbsolutePath(); }

    // ---------- cordis.patch.yml 读写（v1.13 重写） ----------
    // 文件结构：顶层是**平铺的 patch 条目数组** —— 要么 `- id: <行id>` + `disabled: true` / `config:`
    // （作用在别层已注册的行上），要么 `- insert:` 桶（桶内 `    - id: <行id>` + `      name:` 注册新行）。
    // 两条硬规则：① patch 按列表顺序生效 —— insert 桶里的行必须先被注册，之后的行才能按 id 命中该行；
    // ② 开关只能**原地**改写条目本身那一行 —— 删掉 `- id:` 行会留下悬空 `name:`（YAML 重复键 → 引擎启动即崩）。

    /** 行首缩进宽度（空格/Tab 各计 1，够用）。 */
    private static int conIndentOf(String line) {
        int n = 0;
        while (n < line.length() && (line.charAt(n) == ' ' || line.charAt(n) == '\t')) n++;
        return n;
    }

    private static boolean conBlankOrComment(String trimmed) {
        return trimmed.length() == 0 || trimmed.startsWith("#");
    }

    private static String conSpaces(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(' ');
        return sb.toString();
    }

    /** 拆行（去行尾 CR，保留空行）。 */
    private static java.util.List<String> conSplitLines(String txt) {
        String[] raw = txt.split("\n");
        java.util.List<String> out = new java.util.ArrayList<String>();
        for (int i = 0; i < raw.length; i++) {
            String l = raw[i];
            if (l.endsWith("\r")) l = l.substring(0, l.length() - 1);
            out.add(l);
        }
        return out;
    }

    private static String conJoinLines(java.util.List<String> lines, String nl) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) { sb.append(lines.get(i)); sb.append(nl); }
        return sb.toString();
    }

    /** 该行若是 `- id: xxx` 则返回 xxx，否则 null。 */
    private static String conRowIdOf(String trimmed) {
        if (!trimmed.startsWith("- id:")) return null;
        String id = trimmed.substring(5).trim();
        return id.length() == 0 ? null : id;
    }

    private int conFindRow(java.util.List<String> lines, String id) {
        for (int i = 0; i < lines.size(); i++) {
            String rid = conRowIdOf(lines.get(i).trim());
            if (rid != null && rid.equals(id)) return i;
        }
        return -1;
    }

    /** 条目块末行下标（含）：往下直到同级/更浅缩进的非空白行。 */
    private int conRowEnd(java.util.List<String> lines, int idIdx) {
        int base = conIndentOf(lines.get(idIdx));
        int end = idIdx;
        for (int j = idIdx + 1; j < lines.size(); j++) {
            String t = lines.get(j).trim();
            if (t.length() == 0) break;              // 空行即条目结束
            if (t.startsWith("#")) continue;         // 注释归下一条目
            if (conIndentOf(lines.get(j)) <= base) break;
            end = j;
        }
        return end;
    }

    /** 该行是否位于 `- insert:` 桶内（桶内行只能原地开关；顶层条目作用于别层注册的行）。 */
    private boolean conInsideInsert(java.util.List<String> lines, int idIdx) {
        int base = conIndentOf(lines.get(idIdx));
        for (int j = idIdx - 1; j >= 0; j--) {
            String t = lines.get(j).trim();
            if (conBlankOrComment(t)) continue;
            if (conIndentOf(lines.get(j)) < base) return t.startsWith("- insert:");
        }
        return false;
    }

    /** 条目内原地增/删/改 `disabled:` 行（绝不搬动条目本身）。 */
    private void conSetRowDisabled(java.util.List<String> lines, int idIdx, boolean disabled) {
        int end = conRowEnd(lines, idIdx);
        int at = -1;
        for (int j = idIdx + 1; j <= end; j++) {
            if (lines.get(j).trim().startsWith("disabled:")) { at = j; break; }
        }
        if (disabled) {
            String ind = conSpaces(conIndentOf(lines.get(idIdx)) + 2);
            if (at >= 0) lines.set(at, ind + "disabled: true");
            else lines.add(idIdx + 1, ind + "disabled: true");
        } else if (at >= 0) {
            lines.remove(at);
        }
    }

    /** 顶层 patch 条目（id 由别层注册，如 sandbox）：禁用=确保该条目存在且 disabled: true；
     *  启用=删掉 disabled 行，条目再无其它键时连 `- id:` 行一起删；新增一律**追加到文件末尾**
     *  （patch 按顺序生效，插入行必须先被注册）。 */
    private void conSetTopEntryDisabled(java.util.List<String> lines, String id, boolean disabled) {
        int at = conFindRow(lines, id);
        if (at < 0) {
            if (!disabled) return;
            lines.add("- id: " + id);
            lines.add("  disabled: true");
            return;
        }
        if (disabled) { conSetRowDisabled(lines, at, true); return; }
        int end = conRowEnd(lines, at);
        boolean hasOther = false;
        for (int j = at + 1; j <= end; j++) {
            String t = lines.get(j).trim();
            if (conBlankOrComment(t) || t.startsWith("disabled:")) continue;
            hasOther = true;
            break;
        }
        if (hasOther) { conSetRowDisabled(lines, at, false); return; }
        for (int j = end; j > at; j--) lines.remove(j);
        lines.remove(at);
    }

    /** 结构性自愈（返回是否有改动）：修复控制台旧实现写坏的 cordis.patch.yml。
     *  旧实现关插件时把 `- id: xxx` 整行删掉、再把条目搬到文件顶部，后果两连：
     *   ① 原地留下悬空 `name:` → YAML “duplicated mapping key” → 引擎**启动即崩**、App 反复重拉（界面一直闪）；
     *   ② 搬上去的条目落在 `- insert:` 之前 → 插入行还没注册 → 就算 YAML 合法也不生效。
     *  自愈动作：按包名补回被删的 `- id:` 行；把“只有 disabled 的顶层条目”折叠回它对应的 insert 行内。 */
    /** 从 `name: '@deepseek-ai/dsh-tool-vscreen'` 反推行 id（补回被删的 `- id:` 行用）。 */
    private static String conIdFromName(String raw) {
        String n = raw;
        if (n.length() > 1 && ((n.charAt(0) == '\'' && n.endsWith("'"))
                || (n.charAt(0) == '"' && n.endsWith("\"")))) {
            n = n.substring(1, n.length() - 1);
        }
        if (n.startsWith("@deepseek-ai/dsh-")) n = n.substring("@deepseek-ai/dsh-".length());
        else { int k = n.lastIndexOf('/'); if (k >= 0) n = n.substring(k + 1); }
        return n.length() == 0 ? null : n;
    }

    private boolean conNormalizePatchLines(java.util.List<String> lines) {
        boolean changed = false;
        // ① 悬空 name 行 → 补回 `- id:`。判据：一个条目里只允许一个 `name:` 键，
        //   同一个条目里再碰到第二个 name（前面不是 `- id:`）就说明它的 id 行被删了。
        int curRow = -1;            // 当前条目的 `- id:` 缩进；-1 = 不在条目内
        boolean sawName = false;
        for (int i = 0; i < lines.size(); i++) {
            String t = lines.get(i).trim();
            if (t.length() == 0) { curRow = -1; sawName = false; continue; }   // 空行结束条目
            if (t.startsWith("#")) continue;
            int ind = conIndentOf(lines.get(i));
            if (conRowIdOf(t) != null) { curRow = ind; sawName = false; continue; }
            if (!t.startsWith("name:")) continue;
            if (curRow >= 0 && ind > curRow && !sawName) { sawName = true; continue; }   // 正常键
            String id = conIdFromName(t.substring(5).trim());
            if (id == null) continue;
            int at = (curRow >= 0 && ind > curRow) ? curRow : (ind > 2 ? ind - 2 : 0);
            lines.add(i, conSpaces(at) + "- id: " + id);
            curRow = at;
            sawName = false;    // 下一轮处理原 name 行时把它计作新条目的 name
            changed = true;
            i++;
        }
        // ② 顶层“只有 disabled”的条目若对应文件内某个 insert 行 → 折叠进行内
        for (int i = 0; i < lines.size(); i++) {
            String rid = conRowIdOf(lines.get(i).trim());
            if (rid == null || conIndentOf(lines.get(i)) != 0) continue;
            int end = conRowEnd(lines, i);
            boolean hasDisabled = false, hasOther = false;
            for (int j = i + 1; j <= end; j++) {
                String tt = lines.get(j).trim();
                if (conBlankOrComment(tt)) continue;
                if (tt.startsWith("disabled:")) { hasDisabled = true; continue; }
                hasOther = true;
                break;
            }
            if (!hasDisabled || hasOther) continue;
            int row = -1;
            for (int j = 0; j < lines.size(); j++) {
                String jid = conRowIdOf(lines.get(j).trim());
                if (jid != null && jid.equals(rid) && j != i && conInsideInsert(lines, j)) { row = j; break; }
            }
            if (row < 0) continue;
            conSetRowDisabled(lines, row, true);
            for (int j = end; j > i; j--) lines.remove(j);
            lines.remove(i);
            changed = true;
            i--;
        }
        return changed;
    }

    /** 起引擎前调用（幂等；失败只记日志，不阻断启动）。 */
    private void conHealPatchConfig() {
        try {
            File f = new File(conPatchPath());
            if (!f.exists()) return;
            String txt = readFileText(f);
            if (txt == null) return;
            String nl = txt.indexOf("\r\n") >= 0 ? "\r\n" : "\n";
            java.util.List<String> lines = conSplitLines(txt);
            if (!conNormalizePatchLines(lines)) return;
            conWriteText(f, conJoinLines(lines, nl));
            Log.w(TAG, "cordis.patch.yml repaired (broken by an older console plugin toggle)");
        } catch (Throwable t) {
            Log.w(TAG, "conHealPatchConfig", t);
        }
    }

    private boolean conPluginDisabled(String id) {
        try {
            String txt = readFileText(new File(conPatchPath()));
            if (txt == null) return false;
            java.util.List<String> lines = conSplitLines(txt);
            int i = conFindRow(lines, id);
            if (i < 0) return false;
            int end = conRowEnd(lines, i);
            for (int j = i + 1; j <= end; j++) {
                String t = lines.get(j).trim();
                if (t.startsWith("disabled:")) return t.indexOf("true") >= 0;
            }
        } catch (Throwable t) { Log.w(TAG, "conPluginDisabled", t); }
        return false;
    }

    private void conSetPluginDisabled(String id, boolean disabled) {
        try {
            File f = new File(conPatchPath());
            String txt = readFileText(f);
            if (txt == null) return;
            String nl = txt.indexOf("\r\n") >= 0 ? "\r\n" : "\n";
            java.util.List<String> lines = conSplitLines(txt);
            conNormalizePatchLines(lines);   // 先自愈，避免“越点越烂”
            int at = conFindRow(lines, id);
            if (at >= 0 && conInsideInsert(lines, at)) conSetRowDisabled(lines, at, disabled);
            else conSetTopEntryDisabled(lines, id, disabled);
            conWriteText(f, conJoinLines(lines, nl));
        } catch (Throwable t) { Log.w(TAG, "conSetPluginDisabled", t); conToast("写配置失败：" + t.getMessage()); }
    }

    private void conWriteText(File f, String s) throws IOException {
        File p = f.getParentFile();
        if (p != null && !p.exists()) p.mkdirs();
        FileOutputStream fos = new FileOutputStream(f);
        try { fos.write(s.getBytes("UTF-8")); } finally { fos.close(); }
    }

    private void renderConsolePlug() {
        LinearLayout col = consoleBody;
        col.addView(conBackRow("插件"));
        col.addView(cText("关掉的插件不加载：工具不进 AI 的工具表，也少占上下文。改动在重启引擎后生效。",
                11f, cSub(), false), cTop(dp(10)));
        col.addView(cSep(dp(12)));
        for (int i = 0; i < CON_PLUGINS.length; i++) {
            final String id = CON_PLUGINS[i][0];
            boolean disabled = conPluginDisabled(id);
            LinearLayout left = new LinearLayout(this);
            left.setOrientation(LinearLayout.VERTICAL);
            left.addView(cText("dsh-" + id, 13.5f, cText(), false));
            left.addView(cText(CON_PLUGINS[i][1], 11f, cSub(), false));
            TextView pill = cToggle(id, !disabled);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(12), 0, dp(12));
            row.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(pill);
            col.addView(row);
            col.addView(cSep(0));
        }
        Button apply = cButton("重启引擎生效", true);
        apply.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { conRestartEngine(); }
        });
        col.addView(apply, cTop(dp(14)));
    }

    // ---------- 日志页 ----------
    private void renderConsoleLog() {
        LinearLayout col = consoleBody;
        col.addView(conBackRow("日志"));
        col.addView(cText(conLogSummary(), 12f, cText(), false), cTop(dp(12)));
        col.addView(cText("「查看」直接看末尾 200 行；「分享」调用系统分享（QQ / 微信 / 邮件…都能选），正文里带完整日志路径与末尾 400 行。",
                11f, cSub(), false), cTop(dp(8)));
        LinearLayout acts = new LinearLayout(this);
        acts.setOrientation(LinearLayout.HORIZONTAL);
        Button v = cButton("查看日志", false);
        v.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View x) { conViewLog(); } });
        Button e = cButton("分享", true);
        e.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View x) { conShareLog(); } });
        acts.addView(v);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(8);
        acts.addView(e, lp);
        col.addView(acts, cTop(dp(14)));
    }

    private File conLogFile() { return new File(getFilesDir(), "dsh-web.log"); }

    private String conLogSummary() {
        try {
            File f = conLogFile();
            if (!f.exists()) return "还没有日志（引擎没启动过）";
            return "dsh-web.log · " + (f.length() / 1024) + " KB · "
                    + new SimpleDateFormat("MM-dd HH:mm").format(new java.util.Date(f.lastModified()));
        } catch (Throwable t) { return "日志不可读"; }
    }

    private String conTailOf(File f, int maxLines) {
        try {
            java.io.RandomAccessFile raf = new java.io.RandomAccessFile(f, "r");
            long len = raf.length();
            long start = Math.max(0, len - 65536);
            raf.seek(start);
            byte[] buf = new byte[(int) (len - start)];
            raf.readFully(buf);
            raf.close();
            String s = new String(buf, "UTF-8");
            String[] lines = s.split("\n");
            if (lines.length <= maxLines) return s;
            StringBuilder sb = new StringBuilder();
            for (int i = lines.length - maxLines; i < lines.length; i++) { sb.append(lines[i]); sb.append('\n'); }
            return sb.toString();
        } catch (Throwable t) { return "读取失败：" + t.getMessage(); }
    }

    private void conViewLog() {
        try {
            File f = conLogFile();
            if (!f.exists()) { conToast("还没有日志（引擎没启动过）"); return; }
            TextView tv = cText(conTailOf(f, 200), 10.5f, cText(), false);
            tv.setTypeface(android.graphics.Typeface.MONOSPACE);
            tv.setTextIsSelectable(true);
            ScrollView sv = new ScrollView(this);
            sv.setBackground(cShape(isDark() ? 0xFF0F1524 : 0xFFF2F4F8, 0, 0, 6));
            sv.setPadding(dp(12), dp(12), dp(12), dp(12));
            sv.addView(tv);
            sv.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(380)));
            conDialogView("dsh-web.log（末尾 200 行）", sv, "关闭", null, null);
        } catch (Throwable t) { conToast("读取日志失败：" + t.getMessage()); }
    }

    /** v1.12：不再只能导到固定目录 —— 直接调系统分享（QQ / 微信 / 邮件随意选）。 */
    @SuppressWarnings("unused")
    private void conShareLogText() {
        conToast("正在准备日志…");
        new Thread(new Runnable() { @Override public void run() {
            final String body = conBuildShareText();
            ui.post(new Runnable() { @Override public void run() { conSendShare(body); } });
        }}, "log-share").start();
    }

    private String conBuildShareText() {
        try {
            File f = conLogFile();
            String head = "DeepSeek Harness 日志\n"
                    + "版本 " + conVersionLabel() + "\n"
                    + "端口 " + enginePort + " · " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date()) + "\n"
                    + "完整日志路径：" + f.getAbsolutePath() + "\n"
                    + "-------- dsh-web.log（末尾 400 行）--------\n";
            if (!f.exists()) return head + "（还没有日志：引擎没启动过）";
            return head + conTailOf(f, 400);
        } catch (Throwable t) { return "日志读取失败：" + t.getMessage(); }
    }

    /** v1.13：以**文件**形式分享日志（原来是纯文字）。
     * 准备好 dsh-web.log + 诊断文件 → 拷到 cache/share → 用 LogShareProvider 的 content:// URI 发出去。 */
    private void conShareLog() {
        conToast("正在准备日志文件…");
        new Thread(new Runnable() { @Override public void run() {
            final java.util.List<File> files = new java.util.ArrayList<File>();
            String err = null;
            try {
                File dir = LogShareProvider.shareDir(MainActivity.this);
                // 清掉上一次的残留，避免分享到旧文件
                File[] old = dir.listFiles();
                if (old != null) for (File o : old) { try { o.delete(); } catch (Throwable ignored) {} }

                File log = conLogFile();
                if (log.exists() && log.length() > 0) {
                    File dst = new File(dir, "dsh-web.log");
                    copyFile(log, dst);
                    files.add(dst);
                }
                // 启动诊断（启动失败时才有）与外部日志镜像，一并带上
                File diag = new File(getFilesDir(), "startup-diag.txt");
                if (diag.exists() && diag.length() > 0) {
                    File dst = new File(dir, "startup-diag.txt");
                    copyFile(diag, dst);
                    files.add(dst);
                }
            } catch (Throwable t) { err = String.valueOf(t.getMessage()); }
            final String e = err;
            ui.post(new Runnable() { @Override public void run() {
                if (files.isEmpty()) { conToast("没有可分享的日志：" + (e != null ? e : "引擎还没启动过")); return; }
                conSendLogFiles(files);
            } });
        }}, "log-share").start();
    }

    /** 发多个文件（ACTION_SEND_MULTIPLE + 读权限临时授权）。 */
    private void conSendLogFiles(java.util.List<File> files) {
        try {
            java.util.ArrayList<Uri> uris = new java.util.ArrayList<Uri>();
            for (File f : files) uris.add(LogShareProvider.uriFor(this, f.getName()));
            Intent send;
            if (uris.size() == 1) {
                send = new Intent(Intent.ACTION_SEND);
                send.putExtra(Intent.EXTRA_STREAM, uris.get(0));
            } else {
                send = new Intent(Intent.ACTION_SEND_MULTIPLE);
                send.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
            }
            send.setType("text/plain");   // 兼容性好：IM/邮件/网盘都能接
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            Intent chooser = Intent.createChooser(send, "分享日志文件");
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(chooser);
        } catch (Throwable t) { conToast("调不起分享：" + t.getMessage()); }
    }

    /** 原纯文字分享（保留：某些接不住文件的场景可回退）。 */
    private void conSendShare(String text) {
        try {
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("text/plain");
            send.putExtra(Intent.EXTRA_SUBJECT, "DeepSeek Harness 日志");
            send.putExtra(Intent.EXTRA_TEXT, text);
            Intent chooser = Intent.createChooser(send, "分享日志");
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(chooser);
        } catch (Throwable t) { conToast("调不起分享：" + t.getMessage()); }
    }

    private void conExportLog() {
        conToast("正在导出…");
        new Thread(new Runnable() { @Override public void run() {
            try {
                String ts = new SimpleDateFormat("yyyyMMdd-HHmmss").format(new java.util.Date());
                File dir = new File(Environment.getExternalStorageDirectory(), "Download/DSH日志-" + ts);
                if (!dir.exists() && !dir.mkdirs()) throw new IOException("mkdir failed: " + dir);
                File log = conLogFile();
                if (log.exists()) conCopyFile(log, new File(dir, "dsh-web.log"));
                File ext = new File(Environment.getExternalStorageDirectory(), pkgRoot());
                File mirror = new File(ext, "dsh-web.log");
                if (mirror.exists()) conCopyFile(mirror, new File(dir, "dsh-web-mirror.log"));
                File diag = new File(ext, "startup-diag.txt");
                if (diag.exists()) conCopyFile(diag, new File(dir, "startup-diag.txt"));
                final String p = dir.getAbsolutePath();
                ui.post(new Runnable() { @Override public void run() { conToast("已导出到 " + p); } });
            } catch (Throwable t) {
                final String m = String.valueOf(t.getMessage());
                ui.post(new Runnable() { @Override public void run() { conToast("导出失败：" + m); } });
            }
        }}, "log-export").start();
    }

    private void conCopyFile(File src, File dst) throws IOException {
        File p = dst.getParentFile();
        if (p != null && !p.exists()) p.mkdirs();
        FileInputStream in = new FileInputStream(src);
        FileOutputStream out = new FileOutputStream(dst);
        try {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } finally {
            in.close();
            out.close();
        }
    }

    @Override
    public void onBackPressed() {
        // v1.12：控制台内的返回先回控制台首页，再退出
        if (consoleVisible) {
            if (consolePage != 0) { consolePage = 0; renderConsole(); return; }
            confirmExit();
            return;
        }
        // 有历史先回退（可关掉侧边栏/返回上一页）；没有历史则询问是否退出
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
            return;
        }
        confirmExit();
    }
}
