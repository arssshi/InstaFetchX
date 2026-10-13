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
        try {
            JSONArray all = read(c);
            JSONObject o = new JSONObject();
            long id = 1;
            for (int i = 0; i < all.length(); i++) id = Math.max(id, all.optJSONObject(i).optLong("i") + 1);
            o.put("i", id);
            o.put("n", m.fileName());
            o.put("u", uri.toString());
            o.put("s", size);
            o.put("t", System.currentTimeMillis());
            o.put("k", m.kind);
            o.put("v", m.video);
            JSONArray trimmed = new JSONArray();
            trimmed.put(o);
            for (int i = 0; i < all.length() && i < 59; i++) trimmed.put(all.get(i));
            write(c, trimmed);
        } catch (Exception ignored) { }
    }

    static List<Entry> load(Context c) {
        List<Entry> out = new ArrayList<>();
        try {
            JSONArray all = read(c);
            for (int i = 0; i < all.length(); i++) {
                JSONObject o = all.optJSONObject(i);
                if (o == null) continue;
                Entry e = new Entry();
                e.id = o.optLong("i");
                e.name = o.optString("n");
                e.uri = o.optString("u");
                e.size = o.optLong("s");
                e.ts = o.optLong("t");
                e.kind = o.optString("k");
                e.video = o.optBoolean("v");
                out.add(e);
            }
        } catch (Exception ignored) { }
        return out;
    }

    static void remove(Context c, long id) {
        try {
            JSONArray all = read(c);
            JSONArray out = new JSONArray();
            for (int i = 0; i < all.length(); i++) {
                JSONObject o = all.optJSONObject(i);
                if (o != null && o.optLong("i") == id) continue;
                out.put(o);
            }
            write(c, out);
        } catch (Exception ignored) { }
    }

    static void clear(Context c) {
        try {
            write(c, new JSONArray());
        } catch (Exception ignored) { }
    }

    private static JSONArray read(Context c) {
        File f = file(c);
        if (!f.exists()) return new JSONArray();
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            char[] buf = new char[4096];
            int n;
            while ((n = r.read(buf)) > 0) sb.append(buf, 0, n);
            return new JSONArray(sb.toString());
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    private static void write(Context c, JSONArray arr) {
        try {
            File tmp = new File(c.getFilesDir(), "hist.tmp");
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
