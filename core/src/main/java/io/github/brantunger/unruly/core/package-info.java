/**
 * The rules engine implementation. <b>Internal:</b> nothing in this package is part of the API, and it may change in
 * any release.
 *
 * <p>Create an engine with {@link io.github.brantunger.unruly.api.RulesEngineBuilder} and use it through
 * {@link io.github.brantunger.unruly.api.RulesEngine}. The engine classes are package-private. The public types are
 * {@link io.github.brantunger.unruly.core.Engines}, the builder's entry point, and the three context records that the
 * sealed context interfaces in {@code api.language} permit. The module exports this package only to the test kit,
 * {@code io.github.brantunger.unruly.test}. Rules are compiled
 * when {@link io.github.brantunger.unruly.api.RulesEngine#load(java.util.List)} loads them, each by the language it
 * names or by the engine's default language, and evaluated against a {@link io.github.brantunger.unruly.api.FactStore} at
 * run time.</p>
 *
 * <p>The engine logs under the fixed logger name {@code io.github.brantunger.unruly.engine}.</p>
 */
package io.github.brantunger.unruly.core;
