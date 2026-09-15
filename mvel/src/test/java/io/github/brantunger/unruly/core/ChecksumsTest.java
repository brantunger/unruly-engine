package io.github.brantunger.unruly.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("the rule-set checksum is a SHA-256 another system can reproduce")
class ChecksumsTest {

    /** The SHA-256 of no bytes, from the algorithm's specification. */
    private static final String EMPTY_SHA_256 =
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

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
