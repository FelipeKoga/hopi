# Releasing Hopi to Maven Central

Every Hopi artifact, the Gradle plugin and its marker included, goes to Maven Central under the
group `io.github.felipekoga.hopi`, in the namespace `io.github.felipekoga` that the Central
Publisher Portal grants the FelipeKoga GitHub account. The plugin is not on the Gradle Plugin
Portal: apps resolve it from `mavenCentral()` (README, "Quick start"). A release is a tag: the
release workflow (`.github/workflows/`) builds, signs and uploads it, and the owner presses Publish.

## Once, by the owner

1. Sign in to [central.sonatype.com](https://central.sonatype.com) with GitHub, as FelipeKoga.
2. Under Namespaces, confirm `io.github.felipekoga` is there and verified: the portal creates it
   for a GitHub sign-in. If it is missing, Central Support adds it (the portal's Help menu).
3. Generate a user token (Account, Generate User Token). The portal shows its username and password
   once; the next step asks for both. It is not the account's password.
4. `brew install gnupg` (and `gh`, signed in with `gh auth login`, if it is not yet).
5. Create the `release` environment (repository Settings, Environments, New environment): add
   yourself as a required reviewer, and under Deployment branches and tags allow only the tag
   pattern `v*`. Its secrets are readable by the release job alone, and only once you approve
   the run: a pushed tag waits for you before anything is built, signed or uploaded.
6. Run `scripts/release-secrets.sh`. It generates the signing key (RSA 4096, no expiry; it asks for
   the user ID, default `koga (Hopi release signing)`, with no email unless you type one), sends
   the public key to keyserver.ubuntu.com and keys.openpgp.org, where Maven Central looks it up,
   asks for the token, and sets the `release` environment's four secrets: `SIGNING_KEY`,
   `SIGNING_KEY_PASSWORD`, `MAVEN_CENTRAL_USERNAME` and `MAVEN_CENTRAL_PASSWORD`. The key stays in
   your keyring (`~/.gnupg`) with its revocation certificate in `~/.gnupg/openpgp-revocs.d`; the
   passphrase is only in the secret. Run again with another user ID, it makes a new key and
   replaces the secrets.

## Each release

1. Set `hopi.version` in `gradle.properties` (`0.1.0`, then `0.1.1`, `0.2.0`, …): the plugin holds
   every Hopi module of a debuggable build type at that version, so every artifact moves together. Commit and push it, with CI
   green.
2. Tag the commit `v<hopi.version>`: `git tag v0.1.0` (the workflow refuses a tag that is not
   `v` and `hopi.version`).
3. Push the tag: `git push origin v0.1.0`, then approve the run (Actions, the Release run,
   Review deployments): it waits for you. The release workflow first runs the release guards on
   the tagged commit (`verifyReleaseHasNoRuntime`, `verifyIosReleaseExcludesCapture`, the plugin's
   functional tests), with no cache, then the release build,
   `./gradlew --no-configuration-cache checkPublication -Phopi.release=true`, signed with
   `SIGNING_KEY`, then `scripts/central-release.sh`, which uploads
   `build/central/hopi-<hopi.version>-bundle.zip` and waits until the portal has validated it
   (`VALIDATED`), or prints the portal's errors and fails.
4. Press Publish for the deployment at
   [central.sonatype.com/publishing/deployments](https://central.sonatype.com/publishing/deployments),
   after a look at its files. Maven Central serves the release some minutes later.

A version, once published, can never be replaced or deleted: fix and release the next one. A
deployment that failed or that you do not want can be dropped on the same page before Publish.

## The release from the owner's Mac (a fallback)

When no macOS runner is available, `scripts/release-local.sh` runs the same steps on the owner's
Mac, on the commit tagged `v<hopi.version>` with a clean tree: the iOS simulator tests and the
release guards, the signed release build and `scripts/central-release.sh`. It signs with a key from
your gpg keyring (`HOPI_SIGNING_KEY`, its user ID or fingerprint) and asks for its passphrase and
the Central Portal user token; `--dry-run` stops before the upload. The iOS klibs build on macOS
only, so off a Mac `-Phopi.release=true` refuses to start.

## The release, locally (a dry run)

Nothing is uploaded; any key of your own, even a throwaway one, signs:

```bash
export ORG_GRADLE_PROJECT_signingInMemoryKey="$(gpg --armor --export-secret-keys <key id>)"
export ORG_GRADLE_PROJECT_signingInMemoryKeyPassword='<its passphrase>'
./gradlew --no-configuration-cache checkPublication -Phopi.release=true
scripts/central-release.sh --dry-run   # builds and lists the bundle; no network
```

- Without a key, the release build stops at once; without `-Phopi.release=true`, nothing is signed
  and `:checkPublication` checks the rest of Maven Central's rules (every POM's name, description,
  URL, license, developer and SCM; a sources and a javadoc jar beside every artifact, the javadoc
  jars empty; `.md5` and `.sha1` checksums). With it, every file must also have its `.asc`.
- The bundle is this version's directories of `build/publication-check` (the six libraries, each
  KMP module's root and per-target publications) and `gradle-plugin/build/functional-repo`
  (`hopi-gradle-plugin`, its marker `io.github.felipekoga.hopi.gradle.plugin` and
  `hopi-instrument`), without `maven-metadata.xml`. `gradle-plugin/build/functional-repo` is never
  emptied, so after a release build a plain `checkPublication` leaves the plugin's old `.asc` files
  there: run the release build again before a dry run (CI always starts clean).
- `scripts/central-release.sh` reads `MAVEN_CENTRAL_USERNAME` and `MAVEN_CENTRAL_PASSWORD` (the
  token), `CENTRAL_PUBLISHING_TYPE` (`USER_MANAGED`, the default, stops at `VALIDATED` for the
  Publish button; `AUTOMATIC` publishes once validated and waits for `PUBLISHED`) and
  `CENTRAL_TIMEOUT_SECONDS` (default 1800). `--status <deployment id>` waits for a deployment's
  verdict again, after a timeout for example.

## The macOS viewer

The native viewer (`hopi-mac`) ships as a `.dmg`, built by `scripts/mac-dmg.sh` and by the
`macOS app` workflow (`.github/workflows/mac-app.yml`): on `main` (when `hopi-mac` changes) and
by hand, an unsigned build kept as a workflow artifact for 14 days; on a `v*` tag, the release's
`Hopi-<hopi.version>.dmg`, attached to the tag's GitHub release (created as a draft when there is
none: publish it after a look). Locally, `scripts/mac-dmg.sh [version]` writes
`build/mac/Hopi-<version>.dmg`.

Until the Apple secrets below exist, the `.dmg` is unsigned (the app is signed ad hoc): macOS
says it cannot check the developer, and the app opens with right-click, Open (or System
Settings, Privacy & Security, Open Anyway), once. Signed and notarized, it opens without a warning,
even offline (the notarization ticket is stapled to the `.dmg`).

### Signing and notarization, once, by the owner

1. Join the [Apple Developer Program](https://developer.apple.com/programs/) (USD 99 a year); a
   free account cannot issue a Developer ID certificate.
2. Create a **Developer ID Application** certificate: Xcode, Settings, Accounts, the team, Manage
   Certificates, `+`. Export it with its private key from Keychain Access (My Certificates, the
   certificate, Export, `.p12`) under a password.
3. Create an App Store Connect API key for notarization: [App Store Connect](https://appstoreconnect.apple.com),
   Users and Access, Integrations, Team Keys, `+`, access **Developer**. Download the `.p8` (once
   only) and note its Key ID and the Issuer ID above the list.
4. Store the six secrets in the `release` environment (the same reviewer approves every tag):

   ```bash
   gh secret set MACOS_CERTIFICATE_P12 --env release --repo FelipeKoga/hopi < <(base64 -i DeveloperID.p12)
   gh secret set MACOS_CERTIFICATE_PASSWORD --env release --repo FelipeKoga/hopi   # asks for it
   gh secret set APPLE_TEAM_ID --env release --repo FelipeKoga/hopi                # 10 characters, Membership page
   gh secret set NOTARY_KEY_P8 --env release --repo FelipeKoga/hopi < <(base64 -i AuthKey_XXXXXXXXXX.p8)
   gh secret set NOTARY_KEY_ID --env release --repo FelipeKoga/hopi
   gh secret set NOTARY_ISSUER_ID --env release --repo FelipeKoga/hopi
   ```

   Then delete the `.p12` and `.p8` files, or keep them in a password manager. With all six set,
   the next tag's `.dmg` is signed with the Developer ID (timestamped, hardened runtime), notarized
   (`notarytool submit --wait`, a few minutes) and stapled; with some but not all set, the script
   refuses to build. The same six variables sign a local build: `scripts/mac-dmg.sh` reads them
   from the environment, puts the certificate in a temporary keychain and deletes it on exit.
