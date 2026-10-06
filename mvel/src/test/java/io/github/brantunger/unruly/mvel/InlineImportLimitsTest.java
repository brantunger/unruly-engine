package io.github.brantunger.unruly.mvel;

import io.github.brantunger.unruly.api.Fact;
import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
import io.github.brantunger.unruly.api.exception.InvalidExpressionException;
import io.github.brantunger.unruly.api.exception.RuleCompilationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("an import in a rule's own text is bounded as an engine import is, before MVEL looks anything up (#678)")
class InlineImportLimitsTest {

    // A package name of 2,000 parts, for which MVEL looked each name a rule uses up once per dot. Its length is
    // checked first.
    private static final String LONG_PACKAGE = "a.".repeat(1999) + "a";
    private static final String LONG_MESSAGE = "Can't import '" + "a.".repeat(100) + "... (3799 more characters)': "
            + "it has 3999 characters, and an import may have at most 1000";

    private static Rule rule(String condition, String action) {
        return Rule.builder().ruleName("r").condition(condition).action(action).build();
    }

    // The engine takes its class loader for the rules' classes from the thread that loads them.
    private static void load(RecordingClassLoader loader, RulesEngine<Map<String, Object>> engine, Rule rule) {
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        thread.setContextClassLoader(loader);
        try {
            engine.load(List.of(rule));
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    // Every import in these tests is longer than 100 characters, and no class the engine loads is.
    private static long importLookups(RecordingClassLoader loader) {
        return loader.loadedClasses.stream().filter(name -> name.length() > 100).count();
    }

    private static RulesEngine<Map<String, Object>> engine() {
        return RulesEngineBuilder.<Map<String, Object>>firstMatch(HashMap::new).build();
    }

    private static void assertRejected(Rule rule, int line, int column, String description) {
        RecordingClassLoader loader = new RecordingClassLoader();
        RulesEngine<Map<String, Object>> engine = engine();

        RuleCompilationException ex = assertThrows(RuleCompilationException.class, () -> load(loader, engine, rule));

        assertAll(
                () -> assertEquals(List.of(new InvalidExpressionException.Issue(
                        InvalidExpressionException.Issue.Severity.ERROR, line, column, description)), ex.issues()),
                () -> assertTrue(ex.getMessage().endsWith(" failed to compile at line " + line + ", column " + column
                        + ": " + description), ex.getMessage()),
                () -> assertEquals(0, importLookups(loader), "class lookups of the import"));
    }

    @Test
    @DisplayName("a long package import in a condition is rejected at the name, with no lookup")
    void conditionImportRejected() {
        String condition = "import " + LONG_PACKAGE + ".*; v0 == 1 && v1 == 1 && v2 == 1";

        assertRejected(rule(condition, "true"), 1, 8, LONG_MESSAGE);
    }

    @Test
    @DisplayName("a long package import in an action is rejected at the name, with no lookup")
    void actionImportRejected() {
        String action = "x = 1;\n  import " + LONG_PACKAGE + ".*; x = v0; y = v1; z = v2;";

        assertRejected(rule("true", action), 2, 10, LONG_MESSAGE);
    }

    @Test
    @DisplayName("a long package import in a block is rejected too")
    void importInBlockRejected() {
        String action = "if (true) {\n    import " + LONG_PACKAGE + ".*; x = v0;\n}";

        assertRejected(rule("true", action), 2, 12, LONG_MESSAGE);
    }

    @Test
    @DisplayName("a package import of 1,001 characters is rejected, its name shortened")
    void tooLongImportRejected() {
        String name = "a".repeat(1001);

        assertRejected(rule("import " + name + ".*; v0 == 1", "true"), 1, 8, "Can't import '" + "a".repeat(200)
                + "... (801 more characters)': it has 1001 characters, and an import may have at most 1000");
    }

    @Test
    @DisplayName("a package import of 65 dot-separated parts is rejected")
    void tooManyPartsRejected() {
        String name = "a.".repeat(64) + "a";

        assertRejected(rule("true", "import " + name + ".*; x = v0;"), 1, 8, "Can't import '" + name + "': it has 65"
                + " dot-separated parts, and an import may have at most 64");
    }

    @Test
    @DisplayName("the position is the import's, not that of the same text in a string before it")
    void positionAfterStringWithName() {
        String name = "a.".repeat(64) + "a";

        assertRejected(rule("true", "s = '" + name + "';\nimport " + name + ".*; x = v0;"), 2, 8, "Can't import '"
                + name + "': it has 65 dot-separated parts, and an import may have at most 64");
    }

    @Test
    @DisplayName("the position is the import's, not that of the same text in a comment before it")
    void positionAfterCommentWithName() {
        String name = "a.".repeat(64) + "a";

        assertRejected(rule("true", "// " + name + "\nimport " + name + ".*; x = v0;"), 2, 8, "Can't import '"
                + name + "': it has 65 dot-separated parts, and an import may have at most 64");
    }

    // #1048: no other text starts with a line break, so a count that skipped the first character passed.
    @Test
    @DisplayName("the position counts a line break the text starts with")
    void positionAfterLeadingLineBreak() {
        String name = "a.".repeat(64) + "a";

        assertRejected(rule("true", "\nimport " + name + ".*; x = v0;"), 2, 8, "Can't import '" + name
                + "': it has 65 dot-separated parts, and an import may have at most 64");
    }

    // #722: the position was the string's, at line 1, column 13.
    @Test
    @DisplayName("the position is the import's, not that of the same import in a string before it")
    void positionAfterStringWithImport() {
        String name = "a.".repeat(64) + "a";

        assertRejected(rule("true", "s = 'import " + name + "';\nimport " + name + ".*; x = v0;"), 2, 8,
                "Can't import '" + name + "': it has 65 dot-separated parts, and an import may have at most 64");
    }

    // #722: the position was the comment's, at line 1, column 11.
    @Test
    @DisplayName("the position is the import's, not that of the same import in a comment before it")
    void positionAfterCommentWithImport() {
        String name = "a.".repeat(64) + "a";

        assertRejected(rule("true", "// import " + name + "\nimport " + name + ".*; x = v0;"), 2, 8,
                "Can't import '" + name + "': it has 65 dot-separated parts, and an import may have at most 64");
    }

    // #722: the position was the comment's, at line 1, column 11.
    @Test
    @DisplayName("the position is the import's, not that of the same import in a block comment or a division before it")
    void positionAfterBlockCommentWithImport() {
        String name = "a.".repeat(64) + "a";

        assertRejected(rule("true", "/* import " + name + " */ x = 4 / 2;\nimport " + name + ".*; x = v0;"), 2, 8,
                "Can't import '" + name + "': it has 65 dot-separated parts, and an import may have at most 64");
    }

    // The scan read /*/ as the start of a comment that never ends, which MVEL doesn't: the load failed with "Index 150
    // out of bounds for length 150", with no issue.
    @Test
    @DisplayName("the position is the import's after /*/, which MVEL reads as a whole comment")
    void positionAfterSlashStarSlash() {
        String name = "a.".repeat(64) + "a";

        assertRejected(rule("true", "/*/ import " + name + ".*; x = 1;"), 1, 12, "Can't import '" + name
                + "': it has 65 dot-separated parts, and an import may have at most 64");
    }

    @Test
    @DisplayName("the position is the import's after /*/, with a comment after the import")
    void positionAfterSlashStarSlashWithCommentAfter() {
        String name = "a.".repeat(64) + "a";

        assertRejected(rule("true", "/*/ import " + name + ".*; x = 1; /* c */"), 1, 12, "Can't import '" + name
                + "': it has 65 dot-separated parts, and an import may have at most 64");
    }

    // #747: the scan read /*/ as a comment that never ends and found no import after the string, so the first import
    // in the text was taken, the one in the string, at line 1, column 13.
    @Test
    @DisplayName("the position is the import's after a string and /*/, not the one in the string")
    void positionAfterStringAndSlashStarSlash() {
        String name = "a.".repeat(64) + "a";

        assertRejected(rule("true", "s = 'import " + name + ".*'; /*/ import " + name + ".*; x = 1;"), 1, 158,
                "Can't import '" + name + "': it has 65 dot-separated parts, and an import may have at most 64");
    }

    // While the scan read /*/ as a comment, searching from where the last character was skipped found no import:
    // NoSuchElementException.
    @Test
    @DisplayName("the position is the import's after /*/, with code after a comment after it")
    void positionAfterSlashStarSlashWithCodeAfter() {
        String name = "a.".repeat(64) + "a";

        assertRejected(rule("true", "/*/ import " + name + ".*; x = 1; /* c */ y = 2;"), 1, 12, "Can't import '"
                + name + "': it has 65 dot-separated parts, and an import may have at most 64");
    }

    // While the scan read /*/ as a comment, searching from where the last character was skipped found the name in the
    // string after the import.
    @Test
    @DisplayName("the position is the import's after /*/, before a string with the same import")
    void positionAfterSlashStarSlashBeforeString() {
        String name = "a.".repeat(64) + "a";

        assertRejected(rule("true", "/*/ import " + name + ".*; /* c */ s = 'import " + name + "'"), 1, 12,
                "Can't import '" + name + "': it has 65 dot-separated parts, and an import may have at most 64");
    }

    // #704: the name's escapes made the message 1,335 characters, which the engine cut again, inside an escape.
    @Test
    @DisplayName("a long name that escapes to more than the message has room for is shortened within it")
    void escapedNameShortenedWithinTheMessage() {
        String name = "a" + "\u200b".repeat(1_100);
        RulesEngine<Map<String, Object>> engine = engine();

        RuleCompilationException ex = assertThrows(RuleCompilationException.class,
                () -> load(new RecordingClassLoader(), engine, rule("true", "import " + name + ".*; x = 1;")));

        // 1,000 characters, less the 39 of "failed to compile at line 1, column 8: " and the 76 of the rest of the
        // description, holds the first character, 143 of the others, each escaped as 6, and the count of the other 957.
        String description = "Can't import 'a" + "\\u200b".repeat(143) + "... (957 more characters)': it has 1101 "
                + "characters, and an import may have at most 1000";
        assertEquals(List.of(new InvalidExpressionException.Issue(InvalidExpressionException.Issue.Severity.ERROR, 1,
                8, description)), ex.issues());
        assertEquals("Action for rule 'r' failed to compile at line 1, column 8: " + description, ex.getMessage());
        assertEquals(999, ex.getCause().getMessage().length());
    }

    @Test
    @DisplayName("a package import with exactly 64 parts is still imported, and the rule runs")
    void sixtyFourPartsAccepted() {
        RecordingClassLoader loader = new RecordingClassLoader();
        RulesEngine<Map<String, Object>> engine = engine();

        load(loader, engine, rule("import " + "a.".repeat(63) + "a.*; v0 == 1", "output.put('hit', true)"));

        assertEquals(Map.of("hit", true), engine.run(new FactMap<>(new Fact<>("v0", 1))));
        assertTrue(importLookups(loader) > 0, "MVEL looks v0 up in the imported package");
    }

    @Test
    @DisplayName("a package import of exactly 1,000 characters is still imported, and the rule runs")
    void thousandCharactersAccepted() {
        RecordingClassLoader loader = new RecordingClassLoader();
        RulesEngine<Map<String, Object>> engine = engine();

        load(loader, engine, rule("import " + "a".repeat(1000) + ".*; v0 == 1", "output.put('hit', true)"));

        assertEquals(Map.of("hit", true), engine.run(new FactMap<>(new Fact<>("v0", 1))));
        assertTrue(importLookups(loader) > 0, "MVEL looks v0 up in the imported package");
    }
}
