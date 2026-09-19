package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("the rule-set checksum is a SHA-256 another system can reproduce")
class ChecksumsTest {

    /** The SHA-256 of no bytes, from the algorithm's specification. */
    private static final String EMPTY_SHA_256 =
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    /**
     * The checksum an engine reports for one rule, which is what another system reproduces from the documented
     * layout: the length of each value in bytes as four bytes most significant first, then its UTF-8 bytes.
     */
    private static String checksumOf(Rule rule) {
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>firstMatch(HashMap::new)
                .build();
        engine.load(List.of(rule));
        return engine.rules().checksum();
    }

    @Test
    @DisplayName("rules hash to the values an independent implementation of the documented layout computes")
    void knownChecksums() {
        assertEquals("500a059b1cb5650d87d53c91d0658bba42964c122a1d491377154b455d4d004b",
                checksumOf(Rule.builder().ruleName("ab").condition("true").action("x").build()),
                "rule 'ab', no priority, language mvel, condition 'true', action 'x', enabled, no window, no tags");
        // The priority contributes its decimal digits, and a name or an action outside ASCII its UTF-8 bytes, which
        // are longer than the text: a length counted in characters, or ISO-8859-1 bytes, gives another value.
        assertEquals("5bd13e8057d24a6b5e7f7d659d3931f0dbedc305747f7429570a272f1bf5844d",
                checksumOf(Rule.builder().ruleName("é").priority(1).condition("true")
                        .action("output.put('ü', 1)").build()),
                "rule 'é', priority 1, language mvel, condition 'true', action \"output.put('ü', 1)\"");
        // A disabled rule with a window and tags. The end has milliseconds, which Instant.toString() writes as
        // three digits. The tags are in the order of their UTF-8 bytes: U+FF21 before U+1F600, although Java's
        // String order, which compares UTF-16 units, puts U+1F600 (a surrogate pair from U+D83D) first.
        assertEquals("331878412894ed9e73033ba2aa09d60fa1d5ca71a6843856e30d37284c46f4ea",
                checksumOf(Rule.builder().ruleName("r").condition("true").action("x").enabled(false)
                        .validFrom(Instant.parse("2027-06-01T00:00:00Z"))
                        .validTo(Instant.parse("2027-09-01T00:00:00.500Z"))
                        .tags(List.of("\uFF21", "\uD83D\uDE00", "eu")).build()),
                "rule 'r', disabled, from 2027-06-01T00:00:00Z to 2027-09-01T00:00:00.500Z, tags eu, U+FF21, U+1F600");
    }

    @Test
    @DisplayName("enabling a rule, or changing its window or tags, changes the checksum")
    void newFieldsChangeTheChecksum() {
        Rule rule = Rule.builder().ruleName("r").condition("true").action("x").build();
        String plain = checksumOf(rule);

        assertNotEquals(plain, checksumOf(rule.toBuilder().enabled(false).build()), "enabled");
        assertNotEquals(plain, checksumOf(rule.toBuilder().validFrom(Instant.EPOCH).build()), "validFrom");
        assertNotEquals(plain, checksumOf(rule.toBuilder().validTo(Instant.EPOCH).build()), "validTo");
        assertNotEquals(plain, checksumOf(rule.toBuilder().tags(Set.of("eu")).build()), "tags");
        // The start and the end are separate values, so the same instant as one or the other hashes differently.
        assertNotEquals(checksumOf(rule.toBuilder().validFrom(Instant.EPOCH).build()),
                checksumOf(rule.toBuilder().validTo(Instant.EPOCH).build()), "validFrom versus validTo");
        // The count separates the tags from the next rule, so a tag can't pass for the start of another rule.
        assertNotEquals(checksumOf(rule.toBuilder().tags(Set.of("a", "b")).build()),
                checksumOf(rule.toBuilder().tags(Set.of("ab")).build()), "tags a, b versus ab");
    }

    @Test
    @DisplayName("the same characters split differently between two values hash differently")
    void lengthsSeparateTheValues() {
        String split = checksumOf(Rule.builder().ruleName("r").condition("true").action("x").build());
        String other = checksumOf(Rule.builder().ruleName("r").condition("tru").action("ex").build());

        assertNotEquals(split, other,
                "each value is hashed with its length, so the bytes of two values never run together");
    }

    @Test
    @DisplayName("an empty rule list hashes to the SHA-256 of no bytes")
    void emptyRules() {
        assertEquals(EMPTY_SHA_256, Checksums.ofRules(List.of()));
        assertEquals(EMPTY_SHA_256, Checksums.hex(Checksums.SHA_256, new byte[0]));
    }

    @Test
    @DisplayName("a digest the JVM doesn't have is reported, rather than surfacing as a checked exception")
    void unknownAlgorithm() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> Checksums.hex("no-such-digest", new byte[0]));

        assertEquals("This JVM has no no-such-digest digest", ex.getMessage());
    }
}
