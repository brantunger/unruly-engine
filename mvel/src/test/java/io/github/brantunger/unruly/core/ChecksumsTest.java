package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
        assertEquals("7504211ee974cfc0c4b0c3f0a3c8882f8a1f526fb7d842b7bf5b8d7927fd1a61",
                checksumOf(Rule.builder().ruleName("ab").condition("true").action("x").build()),
                "rule 'ab', no priority, language mvel, condition 'true', action 'x'");
        // The priority contributes its decimal digits, and a name or an action outside ASCII its UTF-8 bytes, which
        // are longer than the text: a length counted in characters, or ISO-8859-1 bytes, gives another value.
        assertEquals("80aafc8ba1de8d3e5d96e9d3976d9d939376a1eff0f1fab77422e80241eeafd7",
                checksumOf(Rule.builder().ruleName("é").priority(1).condition("true")
                        .action("output.put('ü', 1)").build()),
                "rule 'é', priority 1, language mvel, condition 'true', action \"output.put('ü', 1)\"");
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
