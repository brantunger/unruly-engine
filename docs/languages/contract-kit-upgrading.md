# 🔼 Upgrading the contract test kit

What changed in the contract kit's checks from one version to the next, and what a new failure means.

**Who it's for:** language authors.
**You'll be able to:** tell why a language that passed an older kit fails a newer one, and what to fix.
**Before you start:** [The contract test kit](contract-kit.md).

[← Documentation index](../README.md)

---

## 🔼 Upgrading from 2.33.2

In 2.33.3 `outputNumbersConverted` got stricter and no check was added. Each new failure is a real defect:

| Check | Now fails a language that | The defect |
| --- | --- | --- |
| `outputNumbersConverted` | Returns `true` from `convertsOutputNumbers()`, and sets `setCreditScore(int)`, or fails nothing, from the `Double` -3.0E9 or the `BigDecimal` 1.5 or 3E9; or sets `setBalance(long)`, or fails nothing, from the `Double` 1.5, `NaN`, `-Infinity` or -(2^53 + 2), or the `BigDecimal` 1.5 or 1E19 | `convertOutput` casts a whole `Double` unbounded below, setting `Integer.MIN_VALUE`; converts a `Double` below -2^53, where it no longer counts by ones; rounds with `Math.round`, setting 2 from 1.5, 0 from `NaN` and `Long.MIN_VALUE` from `-Infinity`; or narrows a `BigDecimal` with `intValue()` or `longValue()`, which set 1 from 1.5 and wrap 3E9 or 1E19 |

## 🔼 Upgrading from 2.33.0

