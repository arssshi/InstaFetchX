package com.instafetchx;

import android.content.Context;
import android.content.SharedPreferences;

/** Local-only preferences (session id for stories/highlights). */
final class Prefs {
    private static final String NAME = "instafetchx";
    private static final String KEY_SESSION = "session";

    static String session(Context c) {
        SharedPreferences p = c.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        String s = p.getString(KEY_SESSION, null);
        return s == null || s.trim().isEmpty() ? null : s.trim();
    }

    static void setSession(Context c, String value) {
        c.getSharedPreferences(NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_SESSION, value == null ? "" : value.trim())
                .apply();
    }
}
