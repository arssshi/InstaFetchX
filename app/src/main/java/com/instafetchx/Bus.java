package com.instafetchx;

import android.os.Handler;
import android.os.Looper;

/** In-process event bus from DownloadService to MainActivity. Zero dependencies. */
final class Bus {

    interface Listener {
        void onStart(int total);
        void onProgress(int done, int total, String name, int percent);
        void onFile(String uri, String name);
        void onDone(int count, boolean error);
        void onError(String message);
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile Listener listener;

    static void set(Listener l) { listener = l; }

    static void start(final int total) {
        MAIN.post(() -> { if (listener != null) listener.onStart(total); });
    }

    static void progress(final int done, final int total, final String name, final int percent) {
        MAIN.post(() -> { if (listener != null) listener.onProgress(done, total, name, percent); });
    }

    static void file(final String uri, final String name) {
        MAIN.post(() -> { if (listener != null) listener.onFile(uri, name); });
    }

    static void done(final int count, final boolean error) {
        MAIN.post(() -> { if (listener != null) listener.onDone(count, error); });
    }

    static void error(final String message) {
        MAIN.post(() -> { if (listener != null) listener.onError(message); });
    }
}
