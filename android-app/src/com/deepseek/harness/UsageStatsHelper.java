package com.deepseek.harness;

import android.app.AppOpsManager;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Process;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 应用使用时长查询（UsageStats）：
 * 依赖 android.permission.PACKAGE_USAGE_STATS —— 用户需在系统设置「使用情况访问权限」中授予
 * （通过 AppOps 校验，与权限引导页的检查一致）。未授予时返回明确错误，AI 可引导用户开启。
 * 用法：UsageStatsHelper.queryUsageJson(ctx, days) → JSON 数组（按使用时长降序，过滤 <1s）。
 */
public class UsageStatsHelper {
    private static final long DAY_MS = 24L * 3600 * 1000;

    /** 使用情况访问权限是否已授予（AppOps）。 */
    public static boolean permissionGranted(Context ctx) {
        try {
            AppOpsManager ops = (AppOpsManager) ctx.getSystemService(Context.APP_OPS_SERVICE);
            int mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(), ctx.getPackageName());
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 查询最近 days 天各应用前台使用总时长，返回 JSON。 */
    public static String queryUsageJson(Context ctx, int days) {
        if (days <= 0) days = 1;
        if (days > 30) days = 30;
        if (!permissionGranted(ctx)) {
            return "{\"ok\":false,\"error\":\"未授予「使用情况访问」权限（UsageStats），请先在系统设置-使用情况访问里开启\"}";
        }
        try {
            UsageStatsManager usm = (UsageStatsManager) ctx.getSystemService(Context.USAGE_STATS_SERVICE);
            long end = System.currentTimeMillis();
            long start = end - (long) days * DAY_MS;
            List<UsageStats> list = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, start, end);
            // 按包名聚合前台时长（注意：项目用 -bootclasspath android.jar 编译，禁用 lambda/方法引用）
            Map<String, Long> total = new HashMap<>();
            for (UsageStats s : list) {
                if (s == null) continue;
                String pkg = s.getPackageName();
                Long cur = total.get(pkg);
                total.put(pkg, (cur == null ? 0L : cur) + s.getTotalTimeInForeground());
            }
            List<Map.Entry<String, Long>> entries = new ArrayList<>(total.entrySet());
            java.util.Collections.sort(entries, new java.util.Comparator<Map.Entry<String, Long>>() {
                @Override public int compare(Map.Entry<String, Long> a, Map.Entry<String, Long> b) {
                    return Long.compare(b.getValue(), a.getValue());
                }
            });

            PackageManager pm = ctx.getPackageManager();
            StringBuilder sb = new StringBuilder("{\"ok\":true,\"days\":").append(days)
                    .append(",\"apps\":[");
            boolean first = true;
            for (Map.Entry<String, Long> e : entries) {
                if (e.getValue() < 1000L) continue; // 过滤不足 1 秒
                String label = e.getKey();
                try {
                    ApplicationInfo ai = pm.getApplicationInfo(e.getKey(), 0);
                    label = pm.getApplicationLabel(ai).toString();
                } catch (Throwable ignored) {}
                if (!first) sb.append(',');
                first = false;
                sb.append("{\"pkg\":\"").append(escape(e.getKey()))
                  .append("\",\"name\":\"").append(escape(label))
                  .append("\",\"ms\":").append(e.getValue())
                  .append(",\"min\":").append(e.getValue() / 60000L).append('}');
            }
            sb.append("]}");
            return sb.toString();
        } catch (Throwable t) {
            return "{\"ok\":false,\"error\":\"" + String.valueOf(t.getMessage()).replace("\"", "'") + "\"}";
        }
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
