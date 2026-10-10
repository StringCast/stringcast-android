# StringCast Android SDK

Over-the-air localization for Android apps. Strings, plurals and string arrays published in
StringCast replace the compiled `strings.xml` values at runtime — no app update needed.

- Kotlin, minSdk 21, dependencies: `kotlinx-coroutines-android`, `androidx.core` (HTTP via
  `HttpURLConnection`, JSON via `org.json`).
- Full guide: https://console.stringcast.app/docs/android

```
sdk-android/
  stringcast/   the library (:stringcast → stringcast-release.aar)
  sample/     demo app (:sample) against the local backend
```

## Build

```bash
./gradlew :stringcast:testDebugUnitTest :stringcast:assembleRelease :sample:assembleDebug
# AAR: stringcast/build/outputs/aar/stringcast-release.aar
```

`local.properties` must point at your Android SDK (`sdk.dir=…`). JDK 17.

Optional contract check against a running backend (skipped unless the variable is set):

```bash
STRINGCAST_LIVE_URL=http://localhost:8787 ./gradlew :stringcast:testDebugUnitTest --tests '*LiveBackendTest*'
```

## Install

The SDK is published to Maven Central as `app.stringcast:stringcast-android`
(`mavenCentral()` is already in the default repositories of new Android projects):

```kotlin
// app/build.gradle.kts
dependencies {
    implementation("app.stringcast:stringcast-android:0.1.2")
}
```

Or from JitPack (tags of this repository):

```kotlin
// settings.gradle.kts → dependencyResolutionManagement { repositories { maven("https://jitpack.io") } }
dependencies {
    implementation("com.github.StringCast:stringcast-android:v0.1.2")
}
```

See [CHANGELOG.md](CHANGELOG.md) for what changed between versions.

The Kotlin package is `app.stringcast.sdk` (`import app.stringcast.sdk.StringCast`,
`import app.stringcast.sdk.StringCastConfig`).

To build against a local checkout instead, include the module:

```kotlin
// settings.gradle.kts
include(":stringcast")
project(":stringcast").projectDir = file("../stringcast-android/stringcast")

// app/build.gradle.kts
dependencies { implementation(project(":stringcast")) }
```

The library's manifest adds `android.permission.INTERNET`.

## Initialise

```kotlin
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        StringCast.init(
            this,
            StringCastConfig(
                projectId = "p_8f3k2j",
                sdkKey = "pk_…",                    // public SDK key, never the sk_ upload key
                // baseUrl = "http://10.0.2.2:8787",  // API origin without /v1; default https://console.stringcast.app
                // languageOverride = "es",          // force a language
                // draftMode = null,                 // null = on for debuggable builds only
                // rClasses = listOf(R::class.java),  // multi-module: every module's R (see Draft mode)
                // refreshIntervalMs = 15 * 60_000L,
                // logging = BuildConfig.DEBUG,
            ),
        )
    }
}
```

`init` reads the disk cache synchronously (manifest + two small JSON files), so the first frame is
already localized with the last-known release, then checks for a new release in the background.
It never throws; on any failure the app simply shows its compiled strings.

## Wrap your contexts

Android resources are served through the context, so wrap it in every Activity (a base Activity
is the easiest place):

```kotlin
open class BaseActivity : Activity() {   // or AppCompatActivity
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(StringCast.wrap(newBase))
    }
}
```

Optionally also in the `Application` (for strings read through the application context,
e.g. notifications or WorkManager):

```kotlin
class App : Application() {
    override fun attachBaseContext(base: Context) = super.attachBaseContext(StringCast.wrap(base))
}
```

What the wrapper covers:

