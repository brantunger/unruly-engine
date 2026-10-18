# 🐕 Windows CI hangs

Why the `windows-latest` Build & Test step runs under a watchdog, what the watchdog writes to `build/reports/hang/`
when the build runs long, and how to read those files.

**Who it's for:** a contributor whose Windows CI job failed at 15 to 17 minutes, or timed out at 20.
**You'll be able to:** find the thread dumps in the job's artifact, tell a stuck thread from a slow one, and tell a
JVM hang from a stalled runner.
**Before you start:** [Build and gates](build-and-gates.md#-what-build-runs), for the test timeouts the watchdog
backs up, and [its CI section](build-and-gates.md#-ci) for the jobs and artifacts.

[← Documentation index](../README.md)

- [Why a watchdog](#-why-a-watchdog)
- [What happens at 14 minutes](#-what-happens-at-14-minutes)
- [Reading the two rounds](#-reading-the-two-rounds)
- [Questions you might not think to ask](#-questions-you-might-not-think-to-ask)
- [Reference](#-reference)

---

## 🧭 Why a watchdog

Three `windows-latest` JDK 21 jobs (2026-10-14, and twice on 2026-10-17) sat in Build & Test until the `build` job's
20-minute `timeout-minutes`. GitHub then couldn't end the step within its five-minute grace, so each job was cancelled
at 25 minutes with no log kept and no upload step run: nothing said what had hung.

A healthy Windows step takes 6 to 12.4 minutes (96 jobs). The Gradle `test` timeout of 10 minutes and the classes'
`@Timeout`s would have failed a plain test hang, with logs, by about 15 minutes, so something those timeouts can't
reach was holding the step.

So on `windows-latest`, and only there, `.github/workflows/ci.yml` runs Build & Test with
`.github/scripts/windows-hang-dump.ps1` started beside `./gradlew`, and stops it when `gradlew` returns. The step
also carries `timeout-minutes: 17` as a second net: if the watchdog can't end the step, the runner fails it at 17
minutes and still runs the upload steps. `ubuntu-latest` and `macos-latest`, which never stalled, run the plain step.

```mermaid
flowchart TD
    A["Build & Test on windows-latest"] -- "gradlew returns before 14 min" --> B["Watchdog stopped: no hang/"]
    A -- "still running at 14 min" --> C["Dumps, again a minute later, then kill: 15 to 16:35"]
    C -- "gradlew returns on its own first" --> G["Step passes; artifact holds the dumps so far"]
    C -- "gradlew returns after the kill" --> D["Step fails; artifact holds hang/"]
    A -- "step still running at 17 min" --> E["Runner fails the step; uploads run"]
    A -- "job reaches 20 min, nothing uploaded" --> F["Runner or VM stalled"]
```

The step ends one of five ways, as the diagram shows. A normal return before 14 minutes leaves no `hang/`. A stall
gets two rounds of dumps and a kill, at 15 to 16 and a half minutes, after which the step fails and uploads them. A
`gradlew` that returns on its own between 14 minutes and the kill stops the watchdog, so the step passes and its
artifact holds the files written so far.

A watchdog that can't end the step is overtaken by the 17-minute step timeout, which still uploads. A job that
reaches 20 minutes with nothing uploaded had no JVM to blame.

## ⏳ What happens at 14 minutes

The watchdog sleeps 14 minutes. If `gradlew` returned sooner, the step stopped it, and nothing was written. Otherwise
it writes a first round of files into `build/reports/hang/`.

`first-processes.txt` holds a timestamp, how many `java` processes it found, the free and total physical memory in
MB, and the 25 largest processes on the runner by working set, with their CPU seconds and start times. A stalled
runner shows here, in the memory line and the process list, when no JVM is to blame.

For every `java` process, the Gradle client and daemon, the test workers and any child JVM a test launched, it
writes `first-jvm-<pid>-command.txt`, the output of `jcmd <pid> VM.command_line`, whose `java_command` line says
which JVM it is, and `first-jvm-<pid>-threads.txt`, the output of `jcmd <pid> Thread.print -l`: a thread dump with
the locks each thread holds and waits for.

Every `jcmd` of a round starts at once, with one 45-second deadline for them all, so a round costs at most 45 seconds
however many JVMs there are. A `jcmd` still running at the deadline is killed, and its file ends with
`jcmd didn't answer in 45 s`: a JVM that can't serve an attach request, which is a finding in itself. Each `jcmd`'s
standard error goes to the file's `.err` twin, which is empty when all went well.

Sixty seconds after the first round ends, a `second-` set of the same files is written. Then the watchdog kills
every `java` process it finds, with its process tree, so `gradlew` returns a failure and the step fails on its own.
A JVM it couldn't kill is noted in `second-processes.txt`, and the file ends with `killed the JVMs`.

A round ends as soon as every `jcmd` has answered, so the kill comes at about 15 minutes into the step when they all
answer at once, and by about 16 minutes 35 seconds at the latest, before the step's 17-minute timeout.

The `Upload Coverage Report` step runs with `if: always()` on the JDK 21 legs, so the files go up in the artifact
`jacoco-report-jdk21-windows-latest`, under `build/reports/hang/`, with the JUnit XML of every test task that
finished before the kill, and the JaCoCo report only if the build had got that far.

> [!NOTE]
> The test task running when the JVMs are killed writes no JUnit XML: Gradle writes it when the task ends, and
> the task never does. The dumps are the record of that task.

## 🧵 Reading the two rounds

Download `jacoco-report-jdk21-windows-latest` from the failed run's summary page and open `build/reports/hang/`.

### Find the JVM

Open each `first-jvm-<pid>-command.txt`. Its `java_command` line names the Gradle daemon
(`org.gradle.launcher.daemon.bootstrap.GradleDaemon`), a test worker
(`worker.org.gradle.process.internal.worker.GradleWorkerMain`), the wrapper client (`gradle-wrapper.jar build
jacocoTestReport -PapiCheck.refresh`: a JVM started with `-jar` shows its jar, not a class), a child JVM a test
launched, or, when `buildSrc` was compiled rather than restored from the build cache, the Kotlin compile daemon that
built it (`org.jetbrains.kotlin.daemon.KotlinCompileDaemon`), idle since.

Start with a test worker: its `Test worker` thread runs the test, or waits on the thread JUnit runs a `@Timeout`
test's body on ([why](build-and-gates.md#-what-build-runs)), so the stuck frames may be on that thread instead. A
child JVM the test is waiting on has its own dump, by its own pid.

### Compare the two dumps of that JVM

A thread whose top frames are the same in `first-jvm-<pid>-threads.txt` and `second-jvm-<pid>-threads.txt`, 60
seconds apart, is stuck there. One whose frames moved is slow, not stuck: look at what it was doing, and at the
process list for memory pressure.

### Follow the locks

`Thread.print -l` lists, under each thread, the locks it holds (`- locked`) and the one it waits for
(`- waiting to lock`, `- parking to wait for`). Two threads each waiting for the other's lock is a deadlock; the
dump ends with a `Found one Java-level deadlock` section when the JVM detects one.

> [!TIP]
> To try the watchdog on your machine, start a JVM that sleeps, then run it against that pid alone, so it leaves
> your other JVMs alone: `pwsh -File .github/scripts/windows-hang-dump.ps1 -AfterMinutes 0.05 -AgainAfterSeconds 5
> -ProcessId <pid> -Out build/reports/hang`. It kills the JVM when it's done. Give it one pid that way: through
> `pwsh -File`, `-ProcessId 1234,5678` binds as the single pid `12345678`, so it finds no JVM and writes `0 JVM(s)`.
> Several pids bind only in-process: `& .github/scripts/windows-hang-dump.ps1 -ProcessId 1234,5678 ...`.

## ❓ Questions you might not think to ask

### The artifact has no `hang/` directory. Was the watchdog broken?

No: the step ended before 14 minutes, so the watchdog was stopped before it wrote anything, and the upload skips a
path that doesn't exist. A `windows-latest` job that failed in under 14 minutes failed for the reason in its log.

### `first-processes.txt` says `0 JVM(s)`. What then?

The build was still running at 14 minutes but no `java` process existed, so nothing of Gradle's was left to dump.
Read the process list and memory line in that file: the runner itself is the suspect.

### The job hit 20 minutes again and uploaded nothing. Didn't the watchdog run?

Then neither the watchdog at 14 minutes nor the step timeout at 17 could act: the runner, or the VM under it, had
stalled. The diagnosis that led to the watchdog named memory pressure as the plausible, unmeasured cause: nothing
in the repository sets `maxHeapSize` on the test tasks, `maxParallelForks` or `org.gradle.jvmargs`. Capping those
on Windows is the next step.

### Does the watchdog run on pull requests, or only on `main`?

On every `windows-latest` JDK 21 Build & Test step, so on both. A docs-only pull request skips the build, and a
release pull request runs only the `ubuntu-latest` leg, so neither runs it.

### Can the watchdog fail a healthy build?

Only one still running when the kill comes: about 15 minutes in when every `jcmd` answers at once, 16 and a half
at the latest. A step that finishes between 14 minutes and then passes, and its artifact holds the rounds written
before `gradlew` returned: read them. The longest successful Windows step was 12.4 minutes, so a step that slows
past 14 is a change worth a dump of its own.

## 📋 Reference

The watchdog's parameters, in `.github/scripts/windows-hang-dump.ps1`; CI passes `-AfterMinutes 14` and the defaults
for the rest:

| Parameter | Default | Meaning |
| --- | --- | --- |
| `-AfterMinutes` | `14` | Minutes to wait before the first round; a fraction is allowed |
| `-AgainAfterSeconds` | `60` | Seconds between the first round's end and the second |
| `-Out` | `build/reports/hang` | Where the files go; created if missing |
| `-ProcessId` | none | Dump and kill only these pids, for a trial on a machine with other JVMs; one pid through `pwsh -File`, several only in-process |

The files of a round, `first-` or `second-`:

| File | Holds |
| --- | --- |
| `<round>-processes.txt` | Timestamp, JVM count, free and total memory, the 25 largest processes; `second-` ends with `killed the JVMs` |
| `<round>-jvm-<pid>-command.txt` | `jcmd <pid> VM.command_line`: the JVM's arguments and `java_command` |
| `<round>-jvm-<pid>-threads.txt` | `jcmd <pid> Thread.print -l`: every thread's stack and locks, or `jcmd didn't answer in 45 s` |
| `*.err` | The `jcmd` call's standard error; empty when it answered |

The timings: watchdog at 14 minutes, each round at most 45 seconds, 60 seconds between rounds, kill at 15 to 16:35,
step timeout 17 minutes, job timeout 20 minutes.
