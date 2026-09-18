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
 *   {@link io.github.brantunger.unruly.api.RuleEvaluation} (one rule's outcome) and
 *   {@link io.github.brantunger.unruly.api.OutputWriter} (how properties an action returns are set on the
 *   output)</li>
 *   <li><b>Observing runs:</b> {@link io.github.brantunger.unruly.api.RuleListener},
 *   {@link io.github.brantunger.unruly.api.RunContext} (one run, as its listeners see it) and
 *   {@link io.github.brantunger.unruly.api.LoggingRuleListener}</li>
 * </ul>
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
 */
@NullMarked
package io.github.brantunger.unruly.api;

import org.jspecify.annotations.NullMarked;
