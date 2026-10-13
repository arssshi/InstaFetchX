package com.instafetchx;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns Instagram share URLs into direct media URLs using only HttpURLConnection + org.json.
 *
 * Strategy (verified working anonymously, Oct 2026):
 *  - Posts / reels / carousels: fetch the post page with a crawler User-Agent and read the
 *    embedded SSR payload (&lt;script data-sjs&gt; → xig_polaris_media → if_not_gated_logged_out).
 *    og:video:secure_url / og:image meta tags cover the simple single-video case.
 *  - Profile pictures: same SSR approach on the profile page (xig_user_by_igid_v2).
 *  - Highlights: /api/v1/feed/reels_media/?reel_ids=highlight:{id} works without login.
 *  - Stories: login-gated by Instagram; works with a user-supplied sessionid.
 */
final class Res {

    private static final String BASE = "https://www.instagram.com";

    private static final Pattern SJS =
            Pattern.compile("<script\\b[^>]*\\bdata-sjs[^>]*>(\\{.*?\\})</script>", Pattern.DOTALL);
    private static final Pattern OG_VIDEO =
            Pattern.compile("<meta\\s+property=\"og:video(?::secure_url)?\"\\s+content=\"([^\"]+)\"");
    private static final Pattern OG_IMAGE =
            Pattern.compile("<meta\\s+property=\"og:image\"\\s+content=\"([^\"]+)\"");
    private static final Pattern SHARED_URL =
            Pattern.compile("(?i)(?<![A-Za-z0-9+.-])https?://[^\\s<>\"']+");
    private static final Pattern SAFE_TOKEN = Pattern.compile("[A-Za-z0-9._-]{1,256}");
    private static final Pattern SAFE_USERNAME = Pattern.compile("[A-Za-z0-9._]{1,256}");

    // ---- link parsing --------------------------------------------------------

    /** Finds the first Instagram URL anywhere in a shared text blob. */
    static String extractUrl(String text) {
        if (text == null) return null;
        Matcher m = SHARED_URL.matcher(text);
        while (m.find()) {
            String cand = stripPunctuation(m.group());
            if (isInstagramUrl(cand)) return cand;
        }
        return null;
    }

    private static String stripPunctuation(String u) {
        int n = u.length();
        while (n > 0) {
            char c = u.charAt(n - 1);
            if (c == '"' || c == '\'' || c == '>' || c == ',' || c == '.' || c == ';'
                    || c == ':' || c == '!' || c == '?') {
                n--;
            } else if (c == ')' && unmatchedClosing(u, n, '(', ')')) {
                n--;
            } else if (c == ']' && unmatchedClosing(u, n, '[', ']')) {
                n--;
            } else if (c == '}' && unmatchedClosing(u, n, '{', '}')) {
                n--;
            } else {
                break;
            }
        }
        return u.substring(0, n);
    }

    /** Whether the trailing closing delimiter is not paired in the URL prefix. */
    private static boolean unmatchedClosing(String value, int end, char open, char close) {
        int opens = 0;
        int closes = 0;
        for (int i = 0; i < end; i++) {
            char c = value.charAt(i);
            if (c == open) opens++;
            else if (c == close) closes++;
        }
        return closes > opens;
    }

    static final class Target {
        int type;      // 0=post 1=profile 2=story 3=highlight
        String code;   // shortcode / username
        String extra;  // story media id or highlight id
    }

