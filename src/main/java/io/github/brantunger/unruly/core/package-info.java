/**
 * Core rules engine implementations.
 *
 * <p>This package contains the engine interface and its implementations:</p>
 * <ul>
 *   <li>{@link io.github.brantunger.unruly.api.RulesEngine} — The engine interface</li>
 *   <li>{@link io.github.brantunger.unruly.core.StatefulRulesEngine} — Fires all matching rules, accumulating state</li>
 *   <li>{@link io.github.brantunger.unruly.core.StatelessRulesEngine} — Fires only the highest-priority matching rule</li>
 * </ul>
 *
 * <p>Rules are compiled from MVEL expressions at configuration time via
 * {@link io.github.brantunger.unruly.api.RulesEngine#setRuleList(java.util.List)}
 * and evaluated against a {@link io.github.brantunger.unruly.api.FactStore} at runtime.</p>
 */
package io.github.brantunger.unruly.core;
