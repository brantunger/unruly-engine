package io.github.brantunger.unruly.api;

import io.github.brantunger.unruly.api.language.ExpressionLanguage;
import io.github.brantunger.unruly.core.CopyLimit;
import io.github.brantunger.unruly.core.EngineCompileContext;
import io.github.brantunger.unruly.core.EngineConfiguration;
import io.github.brantunger.unruly.core.Engines;
import org.jspecify.annotations.Nullable;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Configures and builds a {@link RulesEngine}. An engine's languages, imports, listeners, limit on compiled copies,
 * run timeout and clock are set here and can't change once it's built; only its rules can, with
 * {@link RulesEngine#load(List)}.
 *
 * <p>
 * <b>Compiled copies:</b> a run uses a copy of the rules that no other run is using, and an engine keeps as many as
 * the most runs it has had in progress at once. A thread pool bounds that; virtual threads don't, so by default an
 * engine limits <b>runs on virtual threads</b> to one copy for every two processors. {@link #maxCopies(int)} sets a
 * limit for every kind of thread, and {@link #unlimitedCopies()} turns it off. See
 * <a href="https://github.com/brantunger/unruly-engine/blob/main/docs/compiled-copies.md">Compiled copies</a>.
 * </p>
 *
 * {@snippet :
 * RulesEngine<LoanDecision> engine = RulesEngineBuilder.firstMatch(LoanDecision::new)
 *         .imports("java.time")
 *         .listener(new LoggingRuleListener())
 *         .build();
 * engine.load(rules);
 * }
 *
 * <p>
 * <b>Languages:</b> an engine built without {@link #language(ExpressionLanguage)} has the languages found with
 * {@link java.util.ServiceLoader}, such as MVEL from the {@code unruly-engine} artifact. An engine built with it has
 * exactly the languages given. A rule whose language is {@code null} is written in the engine's default language:
 * the one named with {@link #defaultLanguage(String)}, or else the engine's only language.
 * </p>
 *
 * <p>
 * A builder isn't thread-safe. {@link #build()} can be called more than once, and each call builds a new engine with
 * the settings at that moment.
 * </p>
 *
 * @param <O> The type of the output object
 * @see <a href="https://github.com/brantunger/unruly-engine/blob/main/docs/thread-safety.md">Thread safety</a>
 * @see <a href=
 *      "https://github.com/brantunger/unruly-engine/blob/main/docs/error-handling.md#-exceptions-by-method">Exceptions
 *      by method</a>
 */
public final class RulesEngineBuilder<O> {

    // The smallest limit on compiled copies: one run at a time.
    private static final int MIN_COPIES = 1;

    /** Creates an engine with one match policy from the output supplier and the builder's settings. */
    @FunctionalInterface
    private interface EngineFactory<O> {
        RulesEngine<O> create(Supplier<O> outputFactory, EngineConfiguration<O> configuration);
    }

    private final Supplier<O> outputFactory;
    private final EngineFactory<O> engineFactory;
    private final List<ExpressionLanguage> languageList = new ArrayList<>();
    private @Nullable String defaultLanguageName;
    private final List<String> importNames = new ArrayList<>();
    private final List<RuleListener> listenerList = new ArrayList<>();
    // null until the engine is built, so the default reads the number of processors then, not when the builder
    // was created.
    private @Nullable CopyLimit copies;
    private @Nullable Duration timeout;
    private Clock runClock = Clock.systemUTC();
    private final Map<String, Class<?>> factTypes = new LinkedHashMap<>();
    private boolean allFactsDeclared;
    private Class<? super O> outputClass = Object.class;
    private OutputWriter<? super O> writer = OutputWriter.beansAndMaps();
    private final Map<String, Map<String, String>> languageOptions = new LinkedHashMap<>();

    private RulesEngineBuilder(Supplier<O> outputFactory, EngineFactory<O> engineFactory) {
        this.outputFactory = Objects.requireNonNull(outputFactory, "outputFactory must not be null");
        this.engineFactory = engineFactory;
    }

    /**
     * Starts building an engine that fires the action of the highest-priority rule whose condition is true: the first
     * match, in DMN's terms. Rules with equal priorities keep their list order.
     *
     * <p>
     * Conditions are evaluated in that order, and the run stops at the first match, so the rules below it are never
     * evaluated: they're neither matched nor unmatched, the run's {@link RunResult#evaluations() result} reports them
     * as not evaluated, and a broken condition among them can't fail a run that's already decided. Use
     * {@link #allMatches(Supplier)} or {@link #uniqueMatch(Supplier)} when every condition must be evaluated. A rule
     * the run skips is never evaluated, and is reported as skipped wherever it is; see {@link Rule}.
     * </p>
     *
     * @param outputFactory Creates the output object. It is called once per run that matches a rule and must
     *                      return a new, non-null object each time.
     * @param <O>           The type of the output object
     * @return A new builder
     * @throws NullPointerException if {@code outputFactory} is {@code null}
     */
    public static <O> RulesEngineBuilder<O> firstMatch(Supplier<O> outputFactory) {
        return new RulesEngineBuilder<>(outputFactory, Engines::firstMatch);
    }

    /**
     * Starts building an engine that fires the action of every rule whose condition is true, in priority order, all
     * changing the same output object: DMN's rule order. Every condition, except those of rules the run skips, is
     * evaluated before any action runs.
     *
     * @param outputFactory Creates the output object. It is called once per run that matches a rule and must
     *                      return a new, non-null object each time.
     * @param <O>           The type of the output object
     * @return A new builder
     * @throws NullPointerException if {@code outputFactory} is {@code null}
     */
    public static <O> RulesEngineBuilder<O> allMatches(Supplier<O> outputFactory) {
        return new RulesEngineBuilder<>(outputFactory, Engines::allMatches);
    }

    /**
     * Starts building an engine that fires the action of the one rule whose condition is true, and fails the run when
     * more than one is: DMN's unique match, for a decision table whose rows must not overlap. Every condition, except
     * those of rules the run skips, is evaluated, in priority order, before anything fires, so the failure names every
     * rule that matched.
     *
     * <p>
     * No match returns {@code null}, like the other engines. More than one match fires nothing, doesn't call the output
     * supplier, and throws a {@link io.github.brantunger.unruly.api.exception.RuleExecutionException} that names each
     * matched rule in priority order and belongs to no rule: listeners get {@code afterEvaluate} for every rule the
     * run doesn't skip, then {@code onRunError}. It depends on nothing language-specific: it counts the conditions
     * that were true.
     * </p>
     *
     * @param outputFactory Creates the output object. It is called once per run that matches exactly one rule and
     *                      must return a new, non-null object each time.
     * @param <O>           The type of the output object
     * @return A new builder
     * @throws NullPointerException if {@code outputFactory} is {@code null}
     */
    public static <O> RulesEngineBuilder<O> uniqueMatch(Supplier<O> outputFactory) {
        return new RulesEngineBuilder<>(outputFactory, Engines::uniqueMatch);
    }

    /**
     * Adds an expression language that rules can be written in, chosen by each rule's {@code language}. Once this is
     * called, the engine has exactly the languages added, and finds none with {@link java.util.ServiceLoader}.
     *
     * @param language The language
     * @return This builder
     * @throws IllegalArgumentException if the language's name is {@code null} or blank, or a language with the same
     *                                  name was added already
     * @throws NullPointerException     if {@code language} is {@code null}
     */
    public RulesEngineBuilder<O> language(ExpressionLanguage language) {
        Objects.requireNonNull(language, "language must not be null");
        String name = language.name();
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("An expression language's name must not be null or blank: "
                    + language.getClass().getName());
        }
        for (ExpressionLanguage added : languageList) {
            if (name.equals(added.name())) {
                throw new IllegalArgumentException("Two expression languages are named '" + name + "': "
                        + added.getClass().getName() + " and " + language.getClass().getName());
            }
        }
        languageList.add(language);
        return this;
    }

    /**
     * Names the language of the rules whose {@code language} is {@code null}. Fact names are checked against it too
     * when the loaded rule list is empty. Without this, the default is the engine's only language.
     *
     * @param name The name of one of the engine's languages
     * @return This builder
     * @throws NullPointerException if {@code name} is {@code null}
     */
    public RulesEngineBuilder<O> defaultLanguage(String name) {
        this.defaultLanguageName = Objects.requireNonNull(name, "name must not be null");
        return this;
    }

    /**
     * Adds imports that every language compiles rules with, so rule expressions can refer to classes by their simple
     * names. Each string is a fully qualified package name ({@code "java.util"}) or class name
     * ({@code "java.time.LocalDate"}, or {@code "java.util.Map.Entry"} for a nested class). A language without imports
     * ignores them.
     *
     * <p>
     * Whether a string names a class is decided by {@link #build()}, with the building thread's context class loader,
     * or this library's class loader if the thread has none. A string that loader doesn't find as a class, but that is
     * a valid package name, is imported as a package. Classes in imported packages are looked up each time rules are
     * loaded, with the loading thread's context class loader.
     * </p>
     *
     * @param names Package or class names
     * @return This builder
     * @throws NullPointerException if {@code names} or any element is {@code null}; nothing is added
     */
    public RulesEngineBuilder<O> imports(String... names) {
        Objects.requireNonNull(names, "names must not be null");
        return imports(Arrays.asList(names));
    }

    /**
     * Adds imports, as {@link #imports(String...)} does.
     *
     * @param names Package or class names
     * @return This builder
     * @throws NullPointerException if {@code names} or any element is {@code null}; nothing is added
     */
    public RulesEngineBuilder<O> imports(Collection<String> names) {
        Objects.requireNonNull(names, "names must not be null");
        for (String name : names) {
            Objects.requireNonNull(name, "names must not contain null");
        }
        importNames.addAll(names);
        return this;
    }

    /**
     * Adds a listener that is told about every condition evaluated and every action run.
     *
     * @param listener The listener
     * @return This builder
     * @throws NullPointerException if {@code listener} is {@code null}
     */
    public RulesEngineBuilder<O> listener(RuleListener listener) {
        listenerList.add(Objects.requireNonNull(listener, "listener must not be null"));
        return this;
    }

    /**
     * Adds listeners, in the order given, as {@link #listener(RuleListener)} does.
     *
     * @param listeners The listeners
     * @return This builder
     * @throws NullPointerException if {@code listeners} or any element is {@code null}; nothing is added
     */
    public RulesEngineBuilder<O> listeners(Collection<? extends RuleListener> listeners) {
        Objects.requireNonNull(listeners, "listeners must not be null");
        for (RuleListener listener : listeners) {
            Objects.requireNonNull(listener, "listeners must not contain null");
        }
        listenerList.addAll(listeners);
        return this;
    }

    /**
     * Tells expression languages the type of the output object, through
     * {@link io.github.brantunger.unruly.api.language.CompileContext#outputType()}. A language may use it, for example
     * to check the properties its actions return; the engine doesn't. Without this, languages are told
     * {@link Object}.
     *
     * @param type The output object's class, or a supertype of it, such as {@code Map.class} for a
     *             {@code Map<String, Object>} output
     * @return This builder
     * @throws NullPointerException if {@code type} is {@code null}
     */
    public RulesEngineBuilder<O> outputType(Class<? super O> type) {
        this.outputClass = Objects.requireNonNull(type, "type must not be null");
        return this;
    }

    /**
     * Sets how the engine sets the properties an action returns with
     * {@link io.github.brantunger.unruly.api.language.ActionResult#set(Map)} on the output object. Without this, it's
     * {@link OutputWriter#beansAndMaps()}. Actions that change the output themselves don't use it.
     *
     * @param writer The writer, which runs on many threads at once
     * @return This builder
     * @throws NullPointerException if {@code writer} is {@code null}
     */
    public RulesEngineBuilder<O> outputWriter(OutputWriter<? super O> writer) {
        this.writer = Objects.requireNonNull(writer, "writer must not be null");
        return this;
    }

    /**
     * Sets an option for one expression language, which the language reads from
     * {@link io.github.brantunger.unruly.api.language.CompileContext#options()} when rules are loaded. What the options
     * are is up to the language. Setting the same key again replaces its value.
     *
     * @param language The name of one of the engine's languages
     * @param key      The option's name
     * @param value    Its value
     * @return This builder
     * @throws NullPointerException if an argument is {@code null}
     */
    public RulesEngineBuilder<O> option(String language, String key, String value) {
        Objects.requireNonNull(language, "language must not be null");
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(value, "value must not be null");
        languageOptions.computeIfAbsent(language, name -> new LinkedHashMap<>()).put(key, value);
        return this;
    }

    /**
     * Declares a fact's name and type, so the engine checks a run's value against the type and a language can check
     * its expressions against it.
     *
     * <p>
     * The engine fails a run with {@link IllegalArgumentException} when the fact is present and its value isn't an
     * instance of {@code type}. A {@code null} value passes: nothing about it contradicts the declaration. A run that
     * doesn't supply the fact at all is unaffected, unless {@link #requireDeclaredFacts()} is also set. Declaring a
     * fact twice replaces the first declaration.
     * </p>
     *
     * <p>
     * A primitive type is declared as its wrapper, so {@code fact("age", int.class)} accepts an {@link Integer}, and
     * languages are told {@code Integer}. {@link RulesEngine#load(List)} checks every declared name with the language
     * of each rule, as a run checks the names it's given, so a name no language can refer to fails loading rather than
     * every run.
     * </p>
     *
     * <p>
     * Languages are told what was declared, and use it as they can, for example to check their expressions when the
     * rules load; the engine's own check happens whatever the language does. See each language's documentation.
     * </p>
     *
     * @param name The fact's name, as rules refer to it
     * @param type The type a run's value must be an instance of. Declaring {@link Object} or a {@link java.util.Map}
     *             says the fact's shape isn't fixed, which no language can type-check
     * @return This builder
     * @throws NullPointerException     if {@code name} or {@code type} is {@code null}
     * @throws IllegalArgumentException if {@code name} is {@code output}, which actions use for the output object
     */
    public RulesEngineBuilder<O> fact(String name, Class<?> type) {
        factTypes.put(name, EngineCompileContext.declaredType(name, type));
        return this;
    }

    /**
     * Declares several facts at once, as {@link #fact(String, Class)} does one.
     *
     * @param types The type of each fact, by name; copied, so later changes to the map don't change the engine
     * @return This builder
     * @throws NullPointerException     if {@code types}, a name or a type is {@code null}
     * @throws IllegalArgumentException if a name is {@code output}
     */
    public RulesEngineBuilder<O> facts(Map<String, ? extends Class<?>> types) {
        Objects.requireNonNull(types, "types must not be null");
        types.forEach(this::fact);
        return this;
    }

    /**
     * Rejects a run that supplies a fact nobody declared, or leaves a declared fact out. Without this, declaring a
     * fact only says what its type is when it's there.
     *
     * <p>
     * It says that {@link #fact(String, Class)} lists <b>every</b> fact a run may supply, which is what lets a
     * language reject an expression that refers to anything else, when the language offers that and it's turned on:
     * for MVEL, its {@code strongTyping} option, which also needs this. The engine's own checks on a run's facts don't
     * depend on any language.
     * </p>
     *
     * @return This builder
     */
    public RulesEngineBuilder<O> requireDeclaredFacts() {
        this.allFactsDeclared = true;
        return this;
    }

    /**
     * Keeps at most {@code maxCopies} compiled copies of the rules, for runs on <b>every</b> kind of thread, instead
     * of the default limit on runs from virtual threads.
     *
     * <p>
     * A run that starts while all of them are in use waits until one is free, so at most {@code maxCopies} runs make
     * progress at once. A run started from inside another run on the same thread, and a run that has waited five
     * seconds without one single copy being given back, don't wait: each gets an extra copy that isn't kept. A run
     * that is interrupted while it waits, or that passes its deadline there, throws a
     * {@link io.github.brantunger.unruly.api.exception.RuleExecutionException}; an interrupted thread keeps its
     * interrupt status set. A rule list whose languages all return
     * {@link io.github.brantunger.unruly.api.language.Session#none()} needs no copies, and no limit applies to it.
     * </p>
     *
     * @param maxCopies The most compiled copies of the rules to keep, and so the most runs making progress at once;
     *                  at least 1
     * @return This builder
     * @throws IllegalArgumentException if {@code maxCopies} is less than 1
     * @see <a href=
     * "https://github.com/brantunger/unruly-engine/blob/main/docs/compiled-copies.md#-limiting-the-copies">Limiting
     * the copies</a>
     */
    public RulesEngineBuilder<O> maxCopies(int maxCopies) {
        if (maxCopies < MIN_COPIES) {
            throw new IllegalArgumentException("maxCopies must be at least " + MIN_COPIES + ", but was " + maxCopies);
        }
        this.copies = CopyLimit.of(maxCopies);
        return this;
    }

    /**
     * Makes as many compiled copies of the rules as the runs in progress need, on any kind of thread, instead of the
     * default limit on runs from virtual threads.
     *
     * <p>
     * This is what an engine did before 2.0, and what rules that wait — on I/O, a database or another service —
     * usually want: such a run holds a copy while it waits, so a limit caps how many of them can overlap. The cost is
     * that nothing bounds the copies: a run for each of ten thousand virtual threads makes ten thousand copies, each
     * of which recompiles every expression and generates its own accessor classes. With a thread pool, the pool's
     * size bounds them instead.
     * </p>
     *
     * @return This builder
     */
    public RulesEngineBuilder<O> unlimitedCopies() {
        this.copies = CopyLimit.none();
        return this;
    }

    /**
     * Stops a run that is still going after {@code timeout}. Without this, a run has no deadline.
     *
     * <p>
     * The deadline is taken from when {@link RulesEngine#run(FactStore)} is called, and waiting for a compiled copy
     * of the rules counts towards it. The engine checks it before each condition and each action, and again when
     * each one returns, so a run whose last condition or action returns past its deadline fails even though that
     * rule finished. It doesn't stop an expression that is already running; only a language that checks
     * {@link io.github.brantunger.unruly.api.language.EvaluationContext#isCancelled()} can stop inside one. A run
     * past its deadline throws a {@link io.github.brantunger.unruly.api.exception.RuleExecutionException} caused by
     * a {@link java.util.concurrent.TimeoutException}.
     * </p>
     *
     * @param timeout How long a run may take; positive. {@link RunOptions#withTimeoutOf(Duration)} gives a single run
     *                one instead.
     * @return This builder
     * @throws IllegalArgumentException if {@code timeout} is zero or negative
     * @throws NullPointerException     if {@code timeout} is {@code null}
     * @see <a href="https://github.com/brantunger/unruly-engine/blob/main/docs/stopping-runs.md">Stopping a run</a>
     */
    public RulesEngineBuilder<O> runTimeout(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout must not be null");
        if (!timeout.isPositive()) {
            throw new IllegalArgumentException("timeout must be positive, but was " + timeout);
        }
        this.timeout = timeout;
        return this;
    }

    /**
     * Sets the clock a run reads, once when it starts, to decide which rules are within their validity window
     * ({@link Rule#getValidFrom()}, {@link Rule#getValidTo()}). Without this, the engine uses
     * {@link Clock#systemUTC()}. A test can give a fixed clock, such as
     * {@code Clock.fixed(Instant.parse("2027-06-01T00:00:00Z"), ZoneOffset.UTC)}, to run the rules as they're scheduled
     * at that time.
     *
     * <p>
     * Only the validity window uses the clock. A {@link #runTimeout(Duration) run timeout} is measured with the
     * system clock whatever clock is set here. Anything the clock throws fails the run unchanged, before any listener
     * is told the run started.
     * </p>
     *
     * @param clock The clock
     * @return This builder
     * @throws NullPointerException if {@code clock} is {@code null}
     */
    public RulesEngineBuilder<O> clock(Clock clock) {
        this.runClock = Objects.requireNonNull(clock, "clock must not be null");
        return this;
    }

    /**
     * Builds an engine with this builder's settings. Load its rules with {@link RulesEngine#load(List)} before the
     * first run.
     *
     * @return A new engine
     * @throws IllegalStateException    if the engine has no expression language; if it has several and no
     *                                  {@link #defaultLanguage(String) default language}; if the default language, or
     *                                  a language given an {@link #option(String, String, String) option}, isn't one
     *                                  of its languages; or if a language found with
     *                                  {@link java.util.ServiceLoader} has a {@code null} or blank name, or two found
     *                                  languages have the same name. Anything {@code ServiceLoader} or a language throws
     *                                  while it's found, such as a {@link java.util.ServiceConfigurationError}, is thrown
     *                                  unchanged.
     * @throws IllegalArgumentException if an import is neither a loadable class nor a valid package name, or names a
     *                                  class that exists but can't be loaded, for example because a class it depends on
     *                                  is missing
     */
    public RulesEngine<O> build() {
        EngineConfiguration<O> configuration = new EngineConfiguration<>(languageList, defaultLanguageName,
                importNames, listenerList, copies != null ? copies : CopyLimit.forVirtualThreads(), timeout, runClock,
                outputClass, writer, languageOptions, factTypes, allFactsDeclared);
        return engineFactory.create(outputFactory, configuration);
    }
}