    static Target parse(String url) {
        try {
            String u = url.trim();
            if (u.isEmpty()) return null;
            u = stripPunctuation(u);
            if (!u.regionMatches(true, 0, "http://", 0, 7)
                    && !u.regionMatches(true, 0, "https://", 0, 8)) {
                u = "https://" + u;
            }
            URI parsed = new URI(u);
            if (!isInstagramUri(parsed)) return null;
            String rawPath = parsed.getRawPath();
            if (rawPath == null || !rawPath.startsWith("/")) return null;
            String path = rawPath.substring(1);
            String[] seg = path.split("/", -1);
            int count = seg.length;
            while (count > 0 && seg[count - 1].isEmpty()) count--;
            if (count == 0) return null;

            int offset = 0;
            if ("share".equalsIgnoreCase(seg[0])) { // /share/reel/{code}
                if (count < 3 || !"reel".equalsIgnoreCase(seg[1])) return null;
                offset = 1;
            }

            Target t = new Target();
            String route = offset < count ? seg[offset].toLowerCase(Locale.US) : "";
            if (count >= offset + 2 && (route.equals("p") || route.equals("reel")
                    || route.equals("reels") || route.equals("tv"))
                    && safeToken(seg[offset + 1])) {
                t.type = 0;
                t.code = seg[offset + 1];
            } else if (offset == 0 && count >= 3 && route.equals("stories")
                    && seg[1].equalsIgnoreCase("highlights") && safeToken(seg[2])) {
                t.type = 3;
                t.extra = seg[2];
            } else if (offset == 0 && count >= 2 && route.equals("s")) {
                // /s/{base64 of "highlight:{id}"} — Instagram's highlight share link
                String decoded = decodeBase64(seg[1]);
                String id = null;
                if (decoded != null && decoded.startsWith("highlight:")) {
                    id = decoded.substring("highlight:".length());
                }
                if (safeToken(id)) {
                    t.type = 3;
                    t.extra = id;
                }
            } else if (offset == 0 && count >= 2 && route.equals("stories")
                    && safeUsername(seg[1])) {
                t.type = 2;
                t.code = seg[1];
                if (count >= 3) {
                    if (!safeToken(seg[2])) return null;
                    t.extra = seg[2];
                }
            } else if (offset == 0 && count == 1 && safeUsername(seg[0])) {
                String k = seg[0].toLowerCase(Locale.US);
                if (!k.equals("p") && !k.equals("reel") && !k.equals("reels") && !k.equals("tv")
                        && !k.equals("stories") && !k.equals("highlights") && !k.equals("explore")
                        && !k.equals("accounts") && !k.equals("direct") && !k.equals("api")
                        && !k.equals("about") && !k.equals("developer") && !k.equals("s")) {
                    t.type = 1;
                    t.code = seg[0];
                }
            }
            if (t.code == null && t.extra == null) return null;
            return t;
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean safeToken(String value) {
        return value != null && !value.equals(".") && !value.equals("..")
                && SAFE_TOKEN.matcher(value).matches();
    }

    private static boolean safeUsername(String value) {
        return value != null && !value.equals(".") && !value.equals("..")
                && SAFE_USERNAME.matcher(value).matches();
    }

    private static boolean isInstagramUrl(String value) {
        try {
            URI uri = new URI(value);
            return isInstagramUri(uri) && uri.getRawPath() != null && uri.getRawPath().startsWith("/");
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isInstagramUri(URI uri) {
        if (uri == null || uri.getScheme() == null
                || !("http".equalsIgnoreCase(uri.getScheme())
                || "https".equalsIgnoreCase(uri.getScheme()))) return false;
        if (uri.getUserInfo() != null) return false;
        String host = uri.getHost();
        if (host == null || !("instagram.com".equalsIgnoreCase(host)
                || "www.instagram.com".equalsIgnoreCase(host))) return false;
        int port = uri.getPort();
        return port == -1 || ("http".equalsIgnoreCase(uri.getScheme()) && port == 80)
                || ("https".equalsIgnoreCase(uri.getScheme()) && port == 443);
    }

    private static String decodeBase64(String s) {
        try {
            return new String(Base64.getDecoder().decode(s), StandardCharsets.UTF_8);
        } catch (Exception e) {
            try {
                return new String(Base64.getUrlDecoder().decode(s), StandardCharsets.UTF_8);
            } catch (Exception e2) {
                return null;
            }
        }
    }

    // ---- resolution ----------------------------------------------------------

    static List<Media> resolve(String url, String session) throws Exception {
        Target t = parse(url);
        if (t == null) throw new Exception("That doesn't look like an Instagram link");
        switch (t.type) {
            case 0: return resolvePost(t.code, session);
            case 1: return resolveProfile(t.code, session);
            case 2: return resolveStory(t, session);
            default: return resolveHighlight(t.extra, session);
        }
    }

    // posts, reels, carousels ------------------------------------------------

    private static List<Media> resolvePost(String code, String session) throws Exception {
        if (code == null || code.isEmpty()) throw new Exception("Invalid post link");
        for (String page : new String[]{"/p/" + code + "/", "/reel/" + code + "/", "/reels/" + code + "/"}) {
            Http.Reply r = Http.get(BASE + page, session, false);
            if (!r.ok() || r.body.length() < 500) continue;
            List<Media> list = fromHtml(r.body, code);
            if (!list.isEmpty()) return list;
        }
        throw new Exception("Couldn't read this post — it may be private, deleted, or Instagram is temporarily blocking this network.");
    }

    /** Extracts media from a fetched post page (SSR data-sjs payloads, then og: tags). */
    static List<Media> fromHtml(String html, String fallbackCode) {
        if (html == null || html.isEmpty()) return new ArrayList<>();
        List<Media> best = null;
        int bestScore = -1;
        List<Media> targetBest = null;
        int targetScore = -1;
        try {
            Matcher m = SJS.matcher(html);
            while (m.find()) {
                JSONObject blob;
                try {
                    blob = new JSONObject(m.group(1));
                } catch (Exception e) {
                    continue;
                }
                List<Object> medias = new ArrayList<>();
                findKeys(blob, "xig_polaris_media", medias);
                for (Object mo : medias) {
                    if (!(mo instanceof JSONObject)) continue;
                    JSONObject polaris = (JSONObject) mo;
                    String c = str(polaris, "code", null);
                    boolean target = fallbackCode != null && fallbackCode.equals(c);
                    if (!target && c != null) continue; // some other post's timeline node
                    List<Media> cand = new ArrayList<>();
                    fromMediaObject(polaris, fallbackCode, cand);
                    if (cand.isEmpty()) continue;
                    int score = score(cand);
                    if (target) {
                        if (score > targetScore) {
                            targetBest = cand;
                            targetScore = score;
                        }
                    } else if (score > bestScore) {
                        best = cand;
                        bestScore = score;
                    }
                }
            }
        } catch (Exception ignored) { }
        if (targetBest != null) return targetBest;
        if (best != null) return best;
        // cheap meta tags (single video / single image)
        List<Media> out = new ArrayList<>();
        Matcher v = OG_VIDEO.matcher(html);
        while (v.find()) {
            String url = unescape(v.group(1));
            if (isHttpUrl(url)) out.add(new Media(url, true, "", fallbackCode, "Reel"));
        }
        if (out.isEmpty()) {
            Matcher i = OG_IMAGE.matcher(html);
            while (i.find()) {
                String url = unescape(i.group(1));
                if (isHttpUrl(url)) out.add(new Media(url, false, "", fallbackCode, "Photo"));
            }
        }
        return out;
    }

    private static int score(List<Media> list) {
        int n = 0;
        for (Media m : list) n += m.video ? 3 : 1;
        return n;
    }

    private static void fromMediaObject(JSONObject data, String fallbackCode, List<Media> out) {
        // The logged-out payload may sit under if_not_gated_logged_out; carousel may
        // live on either layer. Prefer whichever has carousel/video/image data.
        JSONObject eff = data;
        JSONObject igl = data.optJSONObject("if_not_gated_logged_out");
        if (igl != null && (igl.optJSONArray("carousel_media") != null
                || igl.has("video_versions") || igl.has("image_versions2"))) {
            if (eff.optJSONArray("carousel_media") == null) eff = igl;
        }
        String owner = owner(eff);
        String code = str(eff, "code", str(eff, "shortcode", str(eff, "pk", fallbackCode)));
        String kind = kindFor(eff);
        JSONArray car = eff.optJSONArray("carousel_media");
        if (car != null && car.length() > 0) {
            for (int i = 0; i < car.length(); i++) {
                JSONObject node = car.optJSONObject(i);
                if (node == null) continue;
                String co = owner(node);
                if (co == null || co.isEmpty()) co = owner;
                String cc = code(node, fallbackCode);
                String vu = pickVideoUrl(node);
                if (vu != null) out.add(new Media(vu, true, co, cc, "Post"));
                else {
                    String iu = pickImageUrl(node);
                    if (iu != null) out.add(new Media(iu, false, co, cc, "Post"));
                }
            }
            return;
        }
        String vu = pickVideoUrl(eff);
        if (vu != null) {
            out.add(new Media(vu, true, owner, code, kind));
            return;
        }
        String iu = pickImageUrl(eff);
        if (iu == null) iu = str(eff, "display_uri", null);
        if (iu != null) out.add(new Media(iu, false, owner, code, kind));
    }

    private static String kindFor(JSONObject data) {
        int mt = data.optInt("media_type", 0);
        if (mt == 2) {
            String pt = str(data, "product_type", "");
            return pt.equals("clips") ? "Reel" : "Video";
        }
        if (mt == 8) return "Post";
        return "Photo";
    }

    // profiles ----------------------------------------------------------------

    private static List<Media> resolveProfile(String username, String session) throws Exception {
        Http.Reply r = Http.get(BASE + "/" + username + "/", session, false);
        if (!r.ok() || r.body.length() < 500) throw new Exception("Couldn't read profile @" + username + " (private or missing?)");
        try {
            Matcher m = SJS.matcher(r.body);
            while (m.find()) {
                JSONObject blob;
                try {
                    blob = new JSONObject(m.group(1));
                } catch (Exception e) {
                    continue;
                }
                Object user = findKey(blob, "xig_user_by_igid_v2");
                if (user == null) user = findKey(blob, "xig_user_by_username");
                if (user instanceof JSONObject) {
                    JSONObject u = (JSONObject) user;
                    String pic = str(u, "profile_pic_url", null);
                    if (pic != null) {
                        String un = str(u, "username", username);
                        List<Media> out = new ArrayList<>();
                        out.add(new Media(pic, false, un, un, "Profile"));
                        return out;
                    }
                }
            }
        } catch (Exception ignored) { }
        throw new Exception("Couldn't read the profile picture of @" + username);
    }

    // stories -----------------------------------------------------------------

    private static List<Media> resolveStory(Target t, String session) throws Exception {
        if (session == null || session.isEmpty()) {
            throw new Exception("Stories need your session id — add it in the menu (⋮)");
        }
        String pk = userPk(t.code, session);
        if (pk == null) throw new Exception("Couldn't find user @" + t.code);
        Http.Reply r = Http.get(BASE + "/api/v1/feed/reels_media/?reel_ids=" + pk, session, true);
        if (!r.ok() || r.body.trim().startsWith("<")) {
            throw new Exception("Stories unavailable (expired, or session invalid)");
        }
        List<Media> out = fromReels(new JSONObject(r.body), "Story");
        if (out.isEmpty()) throw new Exception("No active stories for @" + t.code);
        return out;
    }

    /** Finds a user's numeric id for the story feed request. */
    private static String userPk(String username, String session) {
        if (username == null) return null;
        // 1) profile page SSR (anonymous)
        try {
            Http.Reply r = Http.get(BASE + "/" + username + "/", session, false);
            if (r.ok()) {
                Matcher m = SJS.matcher(r.body);
                while (m.find()) {
                    try {
                        JSONObject blob = new JSONObject(m.group(1));
                        Object user = findKey(blob, "xig_user_by_igid_v2");
                        if (user == null) user = findKey(blob, "xig_user_by_username");
                        if (user instanceof JSONObject) {
                            String pk = str((JSONObject) user, "pk", null);
                            if (pk == null) pk = str((JSONObject) user, "id", null);
                            if (pk != null && pk.matches("\\d+")) return pk;
                        }
                    } catch (Exception ignored) { }
                }
            }
        } catch (Exception ignored) { }
        // 2) stories page embeds {"id":"…","username":"…"}
        try {
            Http.Reply r = Http.get(BASE + "/stories/" + username + "/", session, true);
            Pattern p = Pattern.compile("\"id\"\\s*:\\s*\"(\\d{4,20})\"[^}]{0,400}\"username\"\\s*:\\s*\""
                    + Pattern.quote(username) + "\"");
            Matcher m = p.matcher(r.body);
            if (m.find()) return m.group(1);
        } catch (Exception ignored) { }
        // 3) web profile info with session (works from residential IPs)
        try {
            Http.Reply r = Http.get(BASE + "/api/v1/users/web_profile_info/?username=" + username, session, true);
            if (r.ok()) {
                JSONObject root = new JSONObject(r.body);
                JSONObject user = root.optJSONObject("data") != null
                        ? root.optJSONObject("data").optJSONObject("user") : null;
                if (user != null) {
                    String pk = str(user, "pk", null);
                    if (pk == null) pk = str(user, "id", null);
                    if (pk != null && pk.matches("\\d+")) return pk;
                }
            }
        } catch (Exception ignored) { }
        return null;
    }

    // highlights --------------------------------------------------------------

    private static List<Media> resolveHighlight(String id, String session) throws Exception {
        if (id == null || id.isEmpty()) throw new Exception("Invalid highlight link");
        Http.Reply r = Http.get(BASE + "/api/v1/feed/reels_media/?reel_ids=highlight:" + id, session, true);
        if (!r.ok() || r.body.trim().startsWith("<")) {
            throw new Exception("Highlight unavailable (deleted, or session invalid)");
        }
        List<Media> out = fromReels(new JSONObject(r.body), "Highlight");
        if (out.isEmpty()) throw new Exception("Highlight not found or empty");
        return out;
    }

    /** Parses both the legacy reels_media[] and the newer reels{} shapes. */
    private static List<Media> fromReels(JSONObject root, String kind) {
        List<Media> out = new ArrayList<>();
        try {
            JSONArray reels = root.optJSONArray("reels_media");
            if (reels != null) {
                for (int i = 0; i < reels.length(); i++) {
                    JSONObject reel = reels.optJSONObject(i);
                    if (reel != null) addReelItems(reel, kind, out);
                }
                return out;
            }
            JSONObject map = root.optJSONObject("reels");
            if (map != null) {
                for (java.util.Iterator<String> it = map.keys(); it.hasNext(); ) {
                    String key = it.next();
                    JSONObject reel = map.optJSONObject(key);
                    if (reel != null) addReelItems(reel, kind, out);
                }
            }
        } catch (Exception ignored) { }
        return out;
    }

    private static void addReelItems(JSONObject reel, String kind, List<Media> out) {
        try {
            JSONArray items = reel.optJSONArray("items");
            if (items == null) return;
            String ownerName = owner(reel);
            for (int i = 0; i < items.length(); i++) {
                JSONObject it = items.optJSONObject(i);
                if (it == null) continue;
                String vu = pickVideoUrl(it);
                String iu = pickImageUrl(it);
                if (iu == null) iu = str(it, "display_url", null);
                String u = vu != null ? vu : iu;
                if (u == null) continue;
                out.add(new Media(u, vu != null, ownerName, str(it, "pk", str(it, "code", kind + "_" + i)), kind));
            }
        } catch (Exception ignored) { }
    }

    // ---- JSON helpers ---------------------------------------------------------

    /** Depth-first search for a key; returns the first JSONObject value found. */
    private static Object findKey(Object node, String key) {
        List<Object> all = new ArrayList<>();
        findKeys(node, key, all);
        return all.isEmpty() ? null : all.get(0);
    }

    /** Depth-first search for a key; collects every value found. */
    private static void findKeys(Object node, String key, List<Object> out) {
        if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            try {
                if (o.has(key)) out.add(o.get(key));
            } catch (Exception e) {
                return;
            }
            for (java.util.Iterator<String> it = o.keys(); it.hasNext(); ) {
                findKeys(o.opt(it.next()), key, out);
            }
        } else if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            for (int i = 0; i < a.length(); i++) {
                findKeys(a.opt(i), key, out);
            }
        }
    }

    private static String owner(JSONObject node) {
        JSONObject u = node.optJSONObject("owner");
        if (u == null) u = node.optJSONObject("user");
        if (u != null) {
            String n = str(u, "username", null);
            if (n != null) return n;
        }
        return "";
    }

    private static String code(JSONObject node, String fallback) {
        String c = str(node, "code", null);
        if (c == null) c = str(node, "shortcode", null);
        if (c == null) c = str(node, "pk", null);
        if (c == null) c = str(node, "id", null);
        return c == null ? fallback : c;
    }

    private static String str(JSONObject o, String key, String fallback) {
        if (o == null) return fallback;
        String v = o.optString(key);
        if (v == null || v.isEmpty() || v.equals("null")) return fallback;
        return v;
    }

    private static String pickVideoUrl(JSONObject node) {
        String u = str(node, "video_url", null);
        if (u != null) return u;
        JSONArray vv = node.optJSONArray("video_versions");
        long best = -1;
        String url = null;
        if (vv != null) {
            for (int i = 0; i < vv.length(); i++) {
                JSONObject v = vv.optJSONObject(i);
                if (v == null) continue;
                String vu = str(v, "url", null);
                if (vu == null) continue;
                long area = (long) v.optInt("width") * v.optInt("height");
                if (area <= 0) area = v.optLong("bit_rate", 0);
                if (area > best) { best = area; url = vu; }
            }
        }
        return url;
    }

    private static String pickImageUrl(JSONObject node) {
        JSONObject iv = node.optJSONObject("image_versions2");
        if (iv != null) {
            JSONArray cand = iv.optJSONArray("candidates");
            if (cand != null) {
                long best = -1;
                String url = null;
                for (int i = 0; i < cand.length(); i++) {
                    JSONObject v = cand.optJSONObject(i);
                    if (v == null) continue;
                    String vu = str(v, "url", null);
                    if (vu == null) continue;
                    long area = (long) v.optInt("width") * v.optInt("height");
                    if (area > best) { best = area; url = vu; }
                }
                if (url != null) return url;
            }
        }
        return str(node, "display_url", null);
    }

    private static String unescape(String s) {
        return s.replace("&amp;", "&")
                .replace("&#39;", "'")
                .replace("&quot;", "\"")
                .replace("&lt;", "<")
                .replace("&gt;", ">");
    }

    private static boolean isHttpUrl(String value) {
        try {
            URI uri = new URI(value);
            return uri.getHost() != null && ("http".equalsIgnoreCase(uri.getScheme())
                    || "https".equalsIgnoreCase(uri.getScheme()));
        } catch (Exception e) {
            return false;
        }
    }
}
