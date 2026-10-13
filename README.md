# InstaFetchX

> The tiniest open-source Instagram downloader for Android â€” the APK is the size of a single photo.

| | |
|---|---|
| **APK size** | **46.7 KB** (R8 + resource shrinking, zero third-party libraries) |
| **Min / Target SDK** | 8.0 Oreo (API 26) / Android 16 (API 36) |
| **Code** | 100% Java Â· no AndroidX Â· no Kotlin stdlib Â· **empty `dependencies {}`** |
| **Permissions** | Internet, notifications (+ legacy storage on API â‰¤ 28) |
| **Trackers / ads / accounts** | none, ever |

## What it does

- **Public Reels, posts & carousels** â€” every photo/video in the highest resolution Instagram serves.
- **Profile pictures** (Instagram exposes s150x150 to anonymous clients â€” HD profile pictures are a logged-in-only feature, so the app shows what's actually available).
- **Highlights** of any account, anonymously, from `/stories/highlights/{id}` and `/s/{base64}` share links.
- **Stories** of any account when you add your own `sessionid` (Instagram login-gates stories; the id is stored only on your device).
- **Quick Download** â€” share an Instagram link from the Instagram app (or any browser) straight to InstaFetchX; it downloads *in the background* without opening the app, with a small progress notification. Files land in `Downloads/InstaFetchX`.
- A minimal main screen: paste a link, tap Download, see a short recent history.

## Quick Download (share sheet)

1. In Instagram (or any app), tap **Share â†’ InstaFetchX** on a post/reel/story/highlight link.
2. That's it. A notification shows progress, then "Saved". The app itself never opens.

You can also share a link to InstaFetchX from a browser, or paste links in the app's main screen.

## How it works

```
share link â”€â”€â–¶ ShareActivity (invisible, no UI) â”€â”€â–¶ DownloadService (foreground service, notification)
                                                     â”‚ org.json           HttpURLConnection
Instagram page (crawler UA) â—€â”€â”€ data-sjs SSR payload â”€â”´â”€ xig_polaris_media â†’ video_versions / image_versions2 / carousel_media
                                                     â”‚ MediaStore â”€â”€â–¶ Downloads/InstaFetchX/â€¦
```

- `Res.java` classifies the link (post / reel / profile / story / highlight), then extracts the media:
  - posts/reels/carousels: the post page is fetched with a crawler `User-Agent` and the embedded
    `<script data-sjs>` SSR payload is walked for `xig_polaris_media â†’ if_not_gated_logged_out`
    (large-version selection, `carousel_media[]` for albums). `og:video:secure_url` / `og:image`
    meta tags cover the simple single-video case if the payload is missing.
  - profiles: profile page SSR (`xig_user_by_igid_v2`) â†’ `profile_pic_url`.
  - highlights: `GET /api/v1/feed/reels_media/?reel_ids=highlight:{id}` (anonymous).
  - stories: `GET /api/v1/feed/reels_media/?reel_ids={user_pk}` with your `sessionid` cookie.
  - media URLs are signed and short-lived, so they are fetched fresh right before each download
    and never cached. The CDN download streams straight to `MediaStore`/file with throttled progress.
- `DownloadService` runs each job on a single foreground worker, queues links, shows one
  low-importance notification (progress + Cancel), and saves via `MediaStore` with no storage
  permission on modern Android.
- History is a tiny JSON file in app-private storage â€” no database, no ContentProvider.

## Building

Requirements: JDK 17+ and the Android SDK (platform 36, build-tools 36).

```bash
# Windows
gradlew.bat assembleRelease

# macOS / Linux
./gradlew assembleRelease
```

Output: `app/build/outputs/apk/release/app-release.apk`.

The project has **zero dependencies** (`app/build.gradle.kts` has an empty `dependencies {}`).
Only `java.*`, `javax.*`, `android.*` and the framework `org.json` are used, so R8 trims the APK
to a few dozen kilobytes. To keep it that way: no AndroidX, no Kotlin, no image/crash/analytics
libraries.

For a personal release signing, create a keystore and pass:

```bash
./gradlew assembleRelease -PINSTAFETCHX_STORE_FILE=release.jks \
  -PINSTAFETCHX_STORE_PASSWORD=... -PINSTAFETCHX_KEY_ALIAS=instafetchx -PINSTAFETCHX_KEY_PASSWORD=...
```

## Privacy

- No accounts, no analytics, no crash reporting. Network access goes only to `instagram.com`
  and its media CDN.
- Your session id (if you add one) never leaves the device except in story/highlight requests
  to `instagram.com`.
- No permissions beyond Internet + notifications (and legacy storage on API â‰¤ 28).

## Known limits

- Instagram gates stories (and highlights *discovery*) behind a logged-in session; posts, reels,
  carousels, profile pictures and highlight *retrieval* work anonymously. That is Instagram's
  product decision, not a bug.
- Profile pictures are s150x150 for anonymous clients (Instagram's current policy).
- Extraction relies on Instagram's crawler-facing behavior (`data-sjs` SSR payload under a
  crawler UA). If Instagram changes it, some content may need a retry or stop resolving until
  this project is updated. Endpoints and UAs rotate in `Res.java` / `Http.java`.

## Respect the law

Only download content you have the right to download, and respect Instagram's Terms of Use and
the rights of creators. This project is a research demonstration, provided as-is, without warranty.

## Contributing

Issues and PRs welcome. Please keep the constraints â€” **no dependencies, no AndroidX, no Kotlin
stdlib** â€” the tiny APK is the point.

GPL-3.0.
