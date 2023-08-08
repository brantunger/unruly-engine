package io.github.brantunger.unruly.api;

import lombok.Builder;
import lombok.Data;

import java.io.Serializable;

/**
 * A Rule is an object that guides the {@link io.github.brantunger.unruly.core.RulesEngine}. When the condition
 * expression evaluates to <strong>true</strong>, the {@link io.github.brantunger.unruly.core.RulesEngine} fires the
 * action expression during execution of the {@link io.github.brantunger.unruly.core.RulesEngine#run(FactStore)}
 * method.
 */
@Data
@Builder
public class Rule {

    private String ruleName;

    private String condition;

    private String action;

    private Integer priority;

    private String description;

    private Serializable serializedCondition;

    private Serializable serializedAction;
}