In 2.33.1 no check was added and none got stricter. The kit now finds a
[fatal error](../glossary.md#fatal-error), and the interrupt that stopped a run, with the engine's own search,
`Throwables` in `io.github.brantunger.unruly.api.language`. So, like the engine, it skips the suppressed exceptions
the engine added itself to a failure it reported. Earlier kits read them: from 2.20.3 for a fatal error, and from
2.31.1 for an interrupt.

A language that passed the 2.33.0 kit passes as it is. The engine adds such an exception in two places: to the
failure a listener's `onError` or `onRunError` gets, and to the stop a cancelled run throws. A stop's additions hold
no `Error`, and an interrupt among them is already the stop's cause. A fatal error added for a listener is the one
the run rethrows, or a second one beside it.

So the kit sees a difference only when a listener or the language keeps a reported failure and throws it again: a
fatal error the engine kept there, beside the one it rethrew, the kit no longer rethrows.

The kit and `unruly-engine` now call API added in core 2.33.0, so they need a core at least that new. Keep every
module at one version, as [Testing with the contract kit](contract-kit.md#-testing-with-the-contract-kit) says.

## 🔼 Upgrading from 2.32.0

In 2.32.1 `outputNumbersConverted` got stricter and no check was added, so a language that passed the 2.32.0 kit may
now fail. Each new failure is a real defect:

| Check | Now fails a language that | The defect |
| --- | --- | --- |
| `outputNumbersConverted` | Returns `true` from `convertsOutputNumbers()`, and sets `setCreditScore(int)`, or fails nothing, from the `Double` 3.0E9, `Infinity`, `-Infinity` or `NaN`; or, on a `long` setter, sets it, or fails nothing, from `Infinity` or 9007199254740994.0, which is 2^53 + 2, or doesn't set it to 3000000000 from 3.0E9 | `convertOutput` narrows a whole `Double` without checking the target's range, as `intValue()` and `longValue()` do, which set the type's extreme instead, or 0 for `NaN`; converts a `Double` beyond 2^53, where it no longer counts by ones; or refuses a whole `Double` a `long` holds |

The 2.32.0 kit fed the `int` setter 1.0 and 1.5 only, so a conversion that checked only `d == Math.rint(d)` or
`d == Math.floor(d)` before `intValue()` passed it, and set 2147483647 from 3.0E9. The `long` setter is new: the kit
writes `ExpressionLanguageContractTest.Account`, a public JavaBean with `getBalance()` and `setBalance(long)`, through
the same default writer.

## 🔼 Upgrading from 2.31

In 2.32.0 `outputNumbersConverted` was added and no check got stricter.

| Check | Now fails a language that | The defect |
| --- | --- | --- |
| `outputNumbersConverted` | Returns `true` from `convertsOutputNumbers()`, and doesn't set `setCreditScore(int)` to 1 from the whole `Double` 1.0, or sets it, or fails nothing, from the `Double` 1.5 | `convertOutput` doesn't convert a whole number for an `int` setter, or rounds a fraction instead of refusing it |

`convertsOutputNumbers()` returns `false` by default, which skips the check, so a language written for 2.31 passes as it
is, with one more aborted check. Return `true` once your language
[converts the numbers its actions return](output-conversion.md).

## 🔼 Upgrading from 2.27

In 2.28.0 `endlessActionStopsAtTimeout` was added and no check got stricter.

| Check | Now fails a language that | The defect |
| --- | --- | --- |
| `endlessActionStopsAtTimeout` | Returns an action from `endlessAction()` whose run doesn't fail with its 200 ms timeout within 30 seconds, or leaves its thread interrupted | The language can't stop a running expression at the deadline, or restores an interrupt after a deadline cancel |

`endlessAction()` returns `null` by default, which skips the check, so a language written for 2.27 passes as it is,
with one more aborted check. Return an action that runs until it is stopped once your language can stop one, with
[`onCancel`](custom.md#-stopping-a-run) or by polling `isCancelled()`.

## 🔼 Upgrading from 2.26

In 2.27.0 `factNamesReadHoldsTheFactsRead` was added and no check got stricter.

| Check | Now fails a language that | The defect |
| --- | --- | --- |
| `factNamesReadHoldsTheFactsRead` | Returns a set from `ExpressionCompiler.factNamesRead()` that leaves out a fact the check's rules read | The engine asks your `checkFactName` only about the names in that set, so a fact it leaves out is never checked |

`factNamesRead()` returns `null` by default, which skips the check, so a language written for 2.26 passes as it is,
with one more aborted check, since a skipped check counts as aborted: return the names your rules read from
`factNamesRead()`, or count aborted checks as passing.

## 🔼 Upgrading from 2.25

In 2.26.0 two checks were added and one got stricter; see
[How each check runs](beyond-the-contract-kit.md#-how-each-check-runs).

| Check | Now fails a language that | The defect |
| --- | --- | --- |
| `unreservedOutputReadAsFact` | Neither reserves nor rejects `output`, and doesn't read a fact by that name | A rule confuses the fact and the output object. Skipped by default: the default reserves `output` |
| `concurrentPrepares` | Throws from `prepare()`, or returns before it's ready, when eight threads prepare one instance at once | Your `prepare()` isn't thread-safe; the engine calls it unlocked |
| `unusableFactNameRejected` | Whose run failed only for another reason, such as a type in `configure` | Your `checkFactName` accepts the name |

## 🔼 Upgrading from 2.23

In 2.24.0 one check got stricter, and every check that builds an engine got a new failure:

| Check | Now fails a language that | The defect |
| --- | --- | --- |
| Every check that builds an engine | Returns `x`, `y`, `applicant` or `nest` from `reservedFactNames()` | The checks supply those facts, so the kit can't check the language; reserve other names |
| `reservedFactNamesRejected` | Returns other names from `reservedFactNames()` once it's prepared, which the 2.23.2 kit passed | A later engine built with the same, prepared instance rejects other names; return a constant. A flag kept in a static field may go unnoticed (see [the limit](beyond-the-contract-kit.md#-how-each-check-runs)) |

A language reserving `x`, `y` or `applicant` already failed the 2.23.2 kit: checks such as `conditionReadsFacts`
failed with the engine's "is reserved" message or an unexpected exception. Now each check that builds an engine
fails before building it, with one message naming the names. A language reserving `nest` whose `putFactProperty()`
and `bothConditions()` both return `null` passed 2.23.2, and now fails.

`LanguageTestContexts.evaluation` and `action` now reject a `null` or blank fact name with the run's
`IllegalArgumentException`; see
[Testing a compiler without an engine](beyond-the-contract-kit.md#-testing-a-compiler-without-an-engine).

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
[Upgrading from 2.33.0](#-upgrading-from-2330)).

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
