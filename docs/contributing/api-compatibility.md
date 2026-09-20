# 🧬 API compatibility

How the build decides whether a change breaks code written against an earlier release, and what to do when a break
is intended.

**Who it's for:** contributors changing a public type in `unruly-engine-core`, `unruly-engine` or
`unruly-engine-test`.
**You'll be able to:** predict whether `./gradlew build` accepts your change, add a method to a public interface
safely, and ship an intended break in a major release.
**Before you start:** [CONTRIBUTING.md](../../CONTRIBUTING.md#-run-the-gate-locally), for the gate the check is part
of.

[← Documentation index](../README.md)

- [What the check compares](#-what-the-check-compares)
- [What counts as a break](#-what-counts-as-a-break)
- [Nullness annotations are API for Kotlin](#-nullness-annotations-are-api-for-kotlin)
- [Adding a method to a public interface](#-adding-a-method-to-a-public-interface)
- [Accepting an intended break](#-accepting-an-intended-break)
- [Baselines](#-baselines)
- [Reference](#-reference)

---

## 🔎 What the check compares

The versions follow [Semantic Versioning](https://semver.org/), so a `fix:` or `feat:` release must not break code
written or compiled against an earlier release. `./gradlew build` runs the `japicmp` task of each published project,
which compares the project's jar with its **newest release on Maven Central that isn't higher than the version in
`gradle.properties`**, using [japicmp](https://siom79.github.io/japicmp/). It fails when a public or protected
member is removed or changes in a binary- or source-incompatible way.

Each project writes its report to `<project>/build/reports/japicmp/report.html` (and `report.txt`). CI uploads the
reports as the artifact `api-compatibility-report-jdk21-<os>` when a job fails. The task is configured in
`buildSrc/src/main/groovy/unruly.library.gradle`, and each project's `apiCheck { }` block in its `build.gradle` says
which artifact it compares and which packages it leaves out: the internal `core` package, and, for `unruly-engine`,
the packages that moved to `unruly-engine-core`.

## 🚫 What counts as a break

A change that stops earlier code compiling or linking against the new jar. Examples:

- A changed method signature, or a removed public or protected member.
- A class made `final`, or a new checked exception on a method.
- A new abstract method on an interface that users implement.
- A changed method of `Rule`. When you add a field to `Rule`, add it to the builder, `equals`, `hashCode` and
  `toString`; `Rule` has no public constructor to keep in step.

Additions pass: new classes, new methods on a class, and `default` methods on an interface. The check compares
members, not class-file versions, so `ClassFileVersionTest` separately checks that the published classes target
Java 21.

## 🟣 Nullness annotations are API for Kotlin

Kotlin reads the JSpecify annotations strictly, and japicmp doesn't check them. Marking a parameter non-null that
accepted `null`, or a return value, generic type or listener parameter `@Nullable` that wasn't, breaks Kotlin
sources, so it belongs in a major release like any other break. `NullnessAnnotationsTest` checks the nullness of the
members most likely to change: `run()`, fact names and values, what listeners and conditions see, `Rule`'s fields
and an exception's message and cause, so a change there fails the tests rather than passing unnoticed.

## 🆕 Adding a method to a public interface

Users implement several public interfaces: `RulesEngine` (for example to decorate an engine), `RuleListener`,
`FactStore`, `FactReference`, `OutputWriter`, and a language's `ExpressionLanguage`, `ExpressionCompiler`,
`CompiledCondition`, `CompiledAction` and `Session`. Each interface's Javadoc says who implements it.

Within a major version, a method added to any of them is a `default` method, and the check fails on an abstract
one. When no generic implementation makes sense, the default throws `UnsupportedOperationException`.

The engine-only contexts, `CompileContext`, `EvaluationContext`, `ActionContext` and `RunContext`, are sealed to the
engine's own implementations, so no user implements them and a new method breaks nobody. `SealedContextsTest`
checks that the three a language sees stay sealed.

japicmp can't see sealing, though. It reports a new abstract method on a sealed interface like any other, so give it
a `default` body, or accept it with a line and a `!` title, as the `CompileContext` and `EvaluationContext` lines in
the file did for 2.0. For the same reason, sealing an interface that wasn't is a break the check won't catch: title
that PR with a `!` by hand.

## 💥 Accepting an intended break

An intended break belongs in a major release, and needs three things:

1. For each element the report lists, add a line to
   [`config/japicmp/accepted-breaks.txt`](../../config/japicmp/accepted-breaks.txt): the major version the break
   ships in (for example `2`), the kind, the element as japicmp names it, and why users can live with it. The header
   shows the format. Every change to a listed element is accepted, not only the reported one.
2. Title the PR with a `!`, such as `feat!: make the engine classes package-private`, and describe the migration
   under "Behavior changes" in the description.
3. Add a section to [Migrating to 2.0](../migrating-to-2.md): who is affected, and what to change.

A break in the language SPI or in `RulesEngine` goes in
[Migrating a language or an engine](../migrating-to-2-implementers.md) instead of that page.

A line applies while the baseline is from an earlier major version, so a 1.x release published in the meantime
doesn't turn it off. Once that major version is published and becomes the baseline, the build warns that its lines no
longer apply, and they can be deleted.

## 📅 Baselines

The baseline is the artifact's newest release on Maven Central that isn't higher than the version in
`gradle.properties`. Because the baseline is never higher than the build's own version, a branch is always checked
against its own release line, and a release PR's version, not yet published, is compared with the release before it.

Two artifacts are new in 2.0.0, so until then:

- `unruly-engine-core` has no release of its own, and is compared with the last 1.x `unruly-engine` jar, without
  that jar's `mvel` package (`predecessor` and `predecessorExcludedPackages` in `core/build.gradle`).
- `unruly-engine-test` has nothing to compare with, and its check is skipped (`firstRelease` in
  `test-kit/build.gradle`); the build logs `Skipping the API check`.

The check downloads the baseline, so `./gradlew build` needs access to Maven Central, or `--offline` with the
baseline already in the Gradle cache. The lookup is kept for 24 hours, so a new release becomes the baseline within a
day; pass `-PapiCheck.refresh` to look it up again, as the release workflow does.

## 📋 Reference

| Change | Allowed? | Why |
| --- | :---: | --- |
| A new class, or a new method on a class | ✅ | Earlier code still compiles and links |
| A new `default` method on an interface | ✅ | Implementations inherit it |
| A new `default` method on `CompileContext`, `EvaluationContext`, `ActionContext` or `RunContext` | ✅ | Sealed: only the engine implements them |
| A new abstract method on one of those four | ❌ | Breaks nobody, but japicmp can't see sealing: accept it with a line and a `!` title |
| A new abstract method on any other public interface | ❌ | Every user implementation stops compiling |
| A removed or renamed public or protected member | ❌ | Code that calls it stops linking |
| A changed parameter or return type | ❌ | Callers stop linking, or implementations stop compiling |
| A class made `final`, or a constructor made private | ❌ | Subclasses and callers stop compiling |
| A new checked exception on a method | ❌ | Callers that don't handle it stop compiling |
| A parameter made non-null, or a return value made `@Nullable` | ❌ | Kotlin sources stop compiling; japicmp doesn't see it |
| Sealing an interface | ❌ | japicmp doesn't see it: add the `!` by hand |
| Any change to a class in the `core` package | ✅ | Internal: exported only to the test kit's module, and left out of the check |
