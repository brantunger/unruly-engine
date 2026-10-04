# 🔼 Upgrading the contract test kit

What changed in the contract kit's checks from one version to the next, and what a new failure means.

**Who it's for:** language authors.
**You'll be able to:** tell why a language that passed an older kit fails a newer one, and what to fix.
**Before you start:** [The contract test kit](contract-kit.md).

[← Documentation index](../README.md)

---

## 🔼 Upgrading from 2.23

In 2.24.0 one check got stricter, and every check that builds an engine got a new failure:

| Check | Now fails a language that | The defect |
| --- | --- | --- |
| Every check that builds an engine | Returns `x`, `y`, `applicant` or `nest` from `reservedFactNames()` | The checks supply those facts, so the kit can't check the language; reserve other names |
| `reservedFactNamesRejected` | Returns other names from `reservedFactNames()` once it's prepared, which the 2.23.2 kit passed | A later engine built with the same, prepared instance rejects other names; return a constant. A flag kept in a static field may go unnoticed (see [the limit](contract-kit.md#-testing-with-the-contract-kit)) |

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
`putFact("ok", fact)`. Both are hooks every language already implements, so there is nothing new to supply. A
failure in `ok` fails the check.

| Check | Now fails a language that | The defect |
| --- | --- | --- |
| `conditionAssignmentRejected`, `conditionWritesRejected`, `outputNotReplaceable` and `missingPropertyFailsTheRun` | Leaves a copy's session unusable after a failure, like an "evaluating" flag never cleared | Every later run on that copy fails, whatever its rules. The three condition checks passed it, because the repeated run failed in `r` for that reason. `outputNotReplaceable` failed it with a misleading message, and passed it when only actions were left broken. |
| `outputNotReplaceable` and `missingPropertyFailsTheRun` | Fails `load()` or the first run naming no rule, such as an uncreatable compiler or session, or a rejected declared fact name | The first failure went unchecked |

A repeated run that fails in `ok` reads `<what failed> failed the first run, and the second run failed in the valid
rule ok, which runs before rule r: a failed run must leave its copy usable: `. The condition checks fail in `r` before
any action runs, missing a session broken for actions only.

In 2.20.3 one check got stricter and none was added. The kit now finds a fatal error, any `VirtualMachineError`
but `StackOverflowError`, where the engine finds it: thrown, or carried as a cause or a suppressed exception at any
depth. It also reads the suppressed exceptions the engine added itself, which the engine skips, so it can find one
the engine doesn't.

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

## 🔼 Upgrading from 2.14

In 2.15.0 five checks got stricter and none was added, so a language that passed the 2.14 kit may now fail. Each new
failure is a real defect:

| Check | Now fails a language that | The defect |
| --- | --- | --- |
| `conditionAssignmentRejected` | Fails a condition that assigns to a fact on its first run only | From the second run on, the condition changes the fact |
| `conditionWritesRejected` | Fails a condition that writes a property or declares a variable on its first run only | From the second run on, the caller's fact changes, or a later expression reads a value no action set |
| `conditionWritesRejected`, once `propertyAssignment()` returns a condition | Rejects a condition's write to a map fact, but calls a bean's setter | The caller's bean changes |
| `outputNotReplaceable` | Fails an action that replaces the output on its first run only | From the second run on, the action replaces the output, and nothing throws |
| `missingPropertyFailsTheRun`, unless `missingFactProperty()` returns `null` | Fails a condition that reads a missing property on its first run only | From the second run on, the property reads as `null`, and the rule silently doesn't fire |
| `compilerClosed`, unless `configure` sets `copiesAtLoad(1)` or more | Has a compiler whose `close()` throws only once `warmUp` has run | The engine only logs it at WARN, and the compiler has usually leaked what it holds |
| `compilerClosed`, unless `configure` sets `copiesAtLoad(1)` or more | Has a `warmUp` that throws only for the compiler a reload makes, while the replaced rules' compiler is still open | A reload fails in any engine that makes copies at load; the check fails with `RuleCompilationException: The 'my' expression language failed to warm up a session: ` + what `warmUp` threw |

