package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RuleSetInfo;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
import io.github.brantunger.unruly.api.language.ActionResult;
import io.github.brantunger.unruly.api.language.CompileContext;
import io.github.brantunger.unruly.api.language.CompiledAction;
import io.github.brantunger.unruly.api.language.CompiledCondition;
import io.github.brantunger.unruly.api.language.Expression;
import io.github.brantunger.unruly.api.language.ExpressionCompiler;
import io.github.brantunger.unruly.api.language.ExpressionLanguage;
import io.github.brantunger.unruly.api.language.Session;
import io.github.brantunger.unruly.api.language.ToyExpressionLanguage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * #941: {@code close()} marks the engine closed before it lets go of the rules, so a call that reads them while it
 * closes finds either the rules whole or the engine closed, never an engine with no rules loaded. The calls are made
 * from inside {@code close()}, between its two writes, through a step {@link Faults} watches.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
@DisplayName("#941: a call made while close() runs finds the rules or a closed engine, never no rules loaded")
class CloseOrderTest {

    private static final String CLOSED = "The engine is closed";

    private static final Rule HIGH = Rule.builder().ruleName("high").priority(1).condition("true")
            .action("put high 1").build();

    /**
     * A language that counts the compilers it makes and closes, so a test can tell the rules were retired. Its
     * conditions are true and its actions do nothing.
     */
    private static final class Counting implements ExpressionLanguage {
        private final AtomicInteger compilers = new AtomicInteger();
        private final AtomicInteger compilersClosed = new AtomicInteger();

        @Override
        public String name() {
            return "counting";
        }

        @Override
        public ExpressionCompiler newCompiler(CompileContext context) {
            compilers.incrementAndGet();
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
                    return new Session() {
                    };
                }

