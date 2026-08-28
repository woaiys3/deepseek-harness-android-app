package com.deepseek.harness;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 定时任务后台执行器（⑥ 全自动）：
 * 闹钟到点后由 AlarmReceiver 调用，**不依赖 Activity** ——
 * 直接定位引擎文件、启动 node、等 HTTP 就绪、调 DSH API 让 AI 自动执行任务。
 * 引擎已在运行时（3080 有响应）直接复用，不重复启动。
 */
public final class ScheduleExecutor {
    private static final String TAG = "ScheduleExecutor";
    private static final String REL_BINJS = "lib/node_modules/@deepseek-ai/dsh/lib/bin.js";

    private ScheduleExecutor() {}

    /** 引擎端口：固定默认端口（v1.5.4 起移除「端口冲突自动换端口」，与 MainActivity 一致，不再读 engine_port）。 */
    private static int enginePort(Context ctx) {
        return 3080; // 默认（正式版；Lite 版由构建时改 3082 / 抢先版 3084）
    }

    /** 执行一条定时任务（后台线程，调用方勿阻塞主线程）。 */
    public static void execute(Context ctx, String task) {
        if (task == null || task.isEmpty()) return;
        log(ctx, "开始执行任务: " + task);
        try {
            if (!engineReady(ctx)) {
                log(ctx, "引擎未运行，尝试启动…");
                if (!startEngine(ctx)) {
                    log(ctx, "引擎启动失败，无法自动执行任务");
                    return;
                }
            }
            // 等引擎完全就绪
            for (int i = 0; i < 30; i++) {
                if (engineReady(ctx)) break;
                Thread.sleep(1000);
            }
            if (!engineReady(ctx)) {
                log(ctx, "引擎 30 秒未就绪，放弃");
                return;
            }
            String sessionId = createSession(ctx);
            if (sessionId == null) {
                log(ctx, "创建会话失败（可能未配置 API Key）");
                return;
            }
            String promptResp = sendPromptRaw(ctx, sessionId, task);
            boolean ok = promptResp != null && promptResp.contains("\"ok\":true");
            String summary;
            if (promptResp == null) {
                summary = "任务发送失败（无响应）: " + task;
            } else if (ok) {
                summary = "任务已发送给 AI: " + task;
            } else {
                summary = "任务发送失败，响应: " + promptResp.replace("\n", " ").substring(0, Math.min(300, promptResp.length()));
            }
            log(ctx, summary);
            notifyResult(ctx, ok, ok ? "✅ 定时任务已执行：\n" + task : summary);
        } catch (Throwable t) {
            String msg = "执行异常: " + t.getMessage();
            log(ctx, msg);
            notifyResult(ctx, false, msg);
        }
    }

