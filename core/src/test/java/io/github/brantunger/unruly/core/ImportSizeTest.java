package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.language.ImportLimits;
import io.github.brantunger.unruly.api.language.MessageText;
import io.github.brantunger.unruly.internal.ImportSize;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * #1241: the engine rejects an import it is built with as {@code Can't import '} + the quoted import + {@code ': } +
 * the reason {@link ImportLimits#importSizeProblem} gives, so a language that rejects an inline import with the same
 * reason reports it with the engine's words. {@code api.language.ImportLimitsContractTest} fixes the reasons; this
 * checks the engine's message around them, through {@link ImportSize}, which the engine and the builder's contexts
 * check with.
 */
@DisplayName("ImportSize: the engine's rejection is the quoted import and the published reason")
class ImportSizeTest {

    @Test
    @DisplayName("checkSize accepts an import with no problem, and rejects one with the quoted import and the reason")
    void checkSizeUsesThePublishedReason() {
        int parts = ImportLimits.MAX_IMPORT_PARTS;
        List<String> rejected = List.of("a".repeat(ImportLimits.MAX_IMPORT_LENGTH + 1), "a.".repeat(parts) + "a",
                "a\n.".repeat(parts), "a".repeat(199) + Character.toString(0x1F600) + "a".repeat(801));
        for (String name : rejected) {
            String problem = ImportLimits.importSizeProblem(name);
            assertNotNull(problem, name.length() + " characters");
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> ImportSize.checkSize(name));
            assertEquals("Can't import '" + MessageText.quote(name) + "': " + problem, e.getMessage());
        }
        assertDoesNotThrow(() -> ImportSize.checkSize("java.util"));
        assertDoesNotThrow(() -> ImportSize.checkSize("a".repeat(ImportLimits.MAX_IMPORT_LENGTH)));
    }

    @Test
    @DisplayName("checkLength limits the length alone, as a language's own imports are limited")
    void checkLengthLimitsTheLengthAlone() {
        String tooLong = "a".repeat(ImportLimits.MAX_IMPORT_LENGTH + 1);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ImportSize.checkLength(tooLong));
        assertEquals("Can't import '" + MessageText.quote(tooLong) + "': " + ImportLimits.importSizeProblem(tooLong),
                e.getMessage());
        assertDoesNotThrow(() -> ImportSize.checkLength("a.".repeat(ImportLimits.MAX_IMPORT_PARTS) + "a"));
    }
}
