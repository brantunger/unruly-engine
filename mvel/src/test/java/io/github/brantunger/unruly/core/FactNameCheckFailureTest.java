package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.FactStore;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.language.CompileContext;
import io.github.brantunger.unruly.api.language.CompiledAction;
import io.github.brantunger.unruly.api.language.CompiledCondition;
import io.github.brantunger.unruly.api.language.ExpressionCompiler;
import io.github.brantunger.unruly.api.language.ExpressionLanguage;
import io.github.brantunger.unruly.api.language.Session;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static io.github.brantunger.unruly.core.EngineLoggingTest.assertLoggedAtError;
import static io.github.brantunger.unruly.core.EngineLoggingTest.assertLoggedThenRethrown;
import static org.junit.jupiter.api.Assertions.*;

@DisplayName("a language's fact-name check that fails unexpectedly is reported like a rejected name")
class FactNameCheckFailureTest {

    private final StatefulRulesEngine<Map<String, Object>> engine = new StatefulRulesEngine<>(HashMap::new);

    /** Registers a language named x whose checkFactName calls {@code check}, and loads one rule written in it. */
    private void load(Consumer<String> check) {
        engine.registerLanguage(new ExpressionLanguage() {
            @Override
            public String name() {
                return "x";
            }

            @Override
            public ExpressionCompiler newCompiler(CompileContext context) {
                return new ExpressionCompiler() {
                    @Override
                    public CompiledCondition compileCondition(String source) {
                        return (evaluation, session) -> true;
                    }

                    @Override
                    public CompiledAction compileAction(String source) {
                        return (action, session) -> {
                        };
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
        });
        engine.setRuleList(List.of(Rule.builder().ruleName("r").language("x").condition("c").action("a").build()));
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
}