| Source | Covered by |
|---|---|
| `getString(R.string.x)`, `getString(id, args)`, `getText`, `getText(id, def)` | `Resources` subclass |
| `getQuantityString`, `getQuantityText` (plural category from the **OTA language's** CLDR rules) | `Resources` subclass |
| `getStringArray`, `getTextArray` | `Resources` subclass |
| Layout XML `android:text`, `android:hint`, `android:contentDescription` with `@string/…` | `LayoutInflater` wrapper (see below) |

Layout inflation reads `android:text` through `TypedArray`, which does **not** go through
`Resources.getText`. The wrapped context therefore also returns a `LayoutInflater` that observes
every view created from XML (including through AppCompat's view factory) and applies the OTA
value. Views remember their resource ids, so after an update you can either `recreate()` or call
`StringCast.localizeViewTree(window.decorView)`.

Not covered automatically: `app:title`-style custom attributes, menu XML, the activity label from
the manifest, strings set via styles/themes, and (on API < 29 only) fully-qualified custom views
inside a themed (`android:theme`) sub-tree when no AppCompat-style factory is installed. For
those use `StringCast.getString("key")` / `resources.getString(R.string.key)` in code, or tag a
TextView with `android:tag="stringcast:<key>"` and call `StringCast.localizeViewTree(root)`.

Styled strings: if the compiled string is styled (`<b>` in strings.xml) and the OTA value contains
simple HTML tags, `getText` renders them as spans (`HtmlCompat`); otherwise values are literal.

## Key-based API

```kotlin
StringCast.getString("welcome_title")
StringCast.getString("greeting", userName)               // formatted like Resources.getString(id, args)
StringCast.getQuantityString("items_count", n, n)        // like Resources.getQuantityString
StringCast.getStringArray("planets")

StringCast.currentLanguage        // resolved language in use, e.g. "es"
StringCast.currentVersion         // release version in use
StringCast.availableLanguages     // from the manifest
StringCast.setLanguage("fr")      // persisted; null = back to device languages
StringCast.refresh(force = true)  // check for a new release now

StringCast.addUpdateListener { update -> recreate() }   // main thread; remove in onStop
```

Lookup order (contract §6.5): resolved-language bundle → base-language bundle → the app's
compiled resource of the same name → the key itself.

Placeholders are normalised when bundles load: `%@` → `%s`, `%1$@` → `%1$s` (and iOS
`%ld`/`%lld`/`%lu`/`%u` → `%d`, which `java.util.Formatter` would otherwise reject). Formatting
errors never throw; the unformatted value is returned and a warning is logged.

## How the language is resolved

Exactly contract §6.4. `setLanguage(...)` (persisted) beats `StringCastConfig.languageOverride`,
which beats the device. Otherwise the device's preferred languages (`LocaleList.getDefault()` on
API 24+, `Locale.getDefault()` below) are tried in order; for each:

1. exact match (`es-MX`),
2. same language + script (`zh-Hans-CN` → `zh-Hans`; `zh-TW`/`zh-HK` imply `Hant`, other `zh-XX` imply `Hans`),
3. language only (`es-AR` → `es`),
4. any project language with the same language subtag (`pt` → `pt-BR`).

First hit wins; otherwise the project's `baseLanguage`. Tags compare case-insensitively with
`_` → `-`. A system language change is picked up automatically (listeners are notified).

## Updates, caching and threading

- Cache: `filesDir/stringcast/manifest.json` and `filesDir/stringcast/bundles/<lang>.json`; every
  write is temp file + fsync + rename. The ETag lives in SharedPreferences `app.stringcast.sdk`.
- On `init` (and when an Activity starts, at most every `refreshIntervalMs`) the manifest is
  fetched with `If-None-Match`. On a new version the resolved-language and base-language bundles
  are downloaded, verified (`sha256-<base64>` hash of the raw body, version, language, projectId),
  written to disk, and only then is the manifest committed and the new snapshot published.
  Other languages are downloaded lazily when selected.
- Bundles live in an immutable snapshot swapped through a `@Volatile` field: lookups are lock-free
  from any thread. Network work runs on `Dispatchers.IO`, serialised by a mutex.
- Bundle URLs pointing at `localhost`/`127.0.0.1` are re-pointed at the configured `baseUrl`
  host so a device/emulator can reach a local backend.
- All network, disk and parse errors are logged (tag `StringCast`) and swallowed.

## What the SDK sends

Manifest checks (`GET /v1/sdk/{projectId}/manifest`) and draft-mode reports
(`POST /v1/sdk/{projectId}/missing`) go to `baseUrl` (default `https://console.stringcast.app`) with
your public SDK key (`X-Api-Key`) and these check-in headers, so the portal can show which apps are
connected (contract §4.2):

| Header | Value |
|---|---|
| `X-StringCast-Platform` | `android` |
| `X-StringCast-App-Id` | your package name (`context.packageName`) |
| `X-StringCast-App-Version` | your `versionName` |
| `X-StringCast-SDK-Version` | SDK version (`BuildConfig.SDK_VERSION`, e.g. `0.1.2`) |
| `X-StringCast-Language` | the language the SDK resolved for this device, e.g. `es` |

No device IDs, advertising IDs, user identifiers or other personal data are sent. Empty values are
omitted and values are restricted to printable ASCII. Bundle downloads (CDN URLs from the manifest)
carry no API key and no check-in headers, so they stay cacheable.

## Draft mode

`draftMode` defaults to `true` for debuggable builds and `false` for release builds. Draft mode
is how a debug/QA build fills the project: run it once and the app's strings appear in the portal.

### Automatic full upload (once per build)

Shortly after `init`, on a background thread, the SDK uploads **every string, plural and string
array the app owns** to `POST /v1/sdk/{projectId}/missing`:

1. **Base values** — each key's value in the project's **base language**, read from the compiled
   resources resolved for the base locale (so a device set to Spanish still uploads `values/`, not
   `values-es/`).
2. **Existing translations** — for every other project language, the compiled value for that
   locale (`values-es/`, `values-pt-rBR/`, …), but only when it differs from the base value:
   Android falls back to `values/` when a translation is missing, so "same as base" means "not
   translated". Plurals and arrays are compared as a whole.

Requests carry one language each and at most 500 keys. The server creates keys that don't exist
and fills values that are empty in that language; it **never overwrites** a non-empty value, so
edits made in the portal are safe.

It runs **once per app build**: the marker `versionName|versionCode|sdkVersion` is stored in
SharedPreferences only after every batch succeeded. It waits until the project manifest is known
(cached or fetched) so the base language and project languages are known; if the API can't be
reached, or a batch fails, it tries again on the next launch. Turn it off with
`autoUploadLocalStrings = false` (it is never active outside draft mode).

To force a re-upload of the same build (e.g. you added strings without bumping `versionCode`),
clear the app's data, or call `StringCast.uploadLocalStrings()` — it runs the same upload (base +
translations) and ignores the marker:

