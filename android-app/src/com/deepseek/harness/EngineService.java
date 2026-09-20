package com.deepseek.harness;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

/**
 * 前台保活服务：引擎（node 服务器）运行期间常驻通知栏，
 * 让系统把本应用标记为高优先级进程，挂后台/锁屏不被杀掉，
 * AI 后台任务（对话、工具调用）可持续执行。
 *
 * 生命周期：
 *   - startEngine() 时由 MainActivity 拉起（startForegroundService / startService）
 *   - 用户主动「退出」时由 MainActivity 停止（stopService）
 *   - 按 Home 挂后台不停止（这正是保活的目的）
 */
public class EngineService extends Service {
    private static final String CHANNEL_ID = "dsh_engine";
    private static final int NOTIF_ID = 1;

    @Override
    public void onCreate() {
        super.onCreate();
        ShellLocale.init(this); // язык уведомлений
        createChannel();
        startForeground(NOTIF_ID, buildNotification(ShellLocale.t("DeepSeek Harness 正在运行"), ShellLocale.t("AI 引擎保活中，后台任务持续执行")));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 每次收到启动/重启意图都刷新通知（系统杀进程后 START_STICKY 重建也会走到这里）
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIF_ID, buildNotification(ShellLocale.t("DeepSeek Harness 正在运行"), ShellLocale.t("AI 引擎保活中，后台任务持续执行")));
        // 定时任务自动执行：闹钟到点后带 scheduledTask extra 启动本服务，后台执行任务
        if (intent != null) {
            String task = intent.getStringExtra("scheduledTask");
            if (task != null && !task.isEmpty()) {
                final String fTask = task;
                new Thread(new Runnable() {
                    @Override public void run() {
                        ScheduleExecutor.execute(EngineService.this, fTask);
                    }
                }, "scheduled-exec").start();
            }
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(NOTIF_ID);
    }

    private Notification buildNotification(String title, String text) {
        Intent i = new Intent(this, MainActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            b = new Notification.Builder(this, CHANNEL_ID);
        } else {
            b = new Notification.Builder(this);
        }
        return b.setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentIntent(pi)
                .setOngoing(true)   // 常驻不可滑动删除
                .setPriority(Notification.PRIORITY_LOW)
                .build();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, ShellLocale.t("引擎保活"),
                    NotificationManager.IMPORTANCE_LOW);
            ch.setDescription(ShellLocale.t("DeepSeek Harness 引擎运行状态"));
            nm.createNotificationChannel(ch);
        }
    }
}
