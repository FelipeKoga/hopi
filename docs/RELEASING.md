# Releasing Hopi to Maven Central

Every Hopi artifact, the Gradle plugin and its marker included, goes to Maven Central under the
group `io.github.felipekoga.hopi`, in the namespace `io.github.felipekoga` that the Central
Publisher Portal grants the FelipeKoga GitHub account. The plugin is not on the Gradle Plugin
Portal: apps resolve it from `mavenCentral()` (README, "Install"). A release is a tag: the release
workflow (`.github/workflows/`) builds, signs and uploads it, and the owner presses Publish.

## Once, by the owner

1. Sign in to [central.sonatype.com](https://central.sonatype.com) with GitHub, as FelipeKoga.
2. Under Namespaces, confirm `io.github.felipekoga` is there and verified: the portal creates it
   for a GitHub sign-in. If it is missing, Central Support adds it (the portal's Help menu).
3. Generate a user token (Account, Generate User Token). The portal shows its username and password
   once; the next step asks for both. It is not the account's password.
4. `brew install gnupg` (and `gh`, signed in with `gh auth login`, if it is not yet).
5. Run `scripts/release-secrets.sh`. It generates the signing key (RSA 4096, no expiry; it asks for
   the user ID, default `koga (Hopi release signing)`, with no email unless you type one), sends
   the public key to keyserver.ubuntu.com and keys.openpgp.org, where Maven Central looks it up,
   asks for the token, and sets the repository's four secrets: `SIGNING_KEY`,
   `SIGNING_KEY_PASSWORD`, `MAVEN_CENTRAL_USERNAME` and `MAVEN_CENTRAL_PASSWORD`. The key stays in
   your keyring (`~/.gnupg`) with its revocation certificate in `~/.gnupg/openpgp-revocs.d`; the
   passphrase is only in the secret. Run again with another user ID, it makes a new key and
   replaces the secrets.

## Each release

1. Set `hopi.version` in `gradle.properties` (`0.1.0`, then `0.1.1`, `0.2.0`, …): the plugin adds
   the runtimes at that version, so every artifact moves together. Commit and push it, with CI
   green.
2. Tag the commit `v<hopi.version>`: `git tag v0.1.0` (the workflow refuses a tag that is not
   `v` and `hopi.version`).
3. Push the tag: `git push origin v0.1.0`. The release workflow runs the release build,
   `./gradlew --no-configuration-cache checkPublication -Phopi.release=true`, signed with
   `SIGNING_KEY`, then `scripts/central-release.sh`, which uploads
   `build/central/hopi-<hopi.version>-bundle.zip` and waits until the portal has validated it
   (`VALIDATED`), or prints the portal's errors and fails.
4. Press Publish for the deployment at
   [central.sonatype.com/publishing/deployments](https://central.sonatype.com/publishing/deployments),
   after a look at its files. Maven Central serves the release some minutes later.

A version, once published, can never be replaced or deleted: fix and release the next one. A
deployment that failed or that you do not want can be dropped on the same page before Publish.

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
