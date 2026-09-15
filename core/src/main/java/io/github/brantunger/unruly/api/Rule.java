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
 *     <li>{@code ruleName}: identifies the rule in error messages, exceptions and listener callbacks. Required, not
 *     blank, and unique within a rule list.</li>
 *     <li>{@code condition}: an expression that must evaluate to a boolean. It can't assign or declare anything.
 *     Required.</li>
 *     <li>{@code action}: an expression run when the rule fires. It changes the output object, which it sees as
 *     {@code output}. Required.</li>
 *     <li>{@code priority}: higher values fire first. Equal priorities keep their list order, and a {@code null}
 *     priority sorts last.</li>
 *     <li>{@code description}: free text for your own use. The engine ignores it, but listeners receive it.</li>
 *     <li>{@code language}: the name of the expression language the condition and action are written in,. {@code null}, the
 *     default, means the engine's default language.</li>
 * </ul>
 *
 * <p>
 * A rule is immutable. Create one with {@link #builder()}, whose {@link RuleBuilder#build() build()} rejects a rule
 * without a name, a condition or an action. Copy a rule with a change with {@link #toBuilder()}, for example
 * {@code rule.toBuilder().priority(5).build()}. Because a rule can't change, the engine keeps the rules passed to
 * {@link io.github.brantunger.unruly.api.RulesEngine#load(java.util.List)}, and listeners receive those same
 * instances.
 * </p>
 *
 * <p>
 * <b>Reading rules from JSON:</b> a binder builds rules through {@link RuleBuilder}, whose constructor is public for
 * that. With Jackson, register one mix-in for {@code Rule} and one for its builder:
 * {@snippet :
 * @JsonDeserialize(builder = Rule.RuleBuilder.class)
 * abstract class RuleMixIn {
 * }
 *
 * @JsonPOJOBuilder(withPrefix = "")
 * abstract class RuleBuilderMixIn {
 * }
 *
 * ObjectMapper mapper = JsonMapper.builder()
 *         .addMixIn(Rule.class, RuleMixIn.class)
 *         .addMixIn(Rule.RuleBuilder.class, RuleBuilderMixIn.class)
 *         .build();
 * List<Rule> rules = mapper.readValue(json, new TypeReference<List<Rule>>() { });
 * }
 * The code is the same for Jackson 2 ({@code com.fasterxml.jackson.databind}) and Jackson 3
 * ({@code tools.jackson.databind}); only the imports differ. A rule without a name, a condition or an action fails
 * while it's read.
 * </p>
 *
 * <p>
 * {@code equals} and {@code hashCode} compare every field, including {@code description} and {@code language}. A
 * field added in a later release takes part too.
 * </p>
 *
 * <p>
 * <b>Security:</b> conditions and actions are code. In MVEL, the default language, they have the same access to the
 * JVM as Java code, including processes, files and reflection; what a rule in another language can reach depends on
 * that language. The engine applies no sandbox and no timeout, so only use rules from trusted sources.
 * </p>
 */
public final class Rule {

    /** The multiplier {@link #hashCode()} combines field hash codes with. */
    private static final int HASH_PRIME = 59;

    /** The hash code {@link #hashCode()} uses for a {@code null} field. */
    private static final int NULL_HASH = 43;

    /** The rule's name, used in error messages, exceptions and listener callbacks; unique within a rule list. */
    private final String ruleName;

    /** The condition, which must evaluate to a boolean and can't assign or declare anything. */
    private final String condition;

    /** The action, run when the rule fires; it changes the output object, which it sees as {@code output}. */
    private final String action;

    /** The rule's priority: higher values fire first, and {@code null} sorts last. */
    private final @Nullable Integer priority;

    /** Free text for your own use; the engine ignores it, but listeners receive it. */
    private final @Nullable String description;

    /** The name of the expression language the condition and action are written in, or {@code null} for the engine's default language. */
    private final @Nullable String language;

    private Rule(String ruleName, String condition, String action, @Nullable Integer priority,
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
     * Returns the rule's name, used in error messages, exceptions and listener callbacks.
     *
     * @return The name, which isn't blank
     */
    public String getRuleName() {
        return ruleName;
    }

    /**
     * Returns the condition, which must evaluate to a boolean and can't assign or declare anything.
     *
     * @return The condition
     */
    public String getCondition() {
        return condition;
    }

    /**
     * Returns the action, run when the rule fires. It changes the output object, which it sees as {@code output}.
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
     * @return The language's name, or {@code null} for the engine's default language
     */
    public @Nullable String getLanguage() {
        return language;
    }

    /**
     * Compares every field.
     *
     * @param o The object to compare with
     * @return {@code true} if {@code o} is a {@code Rule} and every field of the two is equal
     */
    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Rule other)) {
            return false;
        }
        return Objects.equals(priority, other.priority)
                && ruleName.equals(other.ruleName)
                && condition.equals(other.condition)
                && action.equals(other.action)
                && Objects.equals(description, other.description)
                && Objects.equals(language, other.language);
    }

    /**
     * Combines the hash codes of every field, consistently with {@link #equals(Object)}.
     *
     * @return The hash code
     */
    @Override
    public int hashCode() {
        int result = 1;
        result = result * HASH_PRIME + hashOf(priority);
        result = result * HASH_PRIME + ruleName.hashCode();
        result = result * HASH_PRIME + condition.hashCode();
        result = result * HASH_PRIME + action.hashCode();
        result = result * HASH_PRIME + hashOf(description);
        result = result * HASH_PRIME + hashOf(language);
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
        return "Rule(ruleName=" + ruleName + ", condition=" + condition + ", action=" + action
                + ", priority=" + priority + ", description=" + description + ", language=" + language + ")";
    }

    /**
     * Builds a {@link Rule}. Get one from {@link Rule#builder()}, or from {@link Rule#toBuilder()} to copy a rule.
     * Every field is {@code null} until it is set, and {@link #build()} checks that the required ones are.
     */
    // Each setter is named after the field it sets, as users of the builder expect.
    @SuppressWarnings("PMD.AvoidFieldNameMatchingMethodName")
    public static final class RuleBuilder {

        private @Nullable String ruleName;
        private @Nullable String condition;
        private @Nullable String action;
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
         * Sets the rule's name, used in error messages, exceptions and listener callbacks. Required, not blank, and
         * unique within a rule list.
         *
         * @param ruleName The name
         * @return This builder
         */
        public RuleBuilder ruleName(String ruleName) {
            this.ruleName = ruleName;
            return this;
        }

        /**
         * Sets the condition, which must evaluate to a boolean and can't assign or declare anything. Required.
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
         * Required.
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
         * @param language The language's name, or {@code null} for the engine's default language
         * @return This builder
         */
        public RuleBuilder language(@Nullable String language) {
            this.language = language;
            return this;
        }

        /**
         * Creates a rule from the fields set so far. The builder can be used again afterwards. A blank condition or
         * action is allowed here; {@link RulesEngine#load(java.util.List)} rejects it, naming the rule.
         *
         * @return A new rule
         * @throws IllegalStateException if the name is {@code null} or blank, or the condition or action is
         *                               {@code null}. The message names the first such field, such as
         *                               {@code ruleName must not be null}.
         */
        public Rule build() {
            String name = required(ruleName, "ruleName");
            if (name.isBlank()) {
                throw new IllegalStateException("ruleName must not be blank");
            }
            return new Rule(name, required(condition, "condition"), required(action, "action"), priority, description,
                    language);
        }

        private static String required(@Nullable String value, String field) {
            if (value == null) {
                throw new IllegalStateException(field + " must not be null");
            }
            return value;
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
