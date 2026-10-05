package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RuleListener;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
import io.github.brantunger.unruly.api.RunContext;
import io.github.brantunger.unruly.api.exception.RuleExecutionException;
import io.github.brantunger.unruly.api.language.ActionResult;
import io.github.brantunger.unruly.api.language.CompileContext;
import io.github.brantunger.unruly.api.language.CompiledAction;
import io.github.brantunger.unruly.api.language.CompiledCondition;
import io.github.brantunger.unruly.api.language.Expression;
import io.github.brantunger.unruly.api.language.ExpressionCompiler;
import io.github.brantunger.unruly.api.language.ExpressionLanguage;
import io.github.brantunger.unruly.api.language.Session;
import io.github.brantunger.unruly.api.language.ToyExpressionLanguage;
import io.github.brantunger.unruly.core.EngineLogs.Outcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static io.github.brantunger.unruly.core.EngineLogs.capture;
import static org.junit.jupiter.api.Assertions.*;

/**
 * A fatal {@link Error} that code outside the engine leaves only among the suppressed exceptions of what it throws, as
 * a {@code try}-with-resources does when its body throws and its resource's {@code close()} then hits an
 * {@link OutOfMemoryError}, is rethrown unchanged, as one in the cause chain is (#845), wherever the engine first
 * catches that code: a rule, a listener, the output supplier, a fact's getter, a language's compile, fact-name check
 * or {@code close()}, and a cancelled run. One the engine keeps on an exception it built itself isn't found there
 * again. Uses only API that predates the fix, so it compiles against the engine before it.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
@DisplayName("a fatal error left only in getSuppressed() is rethrown (#845)")
class SuppressedFatalErrorTest {

    /** How many suppressed exceptions an exception carries in the shapes that once used up the engine's reading. */
    private static final int SIBLINGS = 1_000;

    /** A resource whose {@code close()} fails as the JVM does when memory runs out. */
    private record Resource(Error error) implements AutoCloseable {

        @Override
        public void close() {
            throw error;
        }
    }

    /**
     * Returns what a {@code try}-with-resources throws when its body fails and its resource's {@code close()} then
     * throws {@code error}: the body's exception, carrying the error only as a suppressed exception.
     */
    private static IllegalStateException bodyFailed(String message, Error error) {
        try (Resource resource = new Resource(error)) {
            Objects.requireNonNull(resource);
            throw new IllegalStateException(message);
        } catch (IllegalStateException e) {
            return e;
        }
    }

    private static IllegalStateException bodyFailed(Error error) {
        return bodyFailed("body failed", error);
    }

    /**
     * A language whose conditions are {@code true} and whose actions do nothing, but for an expression named in
     * {@code code}, which runs that code: while it compiles when its text starts with {@code compile:}, and while it
     * runs otherwise. {@code checkFactName} and {@code close} run the code under those names, if any.
     */
    private record ScriptedLanguage(Map<String, Runnable> code) implements ExpressionLanguage {

        @Override
        public String name() {
            return "scripted";
        }

        @Override
        public ExpressionCompiler newCompiler(CompileContext context) {
            return new ExpressionCompiler() {
                @Override
                public CompiledCondition compileCondition(Expression expression) {
                    Runnable run = compiled(expression.text());
                    return (evaluation, session) -> {
                        run.run();
                        return true;
                    };
                }

                @Override
                public CompiledAction compileAction(Expression expression) {
                    Runnable run = compiled(expression.text());
                    return (action, session) -> {
                        run.run();
                        return ActionResult.done();
                    };
                }

                @Override
                public Session newSession() {
                    return Session.none();
                }

                @Override
                public void checkFactName(String name) {
                    code.getOrDefault("checkFactName", () -> { }).run();
                }

                @Override
                public void close() {
                    code.getOrDefault("close", () -> { }).run();
                }
            };
        }

        private Runnable compiled(String text) {
            if (text.startsWith("compile:")) {
                code.get(text).run();
            }
            return code.getOrDefault(text, () -> { });
        }
    }

    private static RulesEngine<Map<String, Object>> engine(Map<String, Runnable> code, Rule rule) {
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>firstMatch(HashMap::new)
                .language(new ScriptedLanguage(code)).build();
        engine.load(List.of(rule));
        return engine;
    }

    private static Rule rule(String condition, String action) {
        return Rule.builder().ruleName("r").condition(condition).action(action).build();
    }

    /** Asserts that {@code oom} itself was thrown, and returns what was logged at ERROR. */
    private static List<String> assertRethrown(OutOfMemoryError oom, Outcome<Throwable> outcome) {
        assertSame(oom, outcome.thrown(), () -> "expected the suppressed OutOfMemoryError, but got "
                + outcome.thrown() + "; logs: " + outcome.logs());
        return outcome.lines("ERROR");
    }

    @Test
    @DisplayName("from a rule's action, which run() used to throw as a RuleExecutionException")
    void fromAnAction() {
        OutOfMemoryError oom = new OutOfMemoryError("OOM in close()");
        RulesEngine<Map<String, Object>> engine = engine(Map.of("fails", () -> {
            throw bodyFailed(oom);
        }), rule("true", "fails"));

        List<String> errors = assertRethrown(oom, capture(() -> engine.run(new FactMap<>())));

        assertEquals(List.of("Failed to execute action for rule 'r': body failed"), errors);
    }

    @Test
    @DisplayName("from a rule's condition")
    void fromACondition() {
        OutOfMemoryError oom = new OutOfMemoryError("OOM in close()");
        RulesEngine<Map<String, Object>> engine = engine(Map.of("fails", () -> {
            throw bodyFailed(oom);
        }), rule("fails", "ok"));

        assertRethrown(oom, capture(() -> engine.run(new FactMap<>())));
    }

    @Test
    @DisplayName("from a RuleExecutionException the rule throws itself: the public type isn't the engine's own")
    void fromTheRulesOwnRuleExecutionException() {
        OutOfMemoryError oom = new OutOfMemoryError("OOM in close()");
        RulesEngine<Map<String, Object>> engine = engine(Map.of("fails", () -> {
            RuleExecutionException own = new RuleExecutionException("the rule's own");
            own.addSuppressed(oom);
            throw own;
        }), rule("true", "fails"));

        assertRethrown(oom, capture(() -> engine.run(new FactMap<>())));
    }

    @Test
    @DisplayName("several levels down, through suppressed exceptions that loop back on each other")
    void throughALoopOfSuppressedExceptions() {
        OutOfMemoryError oom = new OutOfMemoryError("OOM in close()");
        RulesEngine<Map<String, Object>> engine = engine(Map.of("fails", () -> {
            IllegalStateException a = new IllegalStateException("a");
            IllegalStateException b = new IllegalStateException("b", new IllegalArgumentException("c"));
            a.addSuppressed(b);
            b.addSuppressed(a);
            b.getCause().addSuppressed(bodyFailed(oom));
            throw new IllegalStateException("top", a);
        }), rule("true", "fails"));

        assertRethrown(oom, capture(() -> engine.run(new FactMap<>())));
    }

    @Test
    @DisplayName("and a loop of suppressed exceptions with none in it ends the search: the rule fails")
    void aLoopWithNoFatalErrorEnds() {
        RulesEngine<Map<String, Object>> engine = engine(Map.of("fails", () -> {
            IllegalStateException a = new IllegalStateException("a");
            IllegalStateException b = new IllegalStateException("b");
            a.addSuppressed(b);
            b.addSuppressed(a);
            throw a;
        }), rule("true", "fails"));

        Outcome<Throwable> outcome = capture(() -> engine.run(new FactMap<>()));

        assertInstanceOf(RuleExecutionException.class, outcome.thrown());
        assertEquals("Failed to execute action for rule 'r': a", outcome.thrown().getMessage());
    }

    @Test
    @DisplayName("among more suppressed exceptions than the engine reads, only those it reads are searched")
    void searchedUpToTheCap() {
        OutOfMemoryError oom = new OutOfMemoryError("OOM in close()");
        IllegalStateException first = new IllegalStateException("first");
        IllegalStateException last = new IllegalStateException("last");
        first.addSuppressed(oom);
        for (int i = 0; i < Failures.MAX_EXCEPTIONS_READ; i++) {
            first.addSuppressed(new IllegalStateException("suppressed " + i));
            last.addSuppressed(new IllegalStateException("suppressed " + i, new IllegalStateException("cause")));
        }
        last.addSuppressed(oom);

        assertSame(oom, Failures.fatalError(first));
        assertNull(Failures.fatalError(last));
    }

    @Test
    @DisplayName("suppressed on a cause, when the link above it has suppressed exceptions that lead back up the chain")
    void onACauseAfterTheLinkAbove() {
        OutOfMemoryError oom = new OutOfMemoryError("OOM in close()");
        IllegalStateException cause = new IllegalStateException("cause");
        IllegalStateException top = new IllegalStateException("top", cause);
        top.addSuppressed(new IllegalStateException("back up", top));
        cause.addSuppressed(bodyFailed(oom));

        assertSame(oom, Failures.fatalError(top));
    }

    @Test
    @DisplayName("closing that throws a fatal error, after a failure that carries one only as a suppressed exception,"
            + " throws the failure's error, carrying the one from closing, and logs the failure at WARN")
    void closingAfterAFailureWithASuppressedFatalError() {
        OutOfMemoryError oom = new OutOfMemoryError("OOM in close()");
        OutOfMemoryError closeFatal = new OutOfMemoryError("closing");
        IllegalStateException failure = bodyFailed(oom);
        AtomicReference<Error> thrown = new AtomicReference<>();

        Outcome<Throwable> outcome = capture(() -> thrown.set(Failures.fatalInsteadOf(failure, closeFatal)));

        assertSame(oom, thrown.get());
        assertEquals(List.of(closeFatal), List.of(oom.getSuppressed()));
        assertEquals(List.of("A failure was replaced by the fatal error java.lang.OutOfMemoryError: OOM in close()"
                + " suppressed on it: java.lang.IllegalStateException: body failed"), outcome.lines("WARN"));
    }

    @Test
    @DisplayName("on the cause of a suppressed exception that carries a thousand suppressed exceptions")
    void onTheCauseOfASuppressedExceptionWithManyMore() {
        OutOfMemoryError oom = new OutOfMemoryError("on the cause");
        IllegalStateException suppressed = new IllegalStateException("s", oom);
        for (int i = 0; i < SIBLINGS; i++) {
            suppressed.addSuppressed(new IllegalStateException("more " + i));
        }
        IllegalStateException top = new IllegalStateException("top");
        top.addSuppressed(suppressed);

        assertSame(oom, Failures.fatalError(top));
        assertSame(oom, Failures.errorInChain(top));
    }

    @Test
    @DisplayName("two causes down a suppressed exception that carries a thousand suppressed exceptions")
    void twoCausesDownASuppressedExceptionWithManyMore() {
        OutOfMemoryError oom = new OutOfMemoryError("two down");
        IllegalStateException suppressed = new IllegalStateException("s", new IllegalStateException("w", oom));
        for (int i = 0; i < SIBLINGS; i++) {
            suppressed.addSuppressed(new IllegalStateException("more " + i));
        }
        IllegalStateException top = new IllegalStateException("top");
        top.addSuppressed(suppressed);

        assertSame(oom, Failures.fatalError(top));
        assertSame(oom, Failures.errorInChain(top));
    }

    @Test
    @DisplayName("suppressed on a cause of a suppressed exception, before that exception's own thousand suppressed"
            + " ones")
    void suppressedOnACauseOfASuppressedException() {
        OutOfMemoryError oom = new OutOfMemoryError("three down");
        IllegalStateException cause = new IllegalStateException("w");
        cause.addSuppressed(new IllegalStateException("x", oom));
        IllegalStateException suppressed = new IllegalStateException("s", cause);
        for (int i = 0; i < SIBLINGS; i++) {
            suppressed.addSuppressed(new IllegalStateException("more " + i));
            cause.addSuppressed(new IllegalStateException("after " + i));
        }
        IllegalStateException top = new IllegalStateException("top");
        top.addSuppressed(suppressed);

        assertSame(oom, Failures.fatalError(top));
        assertSame(oom, Failures.errorInChain(top));
    }

    /** Returns an exception carrying as many suppressed exceptions as the engine reads. */
    private static IllegalStateException carryingAsManyAsRead(String message, Throwable cause) {
        IllegalStateException many = new IllegalStateException(message, cause);
        for (int i = 0; i < Failures.MAX_EXCEPTIONS_READ; i++) {
            many.addSuppressed(new IllegalStateException(message + " " + i));
        }
        return many;
    }

    @Test
    @DisplayName("suppressed on the top link, though its cause carries an exception with more suppressed than are read")
    void onTheTopLinkBesideACauseWithManyMore() {
        OutOfMemoryError oom = new OutOfMemoryError("on top");
        IllegalStateException cause = new IllegalStateException("cause");
        IllegalStateException top = new IllegalStateException("top", cause);
        top.addSuppressed(oom);
        cause.addSuppressed(carryingAsManyAsRead("x", null));

        assertSame(oom, Failures.fatalError(top));
        assertSame(oom, Failures.errorInChain(top));
    }

    @Test
    @DisplayName("suppressed after an exception with more suppressed than are read")
    void suppressedAfterAnExceptionWithManyMore() {
        OutOfMemoryError oom = new OutOfMemoryError("after");
        IllegalStateException top = new IllegalStateException("top");
        top.addSuppressed(carryingAsManyAsRead("x", null));
        top.addSuppressed(oom);

        assertSame(oom, Failures.fatalError(top));
        assertSame(oom, Failures.errorInChain(top));
    }

    @Test
    @DisplayName("an exception suppressed on another, whose cause carries more suppressed than are read, is found to"
            + " reach it, so the two aren't kept on each other")
    void reachedPastACauseWithManyMore() {
        OutOfMemoryError winner = new OutOfMemoryError("winner");
        IllegalStateException loser = new IllegalStateException("loser", carryingAsManyAsRead("c", null));
        loser.addSuppressed(winner);

        Failures.keepAlso(winner, loser);

        assertEquals(0, winner.getSuppressed().length);
    }

    @Test
    @DisplayName("#1019: a run's failure the engine kept a fatal error on is found to reach it, so the two aren't kept"
            + " on each other")
    void reachedThroughWhatTheEngineKept() {
        OutOfMemoryError winner = new OutOfMemoryError("winner");
        ReportedFailure loser = new ReportedFailure("loser", null);
        loser.addSuppressedByEngine(winner);

        Failures.keepAlso(winner, loser);

        assertEquals(0, winner.getSuppressed().length);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"found as the last exception read", "missed one past the last exception read"})
    @DisplayName("a fatal error is found as the last exception the engine reads, and not past it")
    void foundUpToTheLastExceptionRead(String where) {
        int fewer = where.startsWith("found") ? 2 : 1;
        OutOfMemoryError oom = new OutOfMemoryError("last read");
        IllegalStateException top = new IllegalStateException("top");
        for (int i = 0; i < Failures.MAX_EXCEPTIONS_READ - fewer; i++) {
            top.addSuppressed(new IllegalStateException("before " + i));
        }
        top.addSuppressed(oom);

        assertSame(fewer == 2 ? oom : null, Failures.fatalError(top));
    }

    @Test
    @DisplayName("the engine reads at most so many different exceptions, however often one is suppressed")
    void oneExceptionSuppressedOftenCountsOnce() {
        OutOfMemoryError oom = new OutOfMemoryError("last");
        IllegalStateException shared = new IllegalStateException("shared");
        IllegalStateException top = new IllegalStateException("top");
        for (int i = 0; i < Failures.MAX_EXCEPTIONS_READ * 2; i++) {
            top.addSuppressed(shared);
        }
        top.addSuppressed(oom);

        assertSame(oom, Failures.fatalError(top));
    }

    @Test
    @DisplayName("an exception of its own around a fatal error logged already, suppressed rather than a cause, is"
            + " described with the error as suppressed")
    void describedWithTheErrorAsSuppressed() {
        OutOfMemoryError oom = new OutOfMemoryError("OOM in close()");
        LoggedFailures.enter();
        try {
            assertTrue(LoggedFailures.unloggedFatal(oom));

            assertEquals("body failed (with suppressed java.lang.OutOfMemoryError: OOM in close(), already logged)",
                    Failures.describe(bodyFailed(oom)));
        } finally {
            LoggedFailures.leave();
        }
    }

    /** An exception whose {@code getCause()} gives a cause with a fatal error below it every other time it's read. */
    private static final class CauseEveryOtherRead extends RuntimeException {
        @java.io.Serial
        private static final long serialVersionUID = 1L;

        private final transient Error fatal;
        private int reads;

        CauseEveryOtherRead(Error fatal) {
            super("outer");
            this.fatal = fatal;
        }

        @Override
        public synchronized Throwable getCause() {
            reads++;
            return reads % 2 == 1 ? new IllegalStateException("inner", fatal) : null;
        }
    }

    @Test
    @DisplayName("an exception whose getCause() changes is described from one reading of its chain")
    void describedFromOneReadingOfTheChain() {
        OutOfMemoryError oom = new OutOfMemoryError("logged");
        LoggedFailures.enter();
        try {
            assertTrue(LoggedFailures.unloggedFatal(oom));

            assertEquals("outer (caused by java.lang.OutOfMemoryError: logged, already logged)",
                    Failures.describe(new CauseEveryOtherRead(oom)));
        } finally {
            LoggedFailures.leave();
        }
    }

    /** An exception whose {@code getCause()} gives a new cause, with no fatal error below it, each time it's read. */
    private static final class NewCauseEachRead extends RuntimeException {
        @java.io.Serial
        private static final long serialVersionUID = 1L;

        NewCauseEachRead() {
            super("own");
        }

        @Override
        public synchronized Throwable getCause() {
            return new IllegalStateException("own");
        }
    }

    @Test
    @DisplayName("a fatal error a getCause() of its own no longer gives isn't taken for suppressed below the chain")
    void aCauseReadDifferentlyIsNoSuppressedError() {
        NewCauseEachRead thrown = new NewCauseEachRead();
        thrown.addSuppressed(new IllegalStateException("not it"));

        assertNull(Failures.newsAbove(thrown, new OutOfMemoryError("gone")));
    }

    @Test
    @DisplayName("from a listener's beforeExecute")
    void fromAListener() {
        OutOfMemoryError oom = new OutOfMemoryError("OOM in close()");
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>firstMatch(HashMap::new)
                .language(new ScriptedLanguage(Map.of())).listener(new RuleListener() {
                    @Override
                    public void beforeExecute(Rule rule, Object output) {
                        throw bodyFailed(oom);
                    }
                }).build();
        engine.load(List.of(rule("true", "ok")));

        assertRethrown(oom, capture(() -> engine.run(new FactMap<>())));
    }

    @Test
    @DisplayName("from the output supplier")
    void fromTheOutputSupplier() {
        OutOfMemoryError oom = new OutOfMemoryError("OOM in close()");
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>firstMatch(() -> {
            throw bodyFailed(oom);
        }).language(new ScriptedLanguage(Map.of())).build();
        engine.load(List.of(rule("true", "ok")));

        List<String> errors = assertRethrown(oom, capture(() -> engine.run(new FactMap<>())));

        assertEquals(List.of("Output factory threw java.lang.IllegalStateException: body failed"), errors);
    }

    /** A fact whose getter fails with the exception a try-with-resources throws. */
    public static final class Unreadable {

        private final Error error;

        Unreadable(Error error) {
            this.error = error;
        }

        /**
         * Fails.
         *
         * @return never
         */
        public int getX() {
            throw bodyFailed(error);
        }
    }

    @Test
    @DisplayName("from a fact's getter")
    void fromAFactGetter() {
        OutOfMemoryError oom = new OutOfMemoryError("OOM in close()");
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>firstMatch(HashMap::new)
                .language(new ToyExpressionLanguage()).build();
        engine.load(List.of(rule("fact.x == 1", "put k 1")));
        FactMap<Object> facts = new FactMap<>();
        facts.setValue("fact", new Unreadable(oom));

        assertRethrown(oom, capture(() -> engine.run(facts)));
    }

    @Test
    @DisplayName("from a language's compile, which load() used to throw as a RuleCompilationException")
    void fromCompiling() {
        OutOfMemoryError oom = new OutOfMemoryError("OOM in close()");
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>firstMatch(HashMap::new)
                .language(new ScriptedLanguage(Map.of("compile:fails", () -> {
                    throw bodyFailed(oom);
                }))).build();

        assertRethrown(oom, capture(() -> engine.load(List.of(rule("true", "compile:fails")))));
    }

    @Test
    @DisplayName("from a language's fact-name check")
    void fromCheckingAFactName() {
        OutOfMemoryError oom = new OutOfMemoryError("OOM in close()");
        RulesEngine<Map<String, Object>> engine = engine(Map.of("checkFactName", () -> {
            throw bodyFailed(oom);
        }), rule("true", "ok"));
        FactMap<Object> facts = new FactMap<>();
        facts.setValue("x", 1);

        assertRethrown(oom, capture(() -> engine.run(facts)));
    }

    @Test
    @DisplayName("from a language's close(), which close() used to log only")
    void fromClosing() {
        OutOfMemoryError oom = new OutOfMemoryError("OOM in close()");
        RulesEngine<Map<String, Object>> engine = engine(Map.of("close", () -> {
            throw bodyFailed(oom);
        }), rule("true", "ok"));

        assertRethrown(oom, capture(engine::close));
    }

    /**
     * Builds an engine whose one rule's action waits until the run is cancelled, at a deadline 100 ms after it starts,
     * then runs {@code then} and returns.
     */
    private static RulesEngine<Map<String, Object>> cancelledEngine(Runnable then) {
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>firstMatch(HashMap::new)
                .language(new ExpressionLanguage() {
                    @Override
                    public String name() {
                        return "gives-up";
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
                                    while (!action.isCancelled()) {
                                        Thread.onSpinWait();
                                    }
                                    then.run();
                                    return ActionResult.done();
                                };
                            }

                            @Override
                            public Session newSession() {
                                return Session.none();
                            }
                        };
                    }
                }).runTimeout(Duration.ofMillis(100)).build();
        engine.load(List.of(Rule.builder().ruleName("waits").condition("true").action("waits").build()));
        return engine;
    }

    @Test
    @DisplayName("from an action that throws once the run is cancelled, which used to report a stop")
    void fromAnActionInACancelledRun() {
        OutOfMemoryError oom = new OutOfMemoryError("OOM in close()");
        RulesEngine<Map<String, Object>> engine = cancelledEngine(() -> {
            throw bodyFailed(oom);
        });

        assertRethrown(oom, capture(() -> engine.run(new FactMap<>())));
    }

    @Test
    @DisplayName("an error that isn't fatal, suppressed a level down on what an action throws once the run is"
            + " cancelled, makes it the rule's failure, not a stop")
    void anyErrorSuppressedInACancelledRunIsTheRulesFailure() {
        IllegalStateException thrown = new IllegalStateException("gave up");
        IllegalStateException below = new IllegalStateException("below");
        below.addSuppressed(new StackOverflowError("deep"));
        thrown.addSuppressed(below);
        RulesEngine<Map<String, Object>> engine = cancelledEngine(() -> {
            throw thrown;
        });

        Outcome<Throwable> outcome = capture(() -> engine.run(new FactMap<>()));

        RuleExecutionException failure = assertInstanceOf(RuleExecutionException.class, outcome.thrown());
        assertEquals("waits", failure.getRuleName(), failure::getMessage);
        assertSame(thrown, failure.getCause());
    }

    @Test
    @DisplayName("suppressed on a nested run's failure by a try-with-resources around the nested run")
    void suppressedOnANestedRunsFailure() {
        OutOfMemoryError oom = new OutOfMemoryError("close");
        RulesEngine<Map<String, Object>> inner = engine(Map.of("fails", () -> {
            throw new IllegalStateException("inner boom");
        }), Rule.builder().ruleName("inner").condition("true").action("fails").build());
        RulesEngine<Map<String, Object>> outer = engine(Map.of("nested", () -> {
            try (Resource resource = new Resource(oom)) {
                Objects.requireNonNull(resource);
                inner.run(new FactMap<>());
            }
        }), rule("true", "nested"));

        assertRethrown(oom, capture(() -> outer.run(new FactMap<>())));
    }

    @Test
    @DisplayName("but a nested run's own fatal error comes first, logged once, carrying the one from close()")
    void aNestedRunsOwnFatalErrorComesFirst() {
        OutOfMemoryError inRule = new OutOfMemoryError("inner oom");
        OutOfMemoryError inClose = new OutOfMemoryError("close");
        RulesEngine<Map<String, Object>> inner = engine(Map.of("fails", () -> {
            throw inRule;
        }), Rule.builder().ruleName("inner").condition("true").action("fails").build());
        RulesEngine<Map<String, Object>> outer = engine(Map.of("nested", () -> {
            try (Resource resource = new Resource(inClose)) {
                Objects.requireNonNull(resource);
                inner.run(new FactMap<>());
            }
        }), rule("true", "nested"));

        List<String> errors = assertRethrown(inRule, capture(() -> outer.run(new FactMap<>())));

        assertEquals(List.of("Failed to execute action for rule 'inner': inner oom"), errors);
        assertEquals(List.of(inClose), List.of(inRule.getSuppressed()));
    }

    @Test
    @DisplayName("suppressed on a nested run's stop by a try-with-resources around the nested run")
    void suppressedOnANestedRunsStop() {
        OutOfMemoryError oom = new OutOfMemoryError("close");
        RulesEngine<Map<String, Object>> inner = cancelledEngine(() -> { });
        RulesEngine<Map<String, Object>> outer = engine(Map.of("nested", () -> {
            try (Resource resource = new Resource(oom)) {
                Objects.requireNonNull(resource);
                inner.run(new FactMap<>());
            }
        }), rule("true", "nested"));

        assertRethrown(oom, capture(() -> outer.run(new FactMap<>())));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"onError", "onRunError"})
    @DisplayName("suppressed on the failure a listener was told of, by a try-with-resources that rethrows it")
    void suppressedOnTheToldFailureByAListener(String callback) {
        OutOfMemoryError oom = new OutOfMemoryError("close");
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>firstMatch(HashMap::new)
                .language(new ScriptedLanguage(Map.of("fails", () -> {
                    throw new IllegalStateException("rule failed");
                }))).listener(new RuleListener() {
                    @Override
                    public void onError(Rule rule, RuleExecutionException error) {
                        rethrowIfItIs("onError", error);
                    }

                    @Override
                    public void onRunError(RunContext run, RuntimeException error) {
                        rethrowIfItIs("onRunError", error);
                    }

                    private void rethrowIfItIs(String which, RuntimeException error) {
                        if (which.equals(callback)) {
                            try (Resource resource = new Resource(oom)) {
                                Objects.requireNonNull(resource);
                                throw error;
                            }
                        }
                    }
                }).build();
        engine.load(List.of(rule("true", "fails")));

        assertRethrown(oom, capture(() -> engine.run(new FactMap<>())));
    }

    @Test
    @DisplayName("from a nested run's rule: logged once, by the nested run")
    void fromANestedRun() {
        OutOfMemoryError oom = new OutOfMemoryError("OOM in close()");
        RulesEngine<Map<String, Object>> inner = engine(Map.of("fails", () -> {
            throw bodyFailed(oom);
        }), Rule.builder().ruleName("inner").condition("true").action("fails").build());
        RulesEngine<Map<String, Object>> outer = engine(Map.of("nested", () -> inner.run(new FactMap<>())),
                rule("true", "nested"));

        List<String> errors = assertRethrown(oom, capture(() -> outer.run(new FactMap<>())));

        assertEquals(List.of("Failed to execute action for rule 'inner': body failed"), errors);
    }

    @Test
    @DisplayName("left by a nested run's close() under an exception of the rule's own: the error is logged once, and"
            + " the rule's exception with it as a note")
    void suppressedFromANestedRun() {
        OutOfMemoryError oom = new OutOfMemoryError("OOM in close()");
        RulesEngine<Map<String, Object>> inner = engine(Map.of("fails", () -> {
            throw oom;
        }), Rule.builder().ruleName("inner").condition("true").action("fails").build());
        RulesEngine<Map<String, Object>> outer = engine(Map.of("nested", () -> {
            IllegalStateException body = new IllegalStateException("outer body failed");
            try {
                inner.run(new FactMap<>());
            } catch (OutOfMemoryError e) {
                body.addSuppressed(e);
            }
            throw body;
        }), rule("true", "nested"));

        List<String> errors = assertRethrown(oom, capture(() -> outer.run(new FactMap<>())));

        assertEquals(List.of("Failed to execute action for rule 'inner': OOM in close()",
                "Failed to execute action for rule 'r': outer body failed (after a nested run() failed:"
                        + " java.lang.OutOfMemoryError: OOM in close())"), errors);
    }

    @Test
    @DisplayName("but one the engine keeps on its own failure, from onError, isn't found there again: onRunError"
            + " rethrowing that failure is a listener's exception, not a second fatal error")
    void notFoundAgainOnTheEnginesOwnFailure() {
        OutOfMemoryError oom = new OutOfMemoryError("onError oom");
        AtomicReference<RuntimeException> runError = new AtomicReference<>();
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>firstMatch(HashMap::new)
                .language(new ScriptedLanguage(Map.of("fails", () -> {
                    throw new IllegalStateException("rule failed");
                }))).listener(new RuleListener() {
                    @Override
                    public void onError(Rule rule, RuleExecutionException error) {
                        throw oom;
                    }

                    @Override
                    public void onRunError(RunContext run, RuntimeException error) {
                        runError.set(error);
                        // Rethrows what it was told of, which carries the listener's error as a suppressed exception.
                        throw error;
                    }
                }).build();
        engine.load(List.of(rule("true", "fails")));

        Outcome<Throwable> outcome = capture(() -> engine.run(new FactMap<>()));

        assertSame(oom, outcome.thrown());
        assertInstanceOf(RuleExecutionException.class, runError.get());
        assertEquals(List.of(oom), List.of(runError.get().getSuppressed()));
        assertEquals(0, oom.getSuppressed().length, () -> List.of(oom.getSuppressed()).toString());
        assertEquals(List.of("Failed to execute action for rule 'r': rule failed"), outcome.lines("ERROR"));
        assertEquals(List.of("Listener threw exception in onRunError: " + ReportedFailure.class.getName()
                + ": Failed to execute action for rule 'r': rule failed"), outcome.lines("WARN"));
    }
}
