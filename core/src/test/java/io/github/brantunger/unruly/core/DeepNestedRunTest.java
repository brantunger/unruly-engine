package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RuleListener;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
import io.github.brantunger.unruly.api.RunContext;
import io.github.brantunger.unruly.api.RunOptions;
import io.github.brantunger.unruly.api.RunResult;
import io.github.brantunger.unruly.api.exception.ExpressionKind;
import io.github.brantunger.unruly.api.exception.RuleExecutionException;
import io.github.brantunger.unruly.api.language.ActionContext;
import io.github.brantunger.unruly.api.language.ActionResult;
import io.github.brantunger.unruly.api.language.CompileContext;
import io.github.brantunger.unruly.api.language.CompiledAction;
import io.github.brantunger.unruly.api.language.CompiledCondition;
import io.github.brantunger.unruly.api.language.Expression;
import io.github.brantunger.unruly.api.language.ExpressionCompiler;
import io.github.brantunger.unruly.api.language.ExpressionLanguage;
import io.github.brantunger.unruly.api.language.Session;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A failure many nested runs deep. Each nested {@code run()} adds a link to the cause chain of what the run around it
 * throws, and the engine reads only 100 links of a chain, so a failure or an {@link Error} more than 100 runs down
 * was out of reach: the message repeated {@code a nested run() failed} once per 100 runs, and a cancelled run reported
 * a stop naming no rule instead of its rule's failure. Each run's failure now carries both from the run below it.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
@DisplayName("a failure hundreds of nested runs deep is described once, and an Error there still fails the rule")
class DeepNestedRunTest {

    /** More than a thousand nested runs need more stack than a test thread has by default. */
    private static final long LARGE_STACK_BYTES = 1L << 29;

    /**
     * Long enough for every nested run to start before it passes: 150 of them take tens of milliseconds. The innermost
     * one then waits for it, so the test doesn't depend on how long anything takes as long as they all start in time,
     * which {@link #deepErrorPastTheDeadline} checks.
     */
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    /** How long a test waits for its runs to finish before it fails rather than hang. */
    private static final long JOIN_MILLIS = TimeUnit.SECONDS.toMillis(30);

    private static final String ONE_FAILURE = "Failed to execute action for rule 'r': a nested run() failed: "
            + "Failed to execute action for rule 'r': ";

    /**
     * A language whose only action, {@code nest}, runs the engine in the fact {@code engine} again with the fact
     * {@code n} one lower, until {@code n} is 0. There it throws, after doing what the fact {@code bottom} says:
     * {@code fail} throws an exception, {@code interrupt} interrupts the thread and throws an {@link AssertionError},
     * and {@code deadline} waits for the run's deadline to pass and throws an {@link AssertionError}. The innermost
     * action sets the fact {@code reached}, so a test can tell every run started. With the fact {@code wrap} set, each
     * level wraps the nested run's failure in an exception of its own, as a language might.
     */
    private static final class NestingLanguage implements ExpressionLanguage {

        @Override
        public String name() {
            return "nesting";
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
                    return (action, session) -> {
                        nest(action);
                        return ActionResult.done();
                    };
                }

