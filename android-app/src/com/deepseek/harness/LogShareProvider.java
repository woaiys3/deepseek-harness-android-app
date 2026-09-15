package com.deepseek.harness;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.UriMatcher;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * v1.13：把日志/诊断文件以**文件形式**分享出去（而不是塞一段纯文字）。
 *
 * 为什么要自己写：本项目没有 androidx（libs 下只有 Shizuku 三个 AAR），拿不到
 * androidx.core.content.FileProvider；而 targetSdk 28 又不允许直接分享 file:// URI
 * （FileUriExposedException）。所以实现一个最小 ContentProvider，只读地暴露
 * {@link #shareDir} 下的文件，配合 FLAG_GRANT_READ_URI_PERMISSION 临时授权给接收方。
 *
 * 安全边界：只在 share 目录下按**纯文件名**取文件（拒绝 / .. \ 等路径穿越），
 * provider 本身 exported=false，只有持有人显式授权的 URI 才能读。
 */
public class LogShareProvider extends ContentProvider {

    /** 权限名：<包名>.logshare（三版本共存时各自独立）。 */
    public static String authorityOf(Context ctx) {
        return ctx.getPackageName() + ".logshare";
    }

    /** 待分享文件统一放这里（App 私有缓存，随系统清理，不污染用户目录）。 */
    public static File shareDir(Context ctx) {
        File d = new File(ctx.getCacheDir(), "share");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    /** 拼出可分享的 content:// URI（文件名必须是 shareDir 的直接子文件）。 */
    public static Uri uriFor(Context ctx, String fileName) {
        return Uri.parse("content://" + authorityOf(ctx) + "/" + fileName);
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    private File resolve(Uri uri) {
        try {
            String name = uri.getLastPathSegment();
            if (name == null || name.length() == 0) return null;
            // 防路径穿越：只认纯文件名
            if (name.indexOf('/') >= 0 || name.indexOf('\\') >= 0 || name.indexOf("..") >= 0) return null;
            Context ctx = getContext();
            if (ctx == null) return null;
            return new File(shareDir(ctx), name);
        } catch (Throwable t) {
            return null;
        }
    }

    @Override
    public String getType(Uri uri) {
        return "text/plain";
    }

    /** 多数分享目标（邮件/网盘/IM）会查 DISPLAY_NAME 与 SIZE 来显示文件名和大小。 */
    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        File f = resolve(uri);
        if (f == null || !f.exists()) return null;
        String[] cols = (projection != null && projection.length > 0)
                ? projection
                : new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        MatrixCursor c = new MatrixCursor(cols, 1);
        Object[] row = new Object[cols.length];
        for (int i = 0; i < cols.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(cols[i])) row[i] = f.getName();
            else if (OpenableColumns.SIZE.equals(cols[i])) row[i] = Long.valueOf(f.length());
            else row[i] = null;
        }
        c.addRow(row);
        return c;
    }

    /** 接收方读文件走这里（只读）。 */
    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File f = resolve(uri);
        if (f == null || !f.exists()) throw new FileNotFoundException(String.valueOf(uri));
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("read-only provider");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("read-only provider");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("read-only provider");
    }
}
