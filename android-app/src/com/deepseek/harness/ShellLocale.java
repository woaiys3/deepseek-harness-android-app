package com.deepseek.harness;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

/**
 * Локализация нативной оболочки: консоль (стартовая страница), мастер первого
 * запуска, диалоги, тосты, панель плавающего окна и уведомления.
 *
 * Строки в коде остаются китайскими — это язык-источник и ключ словаря.
 * Перевод подставляется только в момент показа, поэтому логика приложения
 * (сравнение строк, разбор логов, ответы локального API) не меняется.
 *
 * Язык интерфейса: 0 — как в системе, 1 — 中文, 2 — русский.
 */
final class ShellLocale {

    static final int LANG_SYSTEM = 0;
    static final int LANG_ZH = 1;
    static final int LANG_RU = 2;

    private static final String PREFS = "dsh_prefs";
    private static final String KEY = "shell_lang";

    private static volatile boolean ru = false;
    private static volatile int mode = LANG_SYSTEM;

    private ShellLocale() {}

    /** Вызывать в onCreate/onReceive до построения интерфейса. */
    static void init(Context c) {
        int m = LANG_SYSTEM;
        try {
            m = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY, LANG_SYSTEM);
        } catch (Throwable ignored) {}
        mode = m;
        ru = russian(m);
    }

    static int mode() { return mode; }

    static void setMode(Context c, int m) {
        mode = m;
        ru = russian(m);
        try {
            c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY, m).apply();
        } catch (Throwable ignored) {}
    }

    static boolean isRussian() { return ru; }

    private static boolean russian(int m) {
        if (m == LANG_RU) return true;
        if (m == LANG_ZH) return false;
        try {
            return "ru".equalsIgnoreCase(java.util.Locale.getDefault().getLanguage());
        } catch (Throwable t) {
            return false;
        }
    }

    /** Перевод строки; неизвестная строка возвращается как есть (китайский fallback). */
    static String t(String s) {
        if (!ru || s == null || s.length() == 0) return s;
        String v = ShellRu.dict(s);
        return v != null ? v : s;
    }

    /** Перевод всего дерева вью. Идемпотентно: уже переведённый текст не меняется. */
    static void apply(View v) {
        if (!ru || v == null) return;
        if (v instanceof TextView) {
            TextView tv = (TextView) v;
            CharSequence cs = tv.getText();
            if (cs != null && cs.length() > 0) {
                String src = cs.toString();
                String to = t(src);
                if (to != src) tv.setText(to);
            }
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) apply(g.getChildAt(i));
        }
    }
}
