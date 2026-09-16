package io.github.brantunger.unruly.api;

import io.github.brantunger.unruly.api.language.ExpressionLanguage;
import io.github.brantunger.unruly.core.EngineConfiguration;
import io.github.brantunger.unruly.core.Engines;
import org.jspecify.annotations.Nullable;

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
 * Configures and builds a {@link RulesEngine}. An engine's languages, imports, listeners, limit on compiled copies
 * and run timeout are set here and can't change once it's built; only its rules can, with
 * {@link RulesEngine#load(List)}.
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
 */
public final class RulesEngineBuilder<O> {

    // The smallest limit on compiled copies: one run at a time.
    private static final int MIN_COPIES = 1;

    private final Supplier<O> outputFactory;
    private final boolean fireAllMatches;
    private final List<ExpressionLanguage> languageList = new ArrayList<>();
    private @Nullable String defaultLanguageName;
    private final List<String> importNames = new ArrayList<>();
    private final List<RuleListener> listenerList = new ArrayList<>();
    private int copyLimit = EngineConfiguration.UNLIMITED_COPIES;
    private @Nullable Duration timeout;
    private Class<? super O> outputClass = Object.class;
    private OutputWriter<? super O> writer = OutputWriter.beansAndMaps();
    private final Map<String, Map<String, String>> languageOptions = new LinkedHashMap<>();

    private RulesEngineBuilder(Supplier<O> outputFactory, boolean fireAllMatches) {
        this.outputFactory = Objects.requireNonNull(outputFactory, "outputFactory must not be null");
        this.fireAllMatches = fireAllMatches;
    }

    /**
     * Starts building an engine that fires the action of the highest-priority rule whose condition is true: the first
     * match, in DMN's terms. Rules with equal priorities keep their list order.
     *
     * <p>
     * Conditions are evaluated in that order, and the run stops at the first match, so the rules below it are never
     * evaluated: they're neither matched nor unmatched, and a broken condition among them can't fail a run that's
     * already decided. Use {@link #allMatches(Supplier)} when every condition must be evaluated.
     * </p>
     *
     * @param outputFactory Creates the output object. It is called once per run that matches a rule and must
     *                      return a new, non-null object each time.
     * @param <O>           The type of the output object
     * @return A new builder
     * @throws NullPointerException if {@code outputFactory} is {@code null}
     */
    public static <O> RulesEngineBuilder<O> firstMatch(Supplier<O> outputFactory) {
        return new RulesEngineBuilder<>(outputFactory, false);
    }

    /**
     * Starts building an engine that fires the action of every rule whose condition is true, in priority order, all
     * changing the same output object: DMN's rule order. Every condition is evaluated before any action runs.
     *
     * @param outputFactory Creates the output object. It is called once per run that matches a rule and must
     *                      return a new, non-null object each time.
     * @param <O>           The type of the output object
     * @return A new builder
     * @throws NullPointerException if {@code outputFactory} is {@code null}
     */
    public static <O> RulesEngineBuilder<O> allMatches(Supplier<O> outputFactory) {
        return new RulesEngineBuilder<>(outputFactory, true);
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
     * Keeps at most {@code maxCopies} compiled copies of the rules. Without this, an engine has no limit.
     *
     * <p>
     * Each run uses a copy of the rules, one session for each expression language, that no other run is using.
     * An engine without a limit makes a new copy whenever all of them are in use, and keeps as many as the most runs
     * it has had in progress at once. An engine with a limit keeps at most {@code maxCopies}: a run that starts while
     * all of them are in use waits until one is free, so at most {@code maxCopies} runs are in progress at once. A run
     * started from inside another run on the same thread, such as from an action or a listener, doesn't wait: if no
     * copy is free, it gets an extra copy that isn't kept.
     * </p>
     *
     * <p>
     * Use a limit when runs can come from many more threads than you want copies, for example from virtual threads.
     * If a thread is interrupted while its run waits for a copy, the run throws a
     * {@link io.github.brantunger.unruly.api.exception.RuleExecutionException} and the thread's interrupt status stays
     * set.
     * </p>
     *
     * @param maxCopies The most compiled copies of the rules to keep, and so the most runs in progress at once; at
     *                  least 1
     * @return This builder
     * @throws IllegalArgumentException if {@code maxCopies} is less than 1
     */
    public RulesEngineBuilder<O> maxCopies(int maxCopies) {
        if (maxCopies < MIN_COPIES) {
            throw new IllegalArgumentException("maxCopies must be at least " + MIN_COPIES + ", but was " + maxCopies);
        }
        this.copyLimit = maxCopies;
        return this;
    }

    /**
     * Stops a run that is still going after {@code timeout}. Without this, a run has no deadline.
     *
     * <p>
     * The deadline is taken from when {@link RulesEngine#run(FactStore)} is called, so waiting for a compiled copy
     * of the rules counts towards it. The engine checks it before each condition and before each action, so a run
     * stops between rules; it doesn't stop an expression that is already running. MVEL has no hook inside an
     * expression, so an MVEL rule that loops for ever can't be stopped, with or without a timeout: run rules you
     * don't trust in a process of their own. A language that can stop inside an expression, such as one built on
     * JEXL's cancellation, stops there instead, because it is given the deadline.
     * </p>
     *
     * <p>
     * A run past its deadline throws a {@link io.github.brantunger.unruly.api.exception.RuleExecutionException}
     * caused by a {@link java.util.concurrent.TimeoutException}. What ran before that keeps its effects on the
     * output object and on the facts, like any other failed run.
     * </p>
     *
     * @param timeout How long a run may take; positive. {@link RulesEngine#runWithResult(FactStore, Duration)} takes
     *                one for a single run instead.
     * @return This builder
     * @throws IllegalArgumentException if {@code timeout} is zero or negative
     * @throws NullPointerException     if {@code timeout} is {@code null}
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
                importNames, listenerList, copyLimit, timeout, outputClass, writer, languageOptions);
        return fireAllMatches
                ? Engines.allMatches(outputFactory, configuration)
                : Engines.firstMatch(outputFactory, configuration);
    }
}
