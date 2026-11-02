# 🔼 Upgrading the contract test kit from 2.22 or earlier

What changed in the contract kit's checks from 2.17.0 up to 2.23.0, and what a new failure means.

**Who it's for:** language authors.
**You'll be able to:** tell why a language that passed a kit from 2.15 to 2.22 fails a newer one, and what to fix.
**Before you start:** [The contract test kit](contract-kit.md). For 2.23 and later, see
[Upgrading the contract test kit](contract-kit-upgrading.md).

[← Documentation index](../README.md)

---

## 🔼 Upgrading from 2.22

In 2.23.0 `reservedFactNamesRejected` was added and `unusableFactNameRejected` got stricter. Both follow
`ExpressionLanguage.reservedFactNames()`, new in 2.23.0: the fact names your language binds to something of its own,
such as its actions' name for the output object. The engine asks every language it has once, at `build()`, before
it prepares any, and rejects each name for every rule: a declared fact at `build()`, a run's fact at `run()`. The
default returns `output`, so a language written for 2.22 keeps the engine's old rule and passes both checks as it is.

| Check | Now fails a language that | The defect |
| --- | --- | --- |
| `reservedFactNamesRejected` | Returns `null`, a set holding `null`, or a different set the second time from `reservedFactNames()`, or throws when it's called before `prepare()` | `build()` throws `IllegalStateException` or what the method threw, or the engine rejects other names than the ones your language binds |
| `unusableFactNameRejected` | Returns a name from `unusableFactName()` that `reservedFactNames()` returns, not only `output` | The engine rejects that name itself, so the check never reached your `checkFactName`; return a name only your language rejects |

`reservedFactNamesRejected` can't be skipped. An empty set, for a language that binds the output object to no name,
passes it, and `output` is then an ordinary fact name, unless another of the engine's languages reserves it.

A `compile(...)` overload of `LanguageTestContexts` now takes the language first, and rejects a declared fact with a
name it reserves, as `build()` does. The other overloads still reject `output`, the default. Use the new one in
`compileContext()` if your language reserves another name, or none; see
[Testing a compiler without an engine](beyond-the-contract-kit.md#-testing-a-compiler-without-an-engine).

## 🔼 Upgrading from 2.20

In 2.21.0 four checks got stricter and none was added. Each loads a valid rule `ok` before rule `r`, so every
run evaluates `ok` first, on the same copy. `ok`'s condition is `alwaysTrue()` and its action
`putFact("ok", fact)`. Both are hooks every language already implements. A failure in `ok` fails the check.

| Check | Now fails a language that | The defect |
| --- | --- | --- |
| `conditionAssignmentRejected`, `conditionWritesRejected`, `outputNotReplaceable` and `missingPropertyFailsTheRun` | Leaves a copy's session unusable after a failure, like an "evaluating" flag never cleared | Every later run on that copy fails, whatever its rules. The three condition checks passed it, because the repeated run failed in `r` for that reason. `outputNotReplaceable` failed it with a misleading message, and passed it when only actions were left broken. |
| `outputNotReplaceable` and `missingPropertyFailsTheRun` | Fails `load()` or the first run naming no rule, such as an uncreatable compiler or session, or a rejected declared fact name | The first failure went unchecked |

A repeated run that fails in `ok` reads `<what failed> failed the first run, and the second run failed in the valid
rule ok, which runs before rule r: a failed run must leave its copy usable: `. The condition checks fail in `r` before
any action runs, missing a session broken for actions only.

In 2.20.3 one check got stricter and none was added. The kit now finds a
[fatal error](../glossary.md#fatal-error) where the engine finds it: thrown, or carried as a cause or a suppressed
exception at any depth. It also reads the suppressed exceptions the engine added itself, which the engine skips, so
it can find one the engine doesn't; the kit skips them too after 2.33.0 (see
[Upgrading from 2.33.0](contract-kit-upgrading.md#-upgrading-from-2330)).

| Check | Now fails a language that | The defect |
| --- | --- | --- |
| `evaluateAgreesWithDetail` | Throws a fatal error carried as a cause or a suppressed exception from `evaluate`, `evaluateWithDetail`, a session's or compiler's `close()`, or the `close()` of a value kept with `runScopedClosing`, one value's fatal error kept on another value's exception when the run ends included | The engine rethrows that error, where the check passed or failed with its own message |

The failure is the fatal error itself, thrown on. `compilerClosed`, the three session checks and `concurrentRuns`
already failed with the error the engine rethrows; they no longer attach the exception that carried it as one the
engine only logs.

## 🔼 Upgrading from 2.16

In 2.17.0 `sharedStateStaysLocal` was added and no check got stricter. A subclass written for the 2.16 kit still
compiles and passes: the three new hooks return `null` or `false` by default, and while `changeSharedState()` returns
`null` the new check is skipped. MVEL keeps the defaults, since it has no shared built-ins or globals of its own.
Its actions can still change a class's static state through the class's full name (see
[Security](mvel.md#-security)), which the check doesn't cover.

| Check | Now fails a language that | The defect |
| --- | --- | --- |
| `sharedStateStaysLocal`, once `changeSharedState()` returns an action | Lets an action change a built-in object or a global that its runs share | A later run's rule fires on a value no rule in that run set |

The failure reads `a later run's condition saw what an earlier run's action changed in the language's shared state: `
and the later run's output. Override both hooks to turn the check on:

- `changeSharedState(name, value)`: an action that sets the shared state `name` to `value`, such as
  `Math.discount = 50` in JavaScript.
- `sharedStateEquals(name, value)`: a condition that is true when that state holds `value`, and false, without
  throwing, until then. With `changeSharedState()` overridden, a `null` here fails the check.

The check passes when `load()` refuses the `changes` rule's action. Otherwise it makes three runs on one copy of the
rules: one in which that rule doesn't fire, one in which its action changes the state, and a later one. The condition
must be false in the first, and the later run must fire nothing, even when the action failed: it may have changed the
state before failing.

It also fails when the condition is true in the first run, with `sharedStateEquals() was true before any action
changed the language's shared state, or an earlier engine left it changed: `, and when the condition fails `load()` or
any of the runs, with `sharedStateEquals() must be false, not fail, while the shared state doesn't hold its value, but `
and what failed. Any other failure is rethrown.

The third hook, `actionVariablesLastTheRun()`, returns `false` by default, which changes nothing. Return `true` for a
language whose action variables last the run: `actionVariablesStayLocal` then requires a later rule in the same run
to read the variable, failing with a message that starts `actionVariablesLastTheRun() returns true, but`, and still
fails a variable that reaches a later run. `CompiledAction.execute`'s Javadoc now allows this: an action's variables
stay local to the execution "or, for a language that documents it, to the run; they never reach another run."

Skipped, `sharedStateStaysLocal` counts as aborted, as `nestedRunInsideAnAction` does, so a launcher that expects
every check found to succeed sees one more aborted check: override the hooks, or count aborted checks as passing.

## 🔼 Upgrading from 2.14 or earlier

See [Upgrading the contract test kit from 2.14 or earlier](contract-kit-upgrading-older.md).
