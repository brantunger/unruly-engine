package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.OutputWriter;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
import io.github.brantunger.unruly.api.exception.InvalidExpressionException;
import io.github.brantunger.unruly.api.exception.RuleCompilationException;
import io.github.brantunger.unruly.api.exception.RuleExecutionException;
import io.github.brantunger.unruly.api.language.ActionResult;
import io.github.brantunger.unruly.api.language.Session;
import io.github.brantunger.unruly.api.language.StubExpressionLanguage;
import io.github.brantunger.unruly.core.PlainThrowableTest.Recorder;
import io.github.brantunger.unruly.core.PlainThrowableTest.ThrowingLanguage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.InvocationTargetException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static io.github.brantunger.unruly.TestLogs.logsOf;
import static io.github.brantunger.unruly.core.EngineLogs.ENGINE_LOGGER;
import static io.github.brantunger.unruly.core.PlainThrowableTest.EVERY_CALLBACK;
import static io.github.brantunger.unruly.core.PlainThrowableTest.FAILED_ACTION;
import static io.github.brantunger.unruly.core.PlainThrowableTest.builder;
import static io.github.brantunger.unruly.core.PlainThrowableTest.loaded;
import static io.github.brantunger.unruly.core.PlainThrowableTest.rules;
import static io.github.brantunger.unruly.core.PlainThrowableTest.thrownBy;
import static org.junit.jupiter.api.Assertions.*;

/**
 * #579: an exception whose {@code getMessage()}, {@code toString()} or {@code getCause()} throws, as a custom one that
 * builds its message from a field that happens to be {@code null} does, is reported like any other, with a note that
 * its message is unavailable. What reading it threw, a fatal error too, never replaces the outcome the engine
 * documents: from a listener it's logged at WARN and the other listeners still get the callback; from a condition,
 * an action or the output it fails the rule or the run; from loading it fails the load; from closing a session it's
 * logged at WARN and the rest are still closed.
 *
 * <p>
 * Nothing here calls {@code assertNull} or {@code assertSame} with one of these exceptions as the value that could be
 * wrong, because an assertion that fails prints it, and printing it throws.
 * </p>
 */
@DisplayName("an exception whose message can't be read is reported like any other")
class UnreadableMessageTest {

    /** What each of these exceptions' descriptions ends with. */
    private static final String UNAVAILABLE = " (message unavailable: java.lang.NullPointerException)";

    /** A custom exception that builds its message from a field that happens to be {@code null}. */
    private static final class Nasty extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Nasty() {
            this(null);
        }

        Nasty(Throwable cause) {
            // Not super(cause), which would read the cause's toString() for this one's message.
            super(null, cause);
        }

        @Override
        public String getMessage() {
            throw new NullPointerException("detail is null");
        }

        @Override
        public String getLocalizedMessage() {
            return getMessage();
        }

