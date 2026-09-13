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
 * Fields:
 * <ul>
 *     <li>{@code ruleName}: identifies the rule in error messages and listener callbacks. Must be unique within a
 *     rule list; a rule without a name is allowed.</li>
 *     <li>{@code condition}: an MVEL expression that must evaluate to a boolean. It can't assign or declare
 *     anything. Required.</li>
 *     <li>{@code action}: an MVEL expression run when the rule fires. It changes the output object, which it sees
 *     as {@code output}. Required.</li>
 *     <li>{@code priority}: higher values fire first. Equal priorities keep their list order, and a {@code null}
 *     priority sorts last.</li>
 *     <li>{@code description}: free text for your own use. The engine ignores it, but listeners receive it.</li>
 * </ul>
 *
 * <p>
 * Create a rule with {@code Rule.builder()}, with the no-arg constructor and setters, or with the all-args
 * constructor {@code new Rule(ruleName, condition, action, priority, description)}.
 * </p>
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

    /** The rule's name, used in error messages and listener callbacks; unique within a rule list, or {@code null}. */
    private String ruleName;

    /** The MVEL condition, which must evaluate to a boolean and can't assign or declare anything. */
    private String condition;

    /** The MVEL action, run when the rule fires; it changes the output object, which it sees as {@code output}. */
    private String action;

    /** The rule's priority: higher values fire first, and {@code null} sorts last. */
    private Integer priority;

    /** Free text for your own use; the engine ignores it, but listeners receive it. */
    private String description;
}
