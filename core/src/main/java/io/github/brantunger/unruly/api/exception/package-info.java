/**
 * The exceptions the engine throws, all unchecked and all extending
 * {@link io.github.brantunger.unruly.api.exception.UnrulyException}.
 *
 * <ul>
 *   <li>{@link io.github.brantunger.unruly.api.RulesEngine#load(java.util.List)} throws
 *   {@link io.github.brantunger.unruly.api.exception.RuleCompilationException}, whose
 *   {@link io.github.brantunger.unruly.api.exception.RuleCompilationException#failures() failures()} lists each
 *   problem it found.</li>
 *   <li>{@link io.github.brantunger.unruly.api.RulesEngine#run(io.github.brantunger.unruly.api.FactStore)} throws
 *   {@link io.github.brantunger.unruly.api.exception.RuleExecutionException}, for a failure inside a rule or one that
 *   belongs to no rule, such as a stopped run.</li>
 *   <li>An expression language's compiler throws
 *   {@link io.github.brantunger.unruly.api.exception.InvalidExpressionException} to reject an expression;
 *   {@code load()} reports it as the cause of a {@code RuleCompilationException} that names the rule.</li>
 *   <li>Misuse throws {@link java.lang.IllegalStateException} (running before {@code load()}, or on a closed engine)
 *   or {@link java.lang.IllegalArgumentException} (a fact name rules can't use, or a fact that doesn't match its
 *   declaration).</li>
 * </ul>
 *
 * <p>
 * On either engine exception, {@code getRuleName()} names the failing rule, or is {@code null} for a failure that
 * isn't about one rule; {@code getExpressionKind()} says whether its
 * {@link io.github.brantunger.unruly.api.exception.ExpressionKind#CONDITION condition} or its
 * {@link io.github.brantunger.unruly.api.exception.ExpressionKind#ACTION action} failed; and {@code issues()} on a
 * {@code RuleCompilationException} says where the language found each problem.
 * </p>
 *
 * <p>Types in this package are non-null unless annotated {@link org.jspecify.annotations.Nullable}.</p>
 *
 * @see <a href=
 *      "https://github.com/brantunger/unruly-engine/blob/main/docs/error-handling.md#-exceptions-by-method">Exceptions
 *      by method</a>
 */
@NullMarked
package io.github.brantunger.unruly.api.exception;

import org.jspecify.annotations.NullMarked;
