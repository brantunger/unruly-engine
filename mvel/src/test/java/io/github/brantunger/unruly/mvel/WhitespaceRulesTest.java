package io.github.brantunger.unruly.mvel;

import io.github.brantunger.unruly.api.exception.InvalidExpressionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

// #735: the scanners that read a rule's text themselves skip whitespace by one of two rules, MVEL's own, every
// character up to a space, and that one with Java's added. Each character here is on the edge of one of them, and
// each scanner is checked to skip it or not by its rule.
@DisplayName("whitespace rules")
class WhitespaceRulesTest {

    @ParameterizedTest(name = "U+{0}: MVEL''s rule {1}, MVEL''s or Java''s {2}")
    @CsvSource({
            "0000, true, true",
            "0001, true, true",
            "0008, true, true",
            "000E, true, true",
            "001B, true, true",
            "001C, true, true",
            "001F, true, true",
            "0020, true, true",
            "0021, false, false",
            "0085, false, false",
            "00A0, false, false",
            "1680, false, true",
            "2003, false, true",
            "2007, false, false",
            "2028, false, true",
            "2029, false, true",
            "202F, false, false",
            "3000, false, true",
    })
    @DisplayName("each scanner skips a character as whitespace by its rule")
    void scannersSkipByTheirRule(String hex, boolean mvelWhitespace, boolean eitherWhitespace) {
        char c = (char) Integer.parseInt(hex, 16);

        assertAll(
                // Skipped between a dot and a keyword, the keyword is a member's name, not a write.
                () -> assertEquals(eitherWhitespace, ConditionAssignments.find("m." + c + "with == 1") == null),
                // Skipped after an import's keyword, the name is found there, not at the plain import after it.
                () -> assertEquals(mvelWhitespace ? 1 : 2, importLine("import" + c + "p;\nimport p;")));
    }

    private static int importLine(String text) {
        InvalidExpressionException e = MvelCompileErrors.importTooLarge(text, new Imports.ImportTooLarge("p", "'"));
        return e.issues().get(0).line();
    }
}
