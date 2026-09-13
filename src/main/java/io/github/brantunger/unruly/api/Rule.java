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
 *     <li>{@code condition}: an expression that must evaluate to a boolean. It can't assign or declare anything.
 *     Required.</li>
 *     <li>{@code action}: an expression run when the rule fires. It changes the output object, which it sees as
 *     {@code output}. Required.</li>
 *     <li>{@code priority}: higher values fire first. Equal priorities keep their list order, and a {@code null}
 *     priority sorts last.</li>
 *     <li>{@code description}: free text for your own use. The engine ignores it, but listeners receive it.</li>
 *     <li>{@code language}: the name of the expression language the condition and action are written in, as
 *     registered with {@link io.github.brantunger.unruly.api.RulesEngine#registerLanguage}. {@code null}, the
 *     default, means MVEL.</li>
 * </ul>
 *
 * <p>
 * Create a rule with {@code Rule.builder()}, with the no-arg constructor and setters, or with the all-args
 * constructor {@code new Rule(ruleName, condition, action, priority, description, language)}. The constructor
 * without {@code language} creates a rule in the default language.
 * </p>
 *
 * <p>
 * {@link io.github.brantunger.unruly.api.RulesEngine#setRuleList(java.util.List)} takes a copy of each rule.
 * Changing a {@code Rule} afterwards has no effect on the engine, including on what listeners and error
 * messages report, until {@code setRuleList} is called again.
 * </p>
 *
 * <p>
 * <b>Security:</b> conditions and actions are code. In MVEL, the default language, they have the same access to the
 * JVM as Java code, including processes, files and reflection; what a rule in another language can reach depends on
 * that language. The engine applies no sandbox and no timeout, so only use rules from trusted sources.
 * </p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Rule {

    /** The rule's name, used in error messages and listener callbacks; unique within a rule list, or {@code null}. */
    private String ruleName;

    /** The condition, which must evaluate to a boolean and can't assign or declare anything. */
    private String condition;

    /** The action, run when the rule fires; it changes the output object, which it sees as {@code output}. */
    private String action;

    /** The rule's priority: higher values fire first, and {@code null} sorts last. */
    private Integer priority;

    /** Free text for your own use; the engine ignores it, but listeners receive it. */
    private String description;

    /** The name of the expression language the condition and action are written in, or {@code null} for MVEL. */
    private String language;

    /**
     * Creates a rule written in the engine's default language, MVEL.
     *
     * @param ruleName    The rule's name, unique within a rule list, or {@code null}
     * @param condition   The condition, which must evaluate to a boolean
     * @param action      The action, run when the rule fires
     * @param priority    The rule's priority: higher values fire first, and {@code null} sorts last
     * @param description Free text for your own use, or {@code null}
     */
    public Rule(String ruleName, String condition, String action, Integer priority, String description) {
        this(ruleName, condition, action, priority, description, null);
    }
}
