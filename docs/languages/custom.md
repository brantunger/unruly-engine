# 🧩 Other expression languages

Rules are written in [MVEL](mvel.md) unless they name another expression language. You can register any number of
languages on an engine, and one rule list can mix them.

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
RulesEngine<LoanDecision> engine = RulesEngineBuilder.stateless(LoanDecision::new);
engine.registerLanguage(new MyLanguage());          // MVEL is found on the class path

engine.setRuleList(List.of(
        Rule.builder()
                .ruleName("prime-rate")
                .language("my")                     // the name MyLanguage.name() returns
                .condition("...")
                .action("...")
                .build(),
        Rule.builder()
                .ruleName("standard-rate")          // no language: MVEL
                .condition("applicant.creditScore >= 650")
                .action("output.interestRate = 6.9")
                .build()));
```

- Register languages **before** `setRuleList()`. Like imports, a language registered afterwards is used from the next
  `setRuleList()`.
- A language can also be found without registering it. List its class in
  `META-INF/services/io.github.brantunger.unruly.api.language.ExpressionLanguage` inside its jar, which is how MVEL
  is found. Each `setRuleList()` looks with this library's class loader and its thread's context class loader. A
  registered language replaces a found one with the same name, and two found languages with the same name fail
  `setRuleList()`.
- A rule whose language isn't registered is rejected by `setRuleList()`:
  `Rule 'prime-rate' is written in 'cel', which isn't a registered expression language. Registered languages: [mvel]`.
- Registering a language with the same name as a registered one replaces it. A language named `mvel` replaces MVEL
  for every rule whose `language` is `null`.
- `run()` checks each fact's name against every language the loaded rules use, so a name one of them can't refer to
  is rejected. A rule list without rules is checked against MVEL.

## 🛠 Writing a language

Implement three interfaces from `io.github.brantunger.unruly.api.language`:

| Interface | You implement | Called |
| --- | --- | --- |
| `ExpressionLanguage` | `name()` and `newCompiler(CompileContext)` | Once per `setRuleList()` that uses the language |
| `ExpressionCompiler` | `compileCondition(String)`, `compileAction(String)`, and optionally `checkFactName(String)` | For each rule, and `checkFactName` for each fact of each `run()` |
| `CompiledCondition` / `CompiledAction` | `evaluate(EvaluationContext)` / `execute(ActionContext)`, and optionally `copy()` | Each time a rule is evaluated or fires |

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
            public CompiledCondition compileCondition(String source) {
                MyExpression parsed = MyParser.parse(source);   // throw any exception for a syntax error
                if (parsed.assignsSomething()) {
                    throw new InvalidExpressionException("contains an assignment");
                }
                return evaluation -> parsed.evaluate(evaluation.facts());   // must return a Boolean
            }

            @Override
            public CompiledAction compileAction(String source) {
                MyExpression parsed = MyParser.parse(source);
                return action -> parsed.execute(action.facts(), action.output());
            }
        };
    }
}
```

`MyParser` and `MyExpression` stand for your language's own parser and compiled form.

- **Errors while compiling.** Throw `InvalidExpressionException` to reject an expression that breaks a rule the
  engine enforces. Its message is used after the rule's name, as in
  `Condition for rule 'prime-rate' contains an assignment`. Anything else you throw, such as a syntax error, becomes
  the cause of the `RuleCompilationException`, except a fatal `Error`, which is logged and rethrown unchanged. The
  same goes for `newCompiler`. Returning `null` from `newCompiler`, `compileCondition` or `compileAction` fails the
  rule list too.
- **Errors while running.** An exception from `evaluate` or `execute` becomes a `RuleExecutionException` naming the
  rule. A fatal `Error` such as `OutOfMemoryError` is rethrown unchanged, even when you wrap it in your own exception.
- **Facts and output.** `facts()` is a read-only map of fact values by name. Actions see the output object as
  `output` (`ActionContext.OUTPUT_NAME`), and the engine already rejects a fact with that name.
- **Imports.** `CompileContext` carries the packages and classes registered with `addImport()` and the class loader
  to look them up with. A language without imports ignores them.
- **Fact names.** Override `checkFactName` to reject a name your rules couldn't refer to, such as a keyword, with an
  `IllegalArgumentException`. By default every name is accepted. Anything else it throws is logged and becomes an
  `IllegalArgumentException` naming the fact and your language, except a fatal `Error`, which is rethrown unchanged.

## 📋 What the engine enforces

| The engine does this for every language | Your language must do this |
| --- | --- |
| Rejects `null` rules, blank conditions and actions, duplicate names and unregistered languages | Reject syntax errors when compiling, where it can |
| Runs rules in priority order, and fires one match (stateless) or every match (stateful) | Reject a condition that assigns or declares something, where it can detect that |
| Requires a condition to return a `Boolean`: `null`, a string or a number fails the rule | Keep an action's variables local to that action, so later rules still see the original facts |
| Rejects a fact named `null` or `output` | Bind the output object as `output`, and don't let an action replace it |
| Wraps failures in `RuleCompilationException` and `RuleExecutionException`, rethrows fatal errors, and calls listeners | Reject fact names it can't refer to |
| Passes read-only facts, and gives concurrent runs their own copies (see below) | Document what rules can reach: files, processes, reflection |

## 🧵 Thread safety

- The engine never runs one compiled condition or action on two threads at once, as long as `copy()` returns a new
  object. Each run uses its own copy. The condition or action your compiler returned is only copied, never run.
- `copy()` can be called from several threads at once, so build the copy only from state that doesn't change, such
  as the source and what you compiled it with.
- By default `copy()` returns the same object, which is right when several threads can evaluate it at the same time.
  Override it to return a new copy if a compiled expression keeps state while it runs. MVEL does, so each MVEL copy is
  compiled again.
- A `copy()` that throws or returns `null` fails the run that needed the copy, with a `RuleExecutionException` naming
  the rule. A fatal `Error` is rethrown unchanged.
- `checkFactName` is called by every `run()`, possibly on many threads at once, so it must be thread-safe.

## 📦 Packaging a language

A language in its own jar needs only `unruly-engine-core`, the engine without MVEL. So does an application whose rules
all name other languages: it can depend on `unruly-engine-core` instead of `unruly-engine`, and leave MVEL out.

The engine finds a language without `registerLanguage()` when its jar declares it as a service. The class needs a
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
    void conditionComparesFacts() {
        ExpressionCompiler compiler = language().newCompiler(LanguageTestContexts.compile());
        CompiledCondition condition = compiler.compileCondition(factEquals("x", 1));

        assertEquals(true, condition.evaluate(LanguageTestContexts.evaluation(Map.of("x", 1))));
    }
}
```

On the module path, the kit is the module `io.github.brantunger.unruly.test`. Open the package of your contract test
to `org.junit.platform.commons`, so JUnit can run it.

## 🔒 Security

A rule's condition and action are code, and what they can reach depends on the language. MVEL rules have full access
to the JVM. A language that can't reach the JVM, such as one that only reads facts, is safer for rules written by
less trusted people. The engine still has no timeout, so a language that allows loops can block `run()`. Say in your
language's documentation what its rules can do.
