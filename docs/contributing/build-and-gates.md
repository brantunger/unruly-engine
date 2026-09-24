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
release alike (`org.gradle.warning.mode=fail` in `gradle.properties`). The build has six projects. Four are
published: `core` (`unruly-engine-core`, whose tests need no MVEL), `mvel` (`unruly-engine`, which holds the tests
that need MVEL or the test kit, and those that check the whole build), `test-kit` (`unruly-engine-test`), and `bom`
(`unruly-engine-bom`, a [BOM](../glossary.md#artifacts) pinning the other three; its `checkBomCoverage` fails unless
it lists exactly the published artifacts).

The other two aren't: `benchmarks`, whose sources the build checks and whose generated workload one test asserts the
shape of, while only the `jmh` task measures anything, and `native-smoke`, a small application CI builds into a
GraalVM native image.

| Gate | Checks | Configured in |
| --- | --- | --- |
| 🧪 **Tests** | The JUnit suite, on the class path, in three source sets, plus the benchmarks' workload test | `core/src/test`, `mvel/src/test`, `test-kit/src/test`, `benchmarks/src/test`, their `build.gradle` files, and `core/src/testFixtures/resources/junit-platform.properties` |
| 📏 **Checkstyle** | Main and test sources: UTF-8, lines of at most 120 columns, no tabs, a final newline, `AvoidStarImport`, `UnusedImports`, `NeedBraces`, `LeftCurly`, `RightCurly`, `EmptyBlock` | `config/checkstyle/checkstyle.xml` |
| 🔍 **PMD** | Main sources only, by decision, with the best-practices, error-prone and multithreading rule sets | `config/pmd/ruleset.xml`, applied by `buildSrc/src/main/groovy/unruly.java-conventions.gradle` |
| ⚠️ **Warnings** | No javac warning (`-Xlint:all -Werror`) in the jar projects, and no Javadoc warning (`-Xdoclint:all -Werror`) | `buildSrc/src/main/groovy/unruly.java-conventions.gradle` |
| 📊 **JaCoCo** | **100%** instruction *and* branch coverage of the published artifacts' main sources | `build.gradle` |
| 🧬 **API compatibility** | No incompatible change to a public or protected member since the latest release, nor to the `core` constructors the test kit calls | `buildSrc/src/main/groovy/unruly.library.gradle`, `config/japicmp/accepted-breaks.txt`, `config/japicmp/test-kit-linkage.txt` |
| 🧭 **Module path** | `ModulePathTest` compiles four applications against the built jars and runs each on the module path | `mvel/src/test/resources/module-path` |
| 🧱 **Design rules** | Package dependencies, the API's shape, sealed contexts, nullness annotations, engine visibility, class-file version, the test kit's links into `core` | The structural tests below |

Some details behind the table:

- **Checkstyle's** `AvoidStarImport` allows static member imports, so `import static ...Assertions.*` is fine.
- **PMD** excludes `GuardLogStatement` from best-practices, and `AvoidCatchingGenericException` from error-prone,
  because the engine catches whatever a language, listener or setter throws and turns it into a failure.
- **Coverage** excludes `io/github/brantunger/unruly/test/ExpressionLanguageContractTest*`: its always-throwing
  lambdas leave instructions JaCoCo can't reach. `-x test` fails the gate with `No JaCoCo execution data`;
  `./gradlew build -x check` only compiles and packages.
- **The module-path applications** are `withMvel`, `withoutMvel`, `withTestKit` and `withJackson`, each compiled
  with `-Xlint:all -Werror` like the main sources.

**Checkstyle's** `LineLength` fails a line over 120 columns in any main, test or test-fixtures source, except a
`package` or `import` line. The applications under `mvel/src/test/resources/module-path` are test resources, which
Checkstyle doesn't read. `FileTabCharacter` fails a tab, and `NewlineAtEndOfFile` a file without a final newline.
Checkstyle reads every file as UTF-8.

**PMD's multithreading** rule set runs without five of its rules. `config/pmd/ruleset.xml` says why next to each:

| Excluded rule | Why |
| --- | --- |
| `DoNotUseThreads` | A run reads its thread's interrupt flag, sets it again after catching an `InterruptedException`, and reads whether the thread is virtual; the contract kit runs a language on a thread pool |
| `UseConcurrentHashMap` | Each flagged map is used by one thread; or built before another thread sees it and only read after; or is an MVEL session's compiled expressions, which one run at a time uses, handed on through a concurrent queue |
| `AvoidUsingVolatile` | The engine's and a rule list's shared fields are written by `load()`, `close()` or the run that finds no language keeps state, and read by every run without a lock |
| `AvoidSynchronizedStatement` | Only `load()` and `close()` lock the engine's private monitor, so runs never wait on it or pin their virtual threads |
| `AvoidSynchronizedAtMethodLevel` | No method is synchronized today; excluded with `AvoidSynchronizedStatement`, because what matters is whether runs wait on the monitor |

**The build never runs PMD on test sources, by decision.** On them this rule set finds 2,885 violations (measured at
`0367bb2`), 2,576 of them an assertion without a message, a test with several assertions, or a resource a test doesn't
close. The seven multithreading rules it keeps find none. The decision is recorded in
`buildSrc/src/main/groovy/unruly.java-conventions.gradle`, and `check` runs `pmdMain` only.

Each `test` task gives up after 10 minutes (`timeout` in `buildSrc/src/main/groovy/unruly.java-conventions.gradle`),
so a test that never returns fails the build rather than holding CI until the `build` job's own 20-minute cap. Gradle
logs `Requesting stop of task ':core:test' as it has exceeded its configured timeout`, stops the test worker, and
fails the task with `Timeout has been exceeded`. A task stopped that way writes no JUnit XML, so read the HTML
report, where the test that never returned shows as skipped.

A test class that waits on threads, latches or deadlines carries a class-level `@Timeout`. The test fixtures'
`junit-platform.properties`, which the tests of `core`, `mvel` and `test-kit` share, sets
`junit.jupiter.execution.timeout.thread.mode.default = separate_thread`, so JUnit runs the test body on a thread of its
own and aborts it at the deadline, instead of reporting the deadline once the test returns.

`RuleSetTest` opts back out, with `threadMode = SAME_THREAD`, because `@BeforeEach` and `@AfterEach` stay on the
`Test worker` thread whatever the mode, and its `@AfterEach` checks that the test left no run counted on the thread it
ran on. One method in it asks for `SEPARATE_THREAD` by name, to test a wait that has no deadline.

The `benchmarks` project's main sources are the exception to the javac gate: they compile JMH's generated code, so
`compileJava` runs with `-Xlint:none` instead (`benchmarks/build.gradle`). Its test sources are hand-written, so
`compileTestJava` keeps `-Xlint:all -Werror` like everything else.

The design rules are ordinary JUnit tests, under `java/io/github/brantunger/unruly/` in the source set named:

| Test | Source set | Protects |
| --- | --- | --- |
| `PackageDependencyTest` | `mvel/src/test` | Each package uses only the packages listed for it, and only `mvel` uses the MVEL library |
| `api/EngineApiShapeTest` | `core/src/test` | An engine is configured once, on a builder; `RulesEngine` and `RuleListener` have exactly the documented methods |
| `api/language/SealedContextsTest` | `core/src/test` | Only the engine implements the contexts it passes to a language |
| `api/NullnessAnnotationsTest` | `mvel/src/test` | The public API declares its nullness with JSpecify, and the internal `core` package isn't `@NullMarked` |
| `core/EngineVisibilityTest` | `core/src/test` | The engine classes aren't public and can only be created through `RulesEngineBuilder` |
| `ClassFileVersionTest` | `mvel/src/test` | The published classes are compiled for Java 21 |
| `ModulePathTest` | `mvel/src/test` | The module declarations work where they take effect: on the module path, in a new JVM |
| `test/TestKitLinkageTest` | `test-kit/src/test` | `config/japicmp/test-kit-linkage.txt` lists exactly the `core` members the test kit's classes use |
| `TestJdkTest` | `mvel/src/test` | The tests really ran on the JDK `-PtestJdk` asked for |

## 📒 Reports

| Task | Report |
| --- | --- |
| `:core:test`, `:mvel:test`, `:test-kit:test`, `:benchmarks:test` | `<project>/build/reports/tests/test/index.html`, for `core`, `mvel`, `test-kit` and `benchmarks` |
| `jacocoTestReport`, `jacocoTestCoverageVerification` | `build/reports/jacoco/html/index.html`, the aggregate of every artifact; `build/reports/jacoco/report.xml` for tools |
| `checkstyleMain`, `checkstyleTest`, `checkstyleTestFixtures` | `<project>/build/reports/checkstyle/main.html` and `test.html`, and `core`'s `testFixtures.html`, with `.xml` twins |
| `pmdMain` | `<project>/build/reports/pmd/main.html`, with an `.xml` twin |
| `japicmp` | `<project>/build/reports/japicmp/report.html` and `report.txt`, for `core`, `mvel` and `test-kit` |
| `:core:japicmpTestKitLinkage` | `core/build/reports/japicmp/test-kit-linkage.html` and `test-kit-linkage.txt` |
| `javadoc` | `build/docs/javadoc/index.html` for the site; the console for the warnings that failed it |
| `cyclonedxDirectBom`, which `assemble` runs | `<project>/build/reports/cyclonedx-direct/<artifact>-<version>.cdx.json`, the SBOM a release attaches, for `core`, `mvel` and `test-kit` |

`core`, `mvel` and `test-kit` each write a japicmp report, and `core` a second one for the constructors the test
kit calls; [API compatibility](api-compatibility.md#-baselines) explains which release each one is compared with.
`jacocoTestCoverageVerification` fails on the console; the HTML report from `jacocoTestReport` shows the uncovered
lines and branches.

## 💾 The build cache and the configuration cache

Both are on by default (`org.gradle.caching=true` and `org.gradle.configuration-cache=true` in `gradle.properties`),
so a second build reuses task outputs, including those of another branch, and the configured task graph.

- `--rerun` on a task, such as `./gradlew :mvel:test --rerun`, runs it again even though its inputs haven't changed.
- `--no-build-cache --no-configuration-cache` rebuilds everything from scratch, when a result looks stale.
- The release workflow builds with `--no-build-cache`, so the published artifacts are built from scratch.
- Locally, the API baseline lookup is cached 24 hours for configuration cache reuse; `"-PapiCheck.refresh"`, which CI
  passes, skips the cache.

## 🤖 CI

`.github/workflows/ci.yml` runs on every pull request and push to `main`:

| Job | Runs | Why |
| --- | --- | --- |
| JDK 21 on `ubuntu-latest`, `windows-latest` and `macos-latest` | `./gradlew build jacocoTestReport "-PapiCheck.refresh"`; on `ubuntu-latest`, `setup-gradle` also generates the dependency graph, without submitting it | The module-path applications and the child JVMs depend on the OS; Windows and macOS file systems are case-insensitive, so the tests that look a compiled class up in another case run there instead of being skipped. Generating the graph resolves the dependency-graph plugin with verification on, so a stale [pin](dependency-verification.md#-the-dependency-graph-plugin) fails the pull request |
| JDK 25 on `ubuntu-latest` | `./gradlew :core:test :mvel:test :test-kit:test -PtestJdk=25` | Compilation stays on the Java 21 toolchain; only the tests need the newer JDK |
| `native-image` on `ubuntu-latest`, GraalVM CE 21.0.2 | `./gradlew :native-smoke:installDist`, then `native-image` and the binary | The engine and MVEL work in a native image with only the metadata the jar ships and the application's own; see [Native image](../native-image.md) |
| `docs-and-hygiene` on `ubuntu-latest` | `docs/scripts/check_docs.py`, a line-ending check, `docs/scripts/check_style.py` on the pages a pull request changes, and actionlint | Broken links and anchors, joined table rows, files stored with CRLF, the [style guide](style.md)'s mechanical rules, and mistakes in the workflows and in the shell of their `run` blocks, which actionlint checks with the runner's shellcheck |
| `dependency-graph` on `ubuntu-latest`, on pushes to `main` only | `gradle/actions/dependency-submission`, which resolves every configuration and submits the graph | Dependabot alerts then cover transitive dependencies too. The action turns dependency verification off, so this job checks nothing |
| `ci-result` | Nothing | Fails when `build`, `native-image` or `docs-and-hygiene` failed or was cancelled; a skipped job counts as passed, and `changes` isn't judged. It's the one check branch protection can require, because a skipped matrix job doesn't report its per-OS checks |

A pull request that changes only documentation skips the build matrix and `native-image`. That means every changed
file is under `docs/` or ends in `.md`, and none is under a `src/` directory. The `changes` job decides that, on
pull requests only. When it fails, the build and `native-image` run anyway, and a push never skips them.

A new push to a pull request cancels the run it supersedes. A run on `main` is never cancelled once it has started:
the next push to `main` waits for it, so a run that starts isn't cut off before its cache save and its coverage
upload. It isn't a queue, though. GitHub keeps only one waiting run per branch, so a push to `main` that lands while
an earlier one is still waiting replaces it, and the replaced commit gets a cancelled run and no build of its own.

`docs-and-hygiene` only warns for now: a failed step shows as an annotation, and the job stays green. Later, its
checks will block. A page written before the style guide may have findings in lines you didn't touch.
`check_style.py` also reports a page over 2,500 words of prose that `docs/scripts/long-pages.txt` doesn't list, and a
listed page that grew past its number: split the page, and lower its number when it shrinks. Run the checks before
you push, from the repository root, with Python 3 and [actionlint](https://github.com/rhysd/actionlint):

```bash
python docs/scripts/check_docs.py
python docs/scripts/check_style.py docs/facts.md   # the pages you changed
git ls-files --eol | grep -E '^i/(crlf|mixed)'   # lists files stored with CRLF; prints nothing when all is well
actionlint                                        # needs shellcheck on the PATH, or it silently skips the shell checks
```

CI downloads a pinned actionlint release, not an action, so Dependabot doesn't update it. To bump it, change
`ACTIONLINT_VERSION` in `ci.yml`, and set `ACTIONLINT_SHA256` to the hash on the `linux_amd64` line of that release's
`actionlint_<version>_checksums.txt`.

A second workflow, `pr-title.yml`, checks a pull request's title against Conventional Commits, because the title
becomes the release commit. It runs on pull requests only. On one labelled `dependencies`, which Dependabot opens
with a capitalised subject, it skips the format check, but it still rejects a `!` on any type but `feat` and `fix`.

What the jobs leave behind:

- **Artifacts:** `jacoco-report-jdk21-<os>` from every JDK 21 job, always, with that job's JUnit results beside the
  coverage; `api-compatibility-report-jdk21-<os>` only when a job fails.
- **Codecov** gets one upload per run, from the Linux JDK 21 job, authenticated with OIDC. Runs from a fork or
  Dependabot skip it: GitHub gives them no OIDC token.
- **Gradle caches:** runs on `main` save the dependency and build caches, one per OS; pull requests only read them,
  as does the release workflow's `publish` job. The configuration cache isn't saved: that needs an
  encryption key.

No branch protection requires a check; maintainers merge when CI and the title check are green.

## 🔢 Running the tests on JDK 25

Compilation always uses the Java 21 toolchain. `-PtestJdk=25` runs the test task on a JDK 25 launcher instead, so
the JDK must be installed: Gradle doesn't download toolchains (`org.gradle.java.installations.auto-download=false`),
and a missing one fails the build naming the version. The build passes `unruly.test.jdk` to the tests, and
`TestJdkTest` fails if the JVM running them doesn't match.

```bash
./gradlew :core:test :mvel:test :test-kit:test -PtestJdk=25
```

## 🔕 PMD suppressions

PMD runs on the main sources of every project, and never on tests ([why](#-what-build-runs)). A finding is fixed,
unless the code is right and the rule is wrong for it; then the suppression is as narrow as PMD allows, and a comment
on the line above says why. That is the shape every existing suppression has. For example,
`core/src/main/java/io/github/brantunger/unruly/core/RuleSet.java`:

```java
/** Whether every language of these rules returned {@link Session#none()}, so a copy holds nothing of its own. */
// Session.none() is one shared instance, and identity is the question: a session that merely equals it still
// belongs to one run at a time.
@SuppressWarnings("PMD.CompareObjectsWithEquals")
```

The annotation names one rule with its `PMD.` prefix and sits on the smallest scope that has the finding: a method
or field, or, for `Rule.RuleBuilder`, the class whose every setter triggers `AvoidFieldNameMatchingMethodName`.
Excluding a rule for every project is done once, in `config/pmd/ruleset.xml`, with a comment, as
`AvoidCatchingGenericException` is.

## ☕ Javadoc and doclint

Every Javadoc task runs with `-Xdoclint:all -Werror`, so a missing comment, `@param`, `@return` or `@throws`, a
broken `{@link}` or bad HTML fails the build. `check` depends on the Javadoc in two places:

- Each jar project's `javadoc` task documents its own module. Its output is what the project's `-javadoc.jar`
  packages at publish time.
- The root project's `javadoc` task builds one site for all the modules in `build/docs/javadoc`. It hands the
  javadoc tool the modules rather than source files, so it documents only the packages they export, and passes
  `-notimestamp`, so no page carries the date it was generated and a rebuild of the same sources gives the same
  files.

`./gradlew javadoc` runs every project's `javadoc` task, `benchmarks` included, and the site: an unqualified task
name runs the task of that name in every project. `./gradlew :javadoc` builds the site alone. The site is
published to [GitHub Pages](https://brantunger.github.io/unruly-engine/latest/) on each release, as
[RELEASING.md](../../RELEASING.md#-the-javadoc-site) describes.
