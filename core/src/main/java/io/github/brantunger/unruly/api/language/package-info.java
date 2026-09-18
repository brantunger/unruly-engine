/**
 * The interfaces an expression language implements, so rule conditions and actions can be compiled and run, and the
 * ones the engine passes to it. MVEL, from the {@code unruly-engine} artifact, is one such language. An engine has
 * the languages its builder is given, or else those found with {@link java.util.ServiceLoader}.
 *
 * <p><b>Writing a language:</b></p>
 * <ol>
 *   <li>Implement {@link io.github.brantunger.unruly.api.language.ExpressionLanguage}: a name, and a new
 *   {@link io.github.brantunger.unruly.api.language.ExpressionCompiler} for each rule list.</li>
 *   <li>In the compiler, compile each {@link io.github.brantunger.unruly.api.language.Expression} into a
 *   {@link io.github.brantunger.unruly.api.language.CompiledCondition} or a
 *   {@link io.github.brantunger.unruly.api.language.CompiledAction}, and throw
 *   {@link io.github.brantunger.unruly.api.exception.InvalidExpressionException} for one you reject, such as a
 *   condition that assigns to a fact.</li>
 *   <li>Keep what changes while expressions run in a {@link io.github.brantunger.unruly.api.language.Session}, one
 *   for each compiled copy of the rules, or return
 *   {@link io.github.brantunger.unruly.api.language.Session#none()} when nothing does.</li>
 *   <li>Let an action change {@link io.github.brantunger.unruly.api.language.ActionContext#output()} in place and
 *   return {@link io.github.brantunger.unruly.api.language.ActionResult#done()}, or return
 *   {@link io.github.brantunger.unruly.api.language.ActionResult#set(java.util.Map)} with properties for the engine
 *   to set. {@link io.github.brantunger.unruly.api.language.FactProperties} reads a fact's property the way rules
 *   expect, whether the fact is a record, a bean or a map.</li>
 *   <li>Let the engine find the language: a {@code META-INF/services} file or a module's {@code provides} clause for
 *   {@link java.util.ServiceLoader}, or {@code language(...)} on the
 *   {@link io.github.brantunger.unruly.api.RulesEngineBuilder}.</li>
 *   <li>Test it with the {@code unruly-engine-test} artifact: extend
 *   {@code io.github.brantunger.unruly.test.ExpressionLanguageContractTest}, and create the sealed contexts for unit
 *   tests with {@code io.github.brantunger.unruly.test.LanguageTestContexts}.</li>
 * </ol>
 *
 * <p><b>Who implements what:</b></p>
 * <ul>
 *   <li>Expression languages implement {@link io.github.brantunger.unruly.api.language.ExpressionLanguage},
 *   {@link io.github.brantunger.unruly.api.language.ExpressionCompiler},
 *   {@link io.github.brantunger.unruly.api.language.CompiledCondition},
 *   {@link io.github.brantunger.unruly.api.language.CompiledAction} and
 *   {@link io.github.brantunger.unruly.api.language.Session}. A method added to one of these is a {@code default}
 *   method, so a language written against an earlier 2.x release keeps compiling and working.</li>
 *   <li>The engine implements {@link io.github.brantunger.unruly.api.language.CompileContext} (the imports, the class
 *   loader, the output type, the declared facts, this language's options and a way to report warnings),
 *   {@link io.github.brantunger.unruly.api.language.EvaluationContext} (the facts, and whether the run must stop) and
 *   {@link io.github.brantunger.unruly.api.language.ActionContext} (the same, plus the output object). They're sealed
 *   to the engine's own implementations, so a language can't implement them, and they can gain any method.</li>
 * </ul>
 *
 * <p>
 * <b>What the engine enforces for every language:</b> rule names are unique and conditions and actions aren't blank;
 * rules are evaluated in priority order; a condition must evaluate to a {@link Boolean}; no fact is named
 * {@value io.github.brantunger.unruly.api.language.ActionContext#OUTPUT_NAME}; the facts a condition or action sees
 * are read-only; a run is stopped between expressions when its thread is interrupted or its deadline passes; a
 * failure while rules compile is reported as a
 * {@link io.github.brantunger.unruly.api.exception.RuleCompilationException}, and a failure while they run as a
 * {@link io.github.brantunger.unruly.api.exception.RuleExecutionException}, which the run's listeners see once the
 * run has started; and a fact name a language rejects fails the run with an
 * {@link java.lang.IllegalArgumentException}, or the load, for a declared fact.
 * </p>
 *
 * <p>
 * <b>Threads:</b> a language instance may serve several engines at once. Each {@code load()} or {@code validate()}
 * creates its own compiler and calls its compile methods on the thread that called it, so two loads at once compile
 * on two threads; {@code checkFactName} is called on that thread for each declared fact, and by runs on many threads
 * at once; {@code newSession} is called by runs on many threads at once. Compiled conditions and actions are shared
 * by every run, each with its own session, which one run uses at a time.
 * </p>
 *
 * <p>Types in this package are non-null unless annotated {@link org.jspecify.annotations.Nullable}.</p>
 *
 * @see <a href="https://github.com/brantunger/unruly-engine/blob/main/docs/languages/custom.md">Writing an expression
 *      language</a>
 */
@NullMarked
package io.github.brantunger.unruly.api.language;

import org.jspecify.annotations.NullMarked;
