package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.FactStore;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
import io.github.brantunger.unruly.api.exception.RuleCompilationException;
import io.github.brantunger.unruly.api.language.ActionResult;
import io.github.brantunger.unruly.api.language.CompileContext;
import io.github.brantunger.unruly.api.language.CompiledAction;
import io.github.brantunger.unruly.api.language.CompiledCondition;
import io.github.brantunger.unruly.api.language.Expression;
import io.github.brantunger.unruly.api.language.ExpressionCompiler;
import io.github.brantunger.unruly.api.language.ExpressionLanguage;
import io.github.brantunger.unruly.api.language.Session;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

import static io.github.brantunger.unruly.core.EngineLoggingTest.assertLoggedAtError;
import static io.github.brantunger.unruly.core.EngineLoggingTest.assertLoggedThenRethrown;
import static org.junit.jupiter.api.Assertions.*;

@DisplayName("a language's fact-name check that fails unexpectedly is reported like a rejected name")
class FactNameCheckFailureTest {

    private StatefulRulesEngine<Map<String, Object>> engine;

    /** Builds an engine with a language named x whose checkFactName calls {@code check}, and loads one rule in it. */
    private void load(Consumer<String> check) {
        load(check, UnaryOperator.identity());
    }

    private void load(Consumer<String> check, UnaryOperator<RulesEngineBuilder<Map<String, Object>>> extra) {
        engine = TestEngines.allMatches(HashMap::new, builder -> extra.apply(builder).language(new ExpressionLanguage() {
            @Override
            public String name() {
                return "x";
            }

            @Override
            public ExpressionCompiler newCompiler(CompileContext context) {
                return new ExpressionCompiler() {
                    @Override
                    public CompiledCondition compileCondition(Expression expression) {
                        return (evaluation, session) -> true;
                    }

                    @Override
                    public CompiledAction compileAction(Expression expression) {
                        return (action, session) -> ActionResult.done();
                    }

                    @Override
                    public Session newSession() {
                        return Session.none();
                    }

                    @Override
                    public void checkFactName(String name) {
                        check.accept(name);
                    }
                };
            }
        }));
        engine.load(List.of(Rule.builder().ruleName("r").language("x").condition("c").action("a").build()));
    }

    private static FactStore<Object> fact(String name) {
        FactStore<Object> facts = new FactMap<>();
        facts.setValue(name, 1);
        return facts;
    }

    @Test
    @DisplayName("an unexpected exception becomes a logged IllegalArgumentException naming the fact and the language")
    void unexpectedExceptionWrapped() {
        IllegalStateException broken = new IllegalStateException("checker broke on a");
        load(name -> {
            throw broken;
        });

        IllegalArgumentException ex = assertLoggedAtError(IllegalArgumentException.class, () -> engine.run(fact("a")));

        assertEquals("The 'x' expression language failed to check fact name 'a': checker broke on a", ex.getMessage());
        assertSame(broken, ex.getCause());
    }

    @Test
    @DisplayName("an exception without a message is described by its class")
    void exceptionWithoutMessageWrapped() {
        load(name -> {
            throw new NullPointerException();
        });

        IllegalArgumentException ex = assertLoggedAtError(IllegalArgumentException.class, () -> engine.run(fact("a")));

        assertEquals("The 'x' expression language failed to check fact name 'a': java.lang.NullPointerException",
                ex.getMessage());
    }

    @Test
    @DisplayName("a fatal Error inside what the check throws is logged, then rethrown unchanged")
    void fatalErrorRethrown() {
        OutOfMemoryError oom = new OutOfMemoryError("simulated");
        load(name -> {
            throw new IllegalStateException("wrapped", oom);
        });

        assertLoggedThenRethrown(oom, "The 'x' expression language failed to check fact name 'a': wrapped",
                () -> engine.run(fact("a")));
    }

    @Test
    @DisplayName("a name the language rejects with IllegalArgumentException is thrown as is")
    void rejectionUnchanged() {
        IllegalArgumentException rejection = new IllegalArgumentException("'a' is not allowed");
        load(name -> {
            throw rejection;
        });

        assertSame(rejection, assertLoggedAtError(IllegalArgumentException.class, () -> engine.run(fact("a"))));
    }

    @Test
    @DisplayName("a declared name the check fails on unexpectedly fails load(), naming the fact and the language")
    void declaredNameCheckFailsLoading() {
        IllegalStateException broken = new IllegalStateException("checker broke on a");

        RuleCompilationException ex = assertLoggedAtError(RuleCompilationException.class,
                () -> load(name -> {
                    throw broken;
                }, builder -> builder.fact("a", Integer.class)));

        assertEquals("Declared fact 'a' can't be used: The 'x' expression language failed to check fact name 'a': "
                + "checker broke on a", ex.getMessage());
        assertSame(broken, ex.getCause().getCause());
    }

    @Test
    @DisplayName("a fatal Error from checking a declared name is logged, then rethrown from load() unchanged")
    void declaredNameFatalErrorRethrown() {
        OutOfMemoryError oom = new OutOfMemoryError("simulated");

        assertLoggedThenRethrown(oom, "The 'x' expression language failed to check fact name 'a': wrapped",
                () -> load(name -> {
                    throw new IllegalStateException("wrapped", oom);
                }, builder -> builder.fact("a", Integer.class)));
    }
}
