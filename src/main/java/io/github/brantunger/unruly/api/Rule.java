package io.github.brantunger.unruly.api;

import org.jspecify.annotations.Nullable;

import java.util.Objects;

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
 * Create a rule with {@code Rule.builder()}. Copy a rule with a change with {@code toBuilder()}, for example
 * {@code rule.toBuilder().priority(5).build()}. The positional constructors, the no-arg constructor and the setters are
 * deprecated: {@code Rule} is expected to become immutable in 2.0. A JSON or configuration binder can build a rule
 * through {@link RuleBuilder}, whose constructor is public for that; with Jackson, for example, a mix-in with
 * {@code @JsonDeserialize(builder = Rule.RuleBuilder.class)} and {@code @JsonPOJOBuilder(withPrefix = "")}.
 * </p>
 *
 * <p>
 * <b>Nullness:</b> {@code ruleName}, {@code priority}, {@code description} and {@code language} are
 * {@link Nullable}. {@code condition} and {@code action} are required, so they aren't, but a rule created with the
 * no-arg constructor or a builder has neither until they are set, and
 * {@link io.github.brantunger.unruly.api.RulesEngine#setRuleList(java.util.List)} rejects it until then.
 * </p>
 *
 * <p>
 * {@code equals} and {@code hashCode} compare every field, including {@code description} and {@code language}. A
 * field added in a later release takes part too.
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
// Each @Deprecated repeats its version, so the Javadoc shows the version rather than a constant's name.
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
public class Rule {

    /** The multiplier {@link #hashCode()} combines field hash codes with. */
    private static final int HASH_PRIME = 59;

    /** The hash code {@link #hashCode()} uses for a {@code null} field. */
    private static final int NULL_HASH = 43;

    /** The rule's name, used in error messages and listener callbacks; unique within a rule list, or {@code null}. */
    private @Nullable String ruleName;

    /** The condition, which must evaluate to a boolean and can't assign or declare anything. */
    private String condition;

    /** The action, run when the rule fires; it changes the output object, which it sees as {@code output}. */
    private String action;

    /** The rule's priority: higher values fire first, and {@code null} sorts last. */
    private @Nullable Integer priority;

    /** Free text for your own use; the engine ignores it, but listeners receive it. */
    private @Nullable String description;

    /** The name of the expression language the condition and action are written in, or {@code null} for MVEL. */
    private @Nullable String language;

    /**
     * Creates a rule whose fields are all {@code null}, to be filled in with the setters. JSON and configuration
     * binders create rules this way. Set at least the condition and action before passing the rule to
     * {@link io.github.brantunger.unruly.api.RulesEngine#setRuleList(java.util.List)}.
     *
     * @deprecated Use {@link #builder()}. A binder can build rules through {@link RuleBuilder} instead; see the class
     *             description. {@code Rule} is expected to become immutable in 2.0, without this constructor.
     */
    @Deprecated(since = "1.8.0", forRemoval = true)
    public Rule() {
        // Every field starts null.
    }

    /**
     * Creates a rule written in the engine's default language, MVEL.
     *
     * @param ruleName    The rule's name, unique within a rule list, or {@code null}
     * @param condition   The condition, which must evaluate to a boolean
     * @param action      The action, run when the rule fires
     * @param priority    The rule's priority: higher values fire first, and {@code null} sorts last
     * @param description Free text for your own use, or {@code null}
     * @deprecated Use {@link #builder()} instead. A positional constructor's parameters change whenever a field is
     *             added, and this constructor is expected to be removed in 2.0.
     */
    @Deprecated(since = "1.4.0", forRemoval = true)
    public Rule(@Nullable String ruleName, String condition, String action, @Nullable Integer priority,
                @Nullable String description) {
        this(ruleName, condition, action, priority, description, null);
    }

    /**
     * Creates a rule. The builder uses this constructor too.
     *
     * @param ruleName    The rule's name, unique within a rule list, or {@code null}
     * @param condition   The condition, which must evaluate to a boolean
     * @param action      The action, run when the rule fires
     * @param priority    The rule's priority: higher values fire first, and {@code null} sorts last
     * @param description Free text for your own use, or {@code null}
     * @param language    The name of the expression language the condition and action are written in, or
     *                    {@code null} for MVEL
     * @deprecated Use {@link #builder()} instead. A positional constructor's parameters change whenever a field is
     *             added, and this constructor is expected to be removed in 2.0.
     */
    @Deprecated(since = "1.4.0", forRemoval = true)
    public Rule(@Nullable String ruleName, String condition, String action, @Nullable Integer priority,
                @Nullable String description, @Nullable String language) {
        this.ruleName = ruleName;
        this.condition = condition;
        this.action = action;
        this.priority = priority;
        this.description = description;
        this.language = language;
    }

    /**
     * Returns a builder for a new rule. Every field is {@code null} until it is set.
     *
     * @return A new builder
     */
    public static RuleBuilder builder() {
        return new RuleBuilder();
    }

    /**
     * Returns a builder that starts with this rule's fields, to create a copy with some of them changed, such as
     * {@code rule.toBuilder().priority(5).build()}. This rule isn't changed.
     *
     * @return A new builder holding this rule's fields
     */
    public RuleBuilder toBuilder() {
        return new RuleBuilder().ruleName(ruleName).condition(condition).action(action).priority(priority)
                .description(description).language(language);
    }

    /**
     * Returns the rule's name, used in error messages and listener callbacks.
     *
     * @return The name, or {@code null} if the rule has none
     */
    public @Nullable String getRuleName() {
        return ruleName;
    }

    /**
     * Returns the condition, which must evaluate to a boolean and can't assign or declare anything. A rule that isn't
     * complete yet has none; see the class's nullness note.
     *
     * @return The condition
     */
    public String getCondition() {
        return condition;
    }

    /**
     * Returns the action, run when the rule fires. It changes the output object, which it sees as {@code output}. A
     * rule that isn't complete yet has none; see the class's nullness note.
     *
     * @return The action
     */
    public String getAction() {
        return action;
    }

    /**
     * Returns the rule's priority. Higher values fire first, and {@code null} sorts last.
     *
     * @return The priority, or {@code null}
     */
    public @Nullable Integer getPriority() {
        return priority;
    }

    /**
     * Returns the rule's description: free text for your own use, which the engine ignores but listeners receive.
     *
     * @return The description, or {@code null}
     */
    public @Nullable String getDescription() {
        return description;
    }

    /**
     * Returns the name of the expression language the condition and action are written in.
     *
     * @return The language's name, or {@code null} for MVEL
     */
    public @Nullable String getLanguage() {
        return language;
    }

    /**
     * Sets the rule's name, used in error messages and listener callbacks. It must be unique within a rule list.
     *
     * @param ruleName The name, or {@code null} for an unnamed rule
     * @deprecated Build the rule with {@link #builder()}, or copy it with a change with {@link #toBuilder()}. {@code Rule}
     *             is expected to become immutable in 2.0, without setters.
     */
    @Deprecated(since = "1.8.0", forRemoval = true)
    public void setRuleName(@Nullable String ruleName) {
        this.ruleName = ruleName;
    }

    /**
     * Sets the condition, which must evaluate to a boolean and can't assign or declare anything.
     *
     * @param condition The condition
     * @deprecated Build the rule with {@link #builder()}, or copy it with a change with {@link #toBuilder()}. {@code Rule}
     *             is expected to become immutable in 2.0, without setters.
     */
    @Deprecated(since = "1.8.0", forRemoval = true)
    public void setCondition(String condition) {
        this.condition = condition;
    }

    /**
     * Sets the action, run when the rule fires. It changes the output object, which it sees as {@code output}.
     *
     * @param action The action
     * @deprecated Build the rule with {@link #builder()}, or copy it with a change with {@link #toBuilder()}. {@code Rule}
     *             is expected to become immutable in 2.0, without setters.
     */
    @Deprecated(since = "1.8.0", forRemoval = true)
    public void setAction(String action) {
        this.action = action;
    }

    /**
     * Sets the rule's priority. Higher values fire first, and {@code null} sorts last.
     *
     * @param priority The priority, or {@code null}
     * @deprecated Build the rule with {@link #builder()}, or copy it with a change with {@link #toBuilder()}. {@code Rule}
     *             is expected to become immutable in 2.0, without setters.
     */
    @Deprecated(since = "1.8.0", forRemoval = true)
    public void setPriority(@Nullable Integer priority) {
        this.priority = priority;
    }

    /**
     * Sets the rule's description: free text for your own use, which the engine ignores but listeners receive.
     *
     * @param description The description, or {@code null}
     * @deprecated Build the rule with {@link #builder()}, or copy it with a change with {@link #toBuilder()}. {@code Rule}
     *             is expected to become immutable in 2.0, without setters.
     */
    @Deprecated(since = "1.8.0", forRemoval = true)
    public void setDescription(@Nullable String description) {
        this.description = description;
    }

    /**
     * Sets the name of the expression language the condition and action are written in, as registered with
     * {@link io.github.brantunger.unruly.api.RulesEngine#registerLanguage}.
     *
     * @param language The language's name, or {@code null} for MVEL
     * @deprecated Build the rule with {@link #builder()}, or copy it with a change with {@link #toBuilder()}. {@code Rule}
     *             is expected to become immutable in 2.0, without setters.
     */
    @Deprecated(since = "1.8.0", forRemoval = true)
    public void setLanguage(@Nullable String language) {
        this.language = language;
    }

    /**
     * Compares every field, through its getter.
     *
     * @param o The object to compare with
     * @return {@code true} if {@code o} is a {@code Rule} that {@link #canEqual(Object) can equal} this rule, and every
     *         field of the two is equal
     */
    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Rule other)) {
            return false;
        }
        return other.canEqual(this)
                && Objects.equals(getPriority(), other.getPriority())
                && Objects.equals(getRuleName(), other.getRuleName())
                && Objects.equals(getCondition(), other.getCondition())
                && Objects.equals(getAction(), other.getAction())
                && Objects.equals(getDescription(), other.getDescription())
                && Objects.equals(getLanguage(), other.getLanguage());
    }

    /**
     * Returns whether {@code other} may equal a {@code Rule}. A subclass that adds state to {@link #equals(Object)}
     * overrides this, so that it isn't equal to a plain {@code Rule} and {@code equals} stays symmetric.
     *
     * @param other The object being compared
     * @return {@code true} if {@code other} is a {@code Rule}
     * @deprecated {@code Rule} is expected to become a final class in 2.0, so it can't be subclassed and doesn't need
     *             this method.
     */
    @Deprecated(since = "1.8.0", forRemoval = true)
    protected boolean canEqual(@Nullable Object other) {
        return other instanceof Rule;
    }

    /**
     * Combines the hash codes of every field, through its getter, consistently with {@link #equals(Object)}.
     *
     * @return The hash code
     */
    @Override
    public int hashCode() {
        int result = 1;
        result = result * HASH_PRIME + hashOf(getPriority());
        result = result * HASH_PRIME + hashOf(getRuleName());
        result = result * HASH_PRIME + hashOf(getCondition());
        result = result * HASH_PRIME + hashOf(getAction());
        result = result * HASH_PRIME + hashOf(getDescription());
        result = result * HASH_PRIME + hashOf(getLanguage());
        return result;
    }

    private static int hashOf(@Nullable Object value) {
        return value == null ? NULL_HASH : value.hashCode();
    }

    /**
     * Lists every field, such as {@code Rule(ruleName=prime-rate, condition=..., action=..., priority=10,
     * description=null, language=null)}.
     *
     * @return The rule's fields as text
     */
    @Override
    public String toString() {
        return "Rule(ruleName=" + getRuleName() + ", condition=" + getCondition() + ", action=" + getAction()
                + ", priority=" + getPriority() + ", description=" + getDescription() + ", language=" + getLanguage()
                + ")";
    }

    /**
     * Builds a {@link Rule}. Get one from {@link Rule#builder()}, or from {@link Rule#toBuilder()} to copy a rule.
     * Every field is {@code null} until it is set.
     */
    // Each setter is named after the field it sets, as users of the builder expect.
    @SuppressWarnings("PMD.AvoidFieldNameMatchingMethodName")
    public static class RuleBuilder {

        private @Nullable String ruleName;
        private String condition;
        private String action;
        private @Nullable Integer priority;
        private @Nullable String description;
        private @Nullable String language;

        /**
         * Creates a builder with every field {@code null}. In code, call {@link Rule#builder()}. This constructor is
         * public so that a binder can create the builder itself, such as Jackson through
         * {@code @JsonDeserialize(builder = Rule.RuleBuilder.class)}, also on the module path, where a non-public
         * constructor isn't accessible.
         */
        public RuleBuilder() {
            // Every field starts null.
        }

        /**
         * Sets the rule's name, used in error messages and listener callbacks; unique within a rule list.
         *
         * @param ruleName The name, or {@code null} for an unnamed rule
         * @return This builder
         */
        public RuleBuilder ruleName(@Nullable String ruleName) {
            this.ruleName = ruleName;
            return this;
        }

        /**
         * Sets the condition, which must evaluate to a boolean and can't assign or declare anything.
         *
         * @param condition The condition
         * @return This builder
         */
        public RuleBuilder condition(String condition) {
            this.condition = condition;
            return this;
        }

        /**
         * Sets the action, run when the rule fires; it changes the output object, which it sees as {@code output}.
         *
         * @param action The action
         * @return This builder
         */
        public RuleBuilder action(String action) {
            this.action = action;
            return this;
        }

        /**
         * Sets the rule's priority: higher values fire first, and {@code null} sorts last.
         *
         * @param priority The priority, or {@code null}
         * @return This builder
         */
        public RuleBuilder priority(@Nullable Integer priority) {
            this.priority = priority;
            return this;
        }

        /**
         * Sets the rule's description: free text for your own use, which the engine ignores but listeners receive.
         *
         * @param description The description, or {@code null}
         * @return This builder
         */
        public RuleBuilder description(@Nullable String description) {
            this.description = description;
            return this;
        }

        /**
         * Sets the name of the expression language the condition and action are written in.
         *
         * @param language The language's name, or {@code null} for MVEL
         * @return This builder
         */
        public RuleBuilder language(@Nullable String language) {
            this.language = language;
            return this;
        }

        /**
         * Creates a rule from the fields set so far. The builder can be used again afterwards.
         *
         * @return A new rule
         */
        public Rule build() {
            return new Rule(ruleName, condition, action, priority, description, language);
        }

        /**
         * Lists every field set so far, such as {@code Rule.RuleBuilder(ruleName=prime-rate, condition=null, ...)}.
         *
         * @return The builder's fields as text
         */
        @Override
        public String toString() {
            return "Rule.RuleBuilder(ruleName=" + ruleName + ", condition=" + condition + ", action=" + action
                    + ", priority=" + priority + ", description=" + description + ", language=" + language + ")";
        }
    }
}
