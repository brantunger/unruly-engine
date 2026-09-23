package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RulesEngine;
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

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static io.github.brantunger.unruly.TestLogs.logsOf;
import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code load()} on a closed engine throws {@link IllegalStateException} before it looks at the rule list, as
 * {@code validate()} does, so what the list holds can't change the exception, and nothing is compiled or logged. A
 * {@code load()} that was already compiling when {@code close()} ran is the one exception: it finishes compiling, and
 * then either fails as that list fails or finds the engine closed.
 */
@DisplayName("load() on a closed engine")
class ClosedEngineLoadTest {

    private static final String CLOSED = "The engine is closed";

    /**
     * A language that counts the compilers it creates and closes. A condition is always true, except one whose text
     * is {@code bad}, which fails to compile. Given latches, each compiler it creates says so on {@code compiling}
     * and then waits for {@code release}, so a test can close the engine while a load is compiling.
     */
    private static final class CountingLanguage implements ExpressionLanguage {
        final AtomicInteger compilersCreated = new AtomicInteger();
        final AtomicInteger compilersClosed = new AtomicInteger();
        private final CountDownLatch compiling;
        private final CountDownLatch release;

        CountingLanguage() {
            this(new CountDownLatch(0), new CountDownLatch(0));
        }

        CountingLanguage(CountDownLatch compiling, CountDownLatch release) {
            this.compiling = compiling;
            this.release = release;
        }

        @Override
        public String name() {
            return "counting";
        }

