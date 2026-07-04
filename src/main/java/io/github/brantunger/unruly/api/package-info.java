/**
 * Public API for the Unruly rules engine.
 *
 * <p>This package contains the data model used to define and supply facts and rules
 * to the engine:</p>
 * <ul>
 *   <li>{@link io.github.brantunger.unruly.api.RulesEngine} — The main rules engine interface</li>
 *   <li>{@link io.github.brantunger.unruly.api.RulesEngineBuilder} — Builder to instantiate engines without exposing internal implementations</li>
 *   <li>{@link io.github.brantunger.unruly.api.RuleListener} — Interface to monitor rule evaluation and action execution</li>
 *   <li>{@link io.github.brantunger.unruly.api.LoggingRuleListener} — Out-of-the-box SLF4J listener for execution auditing</li>
 *   <li>{@link io.github.brantunger.unruly.api.Rule} — A rule with a condition, action, and priority</li>
 *   <li>{@link io.github.brantunger.unruly.api.FactReference} — Interface for a named, typed fact</li>
 *   <li>{@link io.github.brantunger.unruly.api.Fact} — Default implementation of {@code FactReference}</li>
 *   <li>{@link io.github.brantunger.unruly.api.FactStore} — A {@link java.util.Map}-based store of facts</li>
 *   <li>{@link io.github.brantunger.unruly.api.FactMap} — Default implementation of {@code FactStore}</li>
 * </ul>
 */
package io.github.brantunger.unruly.api;
