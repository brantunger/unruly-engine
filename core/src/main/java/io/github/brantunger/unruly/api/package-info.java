/**
 * The engine's public API: build an engine, give it rules, run it against facts, and observe its runs.
 *
 * <p><b>Where to start:</b></p>
 * <ol>
 *   <li>{@link io.github.brantunger.unruly.api.RulesEngineBuilder} — choose a match policy with {@code firstMatch},
 *   {@code allMatches} or {@code uniqueMatch}, give it an output supplier, and {@code build()} the engine.</li>
 *   <li>{@link io.github.brantunger.unruly.api.Rule} — a name, a condition, an action, a priority and the language
 *   they're written in, from {@code Rule.builder()}.</li>
 *   <li>{@link io.github.brantunger.unruly.api.RulesEngine#load(java.util.List)} — compiles a rule list; call it
 *   again at any time to swap in new rules.</li>
 *   <li>{@link io.github.brantunger.unruly.api.RulesEngine#run(io.github.brantunger.unruly.api.FactStore)} — returns
 *   the output object, or {@code null} when no rule matched;
 *   {@link io.github.brantunger.unruly.api.RulesEngine#runWithResult(io.github.brantunger.unruly.api.FactStore)} also
 *   reports which rules fired and what each condition evaluated to.</li>
 *   <li>{@link io.github.brantunger.unruly.api.RuleListener} — callbacks around every run, condition and action.</li>
 * </ol>
 *
 * <p><b>Types:</b></p>
 * <ul>
 *   <li><b>Engine:</b> {@link io.github.brantunger.unruly.api.RulesEngine},
 *   {@link io.github.brantunger.unruly.api.RulesEngineBuilder}, {@link io.github.brantunger.unruly.api.RunOptions}
 *   (settings for one run) and {@link io.github.brantunger.unruly.api.RuleSetInfo} (the rules an engine has
 *   loaded)</li>
 *   <li><b>Rules:</b> {@link io.github.brantunger.unruly.api.Rule}</li>
 *   <li><b>Facts:</b> {@link io.github.brantunger.unruly.api.FactStore} and its built-in implementation
 *   {@link io.github.brantunger.unruly.api.FactMap}; {@link io.github.brantunger.unruly.api.FactReference} and its
 *   built-in implementation {@link io.github.brantunger.unruly.api.Fact}</li>
 *   <li><b>Results and output:</b> {@link io.github.brantunger.unruly.api.RunResult},
 *   {@link io.github.brantunger.unruly.api.RuleEvaluation} (one rule's outcome),
 *   {@link io.github.brantunger.unruly.api.OutputWriter} (how properties an action returns are set on the
 *   output) and {@link io.github.brantunger.unruly.api.OutputConverter} (how a rule's language converts such a
 *   property's value for a setter's type)</li>
 *   <li><b>Observing runs:</b> {@link io.github.brantunger.unruly.api.RuleListener},
 *   {@link io.github.brantunger.unruly.api.RunContext} (one run, as its listeners see it) and
 *   {@link io.github.brantunger.unruly.api.LoggingRuleListener}</li>
 * </ul>
 *
 * <p>
 * <b>Failures:</b> {@link io.github.brantunger.unruly.api.RulesEngine#load(java.util.List)} throws
 * {@link io.github.brantunger.unruly.api.RuleCompilationException}, whose
 * {@link io.github.brantunger.unruly.api.RuleCompilationException#failures() failures()} lists each problem it found;
 * an expression language's compiler rejects an expression with an
 * {@link io.github.brantunger.unruly.api.language.InvalidExpressionException}, which {@code load()} reports as the
 * cause of a {@code RuleCompilationException} that names the rule.
 * {@link io.github.brantunger.unruly.api.RulesEngine#run(io.github.brantunger.unruly.api.FactStore)} throws
 * {@link io.github.brantunger.unruly.api.RuleExecutionException}, for a failure inside a rule or one that belongs to
 * no rule, such as a stopped run. All three extend {@link io.github.brantunger.unruly.api.language.UnrulyException}. On
 * a {@code RuleCompilationException} or a {@code RuleExecutionException}, {@code getRuleName()} names the failing
 * rule, or is {@code null} for a failure that isn't about one rule; {@code getExpressionKind()} says whether its
 * {@link io.github.brantunger.unruly.api.language.ExpressionKind#CONDITION condition} or its
 * {@link io.github.brantunger.unruly.api.language.ExpressionKind#ACTION action} failed; and {@code issues()} on a
 * {@code RuleCompilationException} says where the language found each problem. Misuse throws
 * {@link java.lang.IllegalStateException} (running before {@code load()}, or on a closed engine) or
 * {@link java.lang.IllegalArgumentException} (a fact name rules can't use, or a fact that doesn't match its
 * declaration). A run also throws {@code IllegalStateException} when, while borrowing a copy of the rules, it found
 * the same rule list closed each time it read the engine's rules again, many times in a row, which means an engine
 * invariant has broken rather than that the call was wrong; a reload in between starts the count again.
 * </p>
 *
 * <p>
 * <b>Threads:</b> an engine is thread-safe once built: {@code run()} from any number of threads, and {@code load()}
 * at any time. The builder and a {@code FactMap} aren't. Listeners, the output supplier and an output writer are
 * called from every thread that runs the engine, so they must be.
 * </p>
 *
 * <p>Types in this package are non-null unless annotated {@link org.jspecify.annotations.Nullable}.</p>
 *
 * @see <a href="https://github.com/brantunger/unruly-engine#-quick-start">Quick start</a>
 * @see <a href="https://github.com/brantunger/unruly-engine/blob/main/docs/README.md">The guides</a>
 * @see <a href=
 *      "https://github.com/brantunger/unruly-engine/blob/main/docs/error-handling.md#-exceptions-by-method">Exceptions
 *      by method</a>
 */
@NullMarked
package io.github.brantunger.unruly.api;

import org.jspecify.annotations.NullMarked;
