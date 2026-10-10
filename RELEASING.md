# Releasing the StringCast Android SDK

Artifact: `app.stringcast:stringcast-android:<VERSION_NAME>` on Maven Central, published through
the Sonatype **Central Portal** (https://central.sonatype.com) with the
[`com.vanniktech.maven.publish`](https://vanniktech.github.io/gradle-maven-publish-plugin/) Gradle
plugin (0.35.0). Only the `:stringcast` module is published; `:sample` is not.

The version lives in one place: `VERSION_NAME` in `gradle.properties`. It is used both for the
published artifact and for `BuildConfig.SDK_VERSION` (the `X-StringCast-SDK-Version` header).

## One-time setup

### 1. Central Portal account and namespace

1. Sign in at https://central.sonatype.com (e.g. with GitHub).
2. **Namespaces → Add namespace → `app.stringcast`.** Because this is a domain namespace, the
   portal shows a verification key; add it as a DNS **TXT** record on `stringcast.app`, then click
   *Verify*. (Remove the TXT record after it is verified.)
3. **Account → Generate User Token.** This gives a token *username* and *password*; these (not
   your login) are the Maven Central credentials.

### 2. GPG signing key

Central rejects unsigned artifacts.

```bash
gpg --full-generate-key            # RSA and RSA, 4096 bits, no expiry (or set one), pick a passphrase
gpg --list-secret-keys --keyid-format=long
#   sec   rsa4096/ABCDEF0123456789 ...      <- long key id after the slash
#   The last 8 hex chars (0123456789 -> "23456789") are the short key id.

# Publish the PUBLIC key so Central can verify signatures:
gpg --keyserver keyserver.ubuntu.com --send-keys ABCDEF0123456789
# (optional, extra mirror)  gpg --keyserver keys.openpgp.org --send-keys ABCDEF0123456789

# Export the PRIVATE key (ASCII-armored) for CI:
gpg --export-secret-keys --armor ABCDEF0123456789 > signing-key.asc
```

Check the upload worked: https://keyserver.ubuntu.com/pks/lookup?search=0xABCDEF0123456789&op=index
Store `signing-key.asc` and the passphrase in a password manager, then delete the file.

### 3. GitHub Actions secrets (public repo `stringcast/stringcast-android`)

Settings → Secrets and variables → Actions → *New repository secret*:

| Secret                               | Value                                                     |
|--------------------------------------|-----------------------------------------------------------|
| `MAVEN_CENTRAL_USERNAME`             | Central Portal user-token username                        |
| `MAVEN_CENTRAL_PASSWORD`             | Central Portal user-token password                        |
| `SIGNING_IN_MEMORY_KEY`              | full contents of `signing-key.asc` (incl. BEGIN/END lines) |
| `SIGNING_IN_MEMORY_KEY_ID`           | short key id (last 8 hex chars)                           |
| `SIGNING_IN_MEMORY_KEY_PASSWORD`     | the key's passphrase                                      |

`.github/workflows/publish.yml` maps them onto the Gradle properties the plugin reads:

```
ORG_GRADLE_PROJECT_mavenCentralUsername      = MAVEN_CENTRAL_USERNAME
ORG_GRADLE_PROJECT_mavenCentralPassword      = MAVEN_CENTRAL_PASSWORD
ORG_GRADLE_PROJECT_signingInMemoryKey        = SIGNING_IN_MEMORY_KEY
ORG_GRADLE_PROJECT_signingInMemoryKeyId      = SIGNING_IN_MEMORY_KEY_ID
ORG_GRADLE_PROJECT_signingInMemoryKeyPassword = SIGNING_IN_MEMORY_KEY_PASSWORD
```

Signing is turned on only when `signingInMemoryKey` (or `signing.keyId`) is set, so local
`publishToMavenLocal` works without any keys.

## Cutting a release

1. Set `VERSION_NAME` in `gradle.properties` (e.g. `0.1.0`), update README install snippets and add a `CHANGELOG.md` entry.
2. Sanity check locally:
   ```bash
   ./gradlew :stringcast:testDebugUnitTest :stringcast:assembleRelease :sample:assembleDebug
   ./gradlew :stringcast:publishToMavenLocal
   ls ~/.m2/repository/app/stringcast/stringcast-android/<VERSION_NAME>/
   ```
3. Commit, then in the monorepo mirror the subtree to the public repo and tag it:
   ```bash
   git subtree split --prefix=sdk-android -b sdk-android-release
   git push git@github.com:stringcast/stringcast-android.git sdk-android-release:main
   # tag on the public repo's main:
   git tag v0.1.0 sdk-android-release && git push git@github.com:stringcast/stringcast-android.git v0.1.0
   ```
4. The tag push runs `publish.yml`: unit tests, then

   ```bash
   ./gradlew publishAndReleaseToMavenCentral --no-configuration-cache
   ```

   which uploads a signed bundle to the Central Portal and releases it automatically once
   validation passes. To upload *without* releasing (review it manually first), use
   `./gradlew publishToMavenCentral --no-configuration-cache` instead and press *Publish* in the
   portal.

Publishing manually from a workstation is the same command with the variables exported:

```bash
export ORG_GRADLE_PROJECT_mavenCentralUsername=...
export ORG_GRADLE_PROJECT_mavenCentralPassword=...
export ORG_GRADLE_PROJECT_signingInMemoryKey="$(cat signing-key.asc)"
export ORG_GRADLE_PROJECT_signingInMemoryKeyId=23456789
export ORG_GRADLE_PROJECT_signingInMemoryKeyPassword=...
./gradlew publishAndReleaseToMavenCentral --no-configuration-cache
```

## Verifying

- https://central.sonatype.com/publishing/deployments shows the deployment: *VALIDATING* →
  *VALIDATED* (→ *PUBLISHING* → *PUBLISHED* with automatic release). Validation errors (missing
  signature, javadoc/sources jar, POM fields) are listed there; a failed deployment can be dropped
  and re-uploaded with the same version.
- Once published, the artifact appears at
  https://central.sonatype.com/artifact/app.stringcast/stringcast-android and, usually within
  ~30 minutes, at https://repo1.maven.org/maven2/app/stringcast/stringcast-android/ (search
  indexes can lag a few hours).
- Smoke test in a fresh app: `implementation("app.stringcast:stringcast-android:<VERSION_NAME>")`.

Released versions are immutable: to fix a bad release, bump `VERSION_NAME` and release again.
