/**
 * Core rules engine implementations.
 *
 * <p>This package contains the implementations of {@link io.github.brantunger.unruly.api.RulesEngine}:</p>
 * <ul>
 *   <li>{@link io.github.brantunger.unruly.core.AbstractRulesEngine} — Shared rule compilation, fact handling and
 *   listener callbacks</li>
 *   <li>{@link io.github.brantunger.unruly.core.StatefulRulesEngine} — Fires all matching rules, accumulating state</li>
 *   <li>{@link io.github.brantunger.unruly.core.StatelessRulesEngine} — Fires only the highest-priority matching rule</li>
 * </ul>
 *
 * <p>Rules are compiled at configuration time, by MVEL unless another expression language is used, via
 * {@link io.github.brantunger.unruly.api.RulesEngine#setRuleList(java.util.List)}
 * and evaluated against a {@link io.github.brantunger.unruly.api.FactStore} at runtime.</p>
 *
 * <p><b>Internal:</b> this package is an implementation detail. Create an engine with
 * {@link io.github.brantunger.unruly.api.RulesEngineBuilder} and use it through
 * {@link io.github.brantunger.unruly.api.RulesEngine}. The engines' public constructors are deprecated for removal,
 * and the classes in this package are expected to become package-private in 2.0.</p>
 */
package io.github.brantunger.unruly.core;
