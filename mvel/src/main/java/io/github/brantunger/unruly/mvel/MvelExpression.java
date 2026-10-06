package io.github.brantunger.unruly.mvel;

import io.github.brantunger.unruly.api.language.ActionContext;
import io.github.brantunger.unruly.api.language.ActionResult;
import io.github.brantunger.unruly.api.language.CompiledAction;
import io.github.brantunger.unruly.api.language.CompiledCondition;
import io.github.brantunger.unruly.api.language.EvaluationContext;
import io.github.brantunger.unruly.api.language.Session;
import io.github.brantunger.unruly.mvel.warmup.WarmUpTarget;
import org.jspecify.annotations.Nullable;
import org.mvel2.MVEL;
import org.mvel2.ParserConfiguration;
import org.mvel2.ParserContext;
import org.mvel2.optimizers.OptimizerFactory;

import java.io.Serializable;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * One compiled MVEL condition or action, shared by every run of its rule list.
 *
 * <p>
 * MVEL caches an accessor in a compiled expression the first time it runs, and replaces it without synchronization
 * when a later run binds the same name to a different kind of object, so MVEL's compiled form isn't shared: each
 * {@link MvelSession} runs its own, from {@link #newCompiled()}.
 * </p>
 *
 * <p>
 * MVEL builds what reads each value as the expression first runs, and goes round in a loop that never ends as it
 * builds it for some expressions, such as a call after a class named with its package and a non-ASCII space (#857).
 * So each run may ask the copy's parser configuration for its class loader, as MVEL does on every round of that loop,
 * from the same place, only as many times from one place as {@link MvelAnalysis#classLoaderCallsPerSite} allows the
 * expression, and as many in all as {@link #classLoaderCallsPerRun} allows, and the rule fails with an
 * {@link Imports.RunLoop} once it has asked more.
 * </p>
 *
 * <p>
 * While it runs, {@link MvelWarningFilter} drops the WARNING MVEL logs itself, with the value unescaped, when a method
 * call or an indexed read fails inside it, most often because MVEL can't convert a fact to the method's parameter
 * type. The run still fails, and the engine reports the failure.
 * </p>
 *
 * <p>
 * While MVEL compiles it, the thread's context class loader is the rule list's {@link ExactNameClassLoader}. After
 * the rule list's class loader refuses a name, MVEL's {@code ParseTools.createClass} asks the thread's context class
 * loader for it, unless that is the same loader. It looks a property read through a value it types as {@code Object}
 * up as a class nested in {@code Object}, such as {@code java.lang.Object$p7} for {@code f.p7}, and one of the JDK's
 * class loaders kept a lock object for each such name it was asked for (#807). MVEL initialises a class a rule names
 * in full while it compiles, so the class's static initialiser sees the rule list's class loader as the context class
 * loader: one that keeps it keeps that loader, and one that asks it for a class defined at run time, with one of the
 * JDK's class loaders as the application's, is refused. The thread's own is restored when
 * MVEL returns or throws, on a run's thread too when it compiles the expression again for a new session. Running the
 * expression doesn't change it.
 * </p>
 */
final class MvelExpression implements CompiledCondition, CompiledAction {

    static {
        // MVEL's optimizer, as it sets up, makes a JVM-wide class loader whose parent is the thread's context class
        // loader at that moment, and keeps it for the life of the JVM. Set up here, before any compilation sets the
        // rule list's class loader as the context class loader, with MVEL's own class loader as the context class
        // loader, it holds neither a rule list's nor the one the first thread to load rules happens to have.
        withClassLoader(mvelClassLoader(), OptimizerFactory::getDefaultAccessorCompiler);
    }

    private final String source;
    private final Imports imports;
    // How many times in all each run of a compiled copy may ask for the class loader: classLoaderCallsPerRun, but for
    // tests.
    private final long callsPerRun;
    // The compiled copy from when the rule list loaded, which no run has used, until a session takes it.
    private final AtomicReference<Copy> loaded;

    private MvelExpression(String source, Imports imports, long callsPerRun, Copy loaded) {
        this.source = source;
        this.imports = imports;
        this.callsPerRun = callsPerRun;
        this.loaded = new AtomicReference<>(loaded);
    }

    /**
     * One session's compiled copy of an expression, and the parser configuration MVEL's compiled expression keeps,
     * which MVEL asks for its class loader as the expression runs.
     *
     * @param expression    MVEL's compiled expression
     * @param configuration The configuration it was compiled with, whose calls for the class loader are limited for
     *                      each run
     */
    record Copy(Serializable expression, ParserConfiguration configuration) {

        // The record's own equals, hashCode and toString, written out so that none links through ObjectMethods,
        // which can fail for good when first called deep in the stack (#996). equals compares the components last
        // first, as ObjectMethods does.
        @Override
        public final boolean equals(@Nullable Object other) {
            return this == other || other instanceof Copy that && Objects.equals(configuration, that.configuration)
                    && Objects.equals(expression, that.expression);
        }

        @Override
        public final int hashCode() {
            return Objects.hashCode(expression) * 31 + Objects.hashCode(configuration);
        }

        @Override
        public final String toString() {
            return "Copy[expression=" + expression + ", configuration=" + configuration + "]";
        }
    }

    /**
     * Compiles an expression.
     *
     * @param source  The expression's source text
     * @param imports The imports to compile it with
     * @return The compiled expression
     */
    static MvelExpression compile(String source, Imports imports) {
        return compile(new MvelAnalysis(source, imports));
    }

    /**
     * Compiles an expression whose runs may each ask for the class loader only so many times in all, in place of what
     * {@link #classLoaderCallsPerRun} allows it. Only for tests: the engine compiles with
     * {@link #compile(MvelAnalysis)}.
     *
     * @param source      The expression's source text
     * @param imports     The imports to compile it with
     * @param callsPerRun How many times in all each run of a compiled copy may ask
     * @return The compiled expression
     */
    static MvelExpression compile(String source, Imports imports, long callsPerRun) {
        return compile(new MvelAnalysis(source, imports), callsPerRun);
    }

    /**
     * Compiles an expression after MVEL's analysis pass over it.
     *
     * @param analysis The analysis pass, which hasn't run yet, of the expression to compile. When it rejects the
     *                 expression, {@link MvelAnalysis#rejectedType()} tells what it could read of the reason.
     * @return The compiled expression
     */
    static MvelExpression compile(MvelAnalysis analysis) {
        return compile(analysis, classLoaderCallsPerRun(analysis.sourceText().length()));
    }

    /**
     * Returns how many times in all each run of an expression may ask its parser configuration for the class loader:
     * as many as MVEL's analysis of it may (see {@link MvelAnalysis#classLoaderCallLimit}). Each run may also ask only
     * as many times from one place as {@link MvelAnalysis#classLoaderCallsPerSite} allows, a place told by the
     * {@value CallSites#RUN_FRAMES} frames at the top of the stack and its depth (see {@link CallSites}).
     *
     * <p>
     * MVEL asks as it builds what reads a value: in a copy's first run, again after 50 runs, and once a value's type
     * changes. The first run of a copy also compiles each argument that is a chain of properties, asking about twice
     * for each of its parts, from two places: 10,200 times for {@code s.equals(m.a.a...)} with 5,100 parts, 10,211
     * characters, 5,100 from each. Calls nested in one another through a class named with its package cost about half
     * the square of how deep they are as a copy first builds them, from places that differ in the stack's depth:
     * {@code a.B.f(} nested 340 deep around {@code x}, 2,381 characters, asks 57,970 times, at most 85 from one place,
     * and {@code java.lang.Math.abs(} nested 450 deep in a {@code foreach} over 60 values, 9,039 characters, asks
     * 202,951 times, at most 113 from one place. Without such arguments a run asked at most 6 times, with 201 packages
     * imported, however many rounds the expression's own loops went, so it never reads where it asks from. A function
     * that calls itself, with a package imported and a class loader that isn't one of the JDK's own, asks about once
     * more for each call still under way, as MVEL builds what reads a value in each before any is built, each at its
     * own depth: 205 times 200 calls deep. Only a thread with a very large stack gets deep enough to pass the limit in
     * all: a run on a thread of the JVM's default stack size ran out of stack at 500 deep.
     * </p>
     *
     * <p>
     * MVEL asks on every round of the loop that never ends (#857), from one place, about once every 1.8 microseconds,
     * keeping more memory each round until the run ends, so the limit for one place ends such a run after at most
     * {@value CallSites#UNCOUNTED_CALLS} calls, plus the limit for one place, plus one gap between two walks of the
     * stack, fewer than twice {@value CallSites#SAMPLED_EVERY} calls, plus the limit and one more, as the calls are
     * then counted one by one (see {@link CallSites}).
     * </p>
     *
     * @param length The expression's length
     * @return How many times each run may ask
     */
    static long classLoaderCallsPerRun(int length) {
        return MvelAnalysis.classLoaderCallLimit(length);
    }

    private static MvelExpression compile(MvelAnalysis analysis, long callsPerRun) {
        // compileExpression alone accepts some malformed input (e.g. `x == == 1`) and defers the error to
        // run(). The analysis pass catches more of it up front.
        Imports imports = analysis.compiledImports();
        return imports.classNameRoots().recordWhile(() -> withClassLoader(imports.classLoader(), () -> {
            analysis.compile();
            MvelExpression expression = new MvelExpression(analysis.sourceText(), imports, callsPerRun,
                    newCopy(analysis.sourceText(), imports, callsPerRun));
            // The classes MVEL looks up only as the expression runs count as well.
            ParserConfiguration lookups = imports.newConfiguration();
            imports.classNameRoots().lookUpDottedNames(analysis.sourceText(), imports.classLoader(),
                    lookups::hasImport);
            return expression;
        }));
    }

    @Override
    public Object evaluate(EvaluationContext context, Session session) throws Exception {
        // MVEL's WARNING for a failed method call or indexed read can quote a fact unescaped; the engine escapes it.
        boolean outermost = MvelWarningFilter.enter();
        try {
            return run(session, context.facts());
        } catch (RuntimeException e) {
            // What the rule's Java code threw, so a failure has the same cause before and after MVEL's JIT.
            throw CalledCodeFailures.<RuntimeException>unwrapped(e);
        } finally {
            MvelWarningFilter.leave(outermost);
        }
    }

    @Override
    public ActionResult execute(ActionContext context, Session session) throws Exception {
        // Reads the facts; the output object and the action's own assignments stay in this action, which changes the
        // output in place.
        boolean outermost = MvelWarningFilter.enter();
        try {
            run(session, new ActionVariables(context.facts(), context.output()));
        } catch (RuntimeException e) {
            throw CalledCodeFailures.<RuntimeException>unwrapped(e);
        } finally {
            MvelWarningFilter.leave(outermost);
        }
        return ActionResult.done();
    }

    /**
     * Returns MVEL's compiled form of this expression for a new session: the one compiled when the rule list loaded,
     * the first time, then a new compilation. The expression already compiled with these imports, so MVEL's analysis
     * pass isn't repeated. Sessions on several threads can call it at once.
     *
     * @return A compiled copy that no other session runs
     */
    Copy newCompiled() {
        Copy first = loaded.getAndSet(null);
        return first != null ? first
                : withClassLoader(imports.classLoader(), () -> newCopy(source, imports, callsPerRun));
    }

    /**
     * Compiles a copy of an expression, whose runs may each ask the configuration it was compiled with for the class
     * loader only so many times in all, and as many from one place as {@link MvelAnalysis#classLoaderCallsPerSite}
     * allows the expression (see {@link Imports#newRunConfiguration}).
     *
     * @param source      The expression's source text
     * @param imports     The imports to compile it with
     * @param callsPerRun How many times in all each run may ask
     * @return The compiled copy
     */
    static Copy newCopy(String source, Imports imports, long callsPerRun) {
        ParserConfiguration configuration = imports.newRunConfiguration(callsPerRun,
                MvelAnalysis.classLoaderCallsPerSite(source.length()));
        return new Copy(MVEL.compileExpression(source, newParserContext(imports, configuration)), configuration);
    }

    /**
     * Compiles and runs a property read and a method call on a {@link WarmUpTarget}, once each, as a run runs an
     * expression, for {@link MvelExpressionLanguage#prepare()}: a JVM's first read and first call load classes MVEL
     * evaluates them with. With MVEL's own class loader as the thread's context class loader, as the static initializer
     * sets MVEL's optimizer up, so the expressions MVEL compiles here, and drops, hold no caller's class loader.
     */
    static void warmUp() {
        Map<String, Object> variables = Map.of("v", new WarmUpTarget());
        withClassLoader(mvelClassLoader(), () -> {
            MVEL.executeExpression(MVEL.compileExpression("v.ready"), (Object) null, variables);
            return MVEL.executeExpression(MVEL.compileExpression("v.check()"), (Object) null, variables);
        });
    }

    /**
     * Calls MVEL with a class loader as the thread's context class loader, such as the rule list's while it compiles,
     * so MVEL asks no other class loader for a name the rule list's refuses, and restores the thread's own once MVEL
     * returns or throws.
     *
     * @param loader The class loader to set
     * @param call   The call to MVEL
     * @param <T>    What the call returns
     * @return What the call returned
     */
    private static <T> T withClassLoader(ClassLoader loader, Supplier<T> call) {
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        thread.setContextClassLoader(loader);
        try {
            return call.get();
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    // The class loader of the MVEL running the rules. PMD asks for the context class loader instead, which is the one
    // MVEL's optimizer mustn't hold.
    @SuppressWarnings("PMD.UseProperClassLoader")
    private static ClassLoader mvelClassLoader() {
        return OptimizerFactory.class.getClassLoader();
    }

    /**
     * Runs the session's compiled copy of this expression, with a run of it started (see {@link Imports#startRun}).
     * When MVEL went round in a loop in the run, the rule fails with the {@link Imports.RunLoop}, whatever MVEL threw:
     * it may wrap it in an exception of its own, as it does for what is thrown while it compiles part of the
     * expression as the expression runs, and end the run with that, or another failure. What MVEL threw in its place
     * is kept as suppressed, unless it holds the loop as its cause, or is a {@link VirtualMachineError}, such as an
     * {@link OutOfMemoryError}, which is thrown as it is, with nothing allocated for it. MVEL never ended a run
     * normally once it had thrown one, in 46 expressions stopped after each number of calls up to 30.
     *
     * @param session   The run's session
     * @param variables The names the expression reads, and their values
     * @return What the expression returned
     * @throws Imports.RunLoop if MVEL went round in a loop in the run
     */
    // What MVEL throws, an Error or a checked exception it throws unchecked too, is thrown again as it is, unless the
    // run went round in a loop. Identity tells whether MVEL let the loop's own Error out.
    @SuppressWarnings("PMD.CompareObjectsWithEquals")
    private Object run(Session session, Map<String, ?> variables) {
        Copy copy = mvelSession(session).compiled(this);
        Imports.startRun(copy.configuration());
        try {
            return MVEL.executeExpression(copy.expression(), (Object) null, variables);
        } catch (VirtualMachineError e) {
            // Fatal: as it is, with nothing allocated for it.
            throw e;
        } catch (Throwable e) {
            Imports.RunLoop loop = Imports.runLoop(copy.configuration());
            if (loop == null || loop == e) {
                throw e;
            }
            // A wrapper of the loop itself would make a chain that loops back to it.
            if (!ExceptionReads.causeChain(e).contains(loop)) {
                loop.addSuppressed(e);
            }
            throw loop;
        }
    }

    /**
     * Returns the session as the MVEL session it must be.
     *
     * @param session A session an MVEL compiler created
     * @return The session
     * @throws IllegalArgumentException if another language's compiler created it
     */
    static MvelSession mvelSession(Session session) {
        if (session instanceof MvelSession mvel) {
            return mvel;
        }
        throw new IllegalArgumentException("An MVEL expression runs with a session its compiler created, not "
                + session);
    }

    /**
     * Creates a context used by exactly one compilation. MVEL records variables, their types and inline
     * {@code import} statements on the context and its configuration, and a compiled expression goes on using
     * its context when it first runs. A context shared across rules let one rule change how another compiled,
     * and let {@code load()} modify it while a concurrent {@code run()} was still reading it. Only the
     * names found not to be classes are shared with the other compilations of the rule list; see
     * {@link Imports#newConfiguration()}.
     */
    static ParserContext newParserContext(Imports imports) {
        return newParserContext(imports, imports.newConfiguration());
    }

    private static ParserContext newParserContext(Imports imports, ParserConfiguration configuration) {
        ParserContext context = new ParserContext(configuration);
        if (imports.stronglyTyped()) {
            // Applied here, not only where the rule list loads, because a session compiles the expression again for
            // its own copy: without this, only the first copy would be the one that was type-checked.
            context.setStrongTyping(true);
            imports.inputs().forEach(context::addInput);
        }
        return context;
    }
}
