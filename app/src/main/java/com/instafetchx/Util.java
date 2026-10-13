package com.instafetchx;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

import java.util.Locale;
import java.util.concurrent.TimeUnit;

/** Small shared helpers. */
final class Util {

    static String sanitize(String s) {
        if (s == null) return "file";
        StringBuilder b = new StringBuilder(s.length());
        boolean lastUnderscore = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.';
            if (ok) {
                b.append(c);
                lastUnderscore = false;
            } else if (!lastUnderscore) {
                b.append('_');
                lastUnderscore = true;
            }
        }
        String r = b.toString();
        return r.isEmpty() ? "file" : r;
    }

    static String fmtSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        String pre = "KMGTPE".charAt(exp - 1) + "";
        return String.format(Locale.US, "%.1f %sB", bytes / Math.pow(1024, exp), pre);
    }

    static String ago(long ts) {
        long d = System.currentTimeMillis() - ts;
        if (d < TimeUnit.MINUTES.toMillis(1)) return "just now";
        if (d < TimeUnit.HOURS.toMillis(1)) return TimeUnit.MILLISECONDS.toMinutes(d) + "m ago";
        if (d < TimeUnit.DAYS.toMillis(1)) return TimeUnit.MILLISECONDS.toHours(d) + "h ago";
        if (d < TimeUnit.DAYS.toMillis(30)) return TimeUnit.MILLISECONDS.toDays(d) + "d ago";
        return new java.text.SimpleDateFormat("MMM d", Locale.US).format(new java.util.Date(ts));
    }

    /** Opens a saved file from the history list. */
    static void openFile(Context ctx, String uri) {
        try {
            String mime = uri.startsWith("content://") ? "*/*" : "*/*";
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(Uri.parse(uri), mime);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (Exception e) {
            Toast.makeText(ctx, "No app can open this file", Toast.LENGTH_SHORT).show();
        }
    }

    /** Best-effort unique file name in a directory (legacy storage path). */
    static String uniqueName(java.io.File dir, String name) {
        java.io.File f = new java.io.File(dir, name);
        if (!f.exists()) return name;
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 2; i < 1000; i++) {
            String candidate = base + "_" + i + ext;
            if (!new java.io.File(dir, candidate).exists()) return candidate;
        }
        return base + "_" + System.currentTimeMillis() + ext;
    }
}
