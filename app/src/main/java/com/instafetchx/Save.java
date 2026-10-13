package com.instafetchx;

import android.annotation.SuppressLint;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/** Saves media files to the public Downloads/InstaFetchX folder. */
final class Save {

    interface Listener {
        void onSaved(Uri uri, String name, long size);
        void onError(String message);
    }

    static void save(Context ctx, Media m, String session, Http.Progress progress, Listener listener) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                saveModern(ctx, m, session, progress, listener);
            } else {
                saveLegacy(ctx, m, session, progress, listener);
            }
        } catch (Exception e) {
            listener.onError(e.getMessage() == null ? "Save failed" : e.getMessage());
        }
    }

    @SuppressLint("NewApi")
    private static void saveModern(Context ctx, Media m, String session, Http.Progress progress, Listener l)
            throws Exception {
        ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, m.fileName());
        v.put(MediaStore.MediaColumns.MIME_TYPE, m.mime());
        v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/InstaFetchX");
        v.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri uri = ctx.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
        if (uri == null) throw new Exception("Could not create file in Downloads");
        Http.Sink sink = new Http.Sink() {
            private CountingStream os;
            @Override public OutputStream open() throws IOException {
                OutputStream raw = ctx.getContentResolver().openOutputStream(uri);
                if (raw == null) throw new IOException("Could not open file in Downloads");
                os = new CountingStream(raw);
                return os;
            }
            @Override public void commit() throws IOException {
                if (os == null) throw new IOException("Download destination was not opened");
                v.clear();
                v.put(MediaStore.MediaColumns.IS_PENDING, 0);
                v.put(MediaStore.MediaColumns.SIZE, os.written);
                if (ctx.getContentResolver().update(uri, v, null, null) <= 0) {
                    throw new IOException("Could not finalize file in Downloads");
                }
                l.onSaved(uri, m.fileName(), os.written);
            }
            @Override public void abort() {
                try { ctx.getContentResolver().delete(uri, null, null); } catch (Exception ignored) { }
            }
        };
        Http.download(m.url, session, sink, progress);
    }

    private static void saveLegacy(Context ctx, Media m, String session, Http.Progress progress, Listener l)
            throws Exception {
        File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "InstaFetchX");
        if (!dir.exists() && !dir.mkdirs()) throw new Exception("Cannot create Downloads/InstaFetchX");
        final File f = new File(dir, Util.uniqueName(dir, m.fileName()));
        ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.DATA, f.getAbsolutePath());
        v.put(MediaStore.MediaColumns.MIME_TYPE, m.mime());
        v.put(MediaStore.MediaColumns.TITLE, f.getName());
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, f.getName());
        Uri base = m.video ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI : MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        final Uri uri = ctx.getContentResolver().insert(base, v);
        Http.Sink sink;
        if (uri != null) {
            sink = new Http.Sink() {
                private CountingStream os;
                @Override public OutputStream open() throws IOException {
                    OutputStream raw = ctx.getContentResolver().openOutputStream(uri);
                    if (raw == null) throw new IOException("Could not open file in Downloads");
                    os = new CountingStream(raw);
                    return os;
                }
                @Override public void commit() throws IOException {
                    if (os == null) throw new IOException("Download destination was not opened");
                    v.put(MediaStore.MediaColumns.SIZE, os.written);
                    if (ctx.getContentResolver().update(uri, v, null, null) <= 0) {
                        throw new IOException("Could not finalize file in Downloads");
                    }
                    l.onSaved(uri, m.fileName(), os.written);
                }
                @Override public void abort() {
                    try { ctx.getContentResolver().delete(uri, null, null); } catch (Exception ignored) { }
                    try { f.delete(); } catch (Exception ignored) { }
                }
            };
        } else {
            sink = new Http.Sink() {
                private CountingStream os;
                @Override public OutputStream open() throws IOException {
                    os = new CountingStream(new java.io.FileOutputStream(f));
                    return os;
                }
                @Override public void commit() throws IOException {
                    if (os == null) throw new IOException("Download destination was not opened");
                    android.media.MediaScannerConnection.scanFile(
                            ctx, new String[]{f.getAbsolutePath()}, null, null);
                    l.onSaved(Uri.parse("file://" + f.getAbsolutePath()), m.fileName(), os.written);
                }
                @Override public void abort() {
                    try { f.delete(); } catch (Exception ignored) { }
                }
            };
        }
        Http.download(m.url, session, sink, progress);
    }

    /** Counts bytes flowing through so the listener can report a real size. */
    private static final class CountingStream extends FilterOutputStream {
        long written;
        CountingStream(OutputStream out) { super(out); }
        @Override public void write(int b) throws IOException {
            out.write(b);
            written++;
        }
        @Override public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
            written += len;
        }
    }
}