                @Override
                public Session newSession() {
                    return Session.none();
                }
            };
        }

        @SuppressWarnings("unchecked")
        private static void nest(ActionContext action) {
            Map<String, Object> facts = action.facts();
            int n = (Integer) facts.get("n");
            if (n == 0) {
                ((AtomicBoolean) facts.get("reached")).set(true);
                switch ((String) facts.get("bottom")) {
                    case "interrupt" -> Thread.currentThread().interrupt();
                    case "deadline" -> {
                        while (!action.isCancelled()) {
                            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
                        }
                    }
                    default -> throw new IllegalStateException("bottom failure");
                }
                throw new AssertionError("bottom error");
            }
            FactMap<Object> below = new FactMap<>();
            facts.forEach(below::setValue);
            below.setValue("n", n - 1);
            RulesEngine<Map<String, Object>> engine = (RulesEngine<Map<String, Object>>) facts.get("engine");
            try {
                engine.run(below);
            } catch (RuleExecutionException nested) {
                if (Boolean.TRUE.equals(facts.get("wrap"))) {
                    throw new IllegalStateException("wrapped", nested);
                }
                throw nested;
            }
        }
    }

    /**
     * What the outermost run reported: the exception it threw, and the callbacks its own rule and run got; and whether
     * the innermost run started.
     */
    private record Outermost(Throwable thrown, List<RuleExecutionException> onError,
                             List<RuntimeException> onRunError, boolean reached) {
    }

    /** Records the callbacks of the outermost run only: the nested runs call the same listener. */
    private static final class OutermostListener implements RuleListener {
        private final List<RuleExecutionException> onError = new ArrayList<>();
        private final List<RuntimeException> onRunError = new ArrayList<>();
        private int depth;

        @Override
        public void beforeRun(RunContext run) {
            depth++;
        }

        @Override
        public void afterRun(RunContext run, RunResult<?> result) {
            depth--;
        }

        @Override
        public void onRunError(RunContext run, RuntimeException error) {
            if (depth == 1) {
                onRunError.add(error);
            }
            depth--;
        }

        @Override
        public void onError(Rule rule, RuleExecutionException error) {
            if (depth == 1) {
                onError.add(error);
            }
        }
    }

    /**
     * Runs the rule {@code r} {@code depth} runs deep, on a thread with a large stack, and collects what the outermost
     * run reported.
     *
     * @param depth   How many runs the outermost one starts, one inside the other
     * @param bottom  What the innermost action does before it throws; see {@link NestingLanguage}
     * @param wrap    Whether each level wraps the nested run's failure in an exception of its own
     * @param options The outermost run's options
     * @return What the outermost run reported
     */
    private static Outermost runNested(int depth, String bottom, boolean wrap, RunOptions options)
            throws InterruptedException {
        OutermostListener listener = new OutermostListener();
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new)
                .language(new NestingLanguage()).listener(listener).build();
        engine.load(List.of(Rule.builder().ruleName("r").condition("true").action("nest").build()));
        FactMap<Object> facts = new FactMap<>();
        facts.setValue("engine", engine);
        facts.setValue("n", depth);
        facts.setValue("bottom", bottom);
        facts.setValue("wrap", wrap);
        AtomicBoolean reached = new AtomicBoolean();
        facts.setValue("reached", reached);
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Thread thread = new Thread(null, () -> {
            try {
                engine.runWithResult(facts, options);
            } catch (Throwable e) {
                thrown.set(e);
            }
        }, "deep", LARGE_STACK_BYTES);
        // A daemon joined with a bound, and interrupted on the way out: a run that hangs fails the test instead of
        // holding up the build with a thread that reserved 512 MB.
        thread.setDaemon(true);
        thread.start();
        try {
            thread.join(JOIN_MILLIS);
            assertFalse(thread.isAlive(), "the nested runs didn't finish");
        } finally {
            thread.interrupt();
        }
        return new Outermost(thrown.get(), listener.onError, listener.onRunError, reached.get());
    }

    private static int count(String text, String part) {
        return text.split(java.util.regex.Pattern.quote(part), -1).length - 1;
    }

    @ParameterizedTest(name = "{0} runs deep")
    @ValueSource(ints = {150, 1_000})
    @DisplayName("a failure more than 100 runs deep reads as one nested failure, as a failure one run deep does")
    void deepFailureDescribedOnce(int depth) throws InterruptedException {
        Outermost outermost = runNested(depth, "fail", false, RunOptions.defaults());

        RuleExecutionException failure = assertInstanceOf(RuleExecutionException.class, outermost.thrown());
        assertEquals(ONE_FAILURE + "bottom failure", failure.getMessage());
    }

    @ParameterizedTest(name = "{0} runs deep")
    @ValueSource(ints = {60, 150})
    @DisplayName("a language that wraps each nested failure doesn't repeat it either")
    void deepWrappedFailureDescribedOnce(int depth) throws InterruptedException {
        Outermost outermost = runNested(depth, "fail", true, RunOptions.defaults());

        RuleExecutionException failure = assertInstanceOf(RuleExecutionException.class, outermost.thrown());
        assertEquals(ONE_FAILURE + "bottom failure", failure.getMessage());
        assertEquals(1, count(failure.getMessage(), "a nested run() failed: "), failure.getMessage());
    }

    /**
     * Asserts that the outermost run reported its rule's failure, caused by the nested run's failure, rather than a
     * stop, and that its rule's callback and its run's got that failure.
     *
     * @param outermost What the outermost run reported
     */
    private static void assertRuleFailed(Outermost outermost) {
        RuleExecutionException failure = assertInstanceOf(RuleExecutionException.class, outermost.thrown());
        assertEquals("r", failure.getRuleName(), "the outermost run reported a stop: " + failure.getMessage());
        assertEquals(ExpressionKind.ACTION, failure.getExpressionKind());
        RuleExecutionException nested = assertInstanceOf(RuleExecutionException.class, failure.getCause(),
                "the cause isn't the nested run's failure");
        assertEquals("r", nested.getRuleName());
        assertEquals(ONE_FAILURE + "bottom error", failure.getMessage());
        assertEquals(List.of(failure), outermost.onError(), "the rule's callback wasn't closed with its failure");
        assertEquals(List.of(failure), outermost.onRunError());
    }

    @ParameterizedTest(name = "{0} runs deep")
    @ValueSource(ints = {100, 101, 150})
    @DisplayName("an interrupted run whose Error is 100 or more nested runs down fails its rule rather than stopping")
    void deepErrorOnInterrupt(int depth) throws InterruptedException {
        Outermost outermost = runNested(depth, "interrupt", false, RunOptions.defaults());

        assertRuleFailed(outermost);
    }

    @ParameterizedTest(name = "{0} runs deep")
    @ValueSource(ints = {100, 101, 150})
    @DisplayName("a run past its deadline whose Error is 100 or more nested runs down fails its rule")
    void deepErrorPastTheDeadline(int depth) throws InterruptedException {
        Outermost outermost = runNested(depth, "deadline", false, RunOptions.withTimeoutOf(TIMEOUT));

        assertTrue(outermost.reached(), "a nested run started after the deadline had passed; raise TIMEOUT");
        assertRuleFailed(outermost);
    }
}
