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

The SDK is served from this GitHub repository, as a Maven repository on
GitHub Pages (the `maven` branch). Add it to your repositories, in
`settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://defdjamel.github.io/hooky-android")
    }
}
```

then the dependency, in your app module's `build.gradle.kts`:

```kotlin
dependencies {
    implementation("com.heyhooky:hooky-android:1.0.0")
}
```

In Groovy: `maven { url 'https://defdjamel.github.io/hooky-android' }` and
`implementation 'com.heyhooky:hooky-android:1.0.0'`. Its own
dependencies (AndroidX, Compose, OkHttp, coroutines) come from `google()` and
`mavenCentral()`.

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
| `Hooky.configure(context, siteKey, apiUrl = Hooky.DEFAULT_API_URL, translation = true)` | Loads your site's look (name, color, greeting), restores the saved conversation, keeps the realtime connection open while the app is in the foreground. `apiUrl` is only for local tests; `translation = false` turns off the translation of the team's messages (on by default). |
| `Hooky.identify(name, email = null, userId = null, attributes = emptyMap())` | Optional. Who the visitor is (your signed-in user): name, email, your own user id and free data (see [Identify your users](#identify-your-users)). With an email, the bubble skips its name/email form. |
| `Hooky.setAttributes(attributes)` | Adds free data about the visitor (`mapOf("plan" to "Pro", "cart" to 49.9)`), merged with the previous; `null` removes a key. |
| `Hooky.open(context)` / `Hooky.close()` | Opens the conversation full screen (an Activity, from any Context) / closes it. |
| `Hooky.track(name, value = null, properties = emptyMap())` | Records an event in your Hooky stats, like `Hooky.track()` on the web: `track("signup")`, `track("order", 49.0, mapOf("plan" to "pro"))`. Failures are silent. |
| `Hooky.reset()` | Forgets the conversation, the identity and the free data. Call it when your user signs out. |
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
Hooky.identify("Lou", "lou@example.com", "42");             // or with a Map<String, Object> of attributes
```

## Identify your users

When your user signs in, tell Hooky who they are. Your team sees it on the
conversation: name, email, your user id, the device, and the free data.

```kotlin
Hooky.identify(
    name = "Lou",
    email = "lou@example.com",
    userId = "42",                                       // your own id for this user
    attributes = mapOf("plan" to "Pro", "cart" to 49.9, "beta" to true),
)

Hooky.setAttributes(mapOf("cart" to 12, "last_screen" to "Checkout"))   // later, merged
Hooky.setAttributes(mapOf("beta" to null))                             // removes a key

Hooky.reset()                                            // when the user signs out
```

- Before the first message, everything is kept in memory (even before
  `configure`) and sent with it. Once a conversation exists, changes are sent
  right away, grouped (one request for all the changes made within a second).
  Nothing throws: offline, they are retried with the next message or when the
  app comes back to the foreground.
- `name` and `email` replace the conversation's; `userId = null` leaves it
  unchanged.
- The `userId` is stored with the conversation's token. If `identify` gives a
  **different** `userId`, the conversation is forgotten (like `reset()`):
  another account on the same device never sees the previous one's thread.
- Free data: a flat map, up to 50 keys (letters, digits, spaces, `_`, `.`, `-`;
  64 characters max). Values are strings (500 characters max), numbers or
  booleans; lists and maps are left out, other objects are sent as text. The
  server cleans the rest, it never refuses a message for it. The user id is a
  hint for your team, not verified.

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
- Translation: when your team writes in another language than the bubble's
  (the device's), the message arrives translated and reads "Translated from
  English · See original" (a toggle). The translate button in the header
  (**Always translate**, shown once such a message is there) turns this off
  and back on in one tap, remembered on the device per site key; turned off,
  the originals come back with a discreet **Translate** link under each.
  A failed translation shows a short error with "Try again". The visitor's own
  messages are never translated. It needs a Hooky server that tells each
  message's language (with an older one, no link appears); turn it off with
  `Hooky.configure(context, "YOUR_SITE_KEY", translation = false)`.

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

The SDK sends what the web bubble sends: the visitor's messages and files, the
optional name and email, the device language and time zone, and
`android-app://<your.package>` as the page; plus the device, readable
(`Samsung SM-S918B · Android 15`, from `Build.MANUFACTURER`, `Build.MODEL` and
the Android version), and what you pass to `identify` and `setAttributes`.
Every request carries `X-Hooky-Client: hooky-android/<version>` and
`X-Hooky-App: <package> <versionName> (<versionCode>)`.

The conversation token (the visitor's only key), the `userId` given to
`identify` and the "Always translate" setting are stored per site key in a private file of your app under
`noBackupFilesDir`: never backed up to the cloud nor restored on another
device.

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
./gradlew :hooky:testDebugUnitTest   # JVM tests: protocol, decoding, storage, reconnection, identify, Compose UI (Robolectric)
./gradlew :exemple:installDebug      # sample app on a device or emulator
```

`./gradlew :hooky:publishReleasePublicationToDepotRepository` writes the
Maven repository to `hooky/build/depot-maven` (AAR, sources, POM and Gradle
module metadata of `com.heyhooky:hooky-android`), which is published to the
`maven` branch, served by GitHub Pages. The sample app is never published.

The sample app reads its settings from `local.properties` (or `-P`):

```properties
hooky.siteKey=YOUR_SITE_KEY
hooky.api=http://10.0.2.2:3032   # debug only; the emulator sees your Mac as 10.0.2.2
```

Cleartext HTTP is allowed in the sample's debug build only, for
`10.0.2.2` and `localhost`.

## License

MIT, see [LICENSE](LICENSE).