```kotlin
StringCast.uploadLocalStrings { result ->
    Log.i("App", "uploaded ${result.total}: ${result.created} new, ${result.ignored} existing, error=${result.error}")
}
```

`uploadLocalStrings(rClass)` also accepts an extra R class to scan in addition to `rClasses`.

### Which keys are "the app's own"

Keys are enumerated by reflection over the app's `R.string`, `R.plurals` and `R.array`:

- **Nothing to configure (default)**: the SDK finds `<applicationId>.R` (and its parent packages, so
  `com.acme.app.qa` → `com.acme.app.R`) **and every module `R` in the app's own package root**, by
  listing the APK's classes in the background: `com.acme.feature.auth.R`, `com.acme.core.ui.R`, …
  Library R classes (`androidx.*`, `com.google.*`, …) are outside the root and ignored. The
  `StringCast` Logcat tag prints the discovered classes when `logging = true`.
- **Explicit list** (overrides discovery): use it when a module lives outside the app's package root
  (e.g. app `com.acme.app`, shared module `org.partner.ui`), or to limit the upload:

  ```kotlin
  StringCastConfig(
      projectId = "p_…", sdkKey = "pk_…",
      rClasses = listOf(R::class.java, com.acme.feature.checkout.R::class.java, com.acme.core.ui.R::class.java),
  )
  ```

