/**
 * The rules engine implementation. <b>Internal:</b> nothing in this package is part of the API, and it may change in
 * any release.
 *
 * <p>Create an engine with {@link io.github.brantunger.unruly.api.RulesEngineBuilder} and use it through
 * {@link io.github.brantunger.unruly.api.RulesEngine}. The engine classes are package-private. The public types are
 * the ones the builder and the test kit need: {@link io.github.brantunger.unruly.core.Engines}, the builder's entry
 * point; {@link io.github.brantunger.unruly.core.EngineConfiguration} and
 * {@link io.github.brantunger.unruly.core.CopyLimit}, the settings it collects;
 * {@link io.github.brantunger.unruly.core.EngineCompileContext},
 * {@link io.github.brantunger.unruly.core.EngineEvaluationContext},
 * {@link io.github.brantunger.unruly.core.EngineActionContext} and
 * {@link io.github.brantunger.unruly.core.EngineRunContext}, the implementations the sealed context interfaces in
 * {@code api} and {@code api.language} permit; and {@link io.github.brantunger.unruly.core.Accessors} and
 * {@link io.github.brantunger.unruly.core.Failures}, which the API's default output writer,
 * {@link io.github.brantunger.unruly.api.language.FactProperties} and logging listener use.
 * The module exports this package only to the test kit, {@code io.github.brantunger.unruly.test}. Rules are compiled
 * when {@link io.github.brantunger.unruly.api.RulesEngine#load(java.util.List)} loads them, each by the language it
 * names or by the engine's default language, and evaluated against a
 * {@link io.github.brantunger.unruly.api.FactStore} at run time.</p>
 *
 * <p>The engine logs under the fixed logger name {@code io.github.brantunger.unruly.engine}.</p>
 */
package io.github.brantunger.unruly.core;
