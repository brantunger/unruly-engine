package io.github.brantunger.unruly.api;

import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * The rules an engine has loaded, from {@link RulesEngine#rules()}: the rules themselves, a checksum that identifies
 * them, and when they were loaded. An audit can record the checksum with a decision, and compare it later with the
 * rules a run used ({@link RunResult#ruleSetChecksum()}).
 *
 * <p>
 * Before the first successful {@link RulesEngine#load(List)}, the engine's {@code rules()} has no rules, the checksum
 * of an empty rule list, and a {@code null} {@link #loadedAt() load time}. A run started before a reload finishes with
 * the rules it started with, so its {@link RunResult#ruleSetChecksum()} can differ from the {@link #checksum()} the
 * engine reports afterwards.
 * </p>
 *
 * <p>
 * It's a final class rather than a record, so a later 2.x release can add accessors without breaking code compiled
 * against this one.
 * </p>
 */
public final class RuleSetInfo {

    private final List<Rule> loaded;
    private final String ruleChecksum;
    private final @Nullable Instant loadTime;

    private RuleSetInfo(List<Rule> rules, String checksum, @Nullable Instant loadedAt) {
        this.loaded = List.copyOf(rules);
        this.ruleChecksum = Objects.requireNonNull(checksum, "checksum must not be null");
        this.loadTime = loadedAt;
    }

    /**
     * Creates the information about a loaded rule list. The engine creates its own; this is for a class that
     * implements {@link RulesEngine}, such as a decorator or a test double.
     *
     * @param rules    The rules, in evaluation order; copied
     * @param checksum Their checksum, as {@link #checksum()} describes it
     * @param loadedAt When they were loaded, or {@code null} if no rules have been loaded
     * @return The information
     * @throws NullPointerException if {@code rules}, one of its elements, or {@code checksum} is {@code null}
     */
    public static RuleSetInfo of(List<Rule> rules, String checksum, @Nullable Instant loadedAt) {
        return new RuleSetInfo(rules, checksum, loadedAt);
    }

    /**
     * Returns the rules the last successful {@link RulesEngine#load(List)} compiled, in the order they're evaluated:
     * by descending priority, with equal priorities in the order they were given.
     *
     * @return The rules; unmodifiable, and empty before the first load
     */
    public List<Rule> rules() {
        return loaded;
    }

    /**
     * Returns a checksum that identifies the loaded rules, so a decision can be tied to a version of them.
     *
     * <p>
     * It's the lowercase hex SHA-256 of every rule in evaluation order. Each rule contributes, in this order: its
     * name; its priority as decimal text; the expression language <b>as the engine resolved it</b> (so a rule with no
     * language contributes the engine's default language); its condition; its action; {@code true} or
     * {@code false} for whether it's {@link Rule#isEnabled() enabled}; its {@link Rule#getValidFrom() validFrom} and
     * {@link Rule#getValidTo() validTo} as ISO-8601 text in UTC, as {@link Instant#toString()} writes them, such as
     * {@code 2027-06-01T00:00:00Z}; and its tags. Each value is written as its length in bytes, as four bytes most
     * significant first, followed by its UTF-8 bytes; a {@code null} priority, validFrom or validTo is written as the
     * length {@code -1}. The tags are written as how many there are, as four bytes, followed by each tag as a value, in
     * the order of their UTF-8 bytes compared as unsigned numbers. A rule's description isn't included, because it
     * doesn't affect what the rules do, so editing it doesn't change the checksum. Another system can compute the same
     * value from the same rules.
     * </p>
     *
     * @return The checksum; for an empty rule list, the SHA-256 of no bytes
     */
    public String checksum() {
        return ruleChecksum;
    }

    /**
     * Returns when the rules were loaded.
     *
     * @return The time {@link RulesEngine#load(List)} finished, or {@code null} if no rules have been loaded
     */
    public @Nullable Instant loadedAt() {
        return loadTime;
    }

    /**
     * Describes the rule set, such as {@code RuleSetInfo(rules=[prime-rate], checksum=9f2c...,
     * loadedAt=2027-06-01T00:00:00Z)}. Rule names are escaped and shortened as the engine's error messages show them.
     *
     * @return The description
     */
    @Override
    public String toString() {
        return "RuleSetInfo(rules=" + Names.quoteEach(loaded.stream().map(Rule::getRuleName).toList())
                + ", checksum=" + ruleChecksum + ", loadedAt=" + loadTime + ")";
    }
}
