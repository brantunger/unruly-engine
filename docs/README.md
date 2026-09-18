<img src="images/logo.svg" alt="unruly-engine logo" width="96" align="right">

# 📚 unruly-engine documentation

> [!NOTE]
> Describes 2.0.0, which isn't released yet. For 1.8.0, see
> [this page at v1.8.0](https://github.com/brantunger/unruly-engine/blob/v1.8.0/docs/README.md).

New here? Start with the [Quick start](../README.md#-quick-start) in the main README.

| Guide | Covers |
| --- | --- |
| 📜 [Writing rules](writing-rules.md) | Anatomy of a rule, choosing a language, what rules may change, loading rules from data, and testing rules |
| 🔀 [Engines and runs](engines-and-runs.md) | Rule order, first match, all matches or unique match, the output object, what a run reports and why each rule did or didn't apply, checksums for auditing, and reloading |
| ⚡ [MVEL](languages/mvel.md) | MVEL syntax, imports and built-in class names, and comparison gotchas |
| 🧩 [Expression languages](languages/README.md) | Choosing a language per rule, how the engine picks one, what a language can offer, and what to depend on |
| 🔨 [Writing a language](languages/custom.md) | The lifecycle, compile errors, facts, sessions, packaging and the contract test kit for a language of your own |
| 📁 [Facts](facts.md) | `FactStore`, `FactMap` and `Fact`, naming rules, null and missing facts, who sees facts, reusing and sharing a store, declaring facts, implementing `FactStore` |
| 🌱 [Spring Boot](spring-boot.md) | Configuring engines as beans, loading rules, reloading them, shutting down, virtual threads, and using several engines |
| 👂 [Listeners & logging](listeners-and-logging.md) | `RuleListener` callbacks, tracing, `LoggingRuleListener`, and logger configuration |
| 🚨 [Error handling](error-handling.md) | Every exception by method, what's caught when rules load and what only at run time |
| ⏳ [Stopping a run](stopping-runs.md) | Timeouts and interrupts: where a run stops, what a timeout can't stop, and nested runs |
| 🟣 [Kotlin](kotlin.md) | Nullness from Kotlin, and what to change in code written for 1.4 or earlier |
| 🧵 [Thread safety](thread-safety.md) | What's shared, the engine's lifecycle and closing, and reloading rules under traffic |
| 📑 [Compiled copies](compiled-copies.md) | What a run works on, how many copies an engine keeps and how to limit them, what a run waits for, and virtual threads |
| 📖 [Glossary](glossary.md) | Short definitions of the terms the guides use, each linked to the page that explains it |
| ☕ [Javadoc](https://brantunger.github.io/unruly-engine/latest/) | The API reference |

For contributors: [CONTRIBUTING.md](../CONTRIBUTING.md) · [Docs style guide](STYLE.md) ·
[RELEASING.md](../RELEASING.md) · [SECURITY.md](../SECURITY.md) · [Code of Conduct](../CODE_OF_CONDUCT.md)
