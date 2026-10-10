# Hooky for Android

The Hooky chat bubble, native on Android: your visitors chat with your team
from inside your app, with your site's color, live replies, photos and PDFs.
No WebView: the conversation is built with Jetpack Compose (Material 3) and
works in Compose apps and in classic View/XML apps alike.

- Kotlin, minSdk 23, compiled against Android 16 (API 36)
- Dependencies: AndroidX (Activity, Core, Compose), Kotlin coroutines, OkHttp.
  No image library, no JSON library, no analytics.
- Realtime over Action Cable (WebSocket), only while your app is in the foreground
- Light and dark mode, font scaling, TalkBack, English, French, Spanish,
  Portuguese, Italian and German (the device language, English otherwise)

## Install

Once published, add the dependency:

```kotlin
// build.gradle.kts of your app module
dependencies {
    implementation("com.heyhooky:hooky-android:1.0.0")
}
```

Until then, include the `hooky` module of this folder in your build
(`includeBuild` or copy it as a module).

## The 3 lines

```kotlin
// 1. Once, at launch (Application.onCreate is the best place)
Hooky.configure(context, "YOUR_SITE_KEY")

// 2. The ready-made bubble, in a corner of your screen (Compose)
HookyBubble(Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(20.dp))

// 3. Or open the conversation from your own button
Hooky.open(context)
```

Your site key is the public key of your site in the Hooky dashboard. Requests
from the app carry no browser origin; the API recognizes them by the
`X-Hooky-Client` and `X-Hooky-App` headers the SDK sends.

### In an XML layout

```xml
<FrameLayout ...>
    <!-- your screen -->
    <com.heyhooky.sdk.HookyBubbleView
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_gravity="bottom|end"
        android:layout_margin="20dp" />
</FrameLayout>
```

The hosting Activity must be an `AppCompatActivity` or a `ComponentActivity`.

## API

All calls are made on the main thread and never throw. Until `configure` has
been called, the others do nothing.

| Call | What it does |
| --- | --- |
| `Hooky.configure(context, siteKey, apiUrl = Hooky.DEFAULT_API_URL)` | Loads your site's look (name, color, greeting), restores the saved conversation, keeps the realtime connection open while the app is in the foreground. `apiUrl` is only for local tests. |
| `Hooky.identify(name, email = null)` | Optional. Who the visitor is (your signed-in user). Sent when the conversation starts; the bubble then skips its name/email form. |
| `Hooky.open(context)` / `Hooky.close()` | Opens the conversation full screen (an Activity, from any Context) / closes it. |
| `Hooky.track(name, value = null, properties = emptyMap())` | Records an event in your Hooky stats, like `Hooky.track()` on the web: `track("signup")`, `track("order", 49.0, mapOf("plan" to "pro"))`. Failures are silent. |
| `Hooky.reset()` | Forgets the conversation and the identity. Call it when your user signs out. |
| `Hooky.unreadCount: StateFlow<Int>` | Team replies the visitor hasn't seen, updated live. Drops to 0 when the conversation is opened. |
| `Hooky.addUnreadCountListener(listener): Closeable` | The same for Java or View code; close the handle to stop listening. |
| `Hooky.isConfigured`, `Hooky.VERSION` | Whether `configure` was called; the SDK version. |
| `@Composable HookyBubble(modifier = Modifier, onClick = null)` | The chat button: site color, Hooky icon, unread badge. Opens the conversation, or calls `onClick`. Shows up once your site is loaded. |
| `HookyBubbleView` | `HookyBubble` as an Android View, for XML layouts. |
| `HookyActivity` | The conversation screen started by `open()`. Declared by the SDK; you don't need to touch it. |

Unread count in Compose:

```kotlin
val unread by Hooky.unreadCount.collectAsState()
```

From Java:

```java
Hooky.configure(this, "YOUR_SITE_KEY");
Closeable listening = Hooky.addUnreadCountListener(count -> badge.setText(String.valueOf(count)));
Hooky.track("signup");
```

## What the conversation does

- First message with the optional first name and email (or your `identify`),
  then a thread like the web bubble: day separators, team member names, links,
  big emoji, "Read", "… is typing", earlier messages on demand.
- Photos from the system photo picker (no permission needed), the camera
  (through the phone's camera app) and PDFs from Files, 4 per message, 10 MB
  each, with an upload progress bar. Received photos open full screen (pinch or
  double tap to zoom); received files open in the matching app or can be shared.
- The draft survives closing the screen and the app. The keyboard never covers
  the input.
- Errors in plain words: offline, too many messages, file too large or refused.

## What the SDK adds to your app

- The `INTERNET` permission.
- `HookyActivity` and a `FileProvider` (authority `<your.package>.hooky.fichiers`,
  limited to the `hooky/` folder of your app's cache) for camera photos and
  received files.
- `<queries>` for apps that take photos or open PDFs (Android 11+).

No other permission. If your app declares the `CAMERA` permission itself,
Android requires it for camera intents too: the SDK asks for it before
opening the camera.

## Privacy and data

The SDK sends what the web bubble sends, nothing more: the visitor's messages
and files, the optional name and email, the device language and time zone,
and `android-app://<your.package>` as the page. Every request carries
`X-Hooky-Client: hooky-android/<version>` and
`X-Hooky-App: <package> <versionName> (<versionCode>)`.

The conversation token (the visitor's only key) is stored per site key in a
private file of your app under `noBackupFilesDir`: it is never backed up to
the cloud nor restored on another device.

## R8 / ProGuard

Nothing to add: the SDK uses no reflection and ships its consumer rules.

## Local development

```
sdk/android
├── hooky/      the library (com.heyhooky.sdk)
├── exemple/    sample app: Compose shop + the same in Views
└── captures/   screenshots, light and dark
```

```sh
./gradlew :hooky:testDebugUnitTest   # JVM tests: protocol, decoding, storage, reconnection, Compose UI (Robolectric)
./gradlew :exemple:installDebug      # sample app on a device or emulator
./gradlew :hooky:publishToMavenLocal # com.heyhooky:hooky-android:1.0.0 in ~/.m2
```

The sample app reads its settings from `local.properties` (or `-P`):

```properties
hooky.siteKey=YOUR_SITE_KEY
hooky.api=http://10.0.2.2:3032   # debug only; the emulator sees your Mac as 10.0.2.2
```

Cleartext HTTP is allowed in the sample's debug build only, for
`10.0.2.2` and `localhost`.