The four checks that repeat a run keep one copy of the rules, as `conditionDetail` does. When `load()` succeeds and
the run fails, they run the rule again, which must throw a `RuleExecutionException` naming the rule and its
`CONDITION`, or its `ACTION` in `outputNotReplaceable`.

A second run that doesn't throw fails the check with `... failed the first run but not the second`. Any other
`RuntimeException` fails it with `... failed the second run differently, not with a RuleExecutionException naming
rule r and its condition: ...`, or `action`, carrying what the run threw as the cause.

`compilerClosed` now sets `copiesAtLoad(1)` after `configure`, so the compiler has warmed up a session before it's
closed. The 2.12 row below, "when `configure` sets `copiesAtLoad(1)` or more", no longer applies. A language whose
`newSession()` returns `Session.none()` still has nothing warmed up: the engine calls `warmUp` only for other sessions.

A subclass written for the 2.14 kit still compiles, and no hook was added. `conditionWritesRejected` also runs your
`propertyAssignment()` condition against `ExpressionLanguageContractTest.WritableApplicant`, a new public JavaBean with
`getCreditScore()` and `setCreditScore(int)`, and requires its score unchanged. The condition must also write a bean's
property, as MVEL's `applicant.creditScore = 1` does. If your `configure` declares `applicant`, keep it `Object`.

## 🔼 Upgrading from 2.13

In 2.14.0 `nestedRunInsideACondition`, `nestedRunFailsInsideACondition` and `nestedRunFailsInsideAnAction` were
added, so a language that passed the 2.13 kit may now fail. Each new failure is a real defect:

| Check | Now fails a language that | The defect |
| --- | --- | --- |
| `nestedRunInsideACondition`, once `bothConditions()` returns a condition | Keeps what a condition works on in per-thread state, such as a `ThreadLocal`, and looks it up again after a read that may start a nested run | The condition reads the nested run's facts, and its rule silently doesn't fire |
| `nestedRunFailsInsideACondition`, once `bothConditions()` returns a condition | Puts back a condition's per-thread state when the condition returns, but not in a `finally` | After a nested run fails, the condition that started it reads the failed run's facts |
| `nestedRunFailsInsideAnAction`, once `putFactProperty()` returns an action | Puts back an action's per-thread state when the action returns, but not in a `finally` | After a nested run fails, the action that started it writes to the failed run's output, and nothing throws |
| `nestedRunFailsInsideACondition` and `nestedRunFailsInsideAnAction` | Swallows what a getter threw, or rethrows something that carries it neither as a cause, nor as a suppressed exception, nor by its message | A run that should fail doesn't, or its failure hides what caused it |

A subclass written for the 2.13 kit still compiles. The new hook returns `null` by default, which leaves the two
condition checks off. Override it to turn them on:

- `bothConditions(condition, other)`: a condition that is true when both are, evaluating `condition` first. For
  MVEL, `condition + " && " + other`. If your `configure` declares facts, declare `nest` too; see
  [Declared facts for the kit](beyond-the-contract-kit.md#-a-language-that-needs-declared-facts-imports-or-options).

`nestedRunFailsInsideACondition` is also skipped when its nested run neither fails nor reads the `nest.value` that
makes it fail. That happens in a language that evaluates the right side of a condition first, even one that overrides
`bothConditions()`: the nested run stops at `x == 1`, which is false there, so a launcher sees one aborted check.
`nestedRunFailsInsideAnAction`'s nested run always reads it, since its action runs.

Skipped, the two condition checks count as aborted, as `nestedRunInsideAnAction` does, so a launcher that expects
every check found to succeed sees two more aborted checks, three if `putFactProperty()` also returns `null`: override
the hooks, or count aborted checks as passing.

## 🔼 Upgrading from 2.11 or earlier

See [Upgrading the contract test kit from 2.11 or earlier](contract-kit-upgrading-older.md).