        @Override
        public ExpressionCompiler newCompiler(CompileContext context) {
            compilersCreated.incrementAndGet();
            compiling.countDown();
            try {
                if (!release.await(30, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("the test never released the compiler");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            return new ExpressionCompiler() {
                @Override
                public CompiledCondition compileCondition(Expression expression) {
                    if ("bad".equals(expression.text())) {
                        throw new IllegalArgumentException("syntax error in condition 'bad'");
                    }
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
                public void close() {
                    compilersClosed.incrementAndGet();
                }
            };
        }
    }

    private static Rule rule(String name, String condition) {
        return Rule.builder().ruleName(name).condition(condition).action("a").build();
    }

    private static RulesEngine<Map<String, Object>> engine(CountingLanguage language) {
        return RulesEngineBuilder.<Map<String, Object>>firstMatch(HashMap::new).language(language)
                .defaultLanguage("counting").build();
    }

    private static RulesEngine<Map<String, Object>> closedEngine(CountingLanguage language) {
        RulesEngine<Map<String, Object>> engine = engine(language);
        engine.close();
        return engine;
    }

    @Test
    @DisplayName("a rule list that doesn't compile is rejected as closed, not as broken")
    void brokenListRejectedAsClosed() {
        RulesEngine<Map<String, Object>> engine = closedEngine(new CountingLanguage());
        List<Rule> rules = List.of(rule("a", "bad"));

        assertEquals(CLOSED, assertThrows(IllegalStateException.class, () -> engine.load(rules)).getMessage());
    }

    @Test
    @DisplayName("a rule list with a duplicate name is rejected as closed")
    void duplicateNameRejectedAsClosed() {
        RulesEngine<Map<String, Object>> engine = closedEngine(new CountingLanguage());
        List<Rule> rules = List.of(rule("a", "true"), rule("a", "true"));

        assertEquals(CLOSED, assertThrows(IllegalStateException.class, () -> engine.load(rules)).getMessage());
    }

    @Test
    @DisplayName("a rule list with a null rule is rejected as closed")
    void nullRuleRejectedAsClosed() {
        RulesEngine<Map<String, Object>> engine = closedEngine(new CountingLanguage());
        List<Rule> rules = Arrays.asList(rule("a", "true"), null);

        assertEquals(CLOSED, assertThrows(IllegalStateException.class, () -> engine.load(rules)).getMessage());
    }

    @Test
    @DisplayName("a rule list that compiles is rejected as closed")
    void validListRejectedAsClosed() {
        RulesEngine<Map<String, Object>> engine = closedEngine(new CountingLanguage());
        List<Rule> rules = List.of(rule("a", "true"));

        assertEquals(CLOSED, assertThrows(IllegalStateException.class, () -> engine.load(rules)).getMessage());
    }

    @Test
    @DisplayName("a null rule list is still a NullPointerException")
    void nullListStillRejected() {
        RulesEngine<Map<String, Object>> engine = closedEngine(new CountingLanguage());

        assertThrows(NullPointerException.class, () -> engine.load(null));
    }

    @Test
    @DisplayName("no compiler is created, so none is closed")
    void noCompilerCreated() {
        CountingLanguage language = new CountingLanguage();
        RulesEngine<Map<String, Object>> engine = closedEngine(language);
        List<Rule> rules = List.of(rule("a", "true"));

        assertThrows(IllegalStateException.class, () -> engine.load(rules));

        assertEquals(0, language.compilersCreated.get(), "the closed engine compiled the rules");
        assertEquals(0, language.compilersClosed.get());
    }

    @Test
    @DisplayName("nothing is logged at ERROR, whatever the rule list holds")
    void nothingLoggedAtError() {
        RulesEngine<Map<String, Object>> engine = closedEngine(new CountingLanguage());
        List<Rule> broken = List.of(rule("a", "bad"));
        List<Rule> duplicate = List.of(rule("a", "true"), rule("a", "true"));

        String logs = logsOf(() -> {
            assertThrows(RuntimeException.class, () -> engine.load(broken));
            assertThrows(RuntimeException.class, () -> engine.load(duplicate));
        });

        assertFalse(logs.contains("ERROR " + EngineLogs.ENGINE_LOGGER), logs);
    }

    @Test
    @DisplayName("a load that was compiling when the engine closed finds it closed, and closes what it compiled")
    void loadCompilingWhenClosedFindsTheEngineClosed() throws InterruptedException {
        CountingLanguage language = new CountingLanguage(new CountDownLatch(1), new CountDownLatch(1));

        Throwable thrown = loadWhileClosing(language, List.of(rule("a", "true")));

        assertInstanceOf(IllegalStateException.class, thrown);
        assertEquals(CLOSED, thrown.getMessage());
        assertEquals(1, language.compilersCreated.get());
        assertEquals(1, language.compilersClosed.get(), "the rules compiled after the engine closed stayed open");
    }

    @Test
    @DisplayName("a load that was compiling when the engine closed still fails as its rule list fails")
    void loadCompilingWhenClosedStillFailsToCompile() throws InterruptedException {
        CountingLanguage language = new CountingLanguage(new CountDownLatch(1), new CountDownLatch(1));

        Throwable thrown = loadWhileClosing(language, List.of(rule("a", "bad")));

        // The caveat close() documents: the load was past the closed check, so the list's own failure wins.
        assertInstanceOf(RuleCompilationException.class, thrown);
        assertTrue(thrown.getMessage().contains("syntax error in condition 'bad'"), thrown.getMessage());
        assertEquals(1, language.compilersCreated.get());
        assertEquals(1, language.compilersClosed.get(), "the compiler of the rule list that failed stayed open");
    }

    /**
     * Loads {@code rules} on another thread and closes the engine while that load is creating its compiler, after it
     * has checked whether the engine is closed and before it compiles anything.
     *
     * @param language A language created with latches, which holds the load until the engine is closed
     * @param rules    The rule list to load
     * @return What the load threw, or {@code null} if it returned
     */
    private static Throwable loadWhileClosing(CountingLanguage language, List<Rule> rules)
            throws InterruptedException {
        RulesEngine<Map<String, Object>> engine = engine(language);
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Thread loader = new Thread(() -> {
            try {
                engine.load(rules);
            } catch (RuntimeException e) {
                thrown.set(e);
            }
        }, "loader");
        loader.start();
        try {
            assertTrue(language.compiling.await(30, TimeUnit.SECONDS), "the load never began compiling");
            engine.close();
        } finally {
            language.release.countDown();
            loader.join(TimeUnit.SECONDS.toMillis(30));
        }
        assertFalse(loader.isAlive(), "the load never returned");
        return thrown.get();
    }
}
