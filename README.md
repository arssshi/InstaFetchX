# InstaFetchX

> A small, open-source Instagram downloader for Android that turns an Instagram link into a
> saved photo or video without an InstaFetchX account, ads, analytics, or third-party runtime libraries.

InstaFetchX is designed around one simple flow: share an Instagram URL to the app, or paste the
URL into its main screen, and let a native Android foreground service resolve and save the media.
It is written in Java against Android framework APIs, with no AndroidX and no Kotlin standard
library in the app. The app module is intentionally zero-dependency: its dependency list is empty,
so the downloader stays easy to inspect and small by design.

## At a glance

| | |
|---|---|
| **Android support** | API 26+ (Android 8.0 or newer) |
| **Compile / target SDK** | 36 / 36 |
| **App source** | Java; framework APIs and Android's `org.json` |
| **App dependencies** | No third-party runtime dependencies; the app module's dependency list is intentionally empty |
| **Output folder** | `Downloads/InstaFetchX` |
| **Input** | Instagram post, reel, profile, story, and highlight URLs handled by the resolver |

## Features

- **Quick Download from the share sheet.** Share an Instagram link from Instagram or a browser.
  `ShareActivity` starts the download service, shows a short status toast, and finishes without
  navigating to the main screen.
- **Paste and download.** Paste a link into the main screen, use the clipboard menu action, or
  press Download. The screen also shows live progress and recent saves.
- **Posts, reels, and carousels.** The resolver walks Instagram's server-rendered media payloads,
  attempts each item in a carousel, and selects the largest image or video candidate exposed in the
  response.
- **Profile pictures.** A profile URL resolves the profile picture URL that Instagram exposes to
  the request.
- **Stories with an optional session ID.** Add your own `sessionid` value in the app menu when
  Instagram requires a logged-in session for story access.
- **Highlights from exact links.** Retrieve highlight media from `/stories/highlights/{id}` links
  and Instagram `/s/{base64}` highlight share links.
- **Background queue and progress.** Repeated download requests are queued and processed by one
  foreground worker. A low-importance notification reports progress and includes Cancel.
- **Direct saves.** Files are streamed to `Downloads/InstaFetchX` through `MediaStore` on modern
  Android and through the legacy public Downloads path on older supported versions.
- **Local recent history.** The app keeps the 60 most recent saved items in a small app-private
  JSON file. Tap an entry to open it, long-press to remove its history entry, or use Clear to
  remove all history records.

## Supported links and known limits

The input parser recognizes the following URL shapes. Availability still depends on what Instagram
returns for that link and account at the time of the request.

| URL shape | Result | Requirements and behavior |
|---|---|---|
| `https://www.instagram.com/p/{code}/` | Post, photo, video, or carousel | The post must be accessible to the request. Every carousel item is considered. |
| `https://www.instagram.com/reel/{code}/`, `/reels/{code}/`, or `/tv/{code}/` | Reel or video media | The resolver tries the supported post/reel page variants. |
| `https://www.instagram.com/share/reel/{code}` | Shared reel link | Parsed as a reel/post link. |
| `https://www.instagram.com/{username}/` | Profile picture | Only the profile picture URL present in Instagram's response can be saved; anonymous responses may not expose a high-resolution original. |
| `https://www.instagram.com/stories/{username}/` | Active story media | Requires the user's `sessionid`. The current resolver requests the account's active story set; a story URL's optional media ID is not used to select a single item. |
| `https://www.instagram.com/stories/highlights/{id}` | Highlight media | An exact highlight link is required. Retrieval may work without a session, but Instagram can still reject or expire it. There is no in-app highlight discovery. |
| `https://www.instagram.com/s/{base64}` | Highlight media | Supported when the decoded value identifies a highlight in the form `highlight:{id}`. |

### Important limits

- Instagram controls access to private, deleted, expired, or otherwise unavailable content. A
  valid-looking link is not a guarantee that Instagram will return media.
- Stories are login-gated by Instagram. A session ID can expire or be rejected, and the app does
  not provide an Instagram login screen.
- The optional session ID is used as a cookie on Instagram requests made while resolving a link.
  Treat it as a sensitive credential: use only your own value, never post it in an issue or log,
  and save an empty value in the menu when you want to remove it.
- Media URLs returned by Instagram are signed and short-lived. InstaFetchX resolves them again for
  each download instead of caching them, so a retry may be needed after an expired response.
- Resolution depends on Instagram's crawler-facing HTML and API response shapes, including the
  `data-sjs` server-rendered payload and media fields such as `video_versions` and
  `image_versions2`. Instagram can change those responses or rate-limit a network at any time.