    /** 定时任务结果通知（Kun 式回报：执行成功/失败都通知用户，点开进 App）。 */
    private static void notifyResult(Context ctx, boolean ok, String summary) {
        try {
            android.app.NotificationManager nm = (android.app.NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                android.app.NotificationChannel ch = new android.app.NotificationChannel("dsh_schedule", "定时任务",
                        android.app.NotificationManager.IMPORTANCE_HIGH);
                ch.setDescription("AI 设置的定时提醒与任务结果");
                nm.createNotificationChannel(ch);
            }
            Intent open = new Intent(ctx, MainActivity.class);
            open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
            android.app.PendingIntent pi = android.app.PendingIntent.getActivity(ctx, 0, open,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE);
            android.app.Notification.Builder b;
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                b = new android.app.Notification.Builder(ctx, "dsh_schedule");
            } else {
                b = new android.app.Notification.Builder(ctx);
            }
            String title = ok ? "✅ 定时任务执行成功" : "❌ 定时任务执行失败";
            String text = summary != null && summary.length() > 200 ? summary.substring(0, 200) : summary;
            android.app.Notification n = b.setContentTitle(title)
                    .setContentText(text)
                    .setSmallIcon(R.drawable.ic_launcher)
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .build();
            nm.notify(ok ? 9003 : 9004, n);
        } catch (Throwable ignored) {}
    }

    /** 引擎是否已在目标端口响应，且确认是 DSH 引擎（首页含 <title>DeepSeek Harness</title>）。
     *  修复 v1.5.1：原来任意 HTTP 200-499 都算就绪，占位服务会被误判为"引擎就绪"。 */
    private static boolean engineReady(Context ctx) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL("http://127.0.0.1:" + enginePort(ctx) + "/").openConnection();
            c.setConnectTimeout(1500);
            c.setReadTimeout(1500);
            int code = c.getResponseCode();
            if (code < 200 || code >= 500) return false;
            InputStream in = c.getInputStream();
            // v1.5.5 修复：首页约 14KB，<title> 在页面末尾（旧实现只读 4096 字节永远匹配不到）。
            // 读完整页（上限 256KB），与 MainActivity.isDshEngine 保持一致。
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
            try { if (c != null) c.disconnect(); } catch (Throwable ignored) {}
        }
    }

    /** 启动 node 引擎（同 MainActivity.spawnNode 的环境变量；payload 必须已解压）。
     *  端口 = enginePort(ctx)，与主引擎换端口后保持一致。 */
    private static boolean startEngine(Context ctx) {
        try {
            File payload = new File(ctx.getFilesDir(), "payload");
            File node = new File(payload, "runtime/bin/node");
            // dshroot：v1.5.3 起【内部优先】（主引擎同款——内部存储读内核快，避免真机外部 FUSE
            // 2 万+ 文件 stat 风暴造成 50-60s 慢启动）；内部缺失时回退本包外部目录
            //（正式版 DeepSeekHarness / Lite DeepSeekHarnessLite），再回退另一版本目录。
            File dshroot = null;
            File internal = new File(payload, "dshroot");
            if (new File(internal, REL_BINJS).exists()) {
                dshroot = internal;
            } else {
                String selfRoot = ctx.getPackageName().contains(".beta")
                        ? "DeepSeekHarnessLite" : "DeepSeekHarness";
                String otherRoot = selfRoot.equals("DeepSeekHarnessLite") ? "DeepSeekHarness" : "DeepSeekHarnessLite";
                for (String root : new String[]{selfRoot, otherRoot}) {
                    File ext = new File(android.os.Environment.getExternalStorageDirectory(), root + "/dshroot");
                    if (new File(ext, REL_BINJS).exists()) { dshroot = ext; break; }
                }
            }
            if (dshroot == null) { log(ctx, "dshroot 未找到"); return false; }
            File binjs = new File(dshroot, REL_BINJS);
            File lib = new File(payload, "runtime/lib");
            File home = new File(payload, "dshhome");
            File bin = new File(payload, "bin");
            File tmp = new File(ctx.getCacheDir(), "tmp");
            if (!tmp.exists()) tmp.mkdirs();
            if (!node.exists()) { log(ctx, "node 缺失"); return false; }
            if (!node.canExecute()) node.setExecutable(true, false);

            ProcessBuilder pb = new ProcessBuilder(
                    node.getAbsolutePath(), "--expose-internals", binjs.getAbsolutePath(),
                    "web", "--host", "127.0.0.1", "--port", String.valueOf(enginePort(ctx)));
            java.util.Map<String, String> env = pb.environment();
            env.put("LD_LIBRARY_PATH", lib.getAbsolutePath());
            // Termux 共存修复（v1.7.4）：同 MainActivity.spawnNode——内置 node 的 OPENSSLDIR
            // 编译死为 /data/data/com.termux/files/usr，装了 Termux 时读其 openssl.cnf EACCES
            // 启动即崩。注入 OPENSSL_CONF 指向 payload 自带的可读配置；存在才注入，避免升级
            // 中途文件缺失时显式指向不存在的路径反而比原来的 ENOENT 静默更糟。
            File osslConf = new File(payload, "runtime/etc/openssl.cnf");
            if (osslConf.exists()) env.put("OPENSSL_CONF", osslConf.getAbsolutePath());
            env.put("PATH", bin.getAbsolutePath() + ":" +
                    new File(payload, "runtime/bin").getAbsolutePath() + ":/system/bin:/system/xbin");
            env.put("HOME", ctx.getFilesDir().getAbsolutePath());
            env.put("DSH_HOME", home.getAbsolutePath());
            env.put("TMPDIR", tmp.getAbsolutePath());
            env.put("TERM", "xterm");
            env.put("SHIZUKU_APP_ID", ctx.getPackageName());
            env.put("SHIZUKU_AVAILABLE", "0");
            env.put("ROOT_AVAILABLE", "0");
            env.put("APP_NOTIFY_PORT", String.valueOf(enginePort(ctx) + 1));
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            // 日志写入 dsh-web.log
            final File logFile = new File(ctx.getFilesDir(), "dsh-web.log");
            final InputStream is = proc.getInputStream();
            new Thread(new Runnable() {
                @Override public void run() {
                    try {
                        FileOutputStream fos = new FileOutputStream(logFile, true);
                        byte[] b = new byte[4096];
                        int n;
                        while ((n = is.read(b)) > 0) { fos.write(b, 0, n); fos.flush(); }
                        fos.close();
                    } catch (Throwable ignored) {}
                }
            }, "sched-node-log").start();
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "startEngine error", t);
            return false;
        }
    }

    /** 调 DSH API 创建会话。 */
    private static String createSession(Context ctx) {
        String json = rpc(ctx, "session.create", "{}");
        if (json == null) return null;
        // 解析 result.value.sessionId 或 result.sessionId
        int i = json.indexOf("\"sessionId\":\"");
        if (i >= 0) {
            int q1 = i + "\"sessionId\":\"".length();
            int q2 = json.indexOf('"', q1);
            if (q2 > q1) return json.substring(q1, q2);
        }
        return null;
    }

    /** 调 DSH API 发送消息。 */
    private static boolean sendPrompt(Context ctx, String sessionId, String text) {
        String payload = "{\"sessionId\":\"" + sessionId + "\",\"mode\":\"queue\",\"content\":[{\"type\":\"text\",\"text\":\"" + escapeJson(text) + "\"}]}";
        String json = rpc(ctx, "session.prompt", payload);
        return json != null && json.contains("\"ok\":true");
    }

    /** 调 DSH API 发送消息，返回完整响应（诊断用）。 */
    private static String sendPromptRaw(Context ctx, String sessionId, String text) {
        String payload = "{\"sessionId\":\"" + sessionId + "\",\"mode\":\"queue\",\"content\":[{\"type\":\"text\",\"text\":\"" + escapeJson(text) + "\"}]}";
        return rpc(ctx, "session.prompt", payload);
    }

    /** DSH RPC 调用：标准协议 {"type":"client-request","rpcId":"...","method":"...","payload":{...}} */
    private static String rpc(Context ctx, String method, String payloadJson) {
        try {
            URL url = new URL("http://127.0.0.1:" + enginePort(ctx) + "/api/" + method);
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

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }

    /** 追加执行记录到外部目录（Lite 版用 DeepSeekHarnessLite，正式版用 DeepSeekHarness，便于排查）。 */
    static void log(Context ctx, String msg) {
        try {
            String rootName = ctx.getPackageName().contains(".beta")
                    ? "DeepSeekHarnessLite" : "DeepSeekHarness";
            File root = new File(android.os.Environment.getExternalStorageDirectory(), rootName);
            if (!root.exists()) root.mkdirs();
            File f = new File(root, "scheduled-log.txt");
            FileOutputStream fos = new FileOutputStream(f, true);
            String line = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()) + " " + msg + "\n";
            fos.write(line.getBytes("UTF-8"));
            fos.close();
        } catch (Throwable ignored) {}
    }
}