        @Override
        public String toString() {
            return getMessage();
        }
    }

    /** A fatal error whose message can't be read either. */
    private static final class NastyOutOfMemoryError extends OutOfMemoryError {
        private static final long serialVersionUID = 1L;

        @Override
        public String getMessage() {
            throw new NullPointerException("detail is null");
        }

        @Override
        public String getLocalizedMessage() {
            return getMessage();
        }

        @Override
        public String toString() {
            return getMessage();
        }
    }

    /** A language's own {@link InvalidExpressionException}, whose message can't be read. */
    private static final class NastyInvalidExpression extends InvalidExpressionException {
        private static final long serialVersionUID = 1L;

        NastyInvalidExpression() {
            super("never read", List.of());
        }

        @Override
        public String getMessage() {
            throw new NullPointerException("detail is null");
        }
    }

    /** A language's own {@link InvalidExpressionException}, whose issues are what {@code issues} gives. */
    private static final class UnreadableIssues extends InvalidExpressionException {
        private static final long serialVersionUID = 1L;

        private final transient Supplier<List<Issue>> issues;

        UnreadableIssues(Supplier<List<Issue>> issues) {
            super("is bad", List.of());
            this.issues = issues;
        }

        @Override
        public List<Issue> issues() {
            return issues.get();
        }
    }

    /** An exception with a readable message whose {@code getCause()} throws. */
    private static final class UnreadableCause extends RuntimeException {
        private static final long serialVersionUID = 1L;

        UnreadableCause() {
            super("readable");
        }

        @Override
        public synchronized Throwable getCause() {
            throw new IllegalStateException("no cause today");
        }
    }

    /** What an output writer of its own may throw: an {@link InvocationTargetException} whose cause can't be read. */
    private static final class UnreadableTarget extends InvocationTargetException {
        private static final long serialVersionUID = 1L;

        @Override
        public Throwable getCause() {
            throw new IllegalStateException("no target today");
        }
    }

    private static String nasty() {
        return Nasty.class.getName() + UNAVAILABLE;
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"beforeRun", "afterRun"})
    @DisplayName("from a listener's callback, it's logged at WARN, the listeners after it still get the callback, and"
            + " the run goes on")
    void fromAListener(String callback) {
        List<String> calls = new CopyOnWriteArrayList<>();
        RulesEngine<Map<String, Object>> engine = loaded(builder(new StubExpressionLanguage())
                .listener(new Recorder("A", calls, callback, new Nasty())).listener(new Recorder("B", calls)));
        AtomicReference<Throwable> thrown = new AtomicReference<>();

        String logs = logsOf(() -> thrown.set(thrownBy(() -> engine.run(new FactMap<>()))));

        assertNull(thrown.get(), "the run goes on");
        assertEquals(EVERY_CALLBACK, calls);
        assertTrue(logs.contains("WARN " + ENGINE_LOGGER + "Listener threw exception in " + callback + ": "
                + nasty()), logs);
    }

    @Test
    @DisplayName("from a listener's onError, it's logged at WARN, the listeners after it still get onError, and the"
            + " run's own failure is thrown")
    void fromAListenerClosingAFailure() {
        List<String> calls = new CopyOnWriteArrayList<>();
        StubExpressionLanguage language = new StubExpressionLanguage().action((action, session) -> {
            throw new IllegalStateException("the action failed");
        });
        RulesEngine<Map<String, Object>> engine = loaded(builder(language)
                .listener(new Recorder("A", calls, "onError", new Nasty())).listener(new Recorder("B", calls)));
        AtomicReference<Throwable> thrown = new AtomicReference<>();

        String logs = logsOf(() -> thrown.set(thrownBy(() -> engine.run(new FactMap<>()))));

        RuleExecutionException failure = assertInstanceOf(RuleExecutionException.class, thrown.get());
        assertEquals("Failed to execute action for rule 'r': the action failed", failure.getMessage());
        assertEquals(FAILED_ACTION, calls);
        assertTrue(logs.contains("WARN " + ENGINE_LOGGER + "Listener threw exception in onError: " + nasty()), logs);
    }

    @Test
    @DisplayName("from a listener, as the cause of a readable exception, it's logged at WARN and the run goes on")
    void fromAListenerAsACause() {
        List<String> calls = new CopyOnWriteArrayList<>();
        RulesEngine<Map<String, Object>> engine = loaded(builder(new StubExpressionLanguage())
                .listener(new Recorder("A", calls, "beforeRun", new IllegalStateException("readable", new Nasty())))
                .listener(new Recorder("B", calls)));
        AtomicReference<Throwable> thrown = new AtomicReference<>();

        String logs = logsOf(() -> thrown.set(thrownBy(() -> engine.run(new FactMap<>()))));

        assertNull(thrown.get(), "the run goes on");
        assertEquals(EVERY_CALLBACK, calls);
        assertTrue(logs.contains("WARN " + ENGINE_LOGGER + "Listener threw exception in beforeRun:"
                + " java.lang.IllegalStateException: readable"), logs);
    }

    @Test
    @DisplayName("from a condition, it fails the rule, logged at ERROR, through onError and then onRunError")
    void fromACondition() {
        Nasty nasty = new Nasty();
        ThrowingLanguage language = new ThrowingLanguage();
        language.conditionFailure = nasty;
        List<String> calls = new CopyOnWriteArrayList<>();
        RulesEngine<Map<String, Object>> engine = loaded(builder(language).listener(new Recorder("A", calls)));
        AtomicReference<Throwable> thrown = new AtomicReference<>();

        String logs = logsOf(() -> thrown.set(thrownBy(() -> engine.run(new FactMap<>()))));

        RuleExecutionException failure = assertInstanceOf(RuleExecutionException.class, thrown.get());
        String msg = "Failed to evaluate condition for rule 'r': " + nasty();
        assertEquals(msg, failure.getMessage());
        assertEquals("r", failure.getRuleName());
        assertTrue(failure.getCause() == nasty, "the cause is what the condition threw");
        assertEquals(List.of("A.beforeRun", "A.beforeEvaluate", "A.onError", "A.onRunError"), calls);
        assertTrue(logs.contains("ERROR " + ENGINE_LOGGER + msg), logs);
    }

    @Test
    @DisplayName("from an action, it fails the rule, logged at ERROR, through onError and then onRunError")
    void fromAnAction() {
        List<String> calls = new CopyOnWriteArrayList<>();
        StubExpressionLanguage language = new StubExpressionLanguage().action((action, session) -> {
            throw new Nasty();
        });
        RulesEngine<Map<String, Object>> engine = loaded(builder(language).listener(new Recorder("A", calls)));
        AtomicReference<Throwable> thrown = new AtomicReference<>();

        String logs = logsOf(() -> thrown.set(thrownBy(() -> engine.run(new FactMap<>()))));

        RuleExecutionException failure = assertInstanceOf(RuleExecutionException.class, thrown.get());
        String msg = "Failed to execute action for rule 'r': " + nasty();
        assertEquals(msg, failure.getMessage());
        assertEquals("r", failure.getRuleName());
        assertEquals(List.of("A.beforeRun", "A.beforeEvaluate", "A.afterEvaluate", "A.beforeExecute", "A.onError",
                "A.onRunError"), calls);
        assertTrue(logs.contains("ERROR " + ENGINE_LOGGER + msg), logs);
    }

    @Test
    @DisplayName("as the root cause of a readable exception from a condition, it fails the rule with that message,"
            + " naming the root cause")
    void asTheRootCauseOfACondition() {
        ThrowingLanguage language = new ThrowingLanguage();
        language.conditionFailure = new IllegalStateException("readable", new Nasty());
        RulesEngine<Map<String, Object>> engine = loaded(builder(language));
        AtomicReference<Throwable> thrown = new AtomicReference<>();

        String logs = logsOf(() -> thrown.set(thrownBy(() -> engine.run(new FactMap<>()))));

        RuleExecutionException failure = assertInstanceOf(RuleExecutionException.class, thrown.get());
        String msg = "Failed to evaluate condition for rule 'r': readable (caused by " + Nasty.class.getName()
                + ": (message unavailable: java.lang.NullPointerException))";
        assertEquals(msg, failure.getMessage());
        assertTrue(logs.contains("ERROR " + ENGINE_LOGGER + msg), logs);
    }

    @Test
    @DisplayName("from the output factory, it fails the run, logged at ERROR, through onRunError")
    void fromTheOutputFactory() {
        List<String> calls = new CopyOnWriteArrayList<>();
        RulesEngine<Map<String, Object>> engine = loaded(RulesEngineBuilder.<Map<String, Object>>allMatches(() -> {
            throw new Nasty();
        }).language(new StubExpressionLanguage()).defaultLanguage(StubExpressionLanguage.LANGUAGE_NAME)
                .listener(new Recorder("A", calls)));
        AtomicReference<Throwable> thrown = new AtomicReference<>();

        String logs = logsOf(() -> thrown.set(thrownBy(() -> engine.run(new FactMap<>()))));

        RuleExecutionException failure = assertInstanceOf(RuleExecutionException.class, thrown.get());
        assertEquals("Output factory threw " + nasty(), failure.getMessage());
        assertEquals(List.of("A.beforeRun", "A.beforeEvaluate", "A.afterEvaluate", "A.onRunError"), calls);
        assertTrue(logs.contains("ERROR " + ENGINE_LOGGER + "Output factory threw " + nasty()), logs);
    }

    @Test
    @DisplayName("from compiling a condition, it fails the load, naming the rule")
    void fromCompiling() {
        ThrowingLanguage language = new ThrowingLanguage();
        language.compileFailure = new Nasty();
        RulesEngine<Map<String, Object>> engine = builder(language).build();
        List<Rule> rules = rules();
        AtomicReference<Throwable> thrown = new AtomicReference<>();

        String logs = logsOf(() -> thrown.set(thrownBy(() -> engine.load(rules))));

        RuleCompilationException failure = assertInstanceOf(RuleCompilationException.class, thrown.get());
        String msg = "Condition for rule 'r' failed to compile: " + nasty();
        assertEquals(msg, failure.getMessage());
        assertEquals("r", failure.getRuleName());
        assertTrue(logs.contains("ERROR " + ENGINE_LOGGER + msg), logs);
    }

    @Test
    @DisplayName("as a language's own InvalidExpressionException, it fails the load as a rejected expression")
    void asARejectedExpression() {
        ThrowingLanguage language = new ThrowingLanguage();
        language.compileFailure = new NastyInvalidExpression();
        RulesEngine<Map<String, Object>> engine = builder(language).build();
        List<Rule> rules = rules();
        AtomicReference<Throwable> thrown = new AtomicReference<>();

        logsOf(() -> thrown.set(thrownBy(() -> engine.load(rules))));

        RuleCompilationException failure = assertInstanceOf(RuleCompilationException.class, thrown.get());
        assertEquals("Condition for rule 'r' was rejected by its expression language" + UNAVAILABLE,
                failure.getMessage());
    }

    @Test
    @DisplayName("as a language's own InvalidExpressionException whose issues() throws, it fails the load as a"
            + " rejected expression with no issues")
    void asARejectedExpressionWithUnreadableIssues() {
        ThrowingLanguage language = new ThrowingLanguage();
        language.compileFailure = new UnreadableIssues(() -> {
            throw new IllegalStateException("no issues today");
        });
        RulesEngine<Map<String, Object>> engine = builder(language).build();
        List<Rule> rules = rules();
        AtomicReference<Throwable> thrown = new AtomicReference<>();

        logsOf(() -> thrown.set(thrownBy(() -> engine.load(rules))));

        RuleCompilationException failure = assertInstanceOf(RuleCompilationException.class, thrown.get());
        assertEquals("Condition for rule 'r' is bad", failure.getMessage());
        assertEquals(List.of(), failure.issues());
    }

    @Test
    @DisplayName("as a language's own InvalidExpressionException whose issues() returns null, it fails the load as a"
            + " rejected expression with no issues")
    void asARejectedExpressionWithNullIssues() {
        ThrowingLanguage language = new ThrowingLanguage();
        language.compileFailure = new UnreadableIssues(() -> null);
        RulesEngine<Map<String, Object>> engine = builder(language).build();
        List<Rule> rules = rules();
        AtomicReference<Throwable> thrown = new AtomicReference<>();

        logsOf(() -> thrown.set(thrownBy(() -> engine.load(rules))));

        RuleCompilationException failure = assertInstanceOf(RuleCompilationException.class, thrown.get());
        assertEquals("Condition for rule 'r' is bad", failure.getMessage());
        assertEquals(List.of(), failure.issues());
    }

    @Test
    @DisplayName("an InvalidExpressionException with a message fails the load with that message, and one without"
            + " says the expression was rejected")
    void aReadableOrMissingRejection() {
        ThrowingLanguage language = new ThrowingLanguage();
        RulesEngine<Map<String, Object>> engine = builder(language).build();
        List<Rule> rules = rules();

        language.compileFailure = new InvalidExpressionException("has a\nproblem");
        RuleCompilationException readable = assertInstanceOf(RuleCompilationException.class,
                thrownBy(() -> logsOf(() -> engine.load(rules))));
        language.compileFailure = new InvalidExpressionException(null);
        RuleCompilationException missing = assertInstanceOf(RuleCompilationException.class,
                thrownBy(() -> logsOf(() -> engine.load(rules))));

        assertEquals("Condition for rule 'r' has a\\nproblem", readable.getMessage());
        assertEquals("Condition for rule 'r' was rejected by its expression language", missing.getMessage());
    }

    @Test
    @DisplayName("from closing a session, it's logged at WARN, and the other languages' sessions are still closed")
    void fromClosingASession() {
        List<String> closed = new CopyOnWriteArrayList<>();
        StubExpressionLanguage bad = StubExpressionLanguage.named("bad").newSession(() -> new Session() {
            @Override
            public void close() {
                closed.add("bad");
                throw new Nasty();
            }
        });
        StubExpressionLanguage good = StubExpressionLanguage.named("good").newSession(() -> new Session() {
            @Override
            public void close() {
                closed.add("good");
            }
        });
        // The language that throws first, so the other is closed after it.
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new)
                .language(bad).language(good).defaultLanguage("bad").build();
        engine.load(List.of(Rule.builder().ruleName("r1").condition("c").action("a").language("bad").build(),
                Rule.builder().ruleName("r2").condition("c").action("a").language("good").build()));
        engine.run(new FactMap<>());
        AtomicReference<Throwable> thrown = new AtomicReference<>();

        String logs = logsOf(() -> thrown.set(thrownBy(engine::close)));

        assertNull(thrown.get(), "close() returns");
        assertEquals(List.of("bad", "good"), closed);
        assertTrue(logs.contains("WARN " + ENGINE_LOGGER + "The 'bad' expression language failed to close a session: "
                + nasty()), logs);
    }

    @Test
    @DisplayName("with a getCause() that throws, from a condition, it fails the rule with its own message")
    void withACauseThatThrows() {
        ThrowingLanguage language = new ThrowingLanguage();
        language.conditionFailure = new UnreadableCause();
        RulesEngine<Map<String, Object>> engine = loaded(builder(language));
        AtomicReference<Throwable> thrown = new AtomicReference<>();

        logsOf(() -> thrown.set(thrownBy(() -> engine.run(new FactMap<>()))));

        RuleExecutionException failure = assertInstanceOf(RuleExecutionException.class, thrown.get());
        assertEquals("Failed to evaluate condition for rule 'r': readable", failure.getMessage());
    }

    @Test
    @DisplayName("as an InvocationTargetException from the output writer whose getCause() throws, it fails the rule")
    void fromTheOutputWriterWithACauseThatThrows() {
        StubExpressionLanguage language = new StubExpressionLanguage()
                .action((action, session) -> ActionResult.set(Map.of("x", 1)));
        OutputWriter<Object> writer = (output, property, value) -> {
            throw new UnreadableTarget();
        };
        RulesEngine<Map<String, Object>> engine = loaded(builder(language).outputWriter(writer));
        AtomicReference<Throwable> thrown = new AtomicReference<>();

        logsOf(() -> thrown.set(thrownBy(() -> engine.run(new FactMap<>()))));

        RuleExecutionException failure = assertInstanceOf(RuleExecutionException.class, thrown.get());
        assertEquals("Failed to set 'x' on the output for rule 'r': " + UnreadableTarget.class.getName(),
                failure.getMessage());
        assertInstanceOf(UnreadableTarget.class, failure.getCause());
    }

    @Test
    @DisplayName("from a listener's onError closing a fatal failure, as a fatal error itself, the failure's own error"
            + " is rethrown, and the listener's is kept on the failure")
    void fromAListenerClosingAFatalFailure() {
        OutOfMemoryError fatal = new OutOfMemoryError("action oom");
        NastyOutOfMemoryError fromListener = new NastyOutOfMemoryError();
        List<String> calls = new CopyOnWriteArrayList<>();
        StubExpressionLanguage language = new StubExpressionLanguage().action((action, session) -> {
            throw fatal;
        });
        Recorder second = new Recorder("B", calls);
        RulesEngine<Map<String, Object>> engine = loaded(builder(language)
                .listener(new Recorder("A", calls, "onError", fromListener)).listener(second));
        AtomicReference<Throwable> thrown = new AtomicReference<>();

        String logs = logsOf(() -> thrown.set(thrownBy(() -> engine.run(new FactMap<>()))));

        assertSame(fatal, thrown.get());
        assertEquals(FAILED_ACTION, calls);
        Throwable[] kept = second.runError.get().getSuppressed();
        assertEquals(1, kept.length);
        assertTrue(kept[0] == fromListener, "the listener's error is kept on the failure");
        assertTrue(logs.contains("WARN " + ENGINE_LOGGER + "Listener threw exception in onError, kept on the failure: "
                + NastyOutOfMemoryError.class.getName() + UNAVAILABLE), logs);
    }

    @Test
    @DisplayName("as the root cause, its class is named with a note that its message is unavailable, where an"
            + " exception above it has no message")
    void describedAsTheRootCause() {
        assertEquals("java.lang.RuntimeException (caused by " + Nasty.class.getName()
                        + ": (message unavailable: java.lang.NullPointerException))",
                Failures.describe(new RuntimeException(null, new Nasty())));
    }

    @Test
    @DisplayName("as the first exception of a chain, its class is named with a note that its message is unavailable,"
            + " and the root cause it hides is named")
    void describedAsTheFirstLink() {
        Nasty nasty = new Nasty(new IllegalStateException("boom"));
        String described = nasty() + " (caused by java.lang.IllegalStateException: boom)";

        assertEquals(described, Failures.describe(nasty));
        assertEquals(described, Failures.describeWithClass(nasty));
    }

    @Test
    @DisplayName("in the middle of a chain, it hides the root cause as a missing message does, so that's named")
    void describedInTheMiddleOfAChain() {
        RuntimeException top = new RuntimeException("top", new Nasty(new IllegalStateException("boom")));

        assertEquals("top (caused by java.lang.IllegalStateException: boom)", Failures.describe(top));
    }

    /** An exception whose {@code getMessage()} and {@code toString()} throw {@code failure}, such as a fatal error. */
    private static final class FatalText extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private final Throwable failure;

        FatalText(Throwable failure) {
            this.failure = failure;
        }

        @Override
        public String getMessage() {
            PlainThrowableTest.<RuntimeException>sneakyThrow(failure);
            return null;
        }

        @Override
        public String getLocalizedMessage() {
            return getMessage();
        }

        @Override
        public String toString() {
            return getMessage();
        }
    }

    /** A fatal error whose {@code getMessage()} and {@code toString()} throw another, {@code second}. */
    private static final class FatalTextOutOfMemoryError extends OutOfMemoryError {
        private static final long serialVersionUID = 1L;

        private final Error second;

        FatalTextOutOfMemoryError(Error second) {
            this.second = second;
        }

        @Override
        public String getMessage() {
            throw second;
        }

        @Override
        public String getLocalizedMessage() {
            return getMessage();
        }

        @Override
        public String toString() {
            return getMessage();
        }
    }

    /** A wrapper whose message is its cause's, as some are written. */
    private static final class Delegating extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Delegating(Throwable cause) {
            super(null, cause);
        }

        @Override
        public String getMessage() {
            return getCause().getMessage();
        }
    }

    /** Another exception whose message can't be read, so its note reads the same as {@link Nasty}'s. */
    private static final class OtherNasty extends RuntimeException {
        private static final long serialVersionUID = 1L;

        @Override
        public String getMessage() {
            throw new NullPointerException("other detail is null");
        }
    }

    /** A linkage error from a class loader of the application's own, whose message can't be read. */
    private static final class NastyLinkageError extends LinkageError {
        private static final long serialVersionUID = 1L;

        @Override
        public String getMessage() {
            throw new NullPointerException("detail is null");
        }

        @Override
        public String getLocalizedMessage() {
            return getMessage();
        }

        @Override
        public String toString() {
            return getMessage();
        }
    }

    private static StubExpressionLanguage closing(String name, List<String> closed, Throwable failure) {
        return StubExpressionLanguage.named(name).newSession(() -> new Session() {
            @Override
            public void close() {
                closed.add(name);
                if (failure != null) {
                    PlainThrowableTest.<RuntimeException>sneakyThrow(failure);
                }
            }
        });
    }

    @Test
    @DisplayName("with a message that throws a fatal error, from a listener after another's fatal error, it's logged"
            + " at WARN, the listeners after it still get the callback, and the other's fatal error is thrown")
    void fromAListenerWhoseMessageThrowsAFatalError() {
        OutOfMemoryError first = new OutOfMemoryError("listener oom");
        OutOfMemoryError fromReading = new OutOfMemoryError("reading");
        List<String> calls = new CopyOnWriteArrayList<>();
        RulesEngine<Map<String, Object>> engine = loaded(builder(new StubExpressionLanguage())
                .listener(new Recorder("A", calls, "beforeRun", first))
                .listener(new Recorder("B", calls, "beforeRun", new FatalText(fromReading)))
                .listener(new Recorder("C", calls)));
        AtomicReference<Throwable> thrown = new AtomicReference<>();

        String logs = logsOf(() -> thrown.set(thrownBy(() -> engine.run(new FactMap<>()))));

        assertSame(first, thrown.get());
        assertTrue(calls.contains("C.beforeRun"), calls.toString());
        assertTrue(logs.contains("WARN " + ENGINE_LOGGER + "Listener threw exception in beforeRun: "
                + FatalText.class.getName() + " (message unavailable: java.lang.OutOfMemoryError)"), logs);
    }

    @Test
    @DisplayName("with a message that throws a fatal error, from a listener, it's logged at WARN, the listeners after"
            + " it still get the callback, and the run goes on")
    void fromTheOnlyListenerWhoseMessageThrowsAFatalError() {
        OutOfMemoryError fromReading = new OutOfMemoryError("reading");
        List<String> calls = new CopyOnWriteArrayList<>();
        RulesEngine<Map<String, Object>> engine = loaded(builder(new StubExpressionLanguage())
                .listener(new Recorder("A", calls, "beforeRun", new FatalText(fromReading)))
                .listener(new Recorder("B", calls)));
        AtomicReference<Throwable> thrown = new AtomicReference<>();

        String logs = logsOf(() -> thrown.set(thrownBy(() -> engine.run(new FactMap<>()))));

        assertEquals(null, thrown.get() == null ? null : thrown.get().getClass().getName(), "the run goes on");
        assertEquals(EVERY_CALLBACK, calls);
        assertTrue(logs.contains("WARN " + ENGINE_LOGGER + "Listener threw exception in beforeRun: "
                + FatalText.class.getName() + " (message unavailable: java.lang.OutOfMemoryError)"), logs);
    }

    @Test
    @DisplayName("with a message that throws a fatal error that another exception wraps, from a listener, it's logged"
            + " at WARN, and the run goes on")
    void fromAListenerWhoseMessageThrowsAWrappedFatalError() {
        List<String> calls = new CopyOnWriteArrayList<>();
        RulesEngine<Map<String, Object>> engine = loaded(builder(new StubExpressionLanguage())
                .listener(new Recorder("A", calls, "beforeRun",
                        new FatalText(new IllegalStateException("reading", new OutOfMemoryError("wrapped")))))
                .listener(new Recorder("B", calls)));
        AtomicReference<Throwable> thrown = new AtomicReference<>();

        String logs = logsOf(() -> thrown.set(thrownBy(() -> engine.run(new FactMap<>()))));

        assertEquals(null, thrown.get() == null ? null : thrown.get().getClass().getName(), "the run goes on");
        assertEquals(EVERY_CALLBACK, calls);
        assertTrue(logs.contains("WARN " + ENGINE_LOGGER + "Listener threw exception in beforeRun: "
                + FatalText.class.getName() + " (message unavailable: java.lang.IllegalStateException)"), logs);
    }

    @Test
    @DisplayName("with a message that throws the fatal error the run already reports, from a listener's onError, the"
            + " listeners after it still get onError, and nothing more is kept on the failure")
    void fromAListenerWhoseMessageThrowsTheReportedFatalError() {
        OutOfMemoryError fatal = new OutOfMemoryError("action oom");
        List<String> calls = new CopyOnWriteArrayList<>();
        StubExpressionLanguage language = new StubExpressionLanguage().action((action, session) -> {
            throw fatal;
        });
        Recorder second = new Recorder("B", calls);
        RulesEngine<Map<String, Object>> engine = loaded(builder(language)
                .listener(new Recorder("A", calls, "onError", new FatalText(fatal))).listener(second));
        AtomicReference<Throwable> thrown = new AtomicReference<>();

        logsOf(() -> thrown.set(thrownBy(() -> engine.run(new FactMap<>()))));

        assertSame(fatal, thrown.get());
        assertEquals(FAILED_ACTION, calls);
        assertEquals(0, second.runError.get().getSuppressed().length);
    }

    @Test
    @DisplayName("with a message that throws a fatal error, from closing a session, it's logged at WARN, the other"
            + " languages' sessions are still closed, and close() returns")
    void fromClosingASessionWithAMessageThatThrowsAFatalError() {
        List<String> closed = new CopyOnWriteArrayList<>();
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new)
                .language(closing("nasty", closed, new FatalText(new OutOfMemoryError("reading"))))
                .language(closing("good", closed, null)).defaultLanguage("nasty").build();
        engine.load(List.of(Rule.builder().ruleName("r1").condition("c").action("a").language("nasty").build(),
                Rule.builder().ruleName("r2").condition("c").action("a").language("good").build()));
        engine.run(new FactMap<>());
        AtomicReference<Throwable> thrown = new AtomicReference<>();

        String logs = logsOf(() -> thrown.set(thrownBy(engine::close)));

        assertEquals(null, thrown.get() == null ? null : thrown.get().getClass().getName(), "close() returns");
        assertEquals(List.of("nasty", "good"), closed);
        assertTrue(logs.contains("WARN " + ENGINE_LOGGER + "The 'nasty' expression language failed to close a"
                + " session: " + FatalText.class.getName() + " (message unavailable: java.lang.OutOfMemoryError)"),
                logs);
    }

    @Test
    @DisplayName("with a message that throws a fatal error, from closing a session after another's fatal error, the"
            + " sessions after it are still closed, and the other's fatal error is thrown")
    void fromClosingASessionAfterAFatalErrorWithAMessageThatThrowsAFatalError() {
        OutOfMemoryError first = new OutOfMemoryError("close oom");
        OutOfMemoryError fromReading = new OutOfMemoryError("reading");
        List<String> closed = new CopyOnWriteArrayList<>();
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new)
                .language(closing("oom", closed, first)).language(closing("nasty", closed, new FatalText(fromReading)))
                .language(closing("good", closed, null)).defaultLanguage("oom").build();
        engine.load(List.of(Rule.builder().ruleName("r1").condition("c").action("a").language("oom").build(),
                Rule.builder().ruleName("r2").condition("c").action("a").language("nasty").build(),
                Rule.builder().ruleName("r3").condition("c").action("a").language("good").build()));
        engine.run(new FactMap<>());
        AtomicReference<Throwable> thrown = new AtomicReference<>();

        logsOf(() -> thrown.set(thrownBy(engine::close)));

        assertSame(first, thrown.get());
        assertEquals(List.of("oom", "nasty", "good"), closed);
    }

    @Test
    @DisplayName("as a fatal error from onError whose message throws another, while a fatal failure closes, it's logged"
            + " at WARN, the failure's own error is thrown, and the listener's is kept on the failure")
    void fromAListenerClosingAFatalFailureWithAMessageThatThrowsAFatalError() {
        OutOfMemoryError fatal = new OutOfMemoryError("action oom");
        OutOfMemoryError fromReading = new OutOfMemoryError("reading");
        FatalTextOutOfMemoryError fromListener = new FatalTextOutOfMemoryError(fromReading);
        List<String> calls = new CopyOnWriteArrayList<>();
        StubExpressionLanguage language = new StubExpressionLanguage().action((action, session) -> {
            throw fatal;
        });
        Recorder second = new Recorder("B", calls);
        RulesEngine<Map<String, Object>> engine = loaded(builder(language)
                .listener(new Recorder("A", calls, "onError", fromListener)).listener(second));
        AtomicReference<Throwable> thrown = new AtomicReference<>();

        String logs = logsOf(() -> thrown.set(thrownBy(() -> engine.run(new FactMap<>()))));

        assertSame(fatal, thrown.get());
        assertEquals(FAILED_ACTION, calls);
        Throwable[] kept = second.runError.get().getSuppressed();
        assertEquals(1, kept.length);
        assertTrue(kept[0] == fromListener, "the listener's error is kept on the failure");
        assertTrue(logs.contains("WARN " + ENGINE_LOGGER + "Listener threw exception in onError, kept on the failure: "
                + FatalTextOutOfMemoryError.class.getName() + " (message unavailable: java.lang.OutOfMemoryError)"),
                logs);
    }

    @Test
    @DisplayName("as a failure replaced by a fatal error that can't carry it, a fatal error from its message only"
            + " makes it unavailable, and the fatal error that replaces it is returned")
    void replacedByAFatalErrorWithAMessageThatThrowsAFatalError() {
        // Suppression disabled, as on an OutOfMemoryError the JVM keeps ready, which no constructor built.
        Error closeFatal = new Error("preallocated", null, false, false) {
        };
        FatalText failure = new FatalText(new OutOfMemoryError("reading"));
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        AtomicReference<Error> result = new AtomicReference<>();

        String logs = logsOf(() -> thrown.set(thrownBy(
                () -> result.set(Failures.fatalInsteadOf(failure, closeFatal)))));

        assertEquals(null, thrown.get() == null ? null : thrown.get().getClass().getName());
        assertSame(closeFatal, result.get());
        assertTrue(logs.contains("WARN " + ENGINE_LOGGER + "A failure was replaced by the fatal error " + closeFatal
                + ", which can't carry it as a suppressed exception: " + FatalText.class.getName()
                + " (message unavailable: java.lang.OutOfMemoryError)"), logs);
    }

    @Test
    @DisplayName("as the first of two exceptions whose messages can't be read, it names the other as the root cause")
    void describedAboveAnotherUnreadableMessage() {
        assertEquals(Nasty.class.getName() + UNAVAILABLE + " (caused by " + OtherNasty.class.getName()
                + ": (message unavailable: java.lang.NullPointerException))",
                Failures.describe(new Nasty(new OtherNasty())));
    }

    @Test
    @DisplayName("under a wrapper whose message is its cause's, it's named as the root cause")
    void describedUnderAWrapperWithItsMessage() {
        assertEquals(Delegating.class.getName() + UNAVAILABLE + " (caused by " + Nasty.class.getName()
                + ": (message unavailable: java.lang.NullPointerException))",
                Failures.describe(new Delegating(new Nasty())));
    }

    @Test
    @DisplayName("as a linkage error from the application's own class loader, an import it can't load is rejected")
    void asALinkageErrorFromAnImport() {
        ClassLoader loader = new ClassLoader(UnreadableMessageTest.class.getClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.equals("p.Nasty")) {
                    throw new NastyLinkageError();
                }
                return super.loadClass(name, resolve);
            }
        };
        RulesEngineBuilder<Map<String, Object>> builder = builder(new StubExpressionLanguage()).imports("p.Nasty");
        Thread thread = Thread.currentThread();
        ClassLoader original = thread.getContextClassLoader();
        Throwable thrown;

        thread.setContextClassLoader(loader);
        try {
            thrown = thrownBy(builder::build);
        } finally {
            thread.setContextClassLoader(original);
        }

        IllegalArgumentException failure = assertInstanceOf(IllegalArgumentException.class, thrown);
        assertEquals("Can't import 'p.Nasty': the class exists but can't be loaded: "
                + NastyLinkageError.class.getName() + UNAVAILABLE, failure.getMessage());
    }
}
