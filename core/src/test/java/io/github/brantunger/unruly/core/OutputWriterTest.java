package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.OutputWriter;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RuleListener;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("the engine sets the properties an action returns with the engine's OutputWriter")
class OutputWriterTest {

    /**
     * A language whose conditions are always true. An action is {@code name=value} pairs separated by {@code ;},
     * returned as properties, or {@code done} for an action that changes nothing.
     */
    private static final ExpressionLanguage PATCH = new ExpressionLanguage() {
        @Override
        public String name() {
            return "patch";
        }

        @Override
        public ExpressionCompiler newCompiler(CompileContext context) {
            return new ExpressionCompiler() {
                @Override
                public CompiledCondition compileCondition(Expression source) {
                    return (evaluation, session) -> true;
                }

                @Override
                public CompiledAction compileAction(Expression source) {
                    if ("done".equals(source.text())) {
                        return (action, session) -> ActionResult.done();
                    }
                    Map<String, Object> properties = new LinkedHashMap<>();
                    for (String pair : source.text().split(";")) {
                        String[] nameAndValue = pair.split("=", 2);
                        properties.put(nameAndValue[0], nameAndValue[1]);
                    }
                    ActionResult result = ActionResult.set(properties);
                    return (action, session) -> result;
                }

                @Override
                public Session newSession() {
                    return Session.none();
                }
            };
        }
    };

