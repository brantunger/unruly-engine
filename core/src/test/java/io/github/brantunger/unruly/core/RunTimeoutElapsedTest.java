package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
import io.github.brantunger.unruly.api.exception.RuleExecutionException;
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

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A run is never told to stop before its timeout has gone by, as {@link System#nanoTime()} measures it from the call.
 * A deadline set and checked with the system clock was: that clock moves in steps, and runs a little ahead of
 * {@code nanoTime()} or behind it. Only the public API is used, so the same test runs against any version.
 */
@DisplayName("a run is never told to stop before its timeout has gone by")
class RunTimeoutElapsedTest {

    /** A language whose condition spins until its run is cancelled, and records when that was. */
    private static ExpressionLanguage spinning(AtomicLong cancelledAt) {
        return new ExpressionLanguage() {
            @Override
            public String name() {
                return "spinning";
            }

            @Override
            public ExpressionCompiler newCompiler(CompileContext context) {
                return new ExpressionCompiler() {
                    @Override
                    public CompiledCondition compileCondition(Expression source) {
                        return (evaluation, session) -> {
                            while (!evaluation.isCancelled()) {
                                Thread.onSpinWait();
                            }
                            cancelledAt.set(System.nanoTime());
                            return true;
                        };
                    }

                    @Override
                    public CompiledAction compileAction(Expression source) {
                        return (action, session) -> ActionResult.done();
                    }

                    @Override
                    public Session newSession() {
                        return Session.none();
                    }
                };
            }
        };
    }

    @Test
    @DisplayName("a condition that waits for the run to be cancelled waits at least the run's timeout")
    void cancelledNoSoonerThanTheTimeout() {
        Duration timeout = Duration.ofMillis(2);
        AtomicLong cancelledAt = new AtomicLong();
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new)
                .language(spinning(cancelledAt)).runTimeout(timeout).build();
        engine.load(List.of(Rule.builder().ruleName("r").condition("c").action("a").build()));

        // Many short runs, because a deadline on the system clock passed early only now and then.
        for (int i = 0; i < 300; i++) {
            long start = System.nanoTime();
            assertThrows(RuleExecutionException.class, () -> engine.run(new FactMap<>()));
            long elapsed = cancelledAt.get() - start;

            assertTrue(elapsed >= timeout.toNanos(), "run " + i + " was cancelled after " + elapsed + " ns");
        }
    }
}
