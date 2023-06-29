package io.github.brantunger.unruly.api;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import org.mvel2.MVEL;

import java.io.Serializable;
import java.util.List;

/**
 * A Rule is an object that guides the {@link io.github.brantunger.unruly.core.RulesEngine}. When the condition
 * expression evaluates to <strong>true</strong>, the {@link io.github.brantunger.unruly.core.RulesEngine} fires the
 * action expression during execution of the {@link io.github.brantunger.unruly.core.RulesEngine#run(List, FactStore)}
 * method.
 */
public class Rule {

    @Getter
    @Setter
    private String ruleName;

    @Getter
    private String condition;

    @Getter
    private String action;

    @Getter
    @Setter
    private Integer priority;

    @Getter
    @Setter
    private String description;

    @Getter
    private Serializable serializedCondition;

    @Getter
    private Serializable serializedAction;

    @Builder
    public Rule(String ruleName, String condition, String action, Integer priority, String description) {
        this.ruleName = ruleName;
        this.condition = condition;
        this.action = action;
        this.priority = priority;
        this.description = description;
        this.serializedAction = (this.action == null) ? null : MVEL.compileExpression(this.action);
        this.serializedCondition = (this.condition == null) ? null : MVEL.compileExpression(this.condition);
    }

    public void setCondition(String condition) {
        this.condition = condition;
        this.serializedCondition = (this.condition == null) ? null : MVEL.compileExpression(condition);
    }

    public void setAction(String action) {
        this.action = action;
        this.serializedAction = (this.action == null) ? null : MVEL.compileExpression(action);
    }
}