- The app downloads media exposed by Instagram; it does not bypass Instagram's access controls or
  turn private content into public content.

## Quick start

After installing an APK built from this project:

1. Open Instagram, a browser, or another app containing an Instagram URL.
2. Choose **Share -> InstaFetchX** when the app appears in the Android share sheet. InstaFetchX
   starts the background job and returns immediately.
3. Watch the progress notification. Completed files appear in `Downloads/InstaFetchX`.

Alternatively, open InstaFetchX, paste a supported link into the input field, and tap **Download**.
The menu also provides **Paste from clipboard** and **Session ID for stories...**.

## Privacy, permissions, and safety

### Data handling

- InstaFetchX has no account system, analytics, crash reporting, ads, or project backend. Network
  requests go to Instagram and to the media URLs returned by Instagram; the app does not send data
  to an InstaFetchX server.
- The app does not maintain an automatic cookie jar. If you provide a `sessionid`, it is stored in
  app-private preferences and attached as a `sessionid` cookie to Instagram requests made during
  resolution. The download code only forwards it to a media URL when that URL contains
  `instagram.com`.
- Recent-download metadata is stored locally in app-private `hist.json`; it contains the saved
  filename, local URI, size, timestamp, media kind, and whether the item is a video. The manifest
  disables Android application backup. Clearing history removes those records, not the files
  already saved in Downloads.
- Saved media is deliberately placed in the user-visible `Downloads/InstaFetchX` folder rather than
  hidden app storage.

### Declared permissions

| Permission | Why it is present |
|---|---|
| `INTERNET` | Fetch Instagram pages, API responses, and media files. |
| `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_DATA_SYNC` | Keep an active download represented by a foreground service. |
| `POST_NOTIFICATIONS` | Show download progress and completion notifications on Android 13+; the app requests it at runtime. |
| `WRITE_EXTERNAL_STORAGE` with `maxSdkVersion="28"` | Save through the legacy public storage path on Android 8 and 9; the app requests it only on supported older versions. |

### Use responsibly

Download only content you have the right to download. Respect Instagram's Terms of Use, applicable
law, and the rights of creators. Never share a session ID or use someone else's account session.
The project is provided as-is under the GPL-3.0-or-later license, without warranty.

## Architecture overview

The app is deliberately a small set of framework-only components:

```text
Android share sheet or browser link
             |
             v
ShareActivity (SEND/VIEW, starts work and finishes)
             |
             v
DownloadService (foreground service, queue, progress notification, cancel)
             |
             +--> Res.java (classify URL and resolve media)
             |       |
             |       +--> HttpURLConnection + org.json
             |               |
             |               +--> Instagram SSR/API response
             |
             +--> Save.java (MediaStore on API 29+, legacy storage on API 26-28)
             |       |
             |       +--> Downloads/InstaFetchX
             |
             +--> History.java (app-private JSON history)

MainActivity <--> Bus.java <--> DownloadService
```

Key implementation pieces:

- `MainActivity.java` owns the paste field, clipboard action, session-ID dialog, progress state,
  and recent-history list.
- `ShareActivity.java` handles `ACTION_SEND` text and supported Instagram `ACTION_VIEW` links. It
  is excluded from recents and has no visible main-screen flow.
- `DownloadService.java` queues incoming URLs, resolves each one, streams media sequentially, and
  updates one low-importance foreground notification.
- `Res.java` parses supported URL forms, reads server-rendered `data-sjs` JSON for posts and
  profiles, and reads the reels API shape for stories and highlights.
- `Http.java` uses `HttpURLConnection` with explicit timeouts, rotating crawler user agents for
  Instagram page/API requests, and a mobile user agent for media downloads.
- `Save.java` writes directly to `MediaStore` on API 29+ and uses the public Downloads directory on
  API 26-28. `Bus.java` is an in-process callback bridge; there is no event-bus dependency.

## Build from source

### Requirements

- JDK 17 or newer. The app's Java source and target compatibility are both Java 17.
- Android SDK Platform 36 and the matching Android build tools installed and available to Gradle.
- A network connection the first time the Gradle wrapper needs to download Gradle 9.8.1 or a
  previously populated Gradle wrapper cache.

Run commands from the repository root. The wrapper keeps the Gradle version consistent across
Windows, macOS, and Linux.

### Windows PowerShell

```powershell
.\gradlew.bat assembleDebug
.\gradlew.bat assembleRelease
```

In Command Prompt, use the same commands without the PowerShell prefix:

```bat
gradlew.bat assembleDebug
gradlew.bat assembleRelease
```

