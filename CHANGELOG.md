# Changelog

## 0.1.2

- Multi-module apps work without configuration: when `rClasses` is not set, draft builds discover every
  module `R` class in the app's own package (e.g. `com.acme.feature.auth.R`) and upload all of their strings.
- Runtime missing-key reports no longer drop keys that aren't in the scanned R classes; only library
  strings (denylist) and `excludedKeys` / `excludedKeyPrefixes` are filtered. Keys from a module missing
  from `rClasses` are reported again.

All notable changes to the StringCast Android SDK (`app.stringcast:stringcast-android`).

## 0.1.1 — 2026-10-10

### Added
- **Automatic full upload in draft mode.** Once per app build (`versionName|versionCode|sdkVersion`),
  after the manifest is known, the SDK uploads every app-owned string, plural and string array in
  the project's base language (read for the base locale, independent of the device language) plus
  the app's existing compiled translations for the project's other languages (only values that
  differ from base). One language per request, ≤ 500 keys each. The marker is stored only after
  every batch succeeded; failures retry on the next launch. New config:
  `autoUploadLocalStrings` (default `true`, draft mode only).
- `StringCastConfig.rClasses`: the R classes to scan for the app's own strings (needed for
  multi-module apps with non-transitive R classes). Without it, `<applicationId>.R` is discovered
  as before.
- `StringCastConfig.excludedKeys` / `excludedKeyPrefixes`: app-specific keys never uploaded or
  reported.

### Changed
- `StringCast.uploadLocalStrings()` now uses the same code path as the automatic upload: base
  values **and** existing translations. Its `rClass` parameter is scanned in addition to
  `rClasses`. It always uploads (ignores the once-per-build marker).
- Library and generated strings (`abc_*`, `exo_*`, `mtrl_*`, `m3c_*`, Compose `tab`/`selected`/…,
  `google_app_id`, `facebook_app_id`, …) are always excluded from uploads and missing-key reports,
  which matters for apps with transitive R classes.
- Runtime missing-key reports only include keys that are in the app's R classes and not excluded
  (previously library strings displayed on screen, e.g. ExoPlayer's
  `exo_controls_playback_speeds`, could be reported).
- Requires the backend's updated `/missing` contract (fills empty values in the request
  language; response `{created, filled, ignored}`).

## 0.1.0

Initial release.

- Over-the-air strings, plurals and string arrays replacing compiled `strings.xml` values at
  runtime via `StringCast.wrap(context)` (Resources subclass + LayoutInflater wrapper) and the
  key-based API (`getString`, `getQuantityString`, `getStringArray`).
- Manifest + bundle download with ETag, hash/version verification and an atomic disk cache;
  first frame localized from cache.
- Language resolution per contract §6.4, `setLanguage`, update listeners.
- Check-in headers (`X-StringCast-*`) on API requests.
- Draft mode (debuggable builds): runtime missing-key reporting and `uploadLocalStrings()`
  (base-language values only).
