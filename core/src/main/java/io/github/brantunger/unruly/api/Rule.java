package io.github.brantunger.unruly.api;

import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * A rule: a condition that decides whether it fires, and an action that runs when it does, with a name, a priority
 * and the language they're written in. A {@link io.github.brantunger.unruly.api.RulesEngine} evaluates the condition
 * during {@link io.github.brantunger.unruly.api.RulesEngine#run(FactStore)}, and fires the action when the condition
 * is {@code true} and the engine's match policy selects the rule.
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
 *     <li>{@code language}: the name of the expression language the condition and action are written in.
 *     {@code null}, the default, means the engine's default language.</li>
 *     <li>{@code enabled}: whether runs use the rule. {@code true} by default.</li>
 *     <li>{@code validFrom} and {@code validTo}: when runs use the rule, from {@code validFrom} (inclusive) until
 *     {@code validTo} (exclusive). {@code null}, the default, means no start or no end.</li>
 *     <li>{@code tags}: names that group rules, such as a market or a product. A run given tags with
 *     {@link RunOptions#withTags(java.util.Collection)} uses only the rules that carry at least one of them. Empty
 *     by default.</li>
 * </ul>
 *
 * <p>
 * A run skips a rule that is disabled, outside its validity window when the run starts, or without any of the run's
 * tags: it doesn't evaluate the rule's condition or tell listeners about it, and its
 * {@link RunResult#evaluations() evaluation} is {@link RuleEvaluation.Outcome#SKIPPED SKIPPED}. The engine still
 * compiles every rule when it loads them, so a skipped rule's errors fail {@link RulesEngine#load(java.util.List)}
 * too, and a rule that becomes valid later needs no reload. The time comes from the engine's
 * {@link RulesEngineBuilder#clock(java.time.Clock) clock}.
 * </p>
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
 * ({@code tools.jackson.databind}); only the imports differ, except that Jackson 2 reads {@code validFrom} and
 * {@code validTo}, which are {@link Instant}s, only with its java.time module:
 * {@code .addModule(new JavaTimeModule())} from {@code jackson-datatype-jsr310}. Jackson 3 reads them without one. A
 * rule without a name, a condition or an action fails while it's read.
 * </p>
 *
 * <p>
 * {@code equals} and {@code hashCode} compare every field, including {@code description} and {@code language}. A
 * field added in a later release takes part too.
 * </p>
 *
 * <p>
 * <b>Security:</b> conditions and actions are code, and what a rule can reach depends on its language: in MVEL, a
 * rule has the same access to the JVM as Java code, including processes, files and reflection. The engine applies no
 * sandbox. A {@link RulesEngineBuilder#runTimeout(java.time.Duration) timeout} stops a run between rules and when an
 * expression returns, but can't stop inside an expression that its language doesn't interrupt. Only use rules from
 * trusted sources.
 * </p>
 *
 * @see <a href="https://github.com/brantunger/unruly-engine/blob/main/docs/writing-rules.md">Writing rules</a>
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

    /** Whether runs use the rule. */
    private final boolean enabled;

    /** When runs start to use the rule (inclusive), or {@code null} for no start. */
    private final @Nullable Instant validFrom;

    /** When runs stop using the rule (exclusive), or {@code null} for no end. */
    private final @Nullable Instant validTo;

    /** The names that group the rule, in {@link String} order; unmodifiable. */
    private final Set<String> tags;

    private Rule(RuleBuilder builder, String ruleName, String condition, String action, Set<String> tags) {
        this.ruleName = ruleName;
        this.condition = condition;
        this.action = action;
        this.priority = builder.priority;
        this.description = builder.description;
        this.language = builder.language;
        this.enabled = builder.enabled;
        this.validFrom = builder.validFrom;
        this.validTo = builder.validTo;
        this.tags = tags;
    }

    /**
     * Returns a builder for a new rule. The rule is enabled, with no validity window and no tags, and every other
     * field is {@code null} until it is set.
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
                .description(description).language(language).enabled(enabled).validFrom(validFrom).validTo(validTo)
                .tags(tags);
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
     * Returns whether runs use the rule. A run skips a disabled rule.
     *
     * @return {@code true} unless the rule was built with {@code enabled(false)}
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Returns when runs start to use the rule. A run that starts before it skips the rule.
     *
     * @return The start, inclusive, or {@code null} if the rule has no start
     */
    public @Nullable Instant getValidFrom() {
        return validFrom;
    }

    /**
     * Returns when runs stop using the rule. A run that starts at or after it skips the rule.
     *
     * @return The end, exclusive, or {@code null} if the rule has no end
     */
    public @Nullable Instant getValidTo() {
        return validTo;
    }

    /**
     * Returns the names that group the rule. A run given tags uses the rule only if it carries at least one of them.
     *
     * @return The tags, in {@link String} order; unmodifiable, and empty if the rule has none
     */
    public Set<String> getTags() {
        return tags;
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
                && Objects.equals(language, other.language)
                && enabled == other.enabled
                && Objects.equals(validFrom, other.validFrom)
                && Objects.equals(validTo, other.validTo)
                && tags.equals(other.tags);
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
        result = result * HASH_PRIME + Boolean.hashCode(enabled);
        result = result * HASH_PRIME + hashOf(validFrom);
        result = result * HASH_PRIME + hashOf(validTo);
        result = result * HASH_PRIME + tags.hashCode();
        return result;
    }

    private static int hashOf(@Nullable Object value) {
        return value == null ? NULL_HASH : value.hashCode();
    }

    /**
     * Lists every field, such as {@code Rule(ruleName=prime-rate, condition=..., action=..., priority=10,
     * description=null, language=null, enabled=true, validFrom=null, validTo=null, tags=[])}.
     *
     * @return The rule's fields as text
     */
    @Override
    public String toString() {
        return "Rule(ruleName=" + ruleName + ", condition=" + condition + ", action=" + action
                + ", priority=" + priority + ", description=" + description + ", language=" + language
                + ", enabled=" + enabled + ", validFrom=" + validFrom + ", validTo=" + validTo + ", tags=" + tags
                + ")";
    }

    /**
     * Builds a {@link Rule}. Get one from {@link Rule#builder()}, or from {@link Rule#toBuilder()} to copy a rule.
     * The rule is enabled, with no validity window and no tags, until those are set; every other field is
     * {@code null} until it is set, and {@link #build()} checks that the required ones are.
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
        // Enabled unless set otherwise, so rules read from data that has no such field are used.
        private boolean enabled = true;
        private @Nullable Instant validFrom;
        private @Nullable Instant validTo;
        // As given, nulls included, so build() can reject them with the other checks.
        private List<String> tags = List.of();

        /**
         * Creates a builder for an enabled rule with no validity window, no tags, and every other field
         * {@code null}. In code, call {@link Rule#builder()}. This constructor is public so that a binder can create
         * the builder itself, such as Jackson through {@code @JsonDeserialize(builder = Rule.RuleBuilder.class)},
         * also on the module path, where a non-public constructor isn't accessible.
         */
        public RuleBuilder() {
            // Every field starts at its default.
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
         * Sets whether runs use the rule. A run skips a disabled rule, but the engine still compiles it when it loads
         * the rules. {@code null} means the default, {@code true}, so rules read from data that holds {@code null}
         * for an unset value, such as a nullable database column, stay enabled.
         *
         * @param enabled {@code false} to have runs skip the rule; {@code true} or {@code null} to have them use it
         * @return This builder
         */
        public RuleBuilder enabled(@Nullable Boolean enabled) {
            this.enabled = enabled == null || enabled;
            return this;
        }

        /**
         * Sets when runs start to use the rule. A run that starts before it skips the rule.
         *
         * @param validFrom The start, inclusive, or {@code null} for no start
         * @return This builder
         */
        public RuleBuilder validFrom(@Nullable Instant validFrom) {
            this.validFrom = validFrom;
            return this;
        }

        /**
         * Sets when runs stop using the rule. A run that starts at or after it skips the rule.
         *
         * @param validTo The end, exclusive, or {@code null} for no end
         * @return This builder
         */
        public RuleBuilder validTo(@Nullable Instant validTo) {
            this.validTo = validTo;
            return this;
        }

        /**
         * Sets the names that group the rule, replacing any set before. A run given tags uses the rule only if it
         * carries at least one of them. A tag repeated in {@code tags} is kept once.
         *
         * @param tags The tags, none null or blank; copied. {@code null} means no tags, the default.
         * @return This builder
         */
        public RuleBuilder tags(@Nullable Collection<String> tags) {
            this.tags = tags == null ? List.of() : new ArrayList<>(tags);
            return this;
        }

        /**
         * Creates a rule from the fields set so far. The builder can be used again afterwards. A blank condition or
         * action is allowed here; {@link RulesEngine#load(java.util.List)} rejects it, naming the rule.
         *
         * @return A new rule
         * @throws IllegalStateException if the name is {@code null} or blank, the condition or action is
         *                               {@code null}, a tag is {@code null} or blank, or {@code validTo} isn't after
         *                               {@code validFrom}. The message names the first such field, such as
         *                               {@code ruleName must not be null}.
         */
        public Rule build() {
            String name = required(ruleName, "ruleName");
            if (name.isBlank()) {
                throw new IllegalStateException("ruleName must not be blank");
            }
            String checkedCondition = required(condition, "condition");
            String checkedAction = required(action, "action");
            if (validFrom != null && validTo != null && !validTo.isAfter(validFrom)) {
                throw new IllegalStateException("validTo must be after validFrom, but validFrom is " + validFrom
                        + " and validTo is " + validTo);
            }
            return new Rule(this, name, checkedCondition, checkedAction, checkedTags());
        }

        /** Returns the tags as the rule keeps them, in {@link String} order, after checking each one. */
        private Set<String> checkedTags() {
            Set<String> sorted = new TreeSet<>();
            for (String tag : tags) {
                if (tag == null) {
                    throw new IllegalStateException("tags must not contain null, but were " + tags);
                }
                if (tag.isBlank()) {
                    throw new IllegalStateException("tags must not contain a blank tag, but were " + tags);
                }
                sorted.add(tag);
            }
            return Collections.unmodifiableSet(sorted);
        }

        private static String required(@Nullable String value, String field) {
            if (value == null) {
                throw new IllegalStateException(field + " must not be null");
            }
            return value;
        }

        /**
         * Lists every field set so far, such as {@code Rule.RuleBuilder(ruleName=prime-rate, condition=null, ...)}.
         * The tags are listed as they were given, before {@link #build()} sorts them and drops repeats.
         *
         * @return The builder's fields as text
         */
        @Override
        public String toString() {
            return "Rule.RuleBuilder(ruleName=" + ruleName + ", condition=" + condition + ", action=" + action
                    + ", priority=" + priority + ", description=" + description + ", language=" + language
                    + ", enabled=" + enabled + ", validFrom=" + validFrom + ", validTo=" + validTo + ", tags=" + tags
                    + ")";
        }
    }
}
