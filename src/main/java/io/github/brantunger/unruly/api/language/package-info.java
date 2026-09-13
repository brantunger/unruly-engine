/**
 * The interfaces an expression language implements, so rule conditions and actions can be compiled and run.
 *
 * <ul>
 *   <li>{@link io.github.brantunger.unruly.api.language.ExpressionLanguage} — A language, which creates a compiler for
 *   each rule list</li>
 *   <li>{@link io.github.brantunger.unruly.api.language.ExpressionCompiler} — Compiles one rule list's expressions and
 *   checks fact names</li>
 *   <li>{@link io.github.brantunger.unruly.api.language.CompiledCondition} and
 *   {@link io.github.brantunger.unruly.api.language.CompiledAction} — Compiled expressions</li>
 *   <li>{@link io.github.brantunger.unruly.api.language.CompileContext},
 *   {@link io.github.brantunger.unruly.api.language.EvaluationContext} and
 *   {@link io.github.brantunger.unruly.api.language.ActionContext} — What the engine passes to them</li>
 * </ul>
 *
 * <p>Rules are written in MVEL, the engine's default language.</p>
 */
package io.github.brantunger.unruly.api.language;
