/**
 * The interfaces an expression language implements, so rule conditions and actions can be compiled and run, and the
 * ones the engine passes to it.
 *
 * <p>Implemented by expression languages:</p>
 * <ul>
 *   <li>{@link io.github.brantunger.unruly.api.language.ExpressionLanguage} — A language, which creates a compiler for
 *   each rule list</li>
 *   <li>{@link io.github.brantunger.unruly.api.language.ExpressionCompiler} — Compiles one rule list's expressions,
 *   checks fact names and creates sessions</li>
 *   <li>{@link io.github.brantunger.unruly.api.language.CompiledCondition} and
 *   {@link io.github.brantunger.unruly.api.language.CompiledAction} — Compiled expressions, which every run shares</li>
 *   <li>{@link io.github.brantunger.unruly.api.language.Session} — What a language changes while its expressions run,
 *   one for each copy of the rules</li>
 * </ul>
 *
 * <p>Implemented by the engine, which passes them to a language. They're sealed to the engine's own implementations,
 * so a language can't implement them; a language's unit tests create them with
 * {@code io.github.brantunger.unruly.test.LanguageTestContexts}, from the {@code unruly-engine-test} artifact:</p>
 * <ul>
 *   <li>{@link io.github.brantunger.unruly.api.language.CompileContext} — The imports and class loader a rule list is
 *   compiled with</li>
 *   <li>{@link io.github.brantunger.unruly.api.language.EvaluationContext} and
 *   {@link io.github.brantunger.unruly.api.language.ActionContext} — What a condition is evaluated against and what
 *   an action runs against</li>
 * </ul>
 *
 * <p>A method added to an interface that languages implement is a {@code default} method, so a language written
 * against an earlier 2.x release keeps compiling and working. The sealed interfaces can gain any method.</p>
 *
 * <p>Rules are written in MVEL, the engine's default language.</p>
 *
 * <p>Types in this package are non-null unless annotated {@link org.jspecify.annotations.Nullable}.</p>
 */
@NullMarked
package io.github.brantunger.unruly.api.language;

import org.jspecify.annotations.NullMarked;
