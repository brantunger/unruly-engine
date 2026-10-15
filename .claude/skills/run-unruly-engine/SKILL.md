---
name: run-unruly-engine
description: Build, run, smoke-test and test the unruly-engine rules engine from source. Use when asked to run the engine, try a rule, load or run rules against the freshly built jars, run a Java snippet or scenario on a chosen JDK with chosen JVM flags (-Xss, -Xcomp) or SLF4J binding, build the jars, run the tests or a single test class, or confirm a core/mvel change works for real.
---

unruly-engine is a Java library (Gradle multi-module: `core`, `mvel`, `test-kit`, `benchmarks`, `bom`,
`native-smoke`), so "running it" means building the jars and calling the engine from code. Drive it via
`.claude/skills/run-unruly-engine/run.sh`: it builds the jars, then runs a single-file Java program against them with
the JDK, JVM flags and SLF4J binding you choose. The default program, `Smoke.java` next to it, loads and runs a small
MVEL rule set through the public API and prints one status line.

All paths are relative to the repo root. Commands are Git Bash unless marked PowerShell.

**Changed `core` or `mvel` internals?** The main check is a single-file Java program run against the installed jars:
`run.sh path/To/Snippet.java` *without* `--skip-build`, so the changed jars are rebuilt first (see
[Run](#run-agent-path)). The unit tests come second (see [Test](#test)).

## Prerequisites

Windows 11 with these JDKs installed (the jars are compiled for Java 21, so only 21+ can run them):

| JDK | Path |
|---|---|
| 26.0.1 (Oracle, `JAVA_HOME`, runs Gradle) | `C:/Program Files/Java/jdk-26.0.1` |
| 24 (Oracle) | `C:/Program Files/Java/jdk-24` |
| 21 (Oracle) | `C:/Program Files/Java/jdk-21` |
| 21 (Microsoft) | `~/.jdks/ms-21.0.7` (also installed, untried here: `~/.jdks/openjdk-21.0.2`) |
| 17 (Corretto) and 11 (Microsoft): can't run the jars | `~/.jdks/corretto-17.0.16`, `C:/Program Files/Microsoft/jdk-11.0.27.6-hotspot` |

Gradle compiles with the Java 21 toolchain it finds installed (no auto-download). To see which JDKs it detects:

```bash
./gradlew -q javaToolchains
```

## Build

Builds `core` and `mvel` and copies every runtime jar (`unruly-engine-core`, `unruly-engine`, `mvel2`, `slf4j-api`,
`slf4j-simple`, `jspecify`, and the `native-smoke` application's own jar) into one directory,
`native-smoke/build/install/native-smoke/lib/`. That directory is the class path everything below uses, so no build
file changes and nothing is looked up in Gradle's cache. The jars carry the version from
`gradle.properties` (for example `unruly-engine-2.30.12.jar`).

```bash
./gradlew :native-smoke:installDist
```

Expect `BUILD SUCCESSFUL` in a few seconds when the compile outputs come from the build cache; longer on a cold cache.

## Run (agent path)

The driver, with its defaults: builds, then runs `Smoke.java` on `JAVA_HOME` with `slf4j-simple` on the class path.

```bash
.claude/skills/run-unruly-engine/run.sh
# -> smoke OK: rate=prime noMatch=null tags=[high, ok] java=26.0.1+8-34 (Oracle Corporation) slf4j=SimpleLoggerFactory
```

The line reports what the engine returned, which JDK ran it and which SLF4J factory it found, so you can read a
flag's effect off the output instead of trusting it was forwarded. Exit code is 0 when every result is right, else 1.

Options (all optional; the first non-option argument is the program, the rest are its arguments):

| option | what it does |
|---|---|
| `--jdk <dir>` | JAVA_HOME of the JDK to run on (default `$JAVA_HOME`). Gradle still runs on `JAVA_HOME`. |
| `--slf4j simple\|none` | `slf4j-simple` on the class path (default), or no binding: SLF4J's NOP logger, with its three `SLF4J(W)` warning lines on stderr. |
| `-J<flag>` | One JVM flag, repeatable: `-J-Xss512k`, `-J-Xcomp`, `-J-Dorg.slf4j.simpleLogger.defaultLogLevel=debug`. |
| `--skip-build` | Don't run Gradle; use the jars from the last build. |
| `Snippet.java [args...]` | A single-file Java program run with the source launcher (`java File.java`), default `Smoke.java`. |

Chosen JDK, no SLF4J binding:

```bash
.claude/skills/run-unruly-engine/run.sh --skip-build --jdk "C:/Program Files/Java/jdk-21" --slf4j none
# -> SLF4J(W): No SLF4J providers were found. (and two more SLF4J(W) lines)
# -> smoke OK: rate=prime noMatch=null tags=[high, ok] java=21.0.7+8-LTS-245 (Oracle Corporation) slf4j=NOPLoggerFactory
```

JVM flags: a small stack with slf4j-simple at DEBUG, and `-Xcomp` on JDK 24 (about 17 s instead of 1 s). The
engine logs at DEBUG through `LoggingRuleListener` and `io.github.brantunger.unruly.engine`, so with `Smoke.java`,
which registers no listener, the DEBUG setting adds no lines; a scenario with `.listener(new LoggingRuleListener())`
is where it shows.

```bash
.claude/skills/run-unruly-engine/run.sh --skip-build -J-Xss512k -J-Dorg.slf4j.simpleLogger.defaultLogLevel=debug
.claude/skills/run-unruly-engine/run.sh --skip-build --jdk "C:/Program Files/Java/jdk-24" -J-Xcomp -J-Xss1m
```

Program arguments: `Smoke.java` takes a credit score (default 780). A score the `prime` rule doesn't match makes it
report `FAILED` and exit 1, which is how to see the exit code path work:

```bash
.claude/skills/run-unruly-engine/run.sh --skip-build .claude/skills/run-unruly-engine/Smoke.java 700
# -> smoke FAILED: rate=standard noMatch=null tags=[ok] ... ; exit code 1
```

An arbitrary scenario: write any single-file program that uses the public API (`io.github.brantunger.unruly.api`)
and pass its path. `build/` at the repo root is git-ignored, so it is a good place for a throwaway one:

```bash
mkdir -p build && cat > build/Scenario.java <<'EOF'
import io.github.brantunger.unruly.api.*;
import java.util.*;

public class Scenario {
    public static void main(String[] args) {
        try (RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new).build()) {
            engine.load(List.of(Rule.builder().ruleName("double").condition("x > 1").action("output.put('y', x * 2)").build()));
            FactStore<Object> facts = new FactMap<>();
            facts.setValue("x", 21);
            System.out.println(engine.run(facts));
        }
    }
}
EOF
.claude/skills/run-unruly-engine/run.sh --skip-build build/Scenario.java
# -> {y=42}
```

Without the driver, the same thing is one `java` call with the wildcard class path; this form works in Git Bash and
PowerShell alike (quote the `*` so the shell leaves it to `java`):

```bash
java -cp 'native-smoke/build/install/native-smoke/lib/*' .claude/skills/run-unruly-engine/Smoke.java
```

```powershell
java -cp 'native-smoke/build/install/native-smoke/lib/*' .claude/skills/run-unruly-engine/Smoke.java
```

The exhaustive smoke: the `native-smoke` application (`native-smoke/src/main/java/com/example/nativesmoke/Main.java`)
is what CI builds into a GraalVM native image. Its start script runs the same code on the JVM: 200-run bean and map
engines, the fact-name check, reflection on non-public classes and a run timeout with `onCancel`. It prints two
`ERROR` lines and one `WARN` line on purpose (the `Date` fact-name rejection and the deadline) before its status
line, and exits 1 if a result is wrong. It always runs on `JAVA_HOME` with `slf4j-simple`.

```bash
native-smoke/build/install/native-smoke/bin/native-smoke
# -> native smoke OK: bean=prime map=standard,raised factName=rejected,prime hidden=prime,gold,standard deadline=timeout,platform dateResource=true dateFactValue=rejected image=false jit=off
```

## Test

The full gate is `./gradlew clean build jacocoTestReport` (CI's JDK 21 leg runs `./gradlew build jacocoTestReport
"-PapiCheck.refresh"`): every module's tests, Checkstyle, PMD, Javadoc with `-Werror`, the 100%
instruction-and-branch JaCoCo gate and the API check. It takes minutes, so run it only when you
mean to. One test class of one module, which is what to run while iterating:

```bash
./gradlew :core:test --tests io.github.brantunger.unruly.api.FactMapTest
# -> BUILD SUCCESSFUL; results in core/build/test-results/test/, one XML per nested class
#    (TEST-io.github.brantunger.unruly.api.FactMapTest$GetSetValue.xml and so on)
```

Tests run on the JDK given by `-PtestJdk` (default 21; the build compiles with 21 regardless):

```bash
./gradlew :core:test --tests io.github.brantunger.unruly.api.FactMapTest -PtestJdk=24
```

When the output says `1 from cache` or `up-to-date`, no test ran: Gradle reused an earlier result. Add `--rerun` to
run the test task again.

The mvel module's tests are `:mvel:test`; the test classes live in
`mvel/src/test/java/io/github/brantunger/unruly/mvel/`.

## Gotchas

**Never `./gradlew --stop`.** The Gradle daemons are shared with other worktrees and agents on this machine; stopping
them cuts off whatever another build is doing. Leave the daemons alone.

**`--skip-build` runs the last build's jars.** After a change to `core` or `mvel`, run once without it (or run
`./gradlew :native-smoke:installDist`), or the program runs against the old code.

**`--jdk` must point at a JDK 21 or later.** The jars are class-file version 65 (Java 21); the source launcher's
javac on an older JDK can't read them (see Troubleshooting). JDK 11 and 17 only serve the `java File.java` launcher
for other projects.

**`--slf4j none` is noisy by design.** SLF4J 2 prints three `SLF4J(W): No SLF4J providers were found...` lines to
stderr and falls back to `NOPLoggerFactory`; the status line's `slf4j=NOPLoggerFactory` confirms the binding was
really left out.

**`-Xcomp` is slow.** Compiling every method before it runs took the smoke from about 1 s to 17 s on JDK 24. Use it
for JIT-related checks only.

**`native-smoke` prints ERROR and WARN lines on a green run.** `'Date' cannot be used as a fact name` (twice) and
`run() passed its deadline` are the checks working; only the last line, `native smoke OK` or `FAILED`, and the
exit code are the verdict.

**The driver converts paths for Windows `java`.** It runs `cygpath -m` on the JDK, class path and program paths, so
Git Bash's `/c/...` form never reaches `java.exe`; when writing your own `java` line, use a repo-relative path or
a `C:/...` path, and quote the `*` in the class path.

## Troubleshooting

- **`error: cannot access FactMap ... class file has wrong version 65.0, should be 61.0`** (16 errors, then
  `compilation failed`): the program was run on JDK 17; JDK 11 says `should be 55.0`. Pass
  `--jdk "C:/Program Files/Java/jdk-21"` or another JDK 21+.
- **`smoke FAILED: ...`, exit 1**: a result is not what `Smoke.java` expects. The line shows what the engine returned
  (`rate=`, `noMatch=`, `tags=`); a wrong `rate` with a score argument below 750 is expected, anything else with the
  defaults means the engine's behaviour changed.
