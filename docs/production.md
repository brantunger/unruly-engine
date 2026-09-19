# 🏭 Before you go to production

> [!NOTE]
> Describes 2.0.0, which isn't released yet. This page has no 1.8.0 version.

A checklist for taking rules to production. Each item is one sentence and one link to the guide that owns it; this
page states no rules of its own.

**Who it's for:** application developers.
**You'll be able to:** check an application's engine, rules, failure handling, logging and shutdown against the
guides before the first request reaches it.
**Before you start:** the [Quick start](../README.md#-quick-start).

[← Documentation index](README.md)

- [Rules and their source](#-rules-and-their-source)
- [Before the first request](#-before-the-first-request)
- [When things fail](#-when-things-fail)
- [Auditing](#-auditing)
- [Threads and stopping](#-threads-and-stopping)

---

## 🔒 Rules and their source

- [ ] Rules come only from a source you trust as much as your code, because rules are code; see
      [Security](../README.md#-security).
- [ ] Every rule is tested against sample facts in CI, because `load()` doesn't check fact or property names unless
      the facts are declared and the language checks rules against them; see
      [Testing rules](writing-rules.md#-testing-rules).
- [ ] A rule editor or pipeline calls `validate(rules)`, which returns every problem `load()` would throw; see
      [Checking a list before loading it](engines-and-runs.md#checking-a-list-before-loading-it).

## 🚦 Before the first request

- [ ] `load()` runs at startup, so a broken rule stops the application from starting; see
      [Configure the engine](spring-boot.md#-configure-the-engine).
- [ ] One engine is built once and reused, not one for each request; see
      [Should I build an engine for each request?](engines-and-runs.md#should-i-build-an-engine-for-each-request).
- [ ] On the module path, the packages of your fact and output types are exported without a `to` clause; see
      [Installation](../README.md#-installation).

## 🧯 When things fail

- [ ] A failed reload is reported: catch `RuleCompilationException`, log `failures()`, and know that the old rules
      keep serving; see [Reload rules without restarting](spring-boot.md#-reload-rules-without-restarting) and
      [Reloading rules](engines-and-runs.md#-reloading-rules).
- [ ] Callers handle `null`, `RuleExecutionException` and `IllegalArgumentException` from `run()`; see
      [Handling failures](error-handling.md#-handling-failures).
- [ ] An SLF4J provider is on the class path, and you've decided whether the `io.github.brantunger.unruly` loggers
      stay on, because their messages and DEBUG lines can contain fact values; see
      [Logging setup](listeners-and-logging.md#-logging-setup).
- [ ] Slow runs show up in a Flight Recorder recording: the run event is on by default with a 10 ms threshold, and
      a jlink image that puts the engine on the class path adds `jdk.jfr`; see
      [Flight Recorder events](listeners-and-logging.md#-flight-recorder-events).

## 🔏 Auditing

- [ ] A decision you must explain is recorded with `runWithResult()`: `firedRules()`, `evaluations()`,
      `ruleSetChecksum()` and the engine's [match policy](glossary.md#match-policy), because the checksum doesn't cover
      the policy, and with the tags you gave the run, if any, because neither the checksum nor the result holds
      them; see [Auditing a decision](engines-and-runs.md#-auditing-a-decision).

## 🧵 Threads and stopping

- [ ] A thread that caught an interrupted run clears its interrupt status before serving more work; see
      [this answer](stopping-runs.md#why-does-every-run-on-my-pooled-thread-fail-after-i-caught-an-interrupted-run).
- [ ] A [timeout](glossary.md#timeout) stops a run only between expressions, and in MVEL never inside one; see
      [What a timeout doesn't do](stopping-runs.md#-what-a-timeout-doesnt-do).
- [ ] On virtual threads, or with rules that wait on I/O, you chose the [copy limit](glossary.md#copy-limit) with
      `maxCopies(...)` or `unlimitedCopies()`; see [Limiting the copies](compiled-copies.md#-limiting-the-copies).
- [ ] With MVEL rules on virtual threads on JDK 24 or later, you decided whether `load()` makes the copies with
      `copiesAtLoad(n)`, and read the known issue before choosing `unlimitedCopies()`; see
      [Virtual threads](compiled-copies.md#-virtual-threads).
- [ ] Work stops reaching the engine before `close()`, which returns at once and doesn't drain; see
      [Draining before you close](thread-safety.md#draining-before-you-close).

> [!TIP]
> When something on this list has already gone wrong, [Troubleshooting](troubleshooting.md) starts from the symptom.
