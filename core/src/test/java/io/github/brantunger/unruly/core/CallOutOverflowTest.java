package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.Fact;
import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.exception.RuleCompilationException;
import io.github.brantunger.unruly.api.exception.RuleExecutionException;
import io.github.brantunger.unruly.api.language.ActionResult;
import io.github.brantunger.unruly.api.language.CompileContext;
import io.github.brantunger.unruly.api.language.CompiledAction;
import io.github.brantunger.unruly.api.language.CompiledCondition;
import io.github.brantunger.unruly.api.language.Expression;
import io.github.brantunger.unruly.api.language.ExpressionCompiler;
import io.github.brantunger.unruly.api.language.ExpressionLanguage;
import io.github.brantunger.unruly.api.language.Session;
import io.github.brantunger.unruly.api.language.StubExpressionLanguage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.reflect.InvocationTargetException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static io.github.brantunger.unruly.core.EngineLogs.ENGINE_LOGGER;
import static io.github.brantunger.unruly.core.EngineLogs.assertLoggedAtError;
import static io.github.brantunger.unruly.core.EngineLogs.assertLoggedThenRethrown;
import static org.junit.jupiter.api.Assertions.*;

/**
 * A language whose own calls overflow the stack, or throw what JDK 25 and 26 give for a class an overflow left
 * unusable, or what the JDK wraps an overflow in. An overflow from a call other than compiling an expression says the
 * stack overflowed (#1135); a {@link NoClassDefFoundError} caused by an overflow isn't one, and reads as any other
 * failure (#1116); an {@link InternalError} around an overflow is an overflow, not a fatal error (#1141).
 */
@DisplayName("a language's call that overflows the stack, or throws an error around an overflow, is reported by why")
class CallOutOverflowTest {

    private static final String LOAD_TIME = "the stack overflowed: the rule text may be too long or too deeply nested"
            + " for the language";

    /** Where the language throws: one of its calls. */
    private enum Call {
        NEW_COMPILER, FACT_NAMES_READ, CHECK_FACT_NAME, WARM_UP, NEW_SESSION, COMPILE, CONDITION, ACTION
    }

    /** A language that throws what {@code thrown} makes from {@code call}, and does nothing anywhere else. */
    private static ExpressionLanguage language(Call call, Supplier<Throwable> thrown) {
        return new ExpressionLanguage() {
            @Override
            public String name() {
                return "x";
            }

            @Override
            public ExpressionCompiler newCompiler(CompileContext context) {
                throwAt(Call.NEW_COMPILER);
                return new ExpressionCompiler() {
                    @Override
                    public CompiledCondition compileCondition(Expression source) {
                        throwAt(Call.COMPILE);
                        return (evaluation, session) -> {
                            throwAt(Call.CONDITION);
                            return true;
                        };
                    }

                    @Override
                    public CompiledAction compileAction(Expression source) {
                        return (action, session) -> {
                            throwAt(Call.ACTION);
                            return ActionResult.done();
                        };
                    }

                    @Override
                    public Session newSession() {
                        throwAt(Call.NEW_SESSION);
                        return new Session() {
                        };
                    }

                    @Override
                    public void warmUp(Session session) {
                        throwAt(Call.WARM_UP);
                    }

                    @Override
                    public void checkFactName(String name) {
                        throwAt(Call.CHECK_FACT_NAME);
                    }

                    @Override
                    public Set<String> factNamesRead() {
                        throwAt(Call.FACT_NAMES_READ);
                        return null;
                    }
                };
            }

            private void throwAt(Call at) {
                if (at == call) {
                    Failures.<RuntimeException>rethrowUnchecked(thrown.get());
                }
            }
        };
    }

    /** An engine with only {@code language}, which makes {@code copiesAtLoad} copies of the rules as it loads them. */
    private static StatefulRulesEngine<Map<String, Object>> engine(ExpressionLanguage language, int copiesAtLoad) {
        return TestEngines.allMatches(HashMap::new, builder -> builder.language(language).defaultLanguage("x")
                .copiesAtLoad(copiesAtLoad));
    }

    private static List<Rule> rules() {
        return List.of(Rule.builder().ruleName("r").condition("c").action("a").build());
    }

    /** A bare overflow, and one a language wrapped as {@code new RuntimeException(e)}. */
    private static Stream<Arguments> overflows() {
        return Stream.of(
                Arguments.of("bare", (Supplier<Throwable>) StackOverflowError::new),
                Arguments.of("wrapped", (Supplier<Throwable>) () -> new RuntimeException(new StackOverflowError())));
    }

