package io.github.brantunger.unruly.api;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * A Rule is an object that guides the {@link io.github.brantunger.unruly.core.RulesEngine}. When the condition
 * expression evaluates to <strong>true</strong>, the {@link io.github.brantunger.unruly.core.RulesEngine} fires the
 * action expression during execution of the {@link io.github.brantunger.unruly.core.RulesEngine#run(List, FactStore)}
 * method.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Rule {
    String ruleName;
    String condition;
    String action;
    Integer priority;
    String description;
}
