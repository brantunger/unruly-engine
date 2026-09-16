# 🧩 Other expression languages

A rule is written in the expression language it names, or in the engine's default language, which is [MVEL](mvel.md)
unless you give the engine others. An engine can have any number of languages, and one rule list can mix them.

[← Writing rules](../writing-rules.md) · [← Back to README](../../README.md)

- [Choosing a language per rule](#-choosing-a-language-per-rule)
- [Writing a language](#-writing-a-language)
- [What the engine enforces](#-what-the-engine-enforces)
- [Thread safety](#-thread-safety)
- [Packaging a language](#-packaging-a-language)
- [Testing a language](#-testing-a-language)
- [Security](#-security)

---

## 🎯 Choosing a language per rule

```java
RulesEngine<LoanDecision> engine = RulesEngineBuilder.firstMatch(LoanDecision::new)
        .language(new MvelExpressionLanguage())     // giving languages replaces finding them, so add MVEL too
        .language(new MyLanguage())
        .defaultLanguage("mvel")                    // the language of rules without one
        .build();

engine.load(List.of(
        Rule.builder()
                .ruleName("prime-rate")
                .language("my")                     // the name MyLanguage.name() returns
                .condition("...")
                .action("...")
                .build(),
        Rule.builder()
                .ruleName("standard-rate")          // no language: the default, MVEL
                .condition("applicant.creditScore >= 650")
                .action("output.interestRate = 6.9")
                .build()));
```

- An engine's languages are set when it's built, and can't change. Once you call `language(...)`, the engine has
  exactly the languages you give it, so add `new MvelExpressionLanguage()` too if rules still use MVEL. Two languages
  with the same name fail `language(...)`.
- Without `language(...)`, `build()` finds languages with `java.util.ServiceLoader`: each class listed in
  `META-INF/services/io.github.brantunger.unruly.api.language.ExpressionLanguage` inside a jar, which is how MVEL is
  found. It looks once, with this library's class loader and the building thread's context class loader. Two found
  languages with the same name fail `build()`.
- The default language, for rules whose `language` is `null`, is the one named with `defaultLanguage(...)`, or else the
  engine's only language. `build()` throws `IllegalStateException` when the engine has no language, has several and no
  default, or has a default that isn't one of its languages.
- A rule written in a language the engine doesn't have is rejected by `load()`:
  `Rule 'prime-rate' is written in 'cel', which isn't one of the engine's expression languages: [mvel]`.
- `run()` checks each fact's name against every language the loaded rules use, so a name one of them can't refer to
  is rejected. A rule list without rules is checked against the default language.

## 🛠 Writing a language

Implement these interfaces from `io.github.brantunger.unruly.api.language`:

| Interface | You implement | Called |
| --- | --- | --- |
| `ExpressionLanguage` | `name()` and `newCompiler(CompileContext)` | Once per `load()` that uses the language |
| `ExpressionCompiler` | `compileCondition(Expression)`, `compileAction(Expression)`, `newSession()`, and optionally `checkFactName(String)` and `close()` | For each rule; `checkFactName` for each fact of each `run()`; `newSession` for each copy of the rules |
| `CompiledCondition` / `CompiledAction` | `evaluate(EvaluationContext, Session)` / `execute(ActionContext, Session)`, which returns an `ActionResult` | Each time a rule is evaluated or fires |
| `Session` | Optionally `close()`, if your expressions keep state while they run | One per language for each copy of the rules |

The engine creates the `CompileContext`, `EvaluationContext` and `ActionContext` it passes to your language. They're
sealed, so only the engine implements them; tests create them with [`LanguageTestContexts`](#-testing-a-language). A
method added to an interface you implement is a `default` method, so a language written against an earlier 2.x
release keeps compiling and working.

```java
import io.github.brantunger.unruly.api.exception.InvalidExpressionException;
import io.github.brantunger.unruly.api.language.*;

public final class MyLanguage implements ExpressionLanguage {

    @Override
    public String name() {
        return "my";
    }

    @Override
    public ExpressionCompiler newCompiler(CompileContext context) {
        // One compiler per rule list: keep per-list caches here. context has the imports and class loader.
        return new ExpressionCompiler() {
            @Override
            public CompiledCondition compileCondition(Expression source) {
                MyExpression parsed = MyParser.parse(source.text());   // see "Errors while compiling" below
                if (parsed.assignsSomething()) {
                    throw new InvalidExpressionException("contains an assignment");
                }
                return (evaluation, session) -> parsed.evaluate(evaluation.facts());   // must return a Boolean
            }

            @Override
            public CompiledAction compileAction(Expression source) {
                MyExpression parsed = MyParser.parse(source.text());
                return (action, session) -> {
                    parsed.execute(action.facts(), action.output());   // changes output in place
                    return ActionResult.done();
                };
            }

            @Override
            public Session newSession() {
                // Nothing changes while these expressions run. Otherwise, return a new session holding that state.
                return Session.none();
            }
        };
    }
}
```

`MyParser` and `MyExpression` stand for your language's own parser and compiled form.

- **The expression.** `compileCondition` and `compileAction` get an `Expression`: the rule's name, whether it's the rule's `CONDITION` or its `ACTION`, and its `text()`.
- **Errors while compiling.** Throw `InvalidExpressionException` to reject an expression that breaks a rule the
  engine enforces, or has an error you can point to, such as a syntax error. Its message is used after the
  expression's name, as in `Condition for rule 'prime-rate' contains an assignment`. Pass its `issues`, each with a
  line and column when you know them, and the `RuleCompilationException` carries them. Anything else you throw
  becomes the cause of a `RuleCompilationException` whose message reads
  `Condition for rule 'prime-rate' failed to compile: ...`, except a fatal `Error`, which is logged and rethrown
  unchanged. The same goes for `newCompiler`. Returning `null` from `newCompiler`, `compileCondition` or
  `compileAction` fails the rule list too. The engine compiles every rule before it throws, so one load reports every
  broken rule.
- **Warnings.** To report a problem that shouldn't stop a rule loading, such as a deprecated function, call
  `warn(source, issue)` on the `CompileContext` your compiler was created with. The engine logs it at WARN, naming the
  rule, the expression and the position.
- **Errors while running.** An exception from `evaluate` or `execute` becomes a `RuleExecutionException` naming the
  rule. A fatal `Error` such as `OutOfMemoryError` is rethrown unchanged, even when you wrap it in your own exception.
- **Facts and output.** `facts()` is a read-only map of fact values by name. Actions see the output object as
  `output` (`ActionContext.OUTPUT_NAME`), and the engine already rejects a fact with that name.
- **What an action returns.** An action that changes `output` in place returns `ActionResult.done()`. A language
  whose expressions compute values without side effects, such as CEL or JsonLogic, returns
  `ActionResult.set(Map.of("approved", true, "interestRate", 4.5))` instead. The engine sets each property in order
  with its `OutputWriter`, by default `put` on a `Map` output or the output's public setter, such as
  `setInterestRate`, whose parameter must accept the value as it is. The application can set its own writer with
  `.outputWriter(...)`, so don't assume how a property is stored. A property the writer can't set, or a `null` result,
  fails the rule with a
  `RuleExecutionException`. In an all-matches run, a later rule's properties overwrite an earlier one's. In
  `ExpressionLanguageContractTest`, return `null` from `reassignOutput()` or `declareVariable()` if your actions
  can't express them.
- **Imports.** `CompileContext` carries the packages and classes the engine was built with, from `imports(...)`, and
  the class loader to look them up with. A language without imports ignores them.
- **Output type and options.** `CompileContext.outputType()` is the class the engine was built with through
  `.outputType(...)`, or `Object` when it wasn't; a typed language can check what its actions return against it.
  `CompileContext.options()` holds your language's own settings, which the application sets with
  `.option("my", "key", "value")`, so a language needs no constructor arguments for them. Both are empty or `Object`
  unless the application sets them, so treat them as optional.
- **Fact names.** Override `checkFactName` to reject a name your rules couldn't refer to, such as a keyword, with an
  `IllegalArgumentException`. By default every name is accepted. Anything else it throws is logged and becomes an
  `IllegalArgumentException` naming the fact and your language, except a fatal `Error`, which is rethrown unchanged.

## 📋 What the engine enforces

| The engine does this for every language | Your language must do this |
| --- | --- |
| Rejects `null` rules, blank conditions and actions, duplicate names and languages the engine doesn't have | Reject syntax errors when compiling, where it can |
| Runs rules in priority order, and fires the highest-priority match (first match) or every match (all matches) | Reject a condition that assigns or declares something, where it can detect that |
| Requires a condition to return a `Boolean`: `null`, a string or a number fails the rule | Keep an action's variables local to that action, so later rules still see the original facts |
| Rejects a fact named `null` or `output`, and sets the properties an action returns with its `OutputWriter` | Bind the output object as `output` and don't let an action replace it, or return the action's results as properties |
| Wraps failures in `RuleCompilationException` and `RuleExecutionException`, rethrows fatal errors, and calls listeners | Reject fact names it can't refer to |
| Stops a run between rules when it's interrupted or past its deadline, and tells every expression with `isCancelled()` | Stop inside an expression too, if it can (see below) |
| Passes read-only facts, gives each run its own sessions, and closes them (see below) | Document what rules can reach: files, processes, reflection |

## ⏱ Stopping a run

A run can be interrupted, or given a [timeout](../error-handling.md#-stopping-a-run). The engine checks between
rules, so what your language can do decides whether a rule that is already running can be stopped:

| Language | Can it stop inside an expression? |
| --- | --- |
| MVEL | No. It has no hook inside a loop, so `while (true) {}` runs for ever. |
| JEXL 3 | Yes, with [`JexlBuilder.cancellable(true)`](https://commons.apache.org/proper/commons-jexl/apidocs/org/apache/commons/jexl3/JexlOptions.html), whose interpreter checks for interruption and cancellation. |
| CEL | Bounded by construction: the language isn't Turing-complete, and cel-java supports cost limits. |

Both contexts tell an expression where it stands:

```java
public CompiledAction compileAction(Expression expression) {
    return (context, session) -> {
        while (moreWork()) {
            if (context.isCancelled()) {         // interrupted, or past the deadline
                return ActionResult.done();
            }
            step(context.deadline());            // null when the run has no deadline
        }
        return ActionResult.done();
    };
}
```

- `isCancelled()` is `true` while the run's thread is interrupted, or once the deadline has passed.
- Returning when it's `true` is enough: the engine stops the run at the next check. Throwing instead fails that rule
  like any other failure.
- `deadline()` is an `Instant`, or `null` when the run has no timeout. Use it to give a call of your own a timeout.
- Neither is required. A language that evaluates an expression and returns needn't check anything.

## 🧵 Thread safety

- Every run shares the compiled conditions and actions, possibly on many threads at once. Keep whatever changes while
  an expression runs in a `Session`: `newSession()` creates one for each copy of the rules, and every condition and
  action of your language in a run gets that copy's session. The engine never gives one session to two runs at once,
  though a session can be used on different threads, one run after another.
- Return `Session.none()` when your compiled expressions keep no state while they run. Return a new session when they
  do, such as a single-threaded interpreter context. MVEL's session holds its own compiled copy of each expression.
- `newSession()` can be called from several threads at once. One that throws or returns `null` fails the run that
  needed the session with a `RuleExecutionException`, and the sessions other languages already made for that copy are
  closed. A fatal `Error` is rethrown unchanged.
- The engine closes each session once no run needs it, and then the compiler: when `load()` has replaced the
  rules and their runs have finished, when the engine is closed, and when a rule list fails to load. Override
  `close()` on either to release resources. A failure is logged at WARN and doesn't fail a run.
- `checkFactName` is called by every `run()`, possibly on many threads at once, so it must be thread-safe.

## 📦 Packaging a language

A language in its own jar needs only `unruly-engine-core`, the engine without MVEL. So does an application whose rules
all name other languages: it can depend on `unruly-engine-core` instead of `unruly-engine`, and leave MVEL out.

An engine built without `language(...)` finds a language when its jar declares it as a service. The class needs a
public no-argument constructor. Declare it both ways to support both paths:

- **Class path:** a file `META-INF/services/io.github.brantunger.unruly.api.language.ExpressionLanguage` that contains
  the class name, such as `com.example.lang.MyLanguage`.
- **Module path:** a `provides` clause. The language's package doesn't need to be exported.
  ```java
  module com.example.lang {
      requires io.github.brantunger.unruly.core;

      provides io.github.brantunger.unruly.api.language.ExpressionLanguage
              with com.example.lang.MyLanguage;
  }
  ```

## 🧪 Testing a language

The `unruly-engine-test` artifact, at the same version as the engine, has two tools for a language's tests. Add it
with test scope, for example `testImplementation 'io.github.brantunger:unruly-engine-test:<version>'`. It's built with
JUnit Jupiter 6, and brings JUnit Jupiter's API.

- **`ExpressionLanguageContractTest`** checks the promises above for any language. Extend it and supply expressions in
  your language, such as a condition that assigns to a fact or an action that declares a variable. MVEL and a small
  test-only language both pass it.
- **`LanguageTestContexts`** creates the contexts the engine passes to a language, to test a compiler or a compiled
  expression without an engine. They're the engine's own contexts: writing to their facts fails as in a run.

```java
import io.github.brantunger.unruly.test.ExpressionLanguageContractTest;
import io.github.brantunger.unruly.test.LanguageTestContexts;

class MyLanguageContractTest extends ExpressionLanguageContractTest {
    @Override
    protected ExpressionLanguage language() {
        return new MyLanguage();
    }

    @Override
    protected String factEquals(String fact, int value) {
        return fact + " == " + value;
    }

    // ... one method for each expression the checks need

    @Test
    void conditionComparesFacts() throws Exception {
        ExpressionCompiler compiler = language().newCompiler(LanguageTestContexts.compile());
        CompiledCondition condition = compiler.compileCondition(
                new Expression("r", ExpressionKind.CONDITION, factEquals("x", 1)));

        assertEquals(true, condition.evaluate(LanguageTestContexts.evaluation(Map.of("x", 1)), compiler.newSession()));
    }
}
```

On the module path, the kit is the module `io.github.brantunger.unruly.test`. Open the package of your contract test
to `org.junit.platform.commons`, so JUnit can run it.

## 🔒 Security

A rule's condition and action are code, and what they can reach depends on the language. MVEL rules have full access
to the JVM. A language that can't reach the JVM, such as one that only reads facts, is safer for rules written by
less trusted people. A run's timeout only stops it between rules unless your language honours `isCancelled()`, so a
language that allows loops and ignores it can still block `run()`. Say in your language's documentation what its
rules can do, and whether they can be stopped part-way.