                @Override
                public void close() {
                    compilersClosed.incrementAndGet();
                }
            };
        }

        void assertAllClosed(String when) {
            assertTrue(compilers.get() > 0, "the rules made a compiler");
            assertEquals(compilers.get(), compilersClosed.get(), when + ": every compiler is closed");
        }
    }

    @AfterEach
    void takeBackTheWatch() {
        Faults.clear();
    }

    private static RulesEngine<Map<String, Object>> engine() {
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>firstMatch(HashMap::new)
                .language(new ToyExpressionLanguage()).build();
        engine.load(List.of(HIGH));
        return engine;
    }

    private static RulesEngine<Map<String, Object>> engine(Counting language) {
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>firstMatch(HashMap::new)
                .language(language).build();
        engine.load(List.of(Rule.builder().ruleName("r").priority(1).condition("c").action("a").build()));
        return engine;
    }

    // What the call returned, or what it threw.
    private static Object outcome(Supplier<?> call) {
        try {
            return call.get();
        } catch (RuntimeException e) {
            return e;
        }
    }

    private static void assertClosed(Object outcome, String call) {
        IllegalStateException thrown = assertInstanceOf(IllegalStateException.class, outcome,
                call + " fails on a closed engine");
        assertEquals(CLOSED, thrown.getMessage(), call + " says the engine is closed");
    }

    @Test
    @DisplayName("once close() has marked the engine closed, a run and rules() still see the rules whole")
    void callsWhileClosingSeeTheRulesOrAClosedEngine() {
        RulesEngine<Map<String, Object>> engine = engine();
        RuleSetInfo loaded = engine.rules();
        AtomicReference<Object> ran = new AtomicReference<>();
        AtomicReference<Object> seen = new AtomicReference<>();
        AtomicReference<Object> validated = new AtomicReference<>();
        Faults.watch(Faults.Step.CLOSE_MARKED, () -> {
            ran.set(outcome(() -> engine.run(new FactMap<>())));
            seen.set(outcome(engine::rules));
            validated.set(outcome(() -> engine.validate(List.of(HIGH))));
        });

        engine.close();

        assertAll(
                () -> assertEquals(Map.of("high", 1), ran.get(),
                        "a run while closing runs the rules, not 'load() must be called'"),
                () -> assertEquals(loaded, seen.get(),
                        "rules() while closing reports the rules, not an empty rule list"),
                () -> assertClosed(validated.get(), "validate() while closing"));
        assertClosed(outcome(() -> engine.run(new FactMap<>())), "run() after close()");
        assertClosed(outcome(engine::rules), "rules() after close()");
    }

    @Test
    @DisplayName("a close() made from inside close(), once the engine is marked closed, returns and leaves it closed")
    void aCloseFromInsideCloseReturns() throws Throwable {
        Counting language = new Counting();
        RulesEngine<Map<String, Object>> engine = engine(language);

        onAnotherThread(() -> {
            Faults.watch(Faults.Step.CLOSE_MARKED, engine::close);
            engine.close();
        });

        language.assertAllClosed("after the close() around the nested one");
        assertClosed(outcome(() -> engine.run(new FactMap<>())), "run() after close()");
        assertClosed(outcome(engine::rules), "rules() after close()");
        onAnotherThread(engine::close);
    }

    @Test
    @DisplayName("a close() that fails once it has marked the engine closed still lets go of the rules")
    void aCloseThatFailsOnceMarkedStillLetsGoOfTheRules() throws Throwable {
        Counting language = new Counting();
        RulesEngine<Map<String, Object>> engine = engine(language);
        StackOverflowError overflow = new StackOverflowError("watching close()");
        Faults.watch(Faults.Step.CLOSE_MARKED, () -> {
            throw overflow;
        });

        assertSame(overflow, assertThrows(StackOverflowError.class, engine::close));

        assertEquals(0, language.compilersClosed.get(), "the failed close() hasn't retired the rules");
        assertClosed(outcome(() -> engine.run(new FactMap<>())), "run() after the failed close()");
        assertClosed(outcome(engine::rules), "rules() after the failed close()");
        // Closing again retires the rules the failed close() detached, rather than linking them to themselves.
        onAnotherThread(engine::close);
        language.assertAllClosed("after closing again");
    }

    @Test
    @DisplayName("a watch runs on the thread it was set for alone, and once")
    void aWatchRunsOnItsOwnThreadOnce() throws Throwable {
        AtomicInteger runs = new AtomicInteger();
        Faults.watch(Faults.Step.CLOSE_MARKED, runs::incrementAndGet);

        onAnotherThread(() -> engine().close());
        assertEquals(0, runs.get(), "closing on another thread doesn't run it");
        engine().close();
        engine().close();

        assertEquals(1, runs.get(), "it runs the first time this thread reaches the step, and not again");
    }

    @Test
    @DisplayName("a step that is only watched can't be made to fail, and one that is made to fail can't be watched")
    void watchedAndFailedStepsAreKeptApart() {
        IllegalArgumentException injected = assertThrows(IllegalArgumentException.class,
                () -> Faults.inject(Faults.Step.CLOSE_MARKED, 1, new StackOverflowError()));
        IllegalArgumentException following = assertThrows(IllegalArgumentException.class,
                () -> Faults.injectThen(Faults.Step.RUN_SCOPE_MAP_MAKING, new StackOverflowError()));
        IllegalArgumentException watched = assertThrows(IllegalArgumentException.class,
                () -> Faults.watch(Faults.Step.SETTLING, () -> {
                }));

        assertEquals("CLOSE_MARKED can only be watched", injected.getMessage());
        assertEquals("RUN_SCOPE_MAP_MAKING can only be watched", following.getMessage());
        assertEquals("SETTLING is made to fail, not watched", watched.getMessage());
    }

    // Runs body on a daemon thread, so one that hangs, as it would looping under the engine's lock, fails the test
    // rather than holding it up, and rethrows what body threw.
    private static void onAnotherThread(Runnable body) throws Throwable {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread other = new Thread(() -> {
            try {
                body.run();
            } catch (Throwable t) {
                failure.set(t);
            }
        });
        other.setDaemon(true);
        other.start();
        other.join(TimeUnit.SECONDS.toMillis(10));

        assertFalse(other.isAlive(), "the other thread finished rather than hanging");
        Throwable thrown = failure.get();
        if (thrown != null) {
            throw thrown;
        }
    }
}
