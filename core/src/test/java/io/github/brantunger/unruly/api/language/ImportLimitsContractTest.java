package io.github.brantunger.unruly.api.language;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * #1241: the limits on an import and the reason one is rejected, as the engine bounds its own imports, for a language
 * that bounds an inline import the same way. The limits and the wording are a contract for 2.x, so every case here is
 * written out rather than read from the engine. {@code core.ImportSizeTest} checks that the engine's own rejection is
 * the quoted import followed by the same reason.
 */
@DisplayName("ImportLimits: how large an import may be, and why one is rejected, as the engine bounds its imports")
class ImportLimitsContractTest {

    @Test
    @DisplayName("the limits are 1,000 characters and 64 dot-separated parts, fixed for 2.x")
    void limitsAreFixed() {
        assertEquals(1_000, ImportLimits.MAX_IMPORT_LENGTH);
        assertEquals(64, ImportLimits.MAX_IMPORT_PARTS);
    }

    @Test
    @DisplayName("an import within both limits has no problem, and one past either has the engine's reason")
    void importSizeProblems() {
        int length = ImportLimits.MAX_IMPORT_LENGTH;
        int parts = ImportLimits.MAX_IMPORT_PARTS;
        String emoji = Character.toString(0x1F600);
        List<String[]> cases = List.of(
                new String[]{"java.util", null},
                new String[]{"", null},
                new String[]{"a".repeat(length), null},
                new String[]{"a.".repeat(parts - 1) + "a", null},
                new String[]{".".repeat(parts - 1), null},
                new String[]{"a".repeat(length + 1), "it has 1001 characters, and an import may have at most 1000"},
                new String[]{"a.".repeat(parts) + "a",
                        "it has 65 dot-separated parts, and an import may have at most 64"},
                new String[]{".".repeat(parts), "it has 65 dot-separated parts, and an import may have at most 64"},
                // Too long and with too many parts: the length comes first.
                new String[]{"a.".repeat(1999) + "a", "it has 3999 characters, and an import may have at most 1000"},
                new String[]{"a\n.".repeat(parts), "it has 65 dot-separated parts, and an import may have at most 64"},
                new String[]{"a".repeat(199) + emoji + "a".repeat(801),
                        "it has 1002 characters, and an import may have at most 1000"});
        for (String[] expected : cases) {
            assertEquals(expected[1], ImportLimits.importSizeProblem(expected[0]),
                    "an import of " + expected[0].length() + " characters");
        }
        assertThrows(NullPointerException.class, () -> ImportLimits.importSizeProblem(null));
    }
}
