package com.instafetchx;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Background download engine. Starts immediately from the Android share sheet,
 * runs as a foreground service, queues several links, and reports progress in
 * a single lightweight notification. No dependencies.
 */
public class DownloadService extends Service {

    static final String EXTRA_URL = "u";
    static final String ACTION_CANCEL = "com.instafetchx.action.CANCEL";

    private static final String CHANNEL = "downloads";
    private static final int NOTIF_ID = 1;

    private static final java.util.Queue<String> QUEUE = new ConcurrentLinkedQueue<>();
    private static volatile boolean workerRunning = false;

    private volatile boolean cancelled = false;
    private volatile long lastNotify = 0;
    private volatile int lastPercent = -1;

    public static void start(Context c, String url) {
        Intent i = new Intent(c, DownloadService.class);
        i.putExtra(EXTRA_URL, url);
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                c.startForegroundService(i);
            } else {
                c.startService(i);
            }
        } catch (Exception e) {
            try { c.startService(i); } catch (Exception ignored) { }
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            finish();
            return START_NOT_STICKY;
        }
        if (ACTION_CANCEL.equals(intent.getAction())) {
            cancelled = true;
            QUEUE.clear();
            return START_NOT_STICKY;
        }
        String url = intent.getStringExtra(EXTRA_URL);
        if (url == null) {
            finish();
            return START_NOT_STICKY;
        }
        cancelled = false; // a fresh intent means fresh intended work
        try {
            startForeground(NOTIF_ID, buildNotification("Starting…", null, 0, 0, true, false));
        } catch (Exception ignored) {
            // Foreground start not allowed: keep going; the OS may kill us, downloads still attempted.
        }
        QUEUE.add(url);
        if (!workerRunning) {
            workerRunning = true;
            cancelled = false;
            new Thread(this::runWorker).start();
        }
        return START_NOT_STICKY;
    }

    private void runWorker() {
        while (true) {
            String url = QUEUE.poll();
            if (url == null) break;
            runOne(url);
        }
        workerRunning = false;
        finish();
    }

    private void runOne(String url) {
        String session = Prefs.session(this);
        updateNotif("Resolving link…", null, 0, 0, true, false);
        List<Media> list;
        try {
            list = Res.resolve(url, session);
        } catch (Exception e) {
            fail(e.getMessage() == null ? "Download failed" : e.getMessage());
            return;
        }
        if (list.isEmpty()) {
            fail("No media found in this link");
            return;
        }
        Bus.start(list.size());
        int saved = 0;
        int failed = 0;
        for (int i = 0; i < list.size(); i++) {
            if (cancelled) break;
            final Media m = list.get(i);
            final int idx = i;
            updateNotif("Downloading " + (i + 1) + " of " + list.size(), m.fileName(), 0, 0, true, true);
            final int[] counts = {saved, failed};
            final String fname = m.fileName();
            final int[] state = {idx + 1, list.size()};
            Http.Progress progress = new Http.Progress() {
                @Override public void on(long done, long total) {
                    int pct = total > 0 ? (int) (done * 100 / total) : 0;
                    long now = System.currentTimeMillis();
                    if (pct >= 100 || (now - lastNotify > 300 && pct != lastPercent)) {
                        lastNotify = now;
                        lastPercent = pct;
                        updateNotif(fname + "  " + pct + "%", null, done, total, false, true);
                        Bus.progress(state[0], state[1], fname, pct);
                    }
                }
                @Override public boolean aborted() { return cancelled; }
            };
            Save.save(this, m, session, progress, new Save.Listener() {
                @Override public void onSaved(Uri uri, String name, long size) {
                    countDown(counts, 1, 0);
                    History.add(DownloadService.this, m, uri, size);
                    Bus.file(uri.toString(), name);
                }
                @Override public void onError(String message) {
                    countDown(counts, 0, 1);
                }
            });
            saved = counts[0];
            failed = counts[1];
        }
        if (cancelled) {
            cancelledNotif(saved);
            return; // runWorker will drain (queue was cleared) and stop the service
        }
        Bus.done(saved, failed > 0);
        doneNotif(saved, failed, list.size());
    }

    private void countDown(int[] counts, int ok, int fail) {
        synchronized (counts) {
            counts[0] += ok;
            counts[1] += fail;
        }
    }

    // ---- notifications --------------------------------------------------------

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            ch.setDescription("Instagram download progress");
            getSystemService(NotificationManager.class).createNotificationChannel(ch);
        }
    }

    private Notification buildNotification(String title, String text, long cur, long total,
                                           boolean indeterminate, boolean withCancel) {
        createChannel();
        Notification.Builder b = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_notif)
                .setContentTitle(title)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setProgress(total > 0 ? (int) total : 0, (int) cur, indeterminate);
        if (text != null) b.setContentText(text);
        Intent open = new Intent(this, MainActivity.class);
        b.setContentIntent(PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        if (withCancel) {
            Intent cancel = new Intent(this, DownloadService.class).setAction(ACTION_CANCEL);
            b.addAction(R.drawable.ic_notif, "Cancel",
                    PendingIntent.getService(this, 1, cancel,
                            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        }
        return b.build();
    }

    private void updateNotif(String title, String text, long cur, long total,
                             boolean indeterminate, boolean withCancel) {
        try {
            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.notify(NOTIF_ID, buildNotification(title, text, cur, total, indeterminate, withCancel));
        } catch (Exception ignored) { }
    }

    private void doneNotif(int saved, int failed, int total) {
        try {
            createChannel();
            Notification.Builder b = new Notification.Builder(this, CHANNEL)
                    .setSmallIcon(R.drawable.ic_notif)
                    .setContentTitle(saved > 0
                            ? "Saved " + saved + (saved == 1 ? " file" : " files") + " to Downloads/InstaFetchX"
                            : "Nothing was saved")
                    .setSubText(saved + "/" + total + (failed > 0 ? "  ·  " + failed + " failed" : ""))
                    .setAutoCancel(true)
                    .setShowWhen(false);
            Intent i;
            try {
                i = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_FILES);
            } catch (Exception e) {
                i = new Intent(this, MainActivity.class);
            }
            b.setContentIntent(PendingIntent.getActivity(this, 2, i,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
            getSystemService(NotificationManager.class).notify(NOTIF_ID, b.build());
        } catch (Exception ignored) { }
    }

    private void cancelledNotif(int saved) {
        try {
            createChannel();
            Notification.Builder b = new Notification.Builder(this, CHANNEL)
                    .setSmallIcon(R.drawable.ic_notif)
                    .setContentTitle(saved > 0 ? "Cancelled — saved " + saved + " before stopping"
                            : "Download cancelled")
                    .setAutoCancel(true)
                    .setShowWhen(false);
            getSystemService(NotificationManager.class).notify(NOTIF_ID, b.build());
        } catch (Exception ignored) { }
    }

    private void fail(String message) {
        Bus.error(message);
        Bus.done(0, true);        try {
            createChannel();
            Notification.Builder b = new Notification.Builder(this, CHANNEL)
                    .setSmallIcon(R.drawable.ic_notif)
                    .setContentTitle("InstaFetchX — download failed")
                    .setContentText(message)
                    .setAutoCancel(true)
                    .setShowWhen(false);
            getSystemService(NotificationManager.class).notify(NOTIF_ID, b.build());
        } catch (Exception ignored) { }
    }

    /** Detach the notification and stop the service. */
    private void finish() {
        try {
            if (Build.VERSION.SDK_INT >= 24) {
                stopForeground(Service.STOP_FOREGROUND_DETACH);
            } else {
                stopForeground(false);
            }
        } catch (Exception ignored) { }
        stopSelf();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