    /** An output bean whose setter interrupts its own thread and then gives up. */
    public static final class InterruptingBean {
        public void setRate(String rate) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("gave up: " + rate);
        }
    }

    /**
     * An output bean whose setter gives up once the run's deadline, kept in {@code deadline}, has passed, and sets
     * {@code reached} when it's called.
     */
    public static final class LateBean {
        private final AtomicReference<Instant> deadline;
        private final AtomicBoolean reached;

        LateBean(AtomicReference<Instant> deadline, AtomicBoolean reached) {
            this.deadline = deadline;
            this.reached = reached;
        }

        public void setRate(String rate) throws InterruptedException {
            reached.set(true);
            waitPast(deadline);
            throw new IllegalStateException("gave up: " + rate);
        }
    }

    private static Rule rule(String name, int priority, String action) {
        return Rule.builder().ruleName(name).priority(priority).language("patch").condition("true").action(action)
                .build();
    }

    private static RulesEngineBuilder<Map<String, Object>> builder() {
        return RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new).language(PATCH);
    }

    /**
     * A language whose conditions are always true, and whose every action returns {@code rate=4.5} and keeps the
     * run's deadline in {@code deadline}, so the output can be written once the deadline has passed.
     */
    private static ExpressionLanguage keeping(AtomicReference<Instant> deadline) {
        return new ExpressionLanguage() {
            @Override
            public String name() {
                return "keeping";
            }

            @Override
            public ExpressionCompiler newCompiler(CompileContext context) {
                return new ExpressionCompiler() {
                    @Override
                    public CompiledCondition compileCondition(Expression source) {
                        return (evaluation, session) -> true;
                    }

                    @Override
                    public CompiledAction compileAction(Expression source) {
                        return (action, session) -> {
                            deadline.set(action.deadline());
                            return ActionResult.set(Map.of("rate", "4.5"));
                        };
                    }

                    @Override
                    public Session newSession() {
                        return Session.none();
                    }
                };
            }
        };
    }

    /** The one rule of the engines whose runs keep their deadline: see {@link #keeping}. */
    private static Rule keepingRule() {
        return Rule.builder().ruleName("r").priority(1).language("keeping").condition("true").action("rate").build();
    }

    /**
     * An engine with one rule, whose action returns {@code rate=4.5} and keeps the run's deadline in
     * {@code deadline}, so its writer can wait until the deadline has passed. A run has a second, far more than it
     * needs to reach the writer, which sets {@code reached} when it does. Runs write to {@code output}.
     */
    private static RulesEngine<Map<String, Object>> timedEngine(Map<String, Object> output,
                                                                AtomicReference<Instant> deadline,
                                                                AtomicBoolean reached,
                                                                OutputWriter<Map<String, Object>> writer) {
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>allMatches(() -> output)
                .language(keeping(deadline)).runTimeout(Duration.ofSeconds(1)).outputWriter((out, property, value) -> {
                    reached.set(true);
                    writer.set(out, property, value);
                }).build();
        engine.load(List.of(keepingRule()));
        return engine;
    }

    /** Returns once the run's deadline has passed, however long that takes, rather than after a fixed time. */
    private static void waitPast(AtomicReference<Instant> deadline) throws InterruptedException {
        while (!Instant.now().isAfter(deadline.get())) {
            Thread.sleep(5);
        }
    }

    @Test
    @DisplayName("a writer is given the output object and each property, in the order the actions returned them")
    void writerSetsEachProperty() {
        List<String> written = new ArrayList<>();
        Map<String, Object> output = new HashMap<>();
        RulesEngine<Map<String, Object>> engine = builder()
                .outputWriter((out, property, value) -> written.add(property + "=" + value + " on " + out.hashCode()))
                .build();
        engine.load(List.of(rule("high", 2, "rate=4.5;approved=true"), rule("low", 1, "rate=6.9")));

        Map<String, Object> result = engine.run(new FactMap<>());

        assertEquals(List.of("rate=4.5 on " + result.hashCode(), "approved=true on " + result.hashCode(),
                "rate=6.9 on " + result.hashCode()), written);
        assertEquals(Map.of(), output);
        assertEquals(Map.of(), result, "the writer wrote nothing to the output itself");
    }

    @Test
    @DisplayName("an action that changed the output itself doesn't reach the writer")
    void doneSkipsTheWriter() {
        List<String> written = new ArrayList<>();
        RulesEngine<Map<String, Object>> engine = builder()
                .outputWriter((out, property, value) -> written.add(property))
                .build();
        engine.load(List.of(rule("r", 1, "done")));

        engine.run(new FactMap<>());

        assertEquals(List.of(), written);
    }

    @Test
    @DisplayName("a writer that throws fails the rule, naming it and the property, and listeners are told")
    void writerFailureFailsTheRule() {
        List<String> errors = new ArrayList<>();
        RuleListener listener = new RuleListener() {
            @Override
            public void onError(Rule rule, RuleExecutionException error) {
                errors.add(rule.getRuleName() + ": " + error.getMessage());
            }
        };
        RulesEngine<Map<String, Object>> engine = builder().listener(listener)
                .outputWriter((out, property, value) -> {
                    throw new IllegalStateException("no room for " + property);
                })
                .build();
        engine.load(List.of(rule("r", 1, "rate=4.5")));

        RuleExecutionException ex = assertThrows(RuleExecutionException.class, () -> engine.run(new FactMap<>()));

        assertEquals("Failed to set 'rate' on the output for rule 'r': no room for rate", ex.getMessage());
        assertEquals(List.of("r: " + ex.getMessage()), errors);
    }

    @Test
    @DisplayName("a writer that throws InterruptedException stops the run, as an interrupted action does")
    void interruptedWriterStopsTheRun() {
        List<RuleExecutionException> errors = new ArrayList<>();
        RuleListener listener = new RuleListener() {
            @Override
            public void onError(Rule rule, RuleExecutionException error) {
                errors.add(error);
            }
        };
        InterruptedException interrupted = new InterruptedException("stop writing");
        RulesEngine<Map<String, Object>> engine = builder().listener(listener)
                .outputWriter((out, property, value) -> {
                    throw interrupted;
                })
                .build();
        engine.load(List.of(rule("r", 1, "rate=4.5")));

        RuleExecutionException ex;
        boolean statusSet;
        try {
            ex = assertThrows(RuleExecutionException.class, () -> engine.run(new FactMap<>()));
        } finally {
            // Cleared whatever happened, so no later test on this thread starts interrupted.
            statusSet = Thread.interrupted();
        }

        assertNull(ex.getRuleName(), ex.getMessage());
        assertInstanceOf(InterruptedException.class, ex.getCause());
        assertEquals(List.of(interrupted), List.of(ex.getSuppressed()));
        assertTrue(statusSet, "the interrupt status wasn't set again");
        assertEquals(List.of(ex), errors);
    }

    @Test
    @DisplayName("a writer on the last rule that returns past the deadline stops the run, and what it set stays set")
    void slowWriterOnTheLastRuleStopsAtTheDeadline() {
        Map<String, Object> output = new HashMap<>();
        AtomicReference<Instant> deadline = new AtomicReference<>();
        AtomicBoolean reached = new AtomicBoolean();
        RulesEngine<Map<String, Object>> engine = timedEngine(output, deadline, reached, (out, property, value) -> {
            waitPast(deadline);
            out.put(property, value);
        });

        RuleExecutionException ex = assertThrows(RuleExecutionException.class, () -> engine.run(new FactMap<>()));

        assertTrue(reached.get(), "the run passed its deadline before it reached the writer");
        assertNull(ex.getRuleName(), ex.getMessage());
        assertInstanceOf(TimeoutException.class, ex.getCause());
        assertEquals(Map.of("rate", "4.5"), output);
    }

    @Test
    @DisplayName("a writer that throws past the deadline stops the run, keeping what it threw")
    void writerThrowingPastTheDeadlineIsAStop() {
        IllegalStateException gaveUp = new IllegalStateException("gave up");
        AtomicReference<Instant> deadline = new AtomicReference<>();
        AtomicBoolean reached = new AtomicBoolean();
        RulesEngine<Map<String, Object>> engine = timedEngine(new HashMap<>(), deadline, reached,
                (out, property, value) -> {
                    waitPast(deadline);
                    throw gaveUp;
                });

        RuleExecutionException ex = assertThrows(RuleExecutionException.class, () -> engine.run(new FactMap<>()));

        assertTrue(reached.get(), "the run passed its deadline before it reached the writer");
        assertNull(ex.getRuleName(), ex.getMessage());
        assertInstanceOf(TimeoutException.class, ex.getCause());
        assertEquals(List.of(gaveUp), List.of(ex.getSuppressed()));
    }

    @Test
    @DisplayName("a writer that throws an Error past the deadline still fails the rule, as an action does")
    void writerErrorPastTheDeadlineIsStillAFailure() {
        AssertionError broke = new AssertionError("broke");
        AtomicReference<Instant> deadline = new AtomicReference<>();
        AtomicBoolean reached = new AtomicBoolean();
        RulesEngine<Map<String, Object>> engine = timedEngine(new HashMap<>(), deadline, reached,
                (out, property, value) -> {
                    waitPast(deadline);
                    throw broke;
                });

        RuleExecutionException ex = assertThrows(RuleExecutionException.class, () -> engine.run(new FactMap<>()));

        assertTrue(reached.get(), "the run passed its deadline before it reached the writer");
        assertEquals("r", ex.getRuleName());
        assertEquals("Failed to set 'rate' on the output for rule 'r': broke", ex.getMessage());
        assertSame(broke, ex.getCause());
    }

    @Test
    @DisplayName("a bean setter that interrupts its thread and throws stops the run under the default writer, which"
            + " reports it wrapped in an InvocationTargetException")
    void interruptedBeanSetterStopsTheRun() {
        List<RuleExecutionException> errors = new ArrayList<>();
        RuleListener listener = new RuleListener() {
            @Override
            public void onError(Rule rule, RuleExecutionException error) {
                errors.add(error);
            }
        };
        RulesEngine<InterruptingBean> engine = RulesEngineBuilder.<InterruptingBean>allMatches(InterruptingBean::new)
                .language(PATCH).listener(listener).build();
        engine.load(List.of(rule("r", 1, "rate=4.5")));

        RuleExecutionException ex;
        boolean statusSet;
        try {
            ex = assertThrows(RuleExecutionException.class, () -> engine.run(new FactMap<>()));
        } finally {
            // Cleared whatever happened, so no later test on this thread starts interrupted.
            statusSet = Thread.interrupted();
        }

        assertNull(ex.getRuleName(), ex.getMessage());
        assertInstanceOf(InterruptedException.class, ex.getCause());
        assertEquals(1, ex.getSuppressed().length, () -> List.of(ex.getSuppressed()).toString());
        assertInstanceOf(IllegalStateException.class, ex.getSuppressed()[0]);
        assertEquals("gave up: 4.5", ex.getSuppressed()[0].getMessage());
        assertTrue(statusSet, "the engine cleared the interrupt status");
        assertEquals(List.of(ex), errors);
    }

    @Test
    @DisplayName("a bean setter that throws past the deadline stops the run under the default writer, keeping what"
            + " it threw")
    void beanSetterThrowingPastTheDeadlineIsAStop() {
        AtomicReference<Instant> deadline = new AtomicReference<>();
        AtomicBoolean reached = new AtomicBoolean();
        List<RuleExecutionException> errors = new ArrayList<>();
        RuleListener listener = new RuleListener() {
            @Override
            public void onError(Rule rule, RuleExecutionException error) {
                errors.add(error);
            }
        };
        RulesEngine<LateBean> engine = RulesEngineBuilder.<LateBean>allMatches(() -> new LateBean(deadline, reached))
                .language(keeping(deadline)).runTimeout(Duration.ofSeconds(1)).listener(listener).build();
        engine.load(List.of(keepingRule()));

        RuleExecutionException ex = assertThrows(RuleExecutionException.class, () -> engine.run(new FactMap<>()));

        assertTrue(reached.get(), "the run passed its deadline before it reached the setter");
        assertNull(ex.getRuleName(), ex.getMessage());
        assertInstanceOf(TimeoutException.class, ex.getCause());
        assertEquals(1, ex.getSuppressed().length, () -> List.of(ex.getSuppressed()).toString());
        assertInstanceOf(IllegalStateException.class, ex.getSuppressed()[0]);
        assertEquals("gave up: 4.5", ex.getSuppressed()[0].getMessage());
        assertEquals(List.of(ex), errors);
    }

    @Test
    @DisplayName("the default writer puts properties into a Map output, like beansAndMaps()")
    void defaultWriterIsBeansAndMaps() {
        RulesEngine<Map<String, Object>> byDefault = builder().build();
        RulesEngine<Map<String, Object>> named = builder().outputWriter(OutputWriter.beansAndMaps()).build();
        byDefault.load(List.of(rule("r", 1, "rate=4.5")));
        named.load(List.of(rule("r", 1, "rate=4.5")));

        assertEquals(Map.of("rate", "4.5"), byDefault.run(new FactMap<>()));
        assertEquals(byDefault.run(new FactMap<>()), named.run(new FactMap<>()));
    }

    @Test
    @DisplayName("outputWriter() rejects null")
    void nullRejected() {
        assertEquals("writer must not be null", assertThrows(NullPointerException.class,
                () -> builder().outputWriter(null)).getMessage());
    }

    @Test
    @DisplayName("the default writer rejects a null or empty property for a Map output as for a bean")
    void defaultWriterRejectsNullAndEmptyProperties() {
        OutputWriter<Object> writer = OutputWriter.beansAndMaps();
        Map<String, Object> map = new HashMap<>();

        assertAll(
                () -> assertEquals("property must not be null", assertThrows(NullPointerException.class,
                        () -> writer.set(map, null, 1)).getMessage()),
                () -> assertEquals("property must not be empty", assertThrows(IllegalArgumentException.class,
                        () -> writer.set(map, "", 1)).getMessage()),
                () -> assertEquals("property must not be null", assertThrows(NullPointerException.class,
                        () -> writer.set(new StringBuilder(), null, 1)).getMessage()),
                () -> assertEquals("property must not be empty", assertThrows(IllegalArgumentException.class,
                        () -> writer.set(new StringBuilder(), "", 1)).getMessage()));
        assertEquals(Map.of(), map);
    }
}