- **Library strings are dropped.** With legacy transitive R classes
  (`android.nonTransitiveRClass=false`) the app's `R` also contains every library's strings. The
  SDK always skips a built-in list of library and generated names — AppCompat `abc_*`, Media3 /
  ExoPlayer `exo_*`, Material `mtrl_*` / `material_*` / `m3_*` / `m3c_*`, Play services
  `common_google_play_services_*`, `fcm_*`, `androidx_*`, Compose accessibility strings such as
  `tab`, `selected`, `expanded`, `in_progress` (and `<name>_*`), and the values generated by the
  google-services / Crashlytics / Facebook plugins (`google_app_id`, `gcm_defaultSenderId`,
  `default_web_client_id`, `google_api_key`, `project_id`, `facebook_app_id`, …). The full list
  is in `internal/KeyFilter.kt`.
- **App-specific exclusions**: `excludedKeys = setOf("debug_menu_title")`,
  `excludedKeyPrefixes = setOf("internal_", "qa_")`.

Plural forms are read back using sample quantities for each CLDR category of the language.
Strings marked `translatable="false"` are uploaded as base values (the SDK can't see that flag at
runtime); their "translations" equal base and are skipped.

### Missing keys at runtime

Strings the app requests that are not in the base bundle are collected, debounced (~5 s) and
POSTed to `/missing` in batches of ≤ 500, with the value from the app's base-language resources.
Library strings (the built-in list above) and your `excludedKeys` / `excludedKeyPrefixes` are never
reported, so ExoPlayer/Material strings shown on screen don't reach the project. Any other key is
reported — including keys from a module the R-class scan didn't cover. Keys without a local value
are not reported.

## Uploading strings at build time (CLI)

Pushing `strings.xml` from CI or Gradle is done by the Node CLI in `cli/`, not by this SDK:

```kotlin
// app/build.gradle.kts
tasks.register<Exec>("stringcastPush") {
    group = "stringcast"
    workingDir = projectDir
    commandLine("npx", "stringcast", "push", "--platform", "android", "--res", "src/main/res")
}
// optionally: tasks.named("preBuild") { dependsOn("stringcastPush") }  (CI only — needs network)
```

The task inherits the environment, so set `STRINGCAST_UPLOAD_KEY` (`sk_…`), `STRINGCAST_PROJECT_ID`
and `STRINGCAST_BASE_URL` (or use the CLI's config file / flags). See `cli/README.md`.
`./gradlew :sample:stringcastPush` is a working example.

## ProGuard / R8

The AAR ships `consumer-rules.pro`, applied automatically:

```proguard
-keepclassmembers class **.R$string { public static int *; }
-keepclassmembers class **.R$plurals { public static int *; }
-keepclassmembers class **.R$array { public static int *; }
```

They keep the R fields used by `uploadLocalStrings()` / the draft-mode filter by reflection (only
relevant if you minify a draft-mode build). Everything else in the SDK works with full
minification. Do not enable resource *name* obfuscation: the SDK maps resource ids to keys via
`Resources.getResourceEntryName(id)`.

App bundles: Play may strip `values-xx` folders for languages the device doesn't use. When the
user picks such a language with `setLanguage`, OTA strings still apply; only the compiled fallback
for keys missing from the bundle comes from the base language.

## Sample app

`sample/` targets the local backend (`cd backend && npm run seed && npm run dev`):
`http://10.0.2.2:8787`, project `p_demo`, key `pk_demo_local` (cleartext allowed for `10.0.2.2`
only). It shows layout strings, a plural driven by a slider, a language picker (System/EN/ES/FR)
and a Refresh button. As a debuggable build it uploads its strings automatically on first launch;
long-press Refresh to run `uploadLocalStrings()` again.

```bash
./gradlew :sample:installDebug
```