    /** What JDK 25 and 26 throw for a class an overflow in its static initializer left unusable. */
    private static NoClassDefFoundError deadClass() {
        NoClassDefFoundError dead = new NoClassDefFoundError("Could not initialize class com.acme.Dead");
        dead.initCause(new StackOverflowError());
        return dead;
    }

    // #1135

    @ParameterizedTest(name = "{0}")
    @MethodSource("overflows")
    @DisplayName("an overflow creating a compiler says the stack overflowed, maybe for the rule text (#1135)")
    void newCompilerOverflows(String how, Supplier<Throwable> overflow) {
        StatefulRulesEngine<Map<String, Object>> engine = engine(language(Call.NEW_COMPILER, overflow), 0);

        RuleCompilationException ex = assertLoggedAtError(RuleCompilationException.class, () -> engine.load(rules()));

        assertEquals("The 'x' expression language failed to create a compiler: " + LOAD_TIME, ex.getMessage());
        assertTrue(Failures.causeChain(ex).stream().anyMatch(StackOverflowError.class::isInstance),
                "the cause chain is kept");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("overflows")
    @DisplayName("an overflow telling which facts the rules read says the stack overflowed, maybe for the rule text"
            + " (#1135)")
    void factNamesReadOverflows(String how, Supplier<Throwable> overflow) {
        StatefulRulesEngine<Map<String, Object>> engine = engine(language(Call.FACT_NAMES_READ, overflow), 0);

        RuleCompilationException ex = assertLoggedAtError(RuleCompilationException.class, () -> engine.load(rules()));

        assertEquals("The 'x' expression language failed to tell which facts its rules read: " + LOAD_TIME,
                ex.getMessage());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("overflows")
    @DisplayName("an overflow checking a fact name says the stack overflowed, maybe for the rule text (#1135)")
    void checkFactNameOverflows(String how, Supplier<Throwable> overflow) {
        StatefulRulesEngine<Map<String, Object>> engine = engine(language(Call.CHECK_FACT_NAME, overflow), 0);
        engine.load(rules());

        EngineLogs.Outcome<IllegalArgumentException> outcome = EngineLogs.capture(IllegalArgumentException.class,
                () -> engine.run(new FactMap<>(new Fact<>("f", 1))));

        assertEquals("The 'x' expression language failed to check fact name 'f': " + LOAD_TIME,
                outcome.thrown().getMessage());
        assertTrue(outcome.logs().contains("ERROR " + ENGINE_LOGGER + outcome.thrown().getMessage()),
                outcome.logs());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("overflows")
    @DisplayName("an overflow checking a declared fact's name fails load() with the words run() uses, and no note"
            + " naming the overflow's class")
    void declaredFactCheckOverflows(String how, Supplier<Throwable> overflow) {
        StatefulRulesEngine<Map<String, Object>> engine = TestEngines.allMatches(HashMap::new, builder -> builder
                .language(language(Call.CHECK_FACT_NAME, overflow)).defaultLanguage("x").copiesAtLoad(0)
                .fact("f", Integer.class));

        RuleCompilationException ex = assertLoggedAtError(RuleCompilationException.class, () -> engine.load(rules()));

        assertEquals("Declared fact 'f' can't be used: The 'x' expression language failed to check fact name 'f': "
                + LOAD_TIME, ex.getMessage());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("overflows")
    @DisplayName("an overflow warming up a session says the stack overflowed, maybe for the rule text (#1135)")
    void warmUpOverflows(String how, Supplier<Throwable> overflow) {
        StatefulRulesEngine<Map<String, Object>> engine = engine(language(Call.WARM_UP, overflow), 1);

        RuleCompilationException ex = assertLoggedAtError(RuleCompilationException.class, () -> engine.load(rules()));

        assertEquals("The 'x' expression language failed to warm up a session: " + LOAD_TIME, ex.getMessage());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("overflows")
    @DisplayName("an overflow creating a session for a run says the stack overflowed (#1135)")
    void newSessionOverflows(String how, Supplier<Throwable> overflow) {
        StatefulRulesEngine<Map<String, Object>> engine = engine(language(Call.NEW_SESSION, overflow), 0);
        engine.load(rules());

        RuleExecutionException ex = assertLoggedAtError(RuleExecutionException.class,
                () -> engine.run(new FactMap<>()));

        assertEquals("The 'x' expression language failed to create a session: the stack overflowed", ex.getMessage());
    }

    @Test
    @DisplayName("an overflow creating a session for the copies made at load says the stack overflowed (#1135)")
    void newSessionAtLoadOverflows() {
        StatefulRulesEngine<Map<String, Object>> engine = engine(language(Call.NEW_SESSION, StackOverflowError::new),
                1);

        RuleCompilationException ex = assertLoggedAtError(RuleCompilationException.class, () -> engine.load(rules()));

        assertEquals("The 'x' expression language failed to create a session: the stack overflowed", ex.getMessage());
    }

    @Test
    @DisplayName("a call's failure whose message names its cause's class has no note naming it again")
    void causeNamedOnce() {
        StatefulRulesEngine<Map<String, Object>> engine = engine(language(Call.NEW_COMPILER,
                () -> new RuntimeException(new IllegalStateException())), 0);

        RuleCompilationException ex = assertLoggedAtError(RuleCompilationException.class, () -> engine.load(rules()));

        assertEquals("The 'x' expression language failed to create a compiler: java.lang.IllegalStateException",
                ex.getMessage());
    }

    @Test
    @DisplayName("a class an overflow left unusable, met creating a compiler, is described, not taken for an overflow")
    void newCompilerDeadClass() {
        StatefulRulesEngine<Map<String, Object>> engine = engine(language(Call.NEW_COMPILER,
                CallOutOverflowTest::deadClass), 0);

        RuleCompilationException ex = assertLoggedAtError(RuleCompilationException.class, () -> engine.load(rules()));

        assertEquals("The 'x' expression language failed to create a compiler: Could not initialize class"
                + " com.acme.Dead (caused by java.lang.StackOverflowError)", ex.getMessage());
    }

    // #1116

    @Test
    @DisplayName("a compile that fails on a class an overflow left unusable is described, not taken for an overflow"
            + " (#1116)")
    void compileDeadClass() {
        StatefulRulesEngine<Map<String, Object>> engine = engine(language(Call.COMPILE,
                () -> new IllegalStateException("[Error: Could not initialize class com.acme.Dead]", deadClass())), 0);

        RuleCompilationException ex = assertLoggedAtError(RuleCompilationException.class, () -> engine.load(rules()));

        assertEquals("Condition for rule 'r' failed to compile: [Error: Could not initialize class com.acme.Dead]"
                + " (caused by java.lang.StackOverflowError)", ex.getMessage());
    }

    @Test
    @DisplayName("a compile that throws the NoClassDefFoundError of a class an overflow left unusable names it (#1116)")
    void compileThrowsDeadClass() {
        StatefulRulesEngine<Map<String, Object>> engine = engine(language(Call.COMPILE,
                CallOutOverflowTest::deadClass), 0);

        RuleCompilationException ex = assertLoggedAtError(RuleCompilationException.class, () -> engine.load(rules()));

        assertEquals("Condition for rule 'r' failed to compile: Could not initialize class com.acme.Dead (caused by"
                + " java.lang.StackOverflowError)", ex.getMessage());
    }

    // #1141

    @Test
    @DisplayName("a compile that throws an InternalError around an overflow fails the rule with the overflow's reason,"
            + " and isn't rethrown as fatal (#1141)")
    void compileInternalErrorAroundOverflow() {
        InternalError wrapped = new InternalError(new StackOverflowError());
        StatefulRulesEngine<Map<String, Object>> engine = engine(language(Call.COMPILE, () -> wrapped), 0);

        RuleCompilationException ex = assertLoggedAtError(RuleCompilationException.class, () -> engine.load(rules()));

        assertEquals("Condition for rule 'r' failed to compile: the expression is too long or too deeply nested to"
                + " compile", ex.getMessage());
        assertSame(wrapped, ex.getCause());
    }

    @Test
    @DisplayName("a condition that throws an InternalError around an overflow, as the JDK wraps one, fails the rule,"
            + " and isn't rethrown as fatal (#1141)")
    void conditionInternalErrorAroundOverflow() {
        InternalError wrapped = new InternalError(new InvocationTargetException(new StackOverflowError()));
        StatefulRulesEngine<Map<String, Object>> engine = engine(language(Call.CONDITION, () -> wrapped), 0);
        engine.load(rules());

        RuleExecutionException ex = assertLoggedAtError(RuleExecutionException.class,
                () -> engine.run(new FactMap<>()));

        assertEquals("r", ex.getRuleName());
        assertTrue(Failures.causeChain(ex).contains(wrapped), ex.getMessage());
    }

    /**
     * A condition's or an action's overflow, bare or in an InternalError as the JDK wraps one, and each with what it
     * throws: which call, what it throws, and the start of the rule's failure.
     */
    private static Stream<Arguments> runOverflows() {
        Supplier<Throwable> bare = StackOverflowError::new;
        Supplier<Throwable> wrapped = () -> new InternalError(new InvocationTargetException(new StackOverflowError()));
        return Stream.of(
                Arguments.of("condition, bare", Call.CONDITION, bare, "Failed to evaluate condition"),
                Arguments.of("condition, in an InternalError", Call.CONDITION, wrapped, "Failed to evaluate condition"),
                Arguments.of("action, bare", Call.ACTION, bare, "Failed to execute action"),
                Arguments.of("action, in an InternalError", Call.ACTION, wrapped, "Failed to execute action"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("runOverflows")
    @DisplayName("a condition or an action that overflows fails its rule saying the stack overflowed, with the cause"
            + " chain kept")
    void runOverflowSaysSo(String how, Call call, Supplier<Throwable> overflow, String failed) {
        Throwable thrown = overflow.get();
        StatefulRulesEngine<Map<String, Object>> engine = engine(language(call, () -> thrown), 0);
        engine.load(rules());

        RuleExecutionException ex = assertLoggedAtError(RuleExecutionException.class,
                () -> engine.run(new FactMap<>()));

        assertEquals(failed + " for rule 'r': the stack overflowed", ex.getMessage());
        assertSame(thrown, ex.getCause());
    }

    @Test
    @DisplayName("an action whose nested run failed on an overflow names the nested run's failure, as for any nested"
            + " run's")
    void nestedRunOverflowNamesTheNestedRun() {
        StatefulRulesEngine<Map<String, Object>> inner = engine(language(Call.CONDITION, StackOverflowError::new), 0);
        inner.load(List.of(Rule.builder().ruleName("inner").condition("c").action("a").build()));
        StatefulRulesEngine<Map<String, Object>> outer = TestEngines.allMatches(HashMap::new, builder -> builder
                .language(StubExpressionLanguage.named("y").action((context, session) -> {
                    inner.run(new FactMap<>());
                    return ActionResult.done();
                })).defaultLanguage("y").copiesAtLoad(0));
        outer.load(rules());

        RuleExecutionException ex = assertThrows(RuleExecutionException.class, () -> outer.run(new FactMap<>()));

        assertEquals("Failed to execute action for rule 'r': a nested run() failed: Failed to evaluate condition for"
                + " rule 'inner': the stack overflowed", ex.getMessage());
    }

    @Test
    @DisplayName("a condition or an action that fails on no overflow is described as it was")
    void runFailureWithoutOverflowUnchanged() {
        for (Call call : List.of(Call.CONDITION, Call.ACTION)) {
            StatefulRulesEngine<Map<String, Object>> engine = engine(language(call,
                    () -> new IllegalStateException(deadClass())), 0);
            engine.load(rules());

            RuleExecutionException ex = assertLoggedAtError(RuleExecutionException.class,
                    () -> engine.run(new FactMap<>()));

            assertEquals((call == Call.CONDITION ? "Failed to evaluate condition" : "Failed to execute action")
                    + " for rule 'r': java.lang.NoClassDefFoundError: Could not initialize class com.acme.Dead"
                    + " (caused by java.lang.StackOverflowError)", ex.getMessage());
        }
    }

    @Test
    @DisplayName("an InternalError with no overflow in its cause chain is still fatal, rethrown unchanged")
    void internalErrorStillFatal() {
        InternalError fatal = new InternalError("JVM fault", new IllegalStateException("cause"));
        StatefulRulesEngine<Map<String, Object>> engine = engine(language(Call.COMPILE, () -> fatal), 0);

        assertLoggedThenRethrown(fatal, "Condition for rule 'r' failed to compile: JVM fault",
                () -> engine.load(rules()));
    }

    @Test
    @DisplayName("an InternalError around a class an overflow left unusable is still fatal: that's no overflow")
    void internalErrorAroundDeadClassStillFatal() {
        InternalError fatal = new InternalError(deadClass());
        StatefulRulesEngine<Map<String, Object>> engine = engine(language(Call.CONDITION, () -> fatal), 0);
        engine.load(rules());

        assertSame(fatal, EngineLogs.thrownBy(() -> engine.run(new FactMap<>())));
    }
}
