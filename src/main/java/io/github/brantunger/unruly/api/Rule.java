package io.github.brantunger.unruly.api;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A Rule is an object that guides the {@link io.github.brantunger.unruly.api.RulesEngine}. When the condition
 * expression evaluates to <strong>true</strong>, the {@link io.github.brantunger.unruly.api.RulesEngine} fires the
 * action expression during execution of the {@link io.github.brantunger.unruly.api.RulesEngine#run(FactStore)}
 * method.
 *
 * <p>
 * Key fields include:
 * <ul>
 *     <li>{@code ruleName}: An identifier used for error reporting and debugging.</li>
 *     <li>{@code priority}: Evaluated in descending order (highest priority executes first or wins).</li>
 * </ul>
 *
 * <p>
 * {@link io.github.brantunger.unruly.api.RulesEngine#setRuleList(java.util.List)} takes a copy of each rule.
 * Changing a {@code Rule} afterwards has no effect on the engine, including on what listeners and error
 * messages report, until {@code setRuleList} is called again.
 * </p>
 *
 * <p>
 * <b>Security:</b> conditions and actions are MVEL expressions with the same access to the JVM as Java code,
 * including processes, files and reflection. The engine applies no sandbox and no timeout, so only use rules from
 * trusted sources.
 * </p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Rule {

    private String ruleName;

    private String condition;

    private String action;

    private Integer priority;

    private String description;
}