### macOS and Linux

```bash
chmod +x ./gradlew       # needed once if the wrapper is not executable
./gradlew assembleDebug
./gradlew assembleRelease
```

Gradle writes APKs below `app/build/outputs/apk/`; the release APK is normally
`app/build/outputs/apk/release/app-release.apk`.

The release variant enables R8 minification and resource shrinking. Without a custom keystore,
the build configuration uses the Android debug signing key so a locally built APK can be installed
for development. For a personal signing key, pass the properties expected by
`app/build.gradle.kts`:

```bash
./gradlew assembleRelease \
  -PINSTAFETCHX_STORE_FILE=release.jks \
  -PINSTAFETCHX_STORE_PASSWORD=... \
  -PINSTAFETCHX_KEY_ALIAS=instafetchx \
  -PINSTAFETCHX_KEY_PASSWORD=...
```

On Windows, replace `./gradlew` with `gradlew.bat` and use PowerShell backticks or one line for
continuation. Keep keystore passwords out of committed files and public logs.

## APK-size philosophy

The goal is a small, inspectable app rather than a published size promise. The generated size can
vary with the Android Gradle Plugin, SDK/build-tools revision, signing, and the artifact being
measured, so measure the APK produced by your own build instead of relying on a fixed number.

The repository keeps the footprint down by combining:

- An intentionally empty app-module dependency list: no AndroidX, no Kotlin app code or Kotlin
  standard library, and no image, crash-reporting, analytics, or networking libraries.
- Plain Android framework APIs, `HttpURLConnection`, and framework `org.json`.
- R8 minification and resource shrinking for `release` builds.
- English-only resources, disabled unused build features, and excluded dependency metadata.
- Direct streaming to the destination file instead of bundling a media pipeline or cache.

To inspect the artifact created by your build:

```powershell
# Windows PowerShell
(Get-Item .\app\build\outputs\apk\release\app-release.apk).Length
```

```bash
# macOS / Linux
du -h app/build/outputs/apk/release/app-release.apk
```

Please preserve these constraints when changing the app; they are part of the project's value
proposition.

## Troubleshooting

### The link is rejected

Use a complete `https://www.instagram.com/...` or `https://instagram.com/...` URL matching one of
the supported forms above. The parser can find the first Instagram URL in shared text, but it does
not resolve arbitrary websites, short links, or direct CDN URLs.

### A story will not download

Open the menu and save your own current `sessionid`. Instagram sessions can expire, and the account
must have an active story. The app does not ask for your Instagram password and does not provide a
login flow. If the session is no longer valid, replace it or save an empty value to disable it.

### A post, reel, profile, or highlight fails

Check that the item is still available and accessible, then retry. Private or deleted content,
temporary Instagram responses, rate limiting, and a changed server-rendered/API response can all
prevent resolution. A highlight must be supplied as an exact supported highlight link; the app does
not browse an account's highlight list.

### The file is not where expected

Check `Downloads/InstaFetchX`. On Android 13+, allow notifications if you want progress updates.
On Android 8 or 9, allow legacy storage access when prompted; that permission is needed for the
older public-storage save path. Clearing the in-app history does not delete downloaded files.

### The build cannot find Java or the SDK

Confirm that `java -version` reports JDK 17 or newer and that Android SDK Platform 36 and matching
build tools are installed. Run the wrapper from the repository root; use `gradlew.bat` on Windows
and `./gradlew` on macOS/Linux. If the wrapper download fails, resolve the local network or Gradle
cache issue and run the same command again.

### The download stops or the notification disappears

Keep the device connected while the service resolves and streams media. Retry when a signed media
URL has expired, and check that the system has not restricted notifications or foreground-service
activity for the app. The notification's Cancel action stops queued work and reports any files
already saved.

## Contributing

Issues and pull requests are welcome. Before changing behavior:

1. Read the resolver and save paths in `app/src/main/java/com/instafetchx/`.
2. Keep supported-link and limit documentation aligned with the actual parser and Instagram
   responses.
3. Keep the app module free of third-party runtime dependencies: no AndroidX, no Kotlin standard
   library, and no image, analytics, crash-reporting, or networking libraries.
4. Build the relevant variant locally with the Gradle wrapper and describe the change and observed
   behavior in the pull request.

Small, focused changes are especially useful because Instagram's response formats can change
without notice.

## License

InstaFetchX is licensed under the **GNU General Public License v3.0 or later**. See
[`LICENSE`](LICENSE) for the complete notice, or read the license at
<https://www.gnu.org/licenses/gpl-3.0.txt>.
