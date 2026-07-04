package io.github.brantunger.unruly.api;

import lombok.Builder;
import lombok.Data;

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
 */
@Data
@Builder
public class Rule {

    private String ruleName;

    private String condition;

    private String action;

    private Integer priority;

    private String description;
}
