package com.instafetchx;

/** One downloadable piece of media. */
final class Media {

    String url;      // direct media url
    String thumb;    // thumbnail url (optional)
    boolean video;
    String owner;    // @username
    String code;     // shortcode / media id
    String kind;     // Reel | Post | Profile | Story | Highlight

    Media(String url, boolean video, String owner, String code, String kind) {
        this.url = url;
        this.video = video;
        this.owner = owner == null ? "" : owner;
        this.code = code == null ? "ig" : code;
        this.kind = kind;
    }

    String ext() {
        if (url == null) return video ? "mp4" : "jpg";
        String path = url;
        int q = path.indexOf('?');
        if (q >= 0) path = path.substring(0, q);
        int dot = path.lastIndexOf('.');
        if (dot >= 0 && dot > path.lastIndexOf('/')) {
            String e = path.substring(dot + 1).toLowerCase();
            if (e.equals("mp4") || e.equals("mov") || e.equals("webm")) return "mp4";
            if (e.equals("webp") || e.equals("jpg") || e.equals("jpeg") || e.equals("png")) return e;
        }
        return video ? "mp4" : "jpg";
    }

    String mime() {
        String e = ext();
        if (e.equals("mp4")) return "video/mp4";
        if (e.equals("webp")) return "image/webp";
        if (e.equals("png")) return "image/png";
        return "image/jpeg";
    }

    String fileName() {
        String name = Util.sanitize(kind + "_" + (owner.isEmpty() ? "instagram" : owner) + "_" + code);
        if (name.length() > 90) name = name.substring(0, 90);
        return name + "." + ext();
    }
}
