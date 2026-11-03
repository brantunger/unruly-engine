# 🔼 Upgrading the contract test kit

What changed in the contract kit's checks from one version to the next, and what a new failure means.

**Who it's for:** language authors.
**You'll be able to:** tell why a language that passed an older kit fails a newer one, and what to fix.
**Before you start:** [The contract test kit](contract-kit.md).

[← Documentation index](../README.md)

---

## 🔼 Upgrading from 2.34

In 2.35.0 `outputNumbersConvertedConcurrently` was added, and `evaluateAgreesWithDetail` and
`reservedFactNamesRejected` got stricter. The new check needs no new hook: `convertsOutputNumbers()` skips it, as it
skips `outputNumbersConverted`, so a language that doesn't opt in sees nothing new. A language that passed the 2.34 kit
may now fail any of the three. Each new failure is a real defect:

| Check | Now fails a language that | The defect |
| --- | --- | --- |
| `evaluateAgreesWithDetail` | Returns another value from `evaluate` than `evaluateWithDetail` reports, or throws from only one of them, for `alwaysTrue()`, `factValue("x")` given `true` or `false`, or `bothConditions(factEquals("x", 1), alwaysTrue())` given 1 or 2 | Code that calls `evaluate` directly gets another answer than the engine, which calls only `evaluateWithDetail` |
| `reservedFactNamesRejected` | Changes its answer from `reservesForEveryRuleList()` on a second call or once prepared | An engine built later with the same instance reserves the names for other rule lists |
| `outputNumbersConvertedConcurrently` | Returns `true` from `convertsOutputNumbers()`, and sets another run's number when 8 threads convert whole `Double`s for `setCreditScore(int)` at once | `convertOutput` keeps state the threads share, such as a field it writes the value to and reads back, though the engine calls it on many threads at once |

A `factValue("x")` your compiler refuses, as a typed language whose `compileContext()` declares `x` as a number may,
is left out rather than failed. Return a constant from `reservesForEveryRuleList()`: the engine asks once, when it's
built. The new check makes a race likely, not certain, to show, so passing it doesn't prove `convertOutput`
thread-safe.

## 🔼 Upgrading from 2.33

In 2.34.0 `nestedRunFailureRethrownAsIs` and `outputUnconvertibleValueFailsTheRule` were added and no check got
stricter. Neither needs a new hook: the first is skipped as `nestedRunFailsInsideAnAction` is, and the second can't
be skipped. So a language that passed the 2.33 kit may now fail either. Each new failure is a real defect:

| Check | Now fails a language that | The defect |
| --- | --- | --- |
| `nestedRunFailureRethrownAsIs` | Fails the action whose getter throws a nested run's failure with a message that doesn't start `Failed to execute action for rule 'outer': a nested run() failed: `, or without that failure in its cause chain | The language throws an exception of its own, with words of its own around the nested failure's message, as SpEL's `SpelEvaluationException` does, so the engine [logs the failure twice](../nested-runs.md#-what-is-logged) |
| `outputUnconvertibleValueFailsTheRule` | Sets a `String` property from the `Double` 1.5, or, with actions that change the output in place, returns `null` or a `String` from `convertOutput(1.5, String.class)` | `convertOutput` returns `null` for a conversion it doesn't have, which sets the property to `null` and fails nothing, or converts a number to a `String` |

To pass `nestedRunFailureRethrownAsIs`, throw what the code your expression called threw, or a wrapper with exactly
its message, as [Implementing the interfaces](custom.md#-implementing-the-interfaces) says.

The default `convertOutput`, which returns the value unchanged, passes `outputUnconvertibleValueFailsTheRule`; see
[What to convert](output-conversion.md#-what-to-convert). The kit writes `ExpressionLanguageContractTest.Customer`, a
public JavaBean with `getName()` and `setName(String)`, through the default writer.

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
failure a listener's `onError` or `onRunError` gets, and to the stop a cancelled run throws. An added interrupt is
already the stop's cause, and an added `Error`, there or in a listener's failure, is a fatal error a listener threw:
the one the run rethrows, or a second one beside it.

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

## 🔼 Upgrading from 2.22 or earlier

See [Upgrading the contract test kit from 2.22 or earlier](contract-kit-upgrading-2.22.md).
