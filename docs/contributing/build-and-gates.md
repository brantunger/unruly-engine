# 🚦 Build and gates

What `./gradlew clean build` runs, where each report lands, how the caches and CI behave, and the conventions the
static checks expect.

**Who it's for:** contributors whose build failed, or who want to know what CI will run before they push.
**You'll be able to:** find the report for a failing task, rebuild without stale results, run the tests on JDK 25,
and suppress a PMD finding the way the code does.
**Before you start:** [CONTRIBUTING.md](../../CONTRIBUTING.md#-run-the-gate-locally), which lists the gates and the
usual fix for each.

[← Documentation index](../README.md)

- [What build runs](#-what-build-runs)
- [Reports](#-reports)
- [The build cache and the configuration cache](#-the-build-cache-and-the-configuration-cache)
- [CI](#-ci)
- [Running the tests on JDK 25](#-running-the-tests-on-jdk-25)
- [PMD suppressions](#-pmd-suppressions)
- [Javadoc and doclint](#-javadoc-and-doclint)

---

## ✅ What build runs

`build` runs `check`, and `check` depends on every gate below. A Gradle deprecation fails every build, local, CI and
release alike (`org.gradle.warning.mode=fail` in `gradle.properties`). The build has five projects: `core`
(`unruly-engine-core`), `mvel` (`unruly-engine`, which also holds all the tests), `test-kit` (`unruly-engine-test`),
and two that aren't published: `benchmarks`, whose sources the build checks while only the `jmh` task runs them, and
`native-smoke`, a small application CI builds into a GraalVM native image.

| Gate | Checks | Configured in |
| --- | --- | --- |
| 🧪 **Tests** | The JUnit suite, on the class path | `mvel/src/test`, `mvel/build.gradle` |
| 📏 **Checkstyle** | Main and test sources: `AvoidStarImport`, `UnusedImports`, `NeedBraces`, `LeftCurly`, `RightCurly`, `EmptyBlock` | `config/checkstyle/checkstyle.xml` |
| 🔍 **PMD** | Main sources, with the best-practices and error-prone rule sets | `buildSrc/src/main/groovy/unruly.java-conventions.gradle` |
| ⚠️ **Warnings** | No javac warning (`-Xlint:all -Werror`) in the published projects, and no Javadoc warning (`-Xdoclint:all -Werror`) | `buildSrc/src/main/groovy/unruly.java-conventions.gradle` |
| 📊 **JaCoCo** | **100%** instruction *and* branch coverage of the published artifacts' main sources | `build.gradle` |
| 🧬 **API compatibility** | No incompatible change to a public or protected member since the latest release | `buildSrc/src/main/groovy/unruly.library.gradle`, `config/japicmp/accepted-breaks.txt` |
| 🧭 **Module path** | `ModulePathTest` compiles four applications against the built jars and runs each on the module path | `mvel/src/test/resources/module-path` |
| 🧱 **Design rules** | Package dependencies, the API's shape, sealed contexts, nullness annotations, engine visibility, class-file version | The structural tests below |

Some details behind the table:

- **Checkstyle's** `AvoidStarImport` allows static member imports, so `import static ...Assertions.*` is fine.
- **PMD** excludes two rules: `GuardLogStatement` from best-practices, and `AvoidCatchingGenericException` from
  error-prone, because the engine catches whatever a language, listener or setter throws and turns it into a failure.
- **Coverage** excludes `io/github/brantunger/unruly/test/ExpressionLanguageContractTest*`: its always-throwing
  lambdas leave instructions JaCoCo can't reach. `-x test` fails the gate with `No JaCoCo execution data`;
  `./gradlew build -x check` only compiles and packages.
- **The module-path applications** are `withMvel`, `withoutMvel`, `withTestKit` and `withJackson`, each compiled
  with `-Xlint:all -Werror` like the main sources.

The `benchmarks` project is the exception to the javac gate: it compiles JMH's generated code, so it runs with
`-Xlint:none` instead (`benchmarks/build.gradle`).

The design rules are ordinary JUnit tests under `mvel/src/test/java/io/github/brantunger/unruly/`:

| Test | Protects |
| --- | --- |
| `PackageDependencyTest` | Each package uses only the packages listed for it, and only `mvel` uses the MVEL library |
| `api/EngineApiShapeTest` | An engine is configured once, on a builder; `RulesEngine` and `RuleListener` have exactly the documented methods |
| `api/language/SealedContextsTest` | Only the engine implements the contexts it passes to a language |
| `api/NullnessAnnotationsTest` | The public API declares its nullness with JSpecify, and the internal `core` package isn't `@NullMarked` |
| `core/EngineVisibilityTest` | The engine classes aren't public and can only be created through `RulesEngineBuilder` |
| `ClassFileVersionTest` | The published classes are compiled for Java 21 |
| `ModulePathTest` | The module declarations work where they take effect: on the module path, in a new JVM |
| `TestJdkTest` | The tests really ran on the JDK `-PtestJdk` asked for |

## 📊 Reports

| Task | Report |
| --- | --- |
| `:mvel:test` | `mvel/build/reports/tests/test/index.html` |
| `jacocoTestReport`, `jacocoTestCoverageVerification` | `build/reports/jacoco/html/index.html`, the aggregate of every artifact; `build/reports/jacoco/report.xml` for tools |
| `checkstyleMain`, `checkstyleTest` | `<project>/build/reports/checkstyle/main.html` and `test.html`, with `.xml` twins |
| `pmdMain` | `<project>/build/reports/pmd/main.html`, with an `.xml` twin |
| `japicmp` | `<project>/build/reports/japicmp/report.html` and `report.txt`, for `core` and `mvel` |
| `javadoc` | `build/docs/javadoc/index.html` for the site; the console for the warnings that failed it |

`test-kit` has no japicmp report until 2.0.0 is released: its check is skipped while the artifact has no release, as
[API compatibility](api-compatibility.md#-baselines) explains. `jacocoTestCoverageVerification` fails on the console;
the HTML report from `jacocoTestReport` shows the uncovered lines and branches.

## 💾 The build cache and the configuration cache

Both are on by default (`org.gradle.caching=true` and `org.gradle.configuration-cache=true` in `gradle.properties`),
so a second build reuses task outputs, including those of another branch, and the configured task graph.

- `--rerun` on a task, such as `./gradlew :mvel:test --rerun`, runs it again even though its inputs haven't changed.
- `--no-build-cache --no-configuration-cache` rebuilds everything from scratch, when a result looks stale.
- The release workflow builds with `--no-build-cache`, so the published artifacts are built from scratch.
- The API check's baseline lookup is cached for 24 hours so the configuration cache stays reusable;
  `-PapiCheck.refresh` looks it up again.

## 🤖 CI

`.github/workflows/ci.yml` runs on every pull request and push to `main` (and to `1.x`, the hotfix branch):

| Job | Runs | Why |
| --- | --- | --- |
| JDK 21 on `ubuntu-latest`, `windows-latest` and `macos-latest` | `./gradlew build jacocoTestReport` | The module-path applications and the child JVMs depend on the OS; Windows and macOS file systems are case-insensitive, so the tests that look a compiled class up in another case run there instead of being skipped |
| JDK 25 on `ubuntu-latest` | `./gradlew :mvel:test -PtestJdk=25` | Compilation stays on the Java 21 toolchain; only the tests need the newer JDK |
| `native-image` on `ubuntu-latest`, GraalVM CE 21.0.2 | `./gradlew :native-smoke:installDist`, then `native-image` and the binary | The engine and MVEL work in a native image with only the metadata the jar ships and the application's own; see [Native image](../native-image.md) |
| `docs-and-hygiene` on `ubuntu-latest` | `config/docs/check_docs.py`, a line-ending check, and `config/docs/check_style.py` on the pages a pull request changes | Broken links and anchors, joined table rows, files stored with CRLF, and [STYLE.md](../STYLE.md)'s mechanical rules |
| `ci-result` | Nothing | Fails when `build`, `native-image` or `docs-and-hygiene` failed or was cancelled; a skipped job counts as passed, and `changes` isn't judged. It's the one check branch protection can require, because a skipped matrix job doesn't report its per-OS checks |

A pull request that changes only documentation skips the build matrix and `native-image`. That means every changed
file is under `docs/` or ends in `.md`, and none is under a `src/` directory. The `changes` job decides that, on
pull requests only. When it fails, the build and `native-image` run anyway, and a push never skips them.

`docs-and-hygiene` only warns for now: a failed step shows as an annotation, and the job stays green. Later, its
checks will block. A page written before STYLE.md may have findings in lines you didn't touch. Run the checks
before you push, from the repository root, with Python 3:

```bash
python config/docs/check_docs.py
python config/docs/check_style.py docs/facts.md   # the pages you changed
git ls-files --eol | grep -E '^i/(crlf|mixed)'   # lists files stored with CRLF; prints nothing when all is well
```

A second workflow, `pr-title.yml`, checks a pull request's title against Conventional Commits, because the title
becomes the release commit. It runs on pull requests only. On one labelled `dependencies`, which Dependabot opens
with a capitalised subject, it skips the format check, but it still rejects a `!` on any type but `feat` and `fix`.

What the jobs leave behind:

- **Artifacts:** `jacoco-report-jdk21-<os>` from every JDK 21 job, always, with that job's JUnit results beside the
  coverage, so you can see which tests ran and which were skipped; `api-compatibility-report-jdk21-<os>`
  only when a job fails.
- **Codecov** gets one upload per run, from the Linux JDK 21 job, authenticated with OIDC. A pull request from a
  fork, or a run Dependabot triggers, skips the upload, because GitHub issues no OIDC token to it.
- **Gradle caches:** runs on `main` save the dependency and build caches, one per OS; pull requests only read them,
  as does the release workflow's `publish` job. The configuration cache isn't saved, because that needs an
  encryption key.

No branch protection requires a check; maintainers merge when CI and the title check are green.

## 🔢 Running the tests on JDK 25

Compilation always uses the Java 21 toolchain. `-PtestJdk=25` runs the test task on a JDK 25 launcher instead, so
the JDK must be installed: Gradle doesn't download toolchains (`org.gradle.java.installations.auto-download=false`),
and a missing one fails the build naming the version. The build passes `unruly.test.jdk` to the tests, and
`TestJdkTest` fails if the JVM running them doesn't match.

```bash
./gradlew :mvel:test -PtestJdk=25
```

## 🔕 PMD suppressions

PMD runs on the main sources of every project. A finding is fixed, unless the code is right and the rule is wrong
for it; then the suppression is as narrow as PMD allows, and a comment on the line above says why. That is the shape
every existing suppression has. For example, `core/src/main/java/io/github/brantunger/unruly/core/RuleSet.java`:

```java
/** Whether every language of these rules returned {@link Session#none()}, so a copy holds nothing of its own. */
// Session.none() is one shared instance, and identity is the question: a session that merely equals it still
// belongs to one run at a time.
@SuppressWarnings("PMD.CompareObjectsWithEquals")
```

The annotation names one rule with its `PMD.` prefix and sits on the smallest scope that has the finding: a method
or field, or, for `Rule.RuleBuilder`, the class whose every setter triggers `AvoidFieldNameMatchingMethodName`.
Excluding a rule for every project is done once, in `buildSrc/src/main/groovy/unruly.java-conventions.gradle`,
with a comment, as `AvoidCatchingGenericException` is.

## ☕ Javadoc and doclint

Every Javadoc task runs with `-Xdoclint:all -Werror`, so a missing comment, `@param`, `@return` or `@throws`, a
broken `{@link}` or bad HTML fails the build. `check` depends on the Javadoc in two places:

- Each published project's `javadoc` task documents its own module. Its output is what the project's `-javadoc.jar`
  packages at publish time.
- The root project's `javadoc` task builds one site for all the modules in `build/docs/javadoc`. It hands the
  javadoc tool the modules rather than source files, so it documents only the packages they export, and passes
  `-notimestamp`, so no page carries the date it was generated and a rebuild of the same sources gives the same
  files.

`./gradlew javadoc` runs every project's `javadoc` task, `benchmarks` included, and the site: an unqualified task
name runs the task of that name in every project. `./gradlew :javadoc` builds the site alone. The site is
published to [GitHub Pages](https://brantunger.github.io/unruly-engine/latest/) on each release, as
[RELEASING.md](../../RELEASING.md#-the-javadoc-site) describes.
