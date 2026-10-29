package io.github.brantunger.unruly.test;

import io.github.brantunger.unruly.api.RunOptions;
import io.github.brantunger.unruly.api.language.ActionContext;
import io.github.brantunger.unruly.api.language.CompileContext;
import io.github.brantunger.unruly.api.language.EvaluationContext;
import io.github.brantunger.unruly.api.language.ExpressionLanguage;
import io.github.brantunger.unruly.core.EngineActionContext;
import io.github.brantunger.unruly.core.EngineCompileContext;
import io.github.brantunger.unruly.core.EngineEvaluationContext;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Creates the contexts the engine passes to an expression language, for unit tests of a language's compiler and
 * compiled expressions. The context interfaces are sealed, so a test can't implement them. These are the engine's own
 * implementations, and behave as they do in a run: for example, writing to the facts fails with the engine's message,
 * and the evaluation and action contexts reject a fact with a {@code null} or blank name with a run's message, though
 * not one with a name a language reserves: they have no engine, and so no languages to ask. Each context from
 * {@code evaluation} or {@code action} is for a run of its own, so the values it keeps with
 * {@link EvaluationContext#runScoped} aren't shared with another context; {@link #actionInRun} creates one in the run
 * of another context, and shares its values. A context's run started when the context was created, by the system
 * clock, and has no tags, unless the context was created with {@link #evaluation(Map, Instant, Instant, Set)} or
 * {@link #action(Map, Object, Instant, Instant, Set)}, which give it the {@link EvaluationContext#startedAt()} and
 * {@link EvaluationContext#tags()} of a run with an engine's clock and {@code RunOptions.withTags(...)}, or with
 * {@link #actionInRun} from such a context, which has the same start and tags. No run ends by itself, so
 * {@link #endRun} closes the values a context keeps with {@link EvaluationContext#runScopedClosing}, and the actions
 * it registered with {@link EvaluationContext#onCancel(Runnable)} that haven't started, as the end of a run does.
 *
 * <p>
 * To check a language against everything the engine promises for its rules, extend
 * {@link ExpressionLanguageContractTest}.
 * </p>
 */
public final class LanguageTestContexts {

    private LanguageTestContexts() {
    }

    /**
     * Creates a compile context without imports. Its class loader is the current thread's context class loader, or
     * this class's class loader if the thread has none, as the engine chooses one.
     *
     * @return The context
     */
    // The engine also falls back to its own class loader when the thread has no context class loader.
    @SuppressWarnings("PMD.UseProperClassLoader")
    public static CompileContext compile() {
        ClassLoader contextLoader = Thread.currentThread().getContextClassLoader();
        return compile(Set.of(), Set.of(),
                contextLoader != null ? contextLoader : LanguageTestContexts.class.getClassLoader());
    }

    /**
     * Creates a compile context.
     *
     * @param packageImports The imported packages, such as {@code java.util}; copied
     * @param classImports   The classes imported one by one; copied
     * @param classLoader    The class loader to look up classes in the imported packages with
     * @return The context
     * @throws NullPointerException     if an argument, or an element of a set, is {@code null}
     * @throws IllegalArgumentException if an imported package has more than 1,000 characters or more than 64
     *                                  dot-separated parts, or isn't a valid package name, which an engine rejects
     *                                  too, with the same message
     */
    public static CompileContext compile(Set<String> packageImports, Set<Class<?>> classImports,
                                         ClassLoader classLoader) {
        return compile(packageImports, classImports, classLoader, Object.class, Map.of());
    }

    /**
     * Creates a compile context with an output type and the language's options.
     *
     * @param packageImports The imported packages, such as {@code java.util}; copied
     * @param classImports   The classes imported one by one; copied
     * @param classLoader    The class loader to look up classes in the imported packages with
     * @param outputType     The type of the output object, as the engine's builder was told; {@code Object.class} when
     *                       it wasn't
     * @param options        The language's options, as the engine's builder was given them; copied
     * @return The context
     * @throws NullPointerException     if an argument, or an element of a set or of the options, is {@code null}
     * @throws IllegalArgumentException if an imported package has more than 1,000 characters or more than 64
     *                                  dot-separated parts, or isn't a valid package name, which an engine rejects
     *                                  too, with the same message
     */
    public static CompileContext compile(Set<String> packageImports, Set<Class<?>> classImports,
                                         ClassLoader classLoader, Class<?> outputType, Map<String, String> options) {
        return compile(packageImports, classImports, classLoader, outputType, options, Map.of(), false);
    }

    /**
     * Creates a compile context for an engine that was told which facts its rules use, so a language's tests can
     * check what it makes of the declarations.
     *
     * @param packageImports   The imported packages, such as {@code java.util}; copied
     * @param classImports     The classes imported one by one; copied
     * @param classLoader      The class loader to look up classes in the imported packages with
     * @param outputType       The type of the output object, as the engine's builder was told; {@code Object.class}
     *                         when it wasn't
     * @param options          The language's options, as the engine's builder was given them; copied
     * @param declaredFacts    The declared type of each fact, by name, as the engine's builder was told; copied
     * @param allFactsDeclared Whether a run may supply only the declared facts, as
     *                         {@code RulesEngineBuilder.requireDeclaredFacts()} says
     * @return The context. A fact declared with a primitive type is given as its wrapper, as an engine gives it.
     * @throws NullPointerException     if an argument, or an element of a set, of the options or of the declarations,
     *                                  is {@code null}
     * @throws IllegalArgumentException if an imported package has more than 1,000 characters or more than 64
     *                                  dot-separated parts, or isn't a valid package name, or a fact is declared with
     *                                  a blank name or the name {@code output}, which an engine rejects too when one
     *                                  of its languages reserves it, as the default
     *                                  {@code ExpressionLanguage.reservedFactNames()} does
     */
    public static CompileContext compile(Set<String> packageImports, Set<Class<?>> classImports,
                                         ClassLoader classLoader, Class<?> outputType, Map<String, String> options,
                                         Map<String, Class<?>> declaredFacts, boolean allFactsDeclared) {
        return new EngineCompileContext(packageImports, classImports, classLoader, outputType, options, declaredFacts,
                allFactsDeclared);
    }

    /**
     * Creates a compile context with the language's own imports, as the engine's builder was given them with
     * {@code RulesEngineBuilder.languageImports(...)}, so a language's tests can check what it makes of them.
     *
     * @param packageImports   The imported packages, such as {@code java.util}; copied
     * @param classImports     The classes imported one by one; copied
     * @param classLoader      The class loader to look up classes in the imported packages with
     * @param outputType       The type of the output object, as the engine's builder was told; {@code Object.class}
     *                         when it wasn't
     * @param options          The language's options, as the engine's builder was given them; copied
     * @param declaredFacts    The declared type of each fact, by name, as the engine's builder was told; copied
     * @param allFactsDeclared Whether a run may supply only the declared facts, as
     *                         {@code RulesEngineBuilder.requireDeclaredFacts()} says
     * @param languageImports  The language's own imports, such as {@code lodash/fp}, as written and in order; copied
     * @return The context. Its {@link CompileContext#languageImports()} are {@code languageImports}, duplicates
     *         included. A fact declared with a primitive type is given as its wrapper, as an engine gives it.
     * @throws NullPointerException     if an argument, or an element of a set, of the language imports, of the options
     *                                  or of the declarations, is {@code null}
     * @throws IllegalArgumentException if an imported package has more than 1,000 characters or more than 64
     *                                  dot-separated parts, or isn't a valid package name, a language import has more
     *                                  than 1,000 characters, or a fact is declared with a blank name or the name
     *                                  {@code output}, which an engine rejects too when one of its languages reserves
     *                                  it, as the default {@code ExpressionLanguage.reservedFactNames()} does
     */
    public static CompileContext compile(Set<String> packageImports, Set<Class<?>> classImports,
                                         ClassLoader classLoader, Class<?> outputType, Map<String, String> options,
                                         Map<String, Class<?>> declaredFacts, boolean allFactsDeclared,
                                         List<String> languageImports) {
        return new EngineCompileContext(packageImports, classImports, classLoader, outputType, options, declaredFacts,
                allFactsDeclared, true, languageImports);
    }

    /**
     * Creates a compile context for one language, which rejects exactly the declarations an engine with that language
     * rejects when it's built: a fact may not be declared with a name the language reserves, as its
     * {@link ExpressionLanguage#reservedFactNames()} returns them, asked once, so a language that reserves no name may
     * declare {@code output}. The other {@code compile} methods reject {@code output}, which the default reserves.
     *
     * @param language         The language the context is for; asked for its name and the fact names it reserves
     * @param packageImports   The imported packages, such as {@code java.util}; copied
     * @param classImports     The classes imported one by one; copied
     * @param classLoader      The class loader to look up classes in the imported packages with
     * @param outputType       The type of the output object, as the engine's builder was told; {@code Object.class}
     *                         when it wasn't
     * @param options          The language's options, as the engine's builder was given them; copied
     * @param declaredFacts    The declared type of each fact, by name, as the engine's builder was told; copied
     * @param allFactsDeclared Whether a run may supply only the declared facts, as
     *                         {@code RulesEngineBuilder.requireDeclaredFacts()} says
     * @param languageImports  The language's own imports, such as {@code lodash/fp}, as written and in order; copied
     * @return The context. Its {@link CompileContext#languageImports()} are {@code languageImports}, duplicates
     *         included. A fact declared with a primitive type is given as its wrapper, as an engine gives it.
     * @throws NullPointerException     if an argument, or an element of a set, of the language imports, of the options
     *                                  or of the declarations, is {@code null}
     * @throws IllegalArgumentException if the language's name is {@code null} or blank; if an imported package has
     *                                  more than 1,000 characters or more than 64 dot-separated parts, or isn't a
     *                                  valid package name, a language import has more than 1,000 characters, or a
     *                                  fact is declared with a blank name; or if a fact is declared with a name the
     *                                  language reserves, with the message {@code build()} gives
     * @throws IllegalStateException    if the language's {@code reservedFactNames()} returns {@code null} or a set
     *                                  holding {@code null}, as {@code build()} throws it
     */
    public static CompileContext compile(ExpressionLanguage language, Set<String> packageImports,
                                         Set<Class<?>> classImports, ClassLoader classLoader, Class<?> outputType,
                                         Map<String, String> options, Map<String, Class<?>> declaredFacts,
                                         boolean allFactsDeclared, List<String> languageImports) {
        return EngineCompileContext.forLanguage(language, packageImports, classImports, classLoader, outputType,
                options, declaredFacts, allFactsDeclared, languageImports);
    }

    /**
     * Creates the context a condition is evaluated against.
     *
     * @param facts The facts by name, whose values can be {@code null}; copied
     * @return The context. Its facts are read-only, and a write fails with the message the engine uses for a condition.
     *         It equals only itself, as in a run, so two created from the same facts aren't equal.
     * @throws NullPointerException     if {@code facts} is {@code null}
     * @throws IllegalArgumentException if a fact's name is {@code null} or blank, which a run rejects with the same
     *                                  message
     */
    public static EvaluationContext evaluation(Map<String, ? extends @Nullable Object> facts) {
        return evaluation(facts, null);
    }

    /**
     * Creates the context a condition is evaluated against, for a run with a deadline.
     *
     * <p>
     * The context reads the system clock once, when it's created, to learn how far away the deadline is, and from
     * then on times it with a monotonic clock, as a run does: a step of the system clock afterwards doesn't move when
     * the context is cancelled. Its {@link EvaluationContext#timeLeft()} is measured the same way.
     * </p>
     *
     * @param facts    The facts by name, whose values can be {@code null}; copied
     * @param deadline When the run must stop, or {@code null} if it has none, as an engine built without
     *                 {@code runTimeout} gives it
     * @return The context. Its {@link EvaluationContext#isCancelled()} answers as it does in a run: {@code true} once
     *         the deadline has passed, measured with a monotonic clock from when the context was created, or while
     *         the calling thread's interrupt status is set. It equals only itself, as in a run.
     * @throws NullPointerException     if {@code facts} is {@code null}
     * @throws IllegalArgumentException if a fact's name is {@code null} or blank, which a run rejects with the same
     *                                  message
     */
    public static EvaluationContext evaluation(Map<String, ? extends @Nullable Object> facts,
                                               @Nullable Instant deadline) {
        return new EngineEvaluationContext(copyOf(facts), deadline);
    }

    /**
     * Returns a copy of the facts a context is created from, so a later change to the caller's map doesn't reach the
     * context.
     */
    private static Map<String, Object> copyOf(Map<String, ? extends @Nullable Object> facts) {
        Objects.requireNonNull(facts, "facts must not be null");
        return new LinkedHashMap<>(facts);
    }

    /**
     * Creates the context a condition is evaluated against, for a run that started at {@code startedAt} by its
     * engine's clock and was given {@code tags}: so a language's test can check what its conditions make of the run's
     * {@link EvaluationContext#startedAt()} and {@link EvaluationContext#tags()}, as a run with
     * {@code RulesEngineBuilder.clock(...)} and {@code RunOptions.withTags(...)} gives them. The deadline is timed as
     * {@link #evaluation(Map, Instant)} describes.
     *
     * @param facts     The facts by name, whose values can be {@code null}; copied
     * @param deadline  When the run must stop, or {@code null} if it has none, as an engine built without
     *                  {@code runTimeout} gives it
     * @param startedAt When the run started, by the engine's clock, as a fixed clock gives it
     * @param tags      The run's tags, as {@code RunOptions.withTags(...)} was given them, or empty for a run that
     *                  uses every rule; copied
     * @return The context. Its {@link EvaluationContext#startedAt()} is {@code startedAt}, and its
     *         {@link EvaluationContext#tags()} are {@code tags} as a run has them: in {@link String} order and
     *         unmodifiable. It equals only itself, as in a run.
     * @throws NullPointerException     if {@code facts}, {@code startedAt} or {@code tags} is {@code null}
     * @throws IllegalArgumentException if a fact's name is {@code null} or blank, or a tag is {@code null} or blank,
     *                                  which a run rejects with the same message
     */
    public static EvaluationContext evaluation(Map<String, ? extends @Nullable Object> facts,
                                               @Nullable Instant deadline, Instant startedAt, Set<String> tags) {
        Map<String, Object> copy = copyOf(facts);
        Objects.requireNonNull(startedAt, "startedAt must not be null");
        return new EngineEvaluationContext(copy, deadline, startedAt, runTags(tags));
    }

    /**
     * Returns the tags a run given {@code tags} has, as {@code RunOptions.withTags(...)} makes them, with its checks
     * and messages: sorted and unmodifiable, or none for a run that uses every rule.
     */
    private static Set<String> runTags(Set<String> tags) {
        Objects.requireNonNull(tags, "tags must not be null");
        return tags.isEmpty() ? Set.of() : RunOptions.defaults().withTags(tags).tags();
    }

    /**
     * Creates the context an action runs against.
     *
     * @param facts  The facts by name, whose values can be {@code null}; copied
     * @param output The output object, which the action changes in place
     * @return The context. Its facts are read-only, and a write fails with the message the engine uses for an action.
     *         It equals only itself, as in a run, so two created from the same facts and output aren't equal.
     * @throws NullPointerException     if {@code facts} or {@code output} is {@code null}
     * @throws IllegalArgumentException if a fact's name is {@code null} or blank, which a run rejects with the same
     *                                  message
     */
    public static ActionContext action(Map<String, ? extends @Nullable Object> facts, Object output) {
        return action(facts, output, null);
    }

    /**
     * Creates the context an action runs against, for a run with a deadline.
     *
     * <p>
     * The context reads the system clock once, when it's created, to learn how far away the deadline is, and from
     * then on times it with a monotonic clock, as a run does: a step of the system clock afterwards doesn't move when
     * the context is cancelled. Its {@link EvaluationContext#timeLeft()} is measured the same way.
     * </p>
     *
     * @param facts    The facts by name, whose values can be {@code null}; copied
     * @param output   The output object, which the action changes in place
     * @param deadline When the run must stop, or {@code null} if it has none, as an engine built without
     *                 {@code runTimeout} gives it
     * @return The context. Its {@link EvaluationContext#isCancelled()} answers as it does in a run, and it equals only
     *         itself.
     * @throws NullPointerException     if {@code facts} or {@code output} is {@code null}
     * @throws IllegalArgumentException if a fact's name is {@code null} or blank, which a run rejects with the same
     *                                  message
     */
    public static ActionContext action(Map<String, ? extends @Nullable Object> facts, Object output,
                                       @Nullable Instant deadline) {
        return new EngineActionContext(copyOf(facts), output, deadline);
    }

    /**
     * Creates the context an action runs against, for a run that started at {@code startedAt} by its engine's clock
     * and was given {@code tags}: so a language's test can check what its actions make of the run's
     * {@link EvaluationContext#startedAt()} and {@link EvaluationContext#tags()}, as a run with
     * {@code RulesEngineBuilder.clock(...)} and {@code RunOptions.withTags(...)} gives them. The deadline is timed as
     * {@link #action(Map, Object, Instant)} describes.
     *
     * @param facts     The facts by name, whose values can be {@code null}; copied
     * @param output    The output object, which the action changes in place
     * @param deadline  When the run must stop, or {@code null} if it has none, as an engine built without
     *                  {@code runTimeout} gives it
     * @param startedAt When the run started, by the engine's clock, as a fixed clock gives it
     * @param tags      The run's tags, as {@code RunOptions.withTags(...)} was given them, or empty for a run that
     *                  uses every rule; copied
     * @return The context. Its {@link EvaluationContext#startedAt()} is {@code startedAt}, and its
     *         {@link EvaluationContext#tags()} are {@code tags} as a run has them: in {@link String} order and
     *         unmodifiable. It equals only itself, as in a run.
     * @throws NullPointerException     if {@code facts}, {@code output}, {@code startedAt} or {@code tags} is
     *                                  {@code null}
     * @throws IllegalArgumentException if a fact's name is {@code null} or blank, or a tag is {@code null} or blank,
     *                                  which a run rejects with the same message
     */
    public static ActionContext action(Map<String, ? extends @Nullable Object> facts, Object output,
                                       @Nullable Instant deadline, Instant startedAt, Set<String> tags) {
        Map<String, Object> copy = copyOf(facts);
        Objects.requireNonNull(startedAt, "startedAt must not be null");
        return new EngineActionContext(copy, output, deadline, startedAt, runTags(tags));
    }

    /**
     * Creates the context an action runs against, in the same run as another context: so a language's test can check
     * that its actions find what its conditions kept with {@link EvaluationContext#runScoped}.
     *
     * @param sameRun A context of the run, created by this class, such as with {@link #evaluation(Map)}
     * @param output  The output object, which the action changes in place
     * @return The context. It has the facts, deadline, {@link EvaluationContext#startedAt() start} and
     *         {@link EvaluationContext#tags() tags} of {@code sameRun}, and shares its run-scoped values. Its facts are
     *         read-only, and a write fails with the message the engine uses for an action. It equals only itself.
     * @throws NullPointerException if {@code sameRun} or {@code output} is {@code null}
     */
    public static ActionContext actionInRun(EvaluationContext sameRun, Object output) {
        return new EngineActionContext(sameRun, output);
    }

    /**
     * Ends the run of a context, as the engine ends a run: closes the actions the run registered with
     * {@link EvaluationContext#onCancel(Runnable)} that haven't started, then the values the run keeps with
     * {@link EvaluationContext#runScopedClosing}, in the reverse of the order they were made, so a language's test can
     * check that it releases what it opened for the run. Every value is closed, whatever the others throw, unless both
     * waits below fail, and from then on asking for one with {@code runScopedClosing}, or registering an action with
     * {@code onCancel}, through any context of the run, throws {@link IllegalStateException}, as it does once a run has
     * ended. It first waits, with no limit, for a {@code runScopedClosing} init running on another thread. If that wait
     * fails, as it can when the stack or the heap runs out, it waits once more, as a run does, and closes what that
     * hands over; if that fails too, it closes nothing, closes no action, and the run hasn't ended. A second call does
     * nothing, unless both waits failed: it then closes the values left open.
     *
     * <p>
     * Called from a {@code runScopedClosing} init that goes on to return a value, it closes the run's values, and
     * that value is closed too, and refused: {@code runScopedClosing} throws the same {@link IllegalStateException},
     * with what that {@code close()} threw suppressed on it, unless that is or carries a fatal {@link Error}, which
     * it throws in its place, carrying the {@link IllegalStateException}. So a value is never left open, unless both
     * of {@code endRun}'s waits failed.
     * </p>
     *
     * <p>
     * It differs from a run in one way: a run logs what a {@code close()} throws at WARN, and throws only a fatal
     * {@link Error}, but this throws whatever was thrown, so the test sees it.
     * </p>
     *
     * @param context A context of the run, created by this class, such as with {@link #evaluation(Map)}
     * @throws NullPointerException if {@code context} is {@code null}
     * @throws Exception            what waiting for an init on another thread threw, if it failed, or else the
     *                              first {@link Throwable} a value's {@code close()} threw, as it is; every other
     *                              {@code close()} failure, what waiting again threw, and the first failure to keep
     *                              one of them are suppressed on it, as far as keeping them doesn't fail: each once,
     *                              and none it already carries or that carries it
     * @throws Error                a {@link VirtualMachineError} other than {@link StackOverflowError}, and other than
     *                              an {@link InternalError} that wraps one with no {@link LinkageError} between them,
     *                              that keeping what failed before on the first failure threw, once every value is
     *                              closed, in place of the first failure, which it doesn't carry
     */
    public static void endRun(EvaluationContext context) throws Exception {
        EngineEvaluationContext.endRun(context);
    }
}
