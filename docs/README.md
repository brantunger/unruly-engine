<img src="images/logo.svg" alt="unruly-engine logo" width="96" align="right">

# 📚 unruly-engine documentation

> [!NOTE]
> Describes 2.0.0, which isn't released yet. For 1.8.0, see
> [this page at v1.8.0](https://github.com/brantunger/unruly-engine/blob/v1.8.0/docs/README.md).

Pick the reading order that matches what you're doing, or find a guide in the table at the end.

## 🚀 Your first rule

1. [Quick start](../README.md#-quick-start): build an engine, load two rules and run them.
2. [How it works](../README.md#-how-it-works): what `load()` and each `run()` do.
3. [Anatomy of a rule](writing-rules.md#-anatomy-of-a-rule): every field of a `Rule`.
4. [Adding facts](facts.md#-adding-facts) and [Null and missing facts](facts.md#-null-and-missing-facts).
5. [Testing rules](writing-rules.md#-testing-rules): a test for each rule, with sample facts.
6. [Comparison gotchas](languages/mvel.md#-comparison-gotchas): what MVEL compares differently from Java.

## 🏭 Running in production

1. [Before you go to production](production.md): the checklist, and [Security](../README.md#-security).
2. [What a run reports](engines-and-runs.md#-what-a-run-reports) and
   [Handling failures](error-handling.md#-handling-failures).
3. [Stopping a run](stopping-runs.md): timeouts and interrupts.
4. [Thread safety at a glance](thread-safety.md#-at-a-glance), [Compiled copies](compiled-copies.md) and, if your
   runs come from virtual threads, [Virtual threads](virtual-threads.md).
5. [Logging setup](listeners-and-logging.md#-logging-setup) and [Spring Boot](spring-boot.md).
6. [Native image](native-image.md), if you build one with GraalVM: MVEL's JIT and the reflection to register.
7. [Troubleshooting](troubleshooting.md): from a symptom or a message to the section that explains it.

## 🔨 Writing an expression language

1. [Expression languages](languages/README.md): how an engine gets its languages and picks one for each rule.
2. [Lifecycle at a glance](languages/custom.md#-lifecycle-at-a-glance) and
   [Implementing the interfaces](languages/custom.md#-implementing-the-interfaces).
3. [Thread safety](languages/custom.md#-thread-safety) and [Stopping a run](languages/custom.md#-stopping-a-run).
4. [Testing with the contract kit](languages/custom.md#-testing-with-the-contract-kit).
5. [Packaging](languages/custom.md#-packaging).

## 🔼 Upgrading from 1.x

1. [Migrating to 2.0](migrating-to-2.md): every change that can affect code written for 1.x.
2. [Migrating a language or an engine](migrating-to-2-implementers.md), if you wrote an expression language or a
   class that implements `RulesEngine`.
3. [Upgrading Kotlin code from 1.4 or earlier](kotlin.md#-upgrading-kotlin-code-from-14-or-earlier), for Kotlin.

## 📋 All guides

| Guide | For | Covers |
| --- | --- | --- |
| 📜 [Writing rules](writing-rules.md) | Rule authors | Anatomy of a rule, choosing a language, what rules may change, loading rules from data, and testing rules |
| 🔀 [Engines and runs](engines-and-runs.md) | Application developers | Rule order, which rules a run uses, first match, all matches or unique match, the output object, what a run reports and why each rule did or didn't apply, checksums for auditing, and reloading |
| ⚡ [MVEL](languages/mvel.md) | Rule authors | MVEL syntax, imports and built-in class names, and comparison gotchas |
| 🧩 [Expression languages](languages/README.md) | Application developers | Choosing a language per rule, how the engine picks one, what a language can offer, and what to depend on |
| 🔨 [Writing a language](languages/custom.md) | Language authors | The lifecycle, compile errors, facts, sessions, packaging and the contract test kit for a language of your own |
| 📁 [Facts](facts.md) | Rule authors and application developers | `FactStore`, `FactMap` and `Fact`, naming rules, null and missing facts, who sees facts, reusing and sharing a store, declaring facts, implementing `FactStore` |
| 🧊 [Native image](native-image.md) | Application developers | Building a GraalVM native image: turning MVEL's JIT off, registering reflection, the errors a missing registration gives, Flight Recorder, and what was tested |
| 🌱 [Spring Boot](spring-boot.md) | Application developers | Configuring engines as beans, loading rules, reloading them, shutting down, virtual threads, and using several engines |
| 👂 [Listeners & logging](listeners-and-logging.md) | Application developers | `RuleListener` callbacks, tracing, `LoggingRuleListener`, and logger configuration |
| 🚨 [Error handling](error-handling.md) | Application developers | Every exception by method, what's caught when rules load and what only at run time |
| 🩺 [Troubleshooting](troubleshooting.md) | Everyone | Symptoms and exception messages from `build()`, `load()` and `run()`, each linked to the guide that explains it |
| 🏭 [Before you go to production](production.md) | Application developers | A checklist: rules and their source, startup, failures, auditing, threads and shutdown |
| ⏳ [Stopping a run](stopping-runs.md) | Application developers | Timeouts and interrupts: where a run stops, what a timeout can't stop, and nested runs |
| 🟣 [Kotlin](kotlin.md) | Application developers | Nullness from Kotlin, and what to change in code written for 1.4 or earlier |
| 🧵 [Thread safety](thread-safety.md) | Application developers | What's shared, the engine's lifecycle and closing, and reloading rules under traffic |
| 📑 [Compiled copies](compiled-copies.md) | Application developers | What a run works on, how many copies an engine keeps and how to limit them, making them at load, and what a run waits for |
| 🧶 [Virtual threads](virtual-threads.md) | Application developers | The copy limit that applies only there, build slots and `unlimitedCopies()`, what each costs, and the JDK versions that deadlock or pin a carrier |
| 🔼 [Migrating to 2.0](migrating-to-2.md) | Everyone | Every change from 1.x that can affect your code, who it affects, and what to change, including the move from GPL-3.0 to Apache-2.0 |
| 🔼 [Migrating a language or an engine](migrating-to-2-implementers.md) | Language authors, `RulesEngine` implementers | What the 2.0 language SPI and `RulesEngine` interface need on top of the changes every caller sees |
| 📖 [Glossary](glossary.md) | Everyone | Short definitions of the terms the guides use, each linked to the page that explains it |
| ☕ [Javadoc](https://brantunger.github.io/unruly-engine/latest/) | Everyone | The API reference |

For contributors: [CONTRIBUTING.md](../CONTRIBUTING.md) · [API compatibility](contributing/api-compatibility.md) ·
[Build and gates](contributing/build-and-gates.md) · [Docs style guide](STYLE.md) · [RELEASING.md](../RELEASING.md) ·
[SECURITY.md](../SECURITY.md) · [Code of Conduct](../CODE_OF_CONDUCT.md)
