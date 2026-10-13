package com.instafetchx;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Minimal HttpURLConnection helper — zero dependencies, no cookie persistence.
 * Instagram serves its SSR media payload to crawler user agents, so page/API
 * requests rotate through those. Media CDN downloads use a plain mobile UA.
 */
final class Http {

    static final String APP_ID = "936619743392459";

    private static final String[] CRAWLER_UAS = {
            "Googlebot/2.1 (+http://www.google.com/bot.html)",
            "Mozilla/5.0 (Linux; Android 6.0.1; Nexus 5X Build/MMB29P) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.6778.33 Mobile Safari/537.36 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)",
            "Mozilla/5.0 (compatible; bingbot/2.0; +http://www.bing.com/bingbot.htm)",
            "Googlebot-Image/1.0",
    };
    private static final String MEDIA_UA =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1";

    private static int uaIndex = 0;

    private static final int CONNECT_MS = 15000;
    private static final int READ_MS = 30000;

    static final class Reply {
        final int code;
        final String body;
        Reply(int code, String body) { this.code = code; this.body = body; }
        boolean ok() { return code >= 200 && code < 300 && body != null && !body.isEmpty(); }
    }

    /** GET with the next crawler UA; appId adds X-IG-App-ID when the endpoint wants it. */
    static Reply get(String url, String session, boolean appId) {
        String ua;
        synchronized (Http.class) {
            ua = CRAWLER_UAS[uaIndex % CRAWLER_UAS.length];
            uaIndex++;
        }
        return get(url, session, appId, ua);
    }

    private static Reply get(String url, String session, boolean appId, String ua) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(CONNECT_MS);
            c.setReadTimeout(READ_MS);
            c.setInstanceFollowRedirects(true);
            c.setRequestMethod("GET");
            c.setRequestProperty("User-Agent", ua);
            c.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
            c.setRequestProperty("Accept-Language", "en-US,en;q=0.9");
            if (appId) c.setRequestProperty("X-IG-App-ID", APP_ID);
            if (session != null) c.setRequestProperty("Cookie", "sessionid=" + session);
            int code = c.getResponseCode();
            InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
            String body = is == null ? "" : readAll(is);
            return new Reply(code, body);
        } catch (Exception e) {
            return new Reply(-1, e.getMessage() == null ? "" : e.getMessage());
        } finally {
            if (c != null) c.disconnect();
        }
    }

    static String readAll(InputStream is) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(48 * 1024);
        byte[] buf = new byte[16384];
        int n;
        while ((n = is.read(buf)) > 0) out.write(buf, 0, n);
        return out.toString("UTF-8");
    }

    interface Progress {
        void on(long done, long total);
        /** Return true to abort the transfer. */
        default boolean aborted() { return false; }
    }

    /** Where bytes land for a download. */
    interface Sink {
        OutputStream open() throws IOException;
        void commit() throws IOException;
        void abort();
    }

    /** Streaming download of a (signed, short-lived) media CDN url. */
    static void download(String url, String session, Sink sink, Progress progress) throws IOException {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(CONNECT_MS);
            c.setReadTimeout(READ_MS);
            c.setInstanceFollowRedirects(true);
            c.setRequestMethod("GET");
            c.setRequestProperty("User-Agent", MEDIA_UA);
            c.setRequestProperty("Accept", "*/*");
            c.setRequestProperty("Accept-Language", "en-US,en;q=0.9");
            c.setRequestProperty("Referer", "https://www.instagram.com/");
            if (session != null && url.contains("instagram.com")) {
                c.setRequestProperty("Cookie", "sessionid=" + session);
            }
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) throw new IOException("HTTP " + code);
            long total = c.getContentLengthLong();
            InputStream is = c.getInputStream();
            OutputStream os = sink.open();
            byte[] buf = new byte[16384];
            long done = 0;
            int n;
            while ((n = is.read(buf)) > 0) {
                if (progress != null && progress.aborted()) throw new IOException("aborted");
                os.write(buf, 0, n);
                done += n;
                if (progress != null) progress.on(done, total);
            }
            os.flush();
            os.close();
            is.close();
            sink.commit();
        } finally {
            if (c != null) c.disconnect();
        }
    }
}
