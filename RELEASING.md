# 🚀 Releasing unruly-engine

Releases are automated. Merging a PR to `main` is the only manual act. The version bump, changelog, git tag,
GitHub Release and Maven Central publish all follow from it.

- [The normal flow](#-the-normal-flow)
- [Forcing a release](#-forcing-a-release)
- [Required secrets](#-required-secrets)
- [One-time GPG setup](#-one-time-gpg-setup)
- [When a publish half-completes](#-when-a-publish-half-completes)
- [Checking a release by hand](#-checking-a-release-by-hand)
- [Verifying signing locally](#-verifying-signing-locally)

---

## 🔄 The normal flow

```mermaid
flowchart LR
    A["🔀 Merge a feat: or<br/>fix: PR"] --> B["🤖 release-please opens<br/>chore(main): release X.Y.Z"]
    B --> C["👀 Review and<br/>squash-merge it"]
    C --> D["🏷 Tag vX.Y.Z +<br/>GitHub Release"]
    D --> E["🔨 publish job<br/>build, check, sign"]
    E --> F["📦 Central<br/>Portal"]
    F -. "30–60 min" .-> G["🌍 repo1.maven.org"]
```

1. Merge PRs to `main` with [Conventional Commit](CONTRIBUTING.md#-commit-and-pr-titles) titles. `feat:` bumps the
   minor version, `fix:` the patch version, and a `!` suffix the major version.
2. [release-please](https://github.com/googleapis/release-please) opens a PR titled
   **chore(main): release X.Y.Z**. It bumps `build.gradle`, the README install snippets,
   `.release-please-manifest.json` and `CHANGELOG.md`, and updates the PR as further commits land.
3. Review the proposed version and changelog, then **squash-merge the release PR**.
4. That merge makes release-please create the tag `vX.Y.Z` and a GitHub Release, which triggers the `publish` job
   in the same workflow run. The job checks out the tag, runs the full `build` (including Checkstyle, PMD and the
   coverage gate), publishes to the Central Portal and attaches the jars to the GitHub Release.

Central Portal validation is synchronous, so a green `publish` job means the release was accepted. Propagation to
`repo1.maven.org` takes a further **30–60 minutes**. The workflow doesn't wait for it, so the release queue isn't
held up; see [Checking a release by hand](#-checking-a-release-by-hand) to confirm the sync.

## 🎛 Forcing a release

Only `feat:`, `fix:` and breaking (`!`) commits open a release PR. Every other type (`deps`, `perf`, `refactor`,
`revert`, `docs`, `chore`, `build`, `ci`, `test`) is hidden in `release-please-config.json`, so it never cuts a
release on its own and never appears in the changelog. That's by design, so weekly Dependabot bumps don't each ship
a version; they go out with the next release. To release pending dependency bumps sooner, merge a `fix:` PR. The
bumps ship in that release without their own changelog entries.

> [!NOTE]
> `Release-As:` footers do **not** work here. The repository squash-merges with the PR title only, so commit bodies
> never reach `main`.

## 🔑 Required secrets

The secrets are stored on the `maven-central` environment, not at repository level, so the publish job is the only
thing that can read them.

| Secret | What it is |
| --- | --- |
| `MAVEN_USER` | Central Portal user token username (`central.sonatype.com` → Account → Generate User Token) |
| `MAVEN_PASSWORD` | Central Portal user token password |
| `GPG_KEY` | ASCII-armored **private** signing key, from `gpg --armor --export-secret-keys <KEY_ID>` |
| `GPG_PASSWORD` | The passphrase for that key |
| `GPG_KEY_ID` | The key ID. The workflow doesn't use it; it's kept for the keyserver step below. |

The workflow passes these to Gradle as `ORG_GRADLE_PROJECT_mavenCentralUsername`, `…Password`,
`…signingInMemoryKey` and `…signingInMemoryKeyPassword`. Signing happens in memory; no GPG keyring is imported
onto the runner.

## 🔐 One-time GPG setup

Central verifies signatures against a public keyserver, so the **public** half of the key has to be published
once. This isn't a per-release step and doesn't run in CI:

```bash
gpg --keyserver keyserver.ubuntu.com --send-keys "$GPG_KEY_ID"
# Mirrors are independent; publishing to a second one speeds up verification.
gpg --keyserver keys.openpgp.org --send-keys "$GPG_KEY_ID"
```

### Rotating the key

1. Generate a new key with `gpg --full-generate-key` (RSA 4096, with an expiry date).
2. `--send-keys` the new key ID to the keyservers above.
3. Update `GPG_KEY`, `GPG_PASSWORD` and `GPG_KEY_ID` on the `maven-central` environment.
4. Leave the old public key on the keyservers, because it still verifies past releases.

If the armored export contains more than one secret key, also set `ORG_GRADLE_PROJECT_signingInMemoryKeyId` to
the short (8-character) key ID so the plugin picks the right one.

## 🩹 When a publish half-completes

> [!CAUTION]
> Maven Central is immutable. A released version can never be re-uploaded or deleted.

| Failure | What to do |
| --- | --- |
| **Before the upload** (build, Checkstyle, PMD or coverage failed) | Nothing shipped, but the tag and GitHub Release for that version already exist. If the failure was transient, use **Re-run failed jobs**; the job rebuilds from the tag. Otherwise, fixing `main` can't repair that tag: edit the GitHub Release to say the version was never published, fix forward on `main`, and ship the next version. |
| **During the upload** | Check the deployment at <https://central.sonatype.com/publishing/deployments>. A deployment stuck in `FAILED` or `VALIDATED` can be dropped from that page; then re-run the `publish` job. |
| **Released but broken** | Don't try to replace it. Cut the next patch version. |

## 🔎 Checking a release by hand

Deployment status is on the Portal (login required): <https://central.sonatype.com/publishing/deployments>.

The public artifact page on `central.sonatype.com` answers HTTP 200 for any version, even one that doesn't exist,
so it can't confirm a release. Check `repo1` instead, 30–60 minutes after publishing:

```bash
VERSION=<version>
curl -sI "https://repo1.maven.org/maven2/io/github/brantunger/unruly-engine/$VERSION/unruly-engine-$VERSION.pom"
# HTTP 200 once synced, 404 before
```

## ✍ Verifying signing locally

`./gradlew clean build` needs no credentials. Publishing, even to a local Maven repository, signs every artifact,
so `publishToMavenLocal` fails with `No configured signatory` unless a signing key is supplied. A throwaway key in
a temporary keyring is enough:

```bash
export GNUPGHOME="$(mktemp -d)"
gpg --batch --pinentry-mode loopback --passphrase test --quick-generate-key "unruly test <test@example.com>" rsa3072 sign never
export ORG_GRADLE_PROJECT_signingInMemoryKey="$(gpg --batch --pinentry-mode loopback --passphrase test --armor --export-secret-keys test@example.com)"
export ORG_GRADLE_PROJECT_signingInMemoryKeyPassword=test

./gradlew publishToMavenLocal
ls ~/.m2/repository/io/github/brantunger/unruly-engine/<version>/
```

Expect `.jar`, `-sources.jar`, `-javadoc.jar`, `.module` and `.pom` files, each with a matching `.asc` signature.
