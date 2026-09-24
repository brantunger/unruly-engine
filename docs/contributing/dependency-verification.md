# 🔏 Dependency verification

How the build checks every file it downloads, when the checked-in verification metadata must be regenerated, and how
to review that change.

**Who it's for:** contributors and maintainers who bump a dependency, a plugin or `gradle/actions`, or who merge a
Dependabot pull request.
**You'll be able to:** tell whether a pull request needs a regeneration commit, make it, and spot a suspicious change
in its diff.
**Before you start:** [Build and gates](build-and-gates.md), for what the build runs.

[← Documentation index](../README.md)

- [What the build checks](#-what-the-build-checks)
- [When to regenerate](#-when-to-regenerate)
- [How to regenerate](#-how-to-regenerate)
- [Reviewing the diff](#-reviewing-the-diff)
- [The dependency-graph plugin](#-the-dependency-graph-plugin)
- [The API baseline](#-the-api-baseline)

---

## 🧭 What the build checks

The build checks every dependency and plugin it downloads, including those of `buildSrc`, against
[`gradle/verification-metadata.xml`](../../gradle/verification-metadata.xml): a PGP signature where the file is
signed, and a sha256 checksum where it isn't. The public keys it checks signatures with are in
[`gradle/verification-keyring.keys`](../../gradle/verification-keyring.keys). Both files are committed.

A signed file is covered by a `<trusted-key>` line, which trusts a key for a whole group, for a group and name, or
for one version, or by a `<pgp>` entry of its own. The files nobody signs upstream, and the dependency-graph plugin,
have a checksum instead.

Key servers are off (`<key-servers enabled="false"/>`), so a build never fetches a key. Only the
[regeneration command](#-how-to-regenerate) does.

## 🔁 When to regenerate

> [!IMPORTANT]
> **Run the regeneration on every dependency or plugin bump.** A Dependabot PR that bumps a Gradle dependency fails
> CI until someone pushes the result to its branch. The exception is a version signed by a key the metadata trusts
> for every version, such as the `org.slf4j` group's: CI passes, and the regeneration may change nothing.
> Dependabot can't regenerate the file ([dependabot-core#1996](https://github.com/dependabot/dependabot-core/issues/1996)).

| Change | Why |
| --- | --- |
| A dependency or plugin bump, including every Gradle Dependabot PR | The new version's files may have no entry in the metadata |
| A new signer: a dependency's new version is signed with a key the keyring doesn't hold | The keyring is the only place a build takes keys from |
| A new signing key for this project's own releases | The API check's baseline is [signed with it](#-the-api-baseline) |
| A `gradle/actions` bump that changes the dependency-graph plugin's version | The plugin is [pinned by hand](#-the-dependency-graph-plugin); the command below doesn't do it |

Dependabot's two Gradle entries in `.github/dependabot.yml`, for the root build and for `buildSrc`, both open such
pull requests. Run the command on each one, even when CI passes, and commit whatever it changes.

A failed build says `Dependency verification failed for configuration`, lists the files it couldn't verify, then
prints one of these lines and a link to the report:

```text
If the artifacts are trustworthy, you will need to update the gradle/verification-metadata.xml file. …
This can indicate that a dependency has been compromised. Please carefully verify the checksums.
This can indicate that a dependency has been compromised. Please carefully verify the signatures and checksums. Key servers are disabled, this can indicate that you need to update the local keyring with the missing keys.
```

- **The first** is what a bump gives when the metadata trusts the signing key for other versions only, as it trusts
  jspecify's for 1.0.1 only. Regenerate.
- **The second** means a checksum didn't match its entry, or the
  [dependency-graph plugin's pin](#-the-dependency-graph-plugin) is stale. Find out why before you regenerate.
- **The third** means a new signer: the keyring doesn't hold the key. Regenerate, then check the key as
  [Reviewing the diff](#-reviewing-the-diff) shows.

The full report is in
`build/reports/dependency-verification/at-<epoch-millis>/dependency-verification-report.html`, in the root project.

## 🧰 How to regenerate

Check out the pull request's branch, and run this from the repository root. It needs no signing key:

```bash
./gradlew --write-verification-metadata sha256,pgp --export-keys --no-build-cache -PapiCheck.refresh \
  clean build jacocoTestReport plainJavadocJar :native-smoke:installDist :benchmarks:classes
```

The tasks are there so the write pass resolves what CI and the release download. The command rewrites
`verification-metadata.xml` and, with `--export-keys`, `verification-keyring.keys`. Commit both to the branch.

- **A new signer's key** is fetched by this command, although key servers are off: Gradle prints
  `Will use key servers to download missing keys`, adds a `<trusted-key>` and exports the key. `--offline` stops it.
- **An `ignored-key` with `Key couldn't be downloaded`** in the output means Gradle's key cache is stale. Run the
  command again with `--refresh-keys`.

The command adds entries and doesn't delete the old version's, and it keeps the dependency-graph plugin's pin as it
is.

## 🔍 Reviewing the diff

Read the diff of `verification-metadata.xml` before you merge. A normal bump adds the new version's entries, or
nothing when a trusted key already covers it. Stop and find out why when the diff has any of these:

| In the diff | Why it's suspicious |
| --- | --- |
| A new `<trusted-key>` | A new signer: check that the key belongs to the dependency's project before you trust it |
| A `<trusted-key>` whose `group` is widened, such as a new `regex="true"` | It trusts that key for more artifacts than before |
| A new `<ignored-key>` | Gradle couldn't use that key, so the file is checked by its checksum only |
| A new checksum for a component a `<trusted-key>` covers | With `reason="Artifact is not signed"`, the new version is unsigned. With no `reason`, check that the file's `.asc` is on Maven Central |
| A changed checksum for a version that already had one | The same published file changed its contents, which a repository never should |
| A `<trust>` or `<trusted-artifacts>` entry | It turns verification off for everything it matches |

As committed, the metadata has one `<ignored-key>` (the revoked key that signed the dependency-graph plugin), no
`<trust>` and no `<trusted-artifacts>`. Five signed files also have a checksum with no `reason`, which Gradle
wrote: this project's three 2.2.0 jars, `mvel2-2.5.4.Final.pom` and `slf4j-api-2.0.19.pom`. The element names are
those of
[Gradle's verification metadata](https://docs.gradle.org/current/userguide/dependency_verification.html).

## 🤖 The dependency-graph plugin

`gradle/actions` injects `org.gradle:github-dependency-graph-gradle-plugin` into the build, in two places:

- The **`dependency-graph` job** on pushes to `main` submits the graph for Dependabot alerts. Its action runs Gradle
  with `-Dorg.gradle.dependency.verification=off`, so it checks nothing.
- The **pull request's `ubuntu-latest` JDK 21 build** generates the graph without submitting it, with verification
  on. That is where a stale pin fails: on the pull request, before the merge.

The plugin's signing key, `7B79ADD1…` (Gradle Inc.), is revoked, so the metadata ignores it with a reason and pins
the plugin's `.jar` and `.module` (version 1.4.2) by sha256. The regeneration command never writes that pin, so it's
kept by hand. When a `gradle/actions` bump changes the plugin's version:

1. Read the default `dependencyGraphPluginVersion` in
   `sources/src/resources/init-scripts/gradle-actions.github-dependency-graph-gradle-plugin-apply.groovy`, at the
   new commit SHA of `gradle/actions`.
2. Download the new version's two files and hash them, as below.
3. Edit the plugin's `<component>` block in `verification-metadata.xml` by hand: its version, both artifact names
   and both `sha256` values.
4. Push it, and check that the pull request's `ubuntu-latest` JDK 21 build passes: no other job resolves the plugin
   with verification on.

```bash
V=<new version>
BASE=https://plugins.gradle.org/m2/org/gradle/github-dependency-graph-gradle-plugin/$V
curl -sfLO "$BASE/github-dependency-graph-gradle-plugin-$V.jar"
curl -sfLO "$BASE/github-dependency-graph-gradle-plugin-$V.module"
sha256sum github-dependency-graph-gradle-plugin-$V.jar github-dependency-graph-gradle-plugin-$V.module
```

`-L` is required: `plugins.gradle.org` answers with a redirect, and without `-L` `curl` saves an empty file and
still exits 0, so you'd pin the empty file's hash, `e3b0c442…`.

The regeneration command doesn't resolve the plugin. If you regenerate with the `gradle/actions` init script added
(`-I`), it can add `<trusted-key id="7B79ADD1…" … version="1.4.2"/>`. That changes nothing, because the ignored key
wins, but delete it.

## 🧬 The API baseline

The [API check](api-compatibility.md#-baselines) downloads each module's newest release from Maven Central, and the
build checks that download too. The metadata trusts this project's release signing key, `BF9661F4…`, for the whole
`io.github.brantunger` group, so a new release becomes the baseline without a regeneration.

A new release signing key is different: the build doesn't trust it. Once the first release signed with it is the
baseline, at once in CI and within a day in a local build, the build fails until you regenerate. RELEASING.md's
[Rotating the key](../../RELEASING.md#rotating-the-key) includes that step.
