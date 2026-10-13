package com.instafetchx;

import android.content.Context;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.List;

/** Tiny JSON-file backed download history. */
final class History {

    private static final Object LOCK = new Object();

    static final class Entry {
        long id;
        String name;
        String uri;
        long size;
        long ts;
        String kind;
        boolean video;

        String sub() {
            return Util.fmtSize(size) + " • " + Util.ago(ts) + " • " + kind;
        }
    }

    private static File file(Context c) {
        return new File(c.getFilesDir(), "hist.json");
    }

    static void add(Context c, Media m, Uri uri, long size) {
        if (c == null || m == null || uri == null) return;
        synchronized (LOCK) {
            try {
                JSONArray all = read(c);
                JSONObject o = new JSONObject();
                long id = 1;
                for (int i = 0; i < all.length(); i++) {
                    JSONObject existing = all.optJSONObject(i);
                    if (existing == null) continue;
                    long old = existing.optLong("i");
                    if (old > 0 && old >= id) id = old == Long.MAX_VALUE ? 1 : old + 1;
                }
                o.put("i", id);
                o.put("n", m.fileName());
                o.put("u", uri.toString());
                o.put("s", Math.max(0, size));
                o.put("t", System.currentTimeMillis());
                o.put("k", m.kind == null || m.kind.isEmpty() ? "Media" : m.kind);
                o.put("v", m.video);
                JSONArray trimmed = new JSONArray();
                trimmed.put(o);
                for (int i = 0; i < all.length() && trimmed.length() < 60; i++) {
                    JSONObject existing = all.optJSONObject(i);
                    if (isUsable(existing)) trimmed.put(existing);
                }
                write(c, trimmed);
            } catch (Exception ignored) { }
        }
    }

    static List<Entry> load(Context c) {
        List<Entry> out = new ArrayList<>();
        if (c == null) return out;
        synchronized (LOCK) {
            try {
                JSONArray all = read(c);
                for (int i = 0; i < all.length(); i++) {
                    JSONObject o = all.optJSONObject(i);
                    if (!isUsable(o)) continue;
                    long id = o.optLong("i");
                    if (id <= 0) continue;
                    Entry e = new Entry();
                    e.id = id;
                    e.name = o.optString("n").trim();
                    e.uri = o.optString("u").trim();
                    e.size = Math.max(0, o.optLong("s"));
                    e.ts = Math.max(0, o.optLong("t"));
                    e.kind = o.optString("k");
                    if (e.kind.isEmpty() || e.kind.equals("null")) e.kind = "Media";
                    e.video = o.optBoolean("v");
                    out.add(e);
                }
            } catch (Exception ignored) { }
        }
        return out;
    }

    static void remove(Context c, long id) {
        if (c == null) return;
        synchronized (LOCK) {
            try {
                JSONArray all = read(c);
                JSONArray out = new JSONArray();
                for (int i = 0; i < all.length(); i++) {
                    JSONObject o = all.optJSONObject(i);
                    if (!isUsable(o)) continue;
                    if (o.optLong("i") == id) continue;
                    out.put(o);
                }
                write(c, out);
            } catch (Exception ignored) { }
        }
    }

    static void clear(Context c) {
        if (c == null) return;
        synchronized (LOCK) {
            try {
                write(c, new JSONArray());
            } catch (Exception ignored) { }
        }
    }

    private static boolean isUsable(JSONObject o) {
        if (o == null || o.optLong("i") <= 0) return false;
        String name = o.optString("n");
        String uri = o.optString("u");
        return name != null && !name.trim().isEmpty() && !name.equals("null")
                && uri != null && !uri.trim().isEmpty() && !uri.equals("null");
    }

    private static JSONArray read(Context c) {
        File f = file(c);
        if (!f.exists()) return new JSONArray();
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            char[] buf = new char[4096];
            int n;
            while ((n = r.read(buf)) != -1) {
                if (n > 0) sb.append(buf, 0, n);
            }
            return new JSONArray(sb.toString());
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    private static void write(Context c, JSONArray arr) {
        try {
            File tmp = new File(c.getFilesDir(), "hist.tmp");
            if (tmp.exists()) tmp.delete();
            try (FileWriter w = new FileWriter(tmp)) {
                w.write(arr.toString());
            }
            if (!tmp.renameTo(file(c))) {
                file(c).delete();
                tmp.renameTo(file(c));
            }
        } catch (Exception ignored) { }
    }
}
