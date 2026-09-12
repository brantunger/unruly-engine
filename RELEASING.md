# Releasing unruly-engine

Releases are automated. Merging a PR to `main` is the only manual act — version bump,
changelog, git tag, GitHub Release and the Maven Central publish all follow from it.

## The normal flow

1. Merge PRs to `main` with [Conventional Commit](CONTRIBUTING.md#commit-and-pr-titles)
   titles. `feat:` bumps the minor, `fix:` the patch, a `!` suffix the major.
2. [`release-please`](https://github.com/googleapis/release-please) opens a PR titled
   **chore(main): release X.Y.Z**, bumping `build.gradle`, both README snippets and
   `CHANGELOG.md`. It updates that PR as further commits land.
3. Review the proposed version and changelog, then **squash-merge the release PR**.
4. That merge makes release-please create tag `vX.Y.Z` and a GitHub Release, which gates
   the `publish` job in the same workflow run. It rebuilds from the tag, runs the full
   `check` suite, publishes to the Central Portal and attaches the jars to the Release.

Central Portal validation is synchronous, so a green `publish` job means the release was
accepted. Propagation to `repo1.maven.org` takes a further **30–60 minutes**. The "Wait for
Maven Central sync" step polls for 20 minutes and is `continue-on-error`, so if it runs out
the job still succeeds and only leaves a warning annotation — that means "not synced yet",
never "the release failed".

## Forcing a release

If only `deps` or `chore` commits have landed, release-please will not open a release PR —
by design, so weekly Dependabot bumps do not each ship a version. To release anyway, merge
any `fix:` PR; it cuts a release and sweeps every pending `deps` commit into its changelog.

`Release-As:` footers do **not** work here: the repo squash-merges with the PR title only,
so commit bodies never reach `main`.

## Required secrets

Stored on the `maven-central` environment, not at repo level, so the publish job is the
only thing that can read them.

| Secret | What it is |
| --- | --- |
| `MAVEN_USER` | Central Portal user token username (`central.sonatype.com` → Account → Generate User Token) |
| `MAVEN_PASSWORD` | Central Portal user token password |
| `GPG_KEY` | ASCII-armored **private** signing key, `gpg --armor --export-secret-keys <KEY_ID>` |
| `GPG_PASSWORD` | Passphrase for that key |
| `GPG_KEY_ID` | Key ID. Not consumed by the workflow; kept for the keyserver step below |

The workflow passes these to Gradle as `ORG_GRADLE_PROJECT_mavenCentralUsername`,
`…Password`, `…signingInMemoryKey` and `…signingInMemoryKeyPassword`. Signing happens
in-memory — no GPG keyring is imported onto the runner.

## One-time GPG setup

Central verifies signatures against a public keyserver, so the **public** half of the key
has to be published once. This is not a per-release step and no longer runs in CI:

```bash
gpg --keyserver keyserver.ubuntu.com --send-keys "$GPG_KEY_ID"
# Mirrors are independent; publishing to a second one speeds up verification.
gpg --keyserver keys.openpgp.org --send-keys "$GPG_KEY_ID"
```

### Rotating the key

1. Generate a new key, `gpg --full-generate-key` (RSA 4096, set an expiry).
2. `--send-keys` the new key ID to the keyservers above.
3. Update `GPG_KEY`, `GPG_PASSWORD` and `GPG_KEY_ID` on the `maven-central` environment.
4. Leave the old public key on the keyservers — it still verifies past releases.

If the armored export contains more than one secret key, also set
`ORG_GRADLE_PROJECT_signingInMemoryKeyId` to the short (8-character) key ID so the plugin
picks the right one.

## When a publish half-completes

Maven Central is immutable: a version, once released, can never be re-uploaded or deleted.

- **Failed before upload** (build, Checkstyle, PMD or coverage failed): nothing shipped.
  Fix forward on `main` and merge a new release PR. The tag already exists and is harmless.
- **Failed during upload**: check the deployment at
  <https://central.sonatype.com/publishing/deployments>. A deployment stuck in `FAILED` or
  `VALIDATED` can be dropped from that page, then re-run the `publish` job.
- **Released but broken**: do not attempt to replace it. Cut the next patch version.

## Checking a release by hand

```bash
# Portal (immediate)
curl -sI https://central.sonatype.com/artifact/io.github.brantunger/unruly-engine/1.0.16
# repo1 (30-60 min later)
curl -sI https://repo1.maven.org/maven2/io/github/brantunger/unruly-engine/1.0.16/unruly-engine-1.0.16.pom
```

## Verifying signing locally

No credentials needed for the build itself:

```bash
./gradlew clean build
./gradlew publishToMavenLocal
ls ~/.m2/repository/io/github/brantunger/unruly-engine/<version>/
```

Expect `.jar`, `-sources.jar`, `-javadoc.jar` and `.pom`, each with a matching `.asc`.
