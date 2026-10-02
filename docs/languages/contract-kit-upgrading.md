# 🔼 Upgrading the contract test kit

What changed in the contract kit's checks from one version to the next, and what a new failure means.

**Who it's for:** language authors.
**You'll be able to:** tell why a language that passed an older kit fails a newer one, and what to fix.
**Before you start:** [The contract test kit](contract-kit.md).

[← Documentation index](../README.md)

---

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
  [A language that needs declared facts](contract-kit.md#a-language-that-needs-declared-facts-imports-or-options).

`nestedRunFailsInsideACondition` is also skipped when its nested run neither fails nor reads the `nest.value` that
makes it fail. That happens in a language that evaluates the right side of a condition first, even one that overrides
`bothConditions()`: the nested run stops at `x == 1`, which is false there, so a launcher sees one aborted check.
`nestedRunFailsInsideAnAction`'s nested run always reads it, since its action runs.

Skipped, the two condition checks count as aborted, as `nestedRunInsideAnAction` does, so a launcher that expects
every check found to succeed sees two more aborted checks, three if `putFactProperty()` also returns `null`: override
the hooks, or count aborted checks as passing.

## 🔼 Upgrading from 2.11

In 2.12.0 `actionVariablesStayLocal`, `compilerClosed` and `conditionDetail` got stricter, and
`conditionWritesRejected` and `failedActionVariablesStayLocal` were added, so a language that passed the 2.11 kit may
now fail. Each new failure is a real defect:

| Check | Now fails a language that | The defect |
| --- | --- | --- |
| `actionVariablesStayLocal` | Lets a later run's condition read a variable an earlier run's action declared | A later run's rule fires on a value no rule in that run set |
| `actionVariablesStayLocal`, when `configure` sets `copiesAtLoad(2)` or more | Keeps an action's variable in its session. The check used to miss it: the later run got the other copy, whose session never saw the variable | A later run reads a value that no rule set for it |
| `actionVariablesStayLocal` | Fails the run whose action declares the variable `y` while a fact is also named `y` | An action can't declare a variable with a fact's name, which the check's first part already requires with `x` |
| `compilerClosed`, when `configure` sets `copiesAtLoad(1)` or more | Has a compiler whose `close()` throws only once `warmUp` has run. The check's wrapper used to skip `warmUp` | The engine only logs it at WARN, and the compiler has usually leaked what it holds |
| `conditionWritesRejected`, once `propertyAssignment()` or `conditionDeclaration()` returns a condition | Lets a condition set a property of a fact, or declare a variable | The caller's fact changes, or a later expression in the run reads a value no action set |
| `failedActionVariablesStayLocal`, once `declareVariableThenFail()` returns an action | Clears an action's variables only when the action ends normally, rather than however it ends | The next run on that copy of the rules reads the failed action's variable |
| `conditionDetail`, when `configure` sets `copiesAtLoad(2)` or more | Returns a detail that reads the session when it's printed. The check used to miss it: the other run got the other copy | The run's result reports another run's values |

Why a variable left in a session reaches a later run is in
[Testing a compiler without an engine](beyond-the-contract-kit.md#-testing-a-compiler-without-an-engine).

A subclass written for the 2.11 kit still compiles. Three new hooks return `null` by default, which leaves their parts
off. Override them to turn those parts on:

- `propertyAssignment(fact, property, value)`: a condition that sets the fact's property to the value. For MVEL,
  `fact + "." + property + " = " + value`.
- `conditionDeclaration(name, value)`: a condition that declares a variable holding the value, and is then true. For
  MVEL, `name + " = " + value + "; true"`.
- `declareVariableThenFail(name, value)`: an action that declares a variable holding the value, then fails the run.
  For MVEL, `name + " = " + value + "; Integer.parseInt('not a number')"`.

The action must declare the variable before it fails, or `failedActionVariablesStayLocal` proves nothing.

Two more, `putVariable(key, variable)` and `variableEquals(variable, value)`, default to `putFact` and `factEquals`. If
your variables have a namespace of their own, such as SpEL's `#y`, override them with an action that puts the variable
under the key and a condition that compares it with the value; see [The contract test kit](contract-kit.md).

Skipped, `conditionWritesRejected` and `failedActionVariablesStayLocal` count as aborted, as `nestedRunInsideAnAction`
does, so a launcher that expects every check found to succeed sees two more aborted checks: override the hooks, or
count aborted checks as passing.

When a run's output differs from what the check expected only in a value's type, such as the `Integer` 1 and the
`String` "1", the failure now says so, rather than printing two identical outputs.

## 🔼 Upgrading from 2.10.0 or earlier

See [Upgrading the contract test kit from 2.10.0 or earlier](contract-kit-upgrading-older.md).
