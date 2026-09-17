<img src="images/logo.svg" alt="unruly-engine logo" width="96" align="right">

# 📚 unruly-engine documentation

> [!NOTE]
> Describes 2.0.0, which isn't released yet. For 1.8.0, see
> [this page at v1.8.0](https://github.com/brantunger/unruly-engine/blob/v1.8.0/docs/README.md).

New here? Start with the [Quick start](../README.md#-quick-start) in the main README.

| Guide | Covers |
| --- | --- |
| 📜 [Writing rules](writing-rules.md) | Anatomy of a rule, choosing a language, what rules may change, and testing rules |
| ⚡ [MVEL](languages/mvel.md) | MVEL syntax, imports and built-in class names, and comparison gotchas |
| 🧩 [Other expression languages](languages/custom.md) | Choosing a language per rule, and writing, registering and testing your own |
| 📁 [Facts](facts.md) | `FactStore`, `FactMap` and `Fact`, naming rules, null and missing facts, copying and sharing |
| 🌱 [Spring Boot](spring-boot.md) | Configuring engines as beans, loading rules, reloading them, and using several engines |
| 👂 [Listeners & logging](listeners-and-logging.md) | `RuleListener` callbacks, tracing, `LoggingRuleListener`, and logger configuration |
| 🚨 [Error handling](error-handling.md) | Every exception by method, what's caught when rules load and what only at run time |
| 🟣 [Kotlin](kotlin.md) | Nullness from Kotlin, and what to change in code written for 1.4 or earlier |
| 🧵 [Thread safety](thread-safety.md) | Concurrency guarantees, reloading rules while running, and compiled copies for concurrent runs and how to limit them |
| 📖 [Glossary](glossary.md) | Short definitions of the terms the guides use, each linked to the page that explains it |
| ☕ [Javadoc](https://brantunger.github.io/unruly-engine/latest/) | The API reference |

For contributors: [CONTRIBUTING.md](../CONTRIBUTING.md) · [Docs style guide](STYLE.md) ·
[RELEASING.md](../RELEASING.md) · [SECURITY.md](../SECURITY.md) · [Code of Conduct](../CODE_OF_CONDUCT.md)
