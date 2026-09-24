package io.github.brantunger.unruly.mvel;

import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.FactStore;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RuleListener;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
import io.github.brantunger.unruly.api.exception.RuleExecutionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.AbstractMap;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A rule's failure has the exception its Java code threw as its cause, whether MVEL called that code through
 * reflection, as it does the first times an expression runs and always with {@code -Dmvel2.disable.jit=true}, or
 * through the accessor its JIT compiled (#580). MVEL's own errors and an exception chain the rule's code built keep
 * their shape.
 */
// Public, as are the fact classes: MVEL's reflective accessors need to reach their methods.
@DisplayName("a rule's failure has what its Java code threw as its cause, before and after MVEL's JIT")
public class CalledCodeCauseTest {

    /** MVEL's reflective accessors for a call, which work out its arguments before they call it. */
    private static final Set<String> REFLECTIVE_CALLS = Set.of("org.mvel2.optimizers.impl.refl.nodes.MethodAccessor",
            "org.mvel2.optimizers.impl.refl.nodes.MethodAccessorNH",
            "org.mvel2.optimizers.impl.refl.nodes.ConstructorAccessor");
    /** How many runs that succeed MVEL's JIT gets to compile an expression. It takes about 50 within 100 ms. */
    static final int RUNS_FOR_THE_JIT = 5_000;

    /** How MVEL called the rule's code, going by the code's stack. */
    enum Stage {
        /** Through reflection, from a run before MVEL kept an accessor for the expression. */
        FIRST_RUNS,
        /** Through reflection, from the reflective accessor MVEL kept after a run that succeeded. */
        REFLECTIVE_ACCESSOR,
        /** From an accessor MVEL's JIT compiled, inside a call MVEL still makes through its reflective accessor. */
        MIXED,
        /** From the accessor MVEL's JIT compiled for the whole expression. */
        COMPILED
    }

    /** Java code a rule calls, which throws {@code failure} if it has one, and records how it was last called. */
    public static class Code {
        private final Throwable failure;
        private final boolean onlyWhileCompiled;
        private StackTraceElement[] calledFrom = new StackTraceElement[0];

        public Code(Throwable failure) {
            this(failure, false);
        }

        /**
         * Code that throws {@code failure} only when MVEL's JIT calls it to compile the whole expression, not an
         * argument of a call MVEL makes through its reflective accessor.
         */
        Code(Throwable failure, boolean onlyWhileCompiled) {
            this.failure = failure;
            this.onlyWhileCompiled = onlyWhileCompiled;
        }

        // A constructor a rule calls.
        public Code(Code code) {
            this(code.failure, code.onlyWhileCompiled);
            code.fail();
        }

        public static int fail(Code code) {
            return code.fail();
        }

        public int fail() {
            calledFrom = new Throwable().getStackTrace();
            if (failure != null && (!onlyWhileCompiled || compilingTheExpression(calledFrom))) {
                throw CalledCodeCauseTest.<RuntimeException>sneaky(failure);
            }
            return 1;
        }

        /**
         * Whether the JIT is compiling the expression itself: the outermost of MVEL's JIT compilations on the stack has
         * no reflective call under it, as the compilation of an argument that call works out would. Reads the stack
         * from its bottom, where the run began.
         */
        private static boolean compilingTheExpression(StackTraceElement[] frames) {
            for (int i = frames.length - 1; i >= 0; i--) {
                if (REFLECTIVE_CALLS.contains(frames[i].getClassName())) {
                    return false;
                }
                if (frames[i].getClassName().startsWith("org.mvel2.optimizers.dynamic.")
                        && frames[i].getMethodName().equals("optimize")) {
                    return true;
                }
            }
            return false;
        }

        public int one() {
            return 1;
        }

        public int getValue() {
            return fail();
        }

        public Code getInner() {
            return this;
        }

        public Code getNothing() {
            return null;
        }

        public void setRate(int rate) {
            fail();
        }

        public int twice(int value) {
            return 2 * value;
        }
    }

    /** A map whose values its own {@code get} computes. */
    public static class ComputedMap extends AbstractMap<String, Object> {
        private final Code code;

        public ComputedMap(Code code) {
            this.code = code;
        }

        @Override
        public Object get(Object key) {
            return code.fail();
        }

        @Override
        public boolean containsKey(Object key) {
            return true;
        }

        @Override
        public Set<Entry<String, Object>> entrySet() {
            return Set.of();
        }
    }

    /** Runs a rule whose code throws {@code failure}, and checks what the run did. */
    @FunctionalInterface
    interface FailingRun {
        void check(Throwable failure, FactStore<Object> facts);
    }

    @SuppressWarnings("unchecked")
    static <T extends Throwable> RuntimeException sneaky(Throwable failure) throws T {
        throw (T) failure;
    }

    public static void rethrow() {
        throw new IllegalArgumentException("from the reflective call");
    }

    static RulesEngine<Map<String, Object>> engine(String condition, String action, RuleListener... listeners) {
        RulesEngineBuilder<Map<String, Object>> builder = RulesEngineBuilder.<Map<String, Object>>firstMatch(
                HashMap::new).imports(Code.class.getName());
        for (RuleListener listener : listeners) {
            builder.listener(listener);
        }
        RulesEngine<Map<String, Object>> engine = builder.build();
        engine.load(List.of(Rule.builder().ruleName("r").condition(condition).action(action).build()));
        return engine;
    }

    static FactStore<Object> facts(Throwable failure) {
        return factsWith(new Code(failure));
    }

    static FactStore<Object> factsWith(Code code) {
        FactStore<Object> facts = new FactMap<>();
        facts.setValue("code", code);
        facts.setValue("fn", (Function<Integer, Integer>) value -> code.fail());
        facts.setValue("map", new ComputedMap(code));
        return facts;
    }

    /** Checks the run failed with what the code threw as its cause. */
    static FailingRun causeIs(RulesEngine<Map<String, Object>> engine) {
        return (failure, facts) -> {
            RuleExecutionException thrown = assertThrows(RuleExecutionException.class, () -> engine.run(facts));
            assertSame(failure, thrown.getCause(), thrown.getMessage());
        };
    }

    /**
     * Returns how MVEL last called the code: the frame that called it, past the code's own frames, and whether a call
     * MVEL makes through its reflective accessor, whose arguments it works out first, or MVEL's first run is under it.
     */
    static Stage stage(Code code) {
        StackTraceElement caller = Arrays.stream(code.calledFrom)
                .filter(frame -> !frame.getClassName().startsWith(CalledCodeCauseTest.class.getName()))
                .findFirst().orElseThrow();
        boolean reflectiveCall = Arrays.stream(code.calledFrom).anyMatch(frame -> REFLECTIVE_CALLS.contains(
                frame.getClassName()));
        if (caller.getClassName().startsWith("ASMAccessorImpl")) {
            return reflectiveCall ? Stage.MIXED : Stage.COMPILED;
        }
        assertTrue(caller.getClassName().startsWith("jdk.internal.reflect."), caller::toString);
        boolean firstRun = Arrays.stream(code.calledFrom).anyMatch(frame -> frame.getClassName()
                .equals("org.mvel2.optimizers.impl.refl.ReflectiveAccessorOptimizer"));
        return firstRun ? Stage.FIRST_RUNS : Stage.REFLECTIVE_ACCESSOR;
    }

    /** Runs the rule, with code that doesn't fail, until MVEL calls the code as {@code stage} says. */
    static void runUntil(RulesEngine<Map<String, Object>> engine, Stage stage) {
        Code code = new Code((Throwable) null);
        for (int run = 0; run < RUNS_FOR_THE_JIT; run++) {
            code = new Code((Throwable) null);
            engine.run(factsWith(code));
            if (stage(code) == stage) {
                return;
            }
        }
        fail("MVEL's JIT didn't compile the expression in " + RUNS_FOR_THE_JIT + " runs: the code was called at "
                + stage(code) + " from " + Arrays.toString(code.calledFrom));
    }

    private static void failTwice(Supplier<Throwable> failures, FailingRun run, Stage stage) {
        for (int i = 0; i < 2; i++) {
            Throwable failure = failures.get();
            Code code = new Code(failure);
            run.check(failure, factsWith(code));
            assertEquals(stage, stage(code), () -> Arrays.toString(code.calledFrom));
        }
    }

    /**
     * Fails the rule's code twice as MVEL calls it each way: on the first runs, before MVEL keeps an accessor; through
     * the reflective accessor it keeps once a run succeeds; and through the accessor its JIT compiled, once a run that
     * succeeds shows it. No run fails while the JIT compiles the expression, which would keep the reflective accessor
     * for good; {@link #argumentCompiledInAReflectiveCall()} is that case.
     */
    static void inEveryStage(RulesEngine<Map<String, Object>> engine, Supplier<Throwable> failures, FailingRun run) {
        failTwice(failures, run, Stage.FIRST_RUNS);
        engine.run(facts(null));
        failTwice(failures, run, Stage.REFLECTIVE_ACCESSOR);
        runUntil(engine, Stage.COMPILED);
        failTwice(failures, run, Stage.COMPILED);
    }

    static Stream<Arguments> conditions() {
        return Stream.of(
                Arguments.of("a method", "code.fail() == 1"),
                Arguments.of("a getter", "code.value == 1"),
                Arguments.of("a nested getter", "code.inner.value == 1"),
                Arguments.of("a static method", "Code.fail(code) == 1"),
                Arguments.of("a lambda", "fn.apply(1) == 1"),
                Arguments.of("a constructor", "new Code(code).one() == 1"));
    }

    // A condition can't assign, so a setter is only an action's.
    static Stream<Arguments> actions() {
        return Stream.of(
                Arguments.of("a method", "code.fail()"),
                Arguments.of("a getter", "output.put('v', code.value)"),
                Arguments.of("a nested getter", "output.put('v', code.inner.value)"),
                Arguments.of("a setter", "code.rate = 5"),
                Arguments.of("a static method", "Code.fail(code)"),
                Arguments.of("a lambda", "fn.apply(1)"),
                Arguments.of("a constructor", "output.put('c', new Code(code))"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("conditions")
    @DisplayName("from a condition calling it, through reflection and after the JIT")
    void fromACondition(String call, String condition) {
        RulesEngine<Map<String, Object>> engine = engine(condition, "output.put('k', 1)");

        inEveryStage(engine, () -> new IllegalStateException(call), causeIs(engine));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("actions")
    @DisplayName("from an action calling it, through reflection and after the JIT")
    void fromAnAction(String call, String action) {
        RulesEngine<Map<String, Object>> engine = engine("true", action);

        inEveryStage(engine, () -> new IllegalStateException(call), causeIs(engine));
    }

    /**
     * A run that fails while MVEL's JIT compiles the call to {@code put} leaves MVEL calling {@code put} through its
     * reflective accessor for good, and the JIT compiles the argument later, on its own. What the argument's code
     * throws then reaches the reflective accessor unwrapped, and it wraps it in a {@link RuntimeException} with no
     * {@link InvocationTargetException} under it. Unwrapping that too would also unwrap MVEL's own errors in an
     * argument, such as a null in a property path, so it's left as MVEL threw it. This documents what happens; a
     * change to it is a change of behaviour to decide on.
     */
    @Test
    @DisplayName("known gap: an argument the JIT compiled, in a call MVEL keeps making through reflection")
    void argumentCompiledInAReflectiveCall() {
        RulesEngine<Map<String, Object>> engine = engine("true", "output.put('v', code.value)");
        IllegalStateException whileCompiled = new IllegalStateException("while compiled");
        RuleExecutionException compiling = null;
        for (int run = 0; compiling == null && run < RUNS_FOR_THE_JIT; run++) {
            try {
                engine.run(factsWith(new Code(whileCompiled, true)));
            } catch (RuleExecutionException e) {
                compiling = e;
            }
        }
        assertNotNull(compiling, "MVEL's JIT didn't compile the expression in " + RUNS_FOR_THE_JIT + " runs");
        assertSame(whileCompiled, compiling.getCause(), compiling.getMessage());
        runUntil(engine, Stage.MIXED);
        IllegalStateException failure = new IllegalStateException("from the compiled argument");

        RuleExecutionException thrown = assertThrows(RuleExecutionException.class,
                () -> engine.run(facts(failure)));

        assertEquals(RuntimeException.class, thrown.getCause().getClass());
        assertTrue(thrown.getCause().getMessage().startsWith("cannot invoke method: put"),
                thrown.getCause().getMessage());
        assertSame(failure, thrown.getCause().getCause());
    }

    @Test
    @DisplayName("with MVEL's JIT turned off, at every run")
    void withTheJitOff(@TempDir Path dir) throws IOException, InterruptedException {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        // Output to a file, so waiting is bounded by waitFor and not by the child closing a pipe.
        Path log = dir.resolve("scenario.log");
        // The class path in an argument file, not on the command line or in the environment, which Windows limits to
        // 32,767 characters. In the file, a quoted argument keeps its spaces and a backslash escapes what follows.
        Path arguments = dir.resolve("classpath.args");
        Files.writeString(arguments, "-cp \"" + System.getProperty("java.class.path").replace("\\", "\\\\") + "\"",
                Charset.forName(System.getProperty("native.encoding")));
        Process process = new ProcessBuilder(java.toString(), "@" + arguments, "-Dmvel2.disable.jit=true",
                "-Dorg.slf4j.simpleLogger.defaultLogLevel=off", NoJitScenario.class.getName())
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start();
        boolean finished;
        try {
            finished = process.waitFor(60, TimeUnit.SECONDS);
        } finally {
            process.destroyForcibly();
        }
        String output = Files.readString(log, StandardCharsets.UTF_8);

        assertTrue(finished, "the scenario didn't finish:\n" + output);
        assertEquals(0, process.exitValue(), "scenario output:\n" + output);
        assertTrue(output.contains(NoJitScenario.DONE), output);
    }

    @Nested
    @DisplayName("what the code threw, whatever its type")
    class Types {

        static Stream<Arguments> failures() {
            return Stream.of(
                    Arguments.of("NoClassDefFoundError",
                            (Supplier<Throwable>) () -> new NoClassDefFoundError("com/acme/Missing")),
                    Arguments.of("AssertionError", (Supplier<Throwable>) () -> new AssertionError("assertion")),
                    Arguments.of("StackOverflowError", (Supplier<Throwable>) () -> new StackOverflowError("deep")),
                    Arguments.of("IOException", (Supplier<Throwable>) () -> new IOException("checked")),
                    Arguments.of("Throwable", (Supplier<Throwable>) () -> new Throwable("neither")));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("failures")
        @DisplayName("is the cause of the rule's failure, in a condition and in an action, before and after the JIT")
        void isTheCause(String type, Supplier<Throwable> failures) {
            RulesEngine<Map<String, Object>> condition = engine("code.fail() == 1", "output.put('k', 1)");
            RulesEngine<Map<String, Object>> action = engine("true", "code.fail()");

            inEveryStage(condition, failures, causeIs(condition));
            inEveryStage(action, failures, causeIs(action));
        }

        @Test
        @DisplayName("an OutOfMemoryError is rethrown, and the rule's failure a listener sees has it as its cause")
        void outOfMemory() {
            List<RuleExecutionException> errors = new CopyOnWriteArrayList<>();
            RulesEngine<Map<String, Object>> engine = engine("code.fail() == 1", "output.put('k', 1)",
                    new RuleListener() {
                        @Override
                        public void onError(Rule rule, RuleExecutionException error) {
                            errors.add(error);
                        }
                    });

            inEveryStage(engine, () -> new OutOfMemoryError("no room"), (oom, facts) -> {
                errors.clear();
                assertSame(oom, assertThrows(OutOfMemoryError.class, () -> engine.run(facts)));
                assertEquals(1, errors.size());
                assertSame(oom, errors.get(0).getCause());
            });
        }

        @Test
        @DisplayName("an InterruptedException stops the run, keeping the interrupt status and what the code threw")
        void interrupted() {
            RulesEngine<Map<String, Object>> engine = engine("code.fail() == 1", "output.put('k', 1)");

            inEveryStage(engine, () -> new InterruptedException("stopped"), (interrupt, facts) -> {
                RuleExecutionException thrown;
                try {
                    thrown = assertThrows(RuleExecutionException.class, () -> engine.run(facts));
                } finally {
                    assertTrue(Thread.interrupted(), "the interrupt status is set again, and cleared here");
                }
                assertEquals("run() was interrupted during rule 'r'", thrown.getMessage());
                assertInstanceOf(InterruptedException.class, thrown.getCause());
                // What the condition threw is kept as a stopped run keeps it: suppressed.
                assertSame(interrupt, thrown.getSuppressed()[0]);
            });
        }
    }

    @Nested
    @DisplayName("an exception chain the rule's code built is kept")
    class UsersOwnChain {

        @Test
        @DisplayName("its own exception wrapping an InvocationTargetException from reflection")
        void wrappingAReflectiveCall() {
            RulesEngine<Map<String, Object>> engine = engine("code.fail() == 1", "output.put('k', 1)");

            inEveryStage(engine, () -> {
                try {
                    CalledCodeCauseTest.class.getMethod("rethrow").invoke(null);
                    throw new AssertionError("rethrow() returned");
                } catch (InvocationTargetException e) {
                    return new RuntimeException("the rule's code wraps a reflective call", e);
                } catch (ReflectiveOperationException e) {
                    throw new AssertionError(e);
                }
            }, (wrapper, facts) -> {
                causeIs(engine).check(wrapper, facts);
                assertInstanceOf(InvocationTargetException.class, wrapper.getCause());
            });
        }

        @Test
        @DisplayName("an InvocationTargetException it threw itself")
        void throwingAnInvocationTargetException() {
            RulesEngine<Map<String, Object>> engine = engine("code.fail() == 1", "output.put('k', 1)");

            inEveryStage(engine, () -> new InvocationTargetException(new IllegalArgumentException("inner")),
                    causeIs(engine));
        }
    }

    @Nested
    @DisplayName("MVEL's own errors are unchanged")
    class MvelErrors {

        private RuleExecutionException failed(String condition) {
            return assertThrows(RuleExecutionException.class,
                    () -> engine(condition, "output.put('k', 1)").run(facts(null)));
        }

        @Test
        @DisplayName("a property it can't resolve")
        void unresolvableProperty() {
            RuleExecutionException thrown = failed("code.nope == 1");

            assertEquals("org.mvel2.PropertyAccessException", thrown.getCause().getClass().getName());
            assertNull(thrown.getCause().getCause());
        }

        @Test
        @DisplayName("a value it can't convert")
        void coercion() {
            RuleExecutionException thrown = failed("code.twice('abc') == 2");

            assertEquals("org.mvel2.CompileException", thrown.getCause().getClass().getName());
            assertInstanceOf(NumberFormatException.class, thrown.getCause().getCause());
        }

        @Test
        @DisplayName("a null in a property path")
        void nullInAPath() {
            RuleExecutionException thrown = failed("code.nothing.value == 1");

            assertEquals("org.mvel2.PropertyAccessException", thrown.getCause().getClass().getName());
            assertInstanceOf(NullPointerException.class, thrown.getCause().getCause());
        }

        @Test
        @DisplayName("a method that doesn't exist")
        void missingMethod() {
            RuleExecutionException thrown = failed("code.noSuch() == 1");

            assertEquals("org.mvel2.PropertyAccessException", thrown.getCause().getClass().getName());
            assertNull(thrown.getCause().getCause());
        }
    }

    /**
     * Not reached: MVEL reads a {@link Map} with {@code get} directly the first times, not through reflection, and
     * wraps what {@code get} threw in its own exception with no {@link InvocationTargetException} between them. This
     * documents what happens; a change to it is a change of behaviour to decide on.
     */
    @Test
    @DisplayName("known gap: what a Map's own get threw is under MVEL's CompileException the first time")
    void mapGetBeforeTheJit() {
        IllegalStateException failure = new IllegalStateException("from get");

        RuleExecutionException thrown = assertThrows(RuleExecutionException.class,
                () -> engine("map.key == 1", "output.put('k', 1)").run(facts(failure)));

        assertEquals("org.mvel2.CompileException", thrown.getCause().getClass().getName());
        assertSame(failure, thrown.getCause().getCause());
    }

    /**
     * The unwrapping recognises reflection's {@link InvocationTargetException} by the package of the frame that
     * created it. A JDK that moved that code would leave every failure wrapped, so this fails first.
     */
    @Test
    @DisplayName("the running JDK creates reflection's InvocationTargetException in jdk.internal.reflect")
    void reflectionFramesOnThisJdk() {
        InvocationTargetException method = assertThrows(InvocationTargetException.class,
                () -> CalledCodeCauseTest.class.getMethod("rethrow").invoke(null));
        InvocationTargetException constructor = assertThrows(InvocationTargetException.class,
                () -> Code.class.getConstructor(Code.class).newInstance(new Code(new IllegalStateException())));

        assertTrue(method.getStackTrace()[0].getClassName().startsWith("jdk.internal.reflect."),
                () -> String.valueOf(method.getStackTrace()[0]));
        assertTrue(constructor.getStackTrace()[0].getClassName().startsWith("jdk.internal.reflect."),
                () -> String.valueOf(constructor.getStackTrace()[0]));
    }
}
