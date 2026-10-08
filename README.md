# StringCast Android SDK

Over-the-air localization for Android apps. Strings, plurals and string arrays published in
StringCast replace the compiled `strings.xml` values at runtime — no app update needed.

- Kotlin, minSdk 21, dependencies: `kotlinx-coroutines-android`, `androidx.core` (HTTP via
  `HttpURLConnection`, JSON via `org.json`).
- Implements the runtime behaviour of `docs/CONTRACT.md` §6.

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

Until it is published to a Maven repository, include the module or the AAR:

```kotlin
// settings.gradle.kts
include(":stringcast")
project(":stringcast").projectDir = file("../stringcast/sdk-android/stringcast")

// app/build.gradle.kts
dependencies { implementation(project(":stringcast")) }
// or: implementation(files("libs/stringcast-release.aar")) + kotlinx-coroutines-android + androidx.core
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
                baseUrl = "https://api.example.com", // API origin, without /v1
                // languageOverride = "es",          // force a language
                // draftMode = null,                 // null = on for debuggable builds only
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
  write is temp file + fsync + rename. The ETag lives in SharedPreferences `dev.stringcast.sdk`.
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

## Draft mode

`draftMode` defaults to `true` for debuggable builds and `false` for release builds.

- **Missing keys**: strings the app requests that are not in the base bundle are collected,
  debounced (~5 s) and POSTed to `/v1/sdk/{projectId}/missing` in batches of ≤ 500, with the value
  from the app's base-language (`values/`) resources. Only the app's own resources are reported
  (framework and library strings such as `abc_*` are filtered out); keys without a local value
  are not reported. The server never overwrites existing keys.
- **`StringCast.uploadLocalStrings()`** uploads every entry of the app's `R.string`, `R.plurals`
  and `R.array` (base-language values) in batches of ≤ 500:

  ```kotlin
  StringCast.uploadLocalStrings(R::class.java) { result ->
      Log.i("App", "uploaded ${result.total}: ${result.created} new, ${result.ignored} existing, error=${result.error}")
  }
  ```

  `R::class.java` is optional — without it the SDK looks for `<applicationId>.R` and its parent
  packages (so `.debug` suffixes work). With AGP 8's default non-transitive R classes this is
  exactly the app module's own strings. Plural forms are read back using sample quantities for
  each CLDR category of the base language.

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
and a Refresh button; long-press Refresh to run `uploadLocalStrings()`.

```bash
./gradlew :sample:installDebug
```
