package io.github.brantunger.unruly.mvel;

import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.FactStore;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
import io.github.brantunger.unruly.api.exception.RuleCompilationException;
import io.github.brantunger.unruly.api.language.ToyExpressionLanguage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MVEL's imports are Java packages and classes, from {@code imports(...)}. Imports given to it alone, with
 * {@code languageImports(...)}, are a mistake, so it fails to create a compiler rather than ignore them.
 */
@DisplayName("MVEL rejects imports given to it alone, and points to imports(...)")
class MvelLanguageImportsTest {

    private static final String REJECTED = "The 'mvel' expression language failed to create a compiler: MVEL takes "
            + "no language imports; give Java imports with imports(...): ";

    private static final Rule MVEL_RULE = Rule.builder().ruleName("mvel-rule").language("mvel")
            .condition("x == 1").action("output.put('seen', x)").build();

    private static FactStore<Object> xIsOne() {
        FactStore<Object> facts = new FactMap<>();
        facts.setValue("x", 1);
        return facts;
    }

    private static RulesEngineBuilder<Map<String, Object>> builder() {
        return RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new)
                .language(new MvelExpressionLanguage()).language(new ToyExpressionLanguage()).defaultLanguage("mvel");
    }

    @Test
    @DisplayName("load() of a rule list with an MVEL rule fails, naming the imports")
    void loadFails() {
        RulesEngine<Map<String, Object>> engine = builder().languageImports("mvel", "lodash", "os.path").build();

        RuleCompilationException thrown = assertThrows(RuleCompilationException.class,
                () -> engine.load(List.of(MVEL_RULE)));

        assertEquals(REJECTED + "'lodash', 'os.path'", thrown.getMessage());
        assertInstanceOf(IllegalArgumentException.class, thrown.getCause());
    }

    @Test
    @DisplayName("validate() of a rule list with an MVEL rule returns the same failure")
    void validateReports() {
        RulesEngine<Map<String, Object>> engine = builder().languageImports("mvel", List.of("lodash/fp")).build();

        List<RuleCompilationException> problems = engine.validate(List.of(MVEL_RULE));

        assertEquals(1, problems.size(), problems.toString());
        assertEquals(REJECTED + "'lodash/fp'", problems.get(0).getMessage());
    }

    @Test
    @DisplayName("with MVEL the default language, load() of a list with no rules fails too: its compiler is created")
    void emptyListFailsForTheDefaultLanguage() {
        RulesEngine<Map<String, Object>> engine = builder().languageImports("mvel", "lodash").build();

        RuleCompilationException thrown = assertThrows(RuleCompilationException.class, () -> engine.load(List.of()));

        assertEquals(REJECTED + "'lodash'", thrown.getMessage());
        assertInstanceOf(IllegalArgumentException.class, thrown.getCause());
    }

    @Test
    @DisplayName("a list of only another language's rules loads: MVEL's compiler isn't created, so its imports aren't"
            + " checked")
    void listWithoutMvelRulesLoads() {
        RulesEngine<Map<String, Object>> engine = builder().languageImports("mvel", "lodash").build();

        engine.load(List.of(Rule.builder().ruleName("toy-rule").language(ToyExpressionLanguage.LANGUAGE_NAME)
                .condition("true").action("put k 1").build()));

        assertEquals(1, engine.run(xIsOne()).get("k"));
    }

    @Test
    @DisplayName("an import's name is escaped and cut to 200 characters in the message")
    void namesEscapedAndShortened() {
        RulesEngine<Map<String, Object>> engine = builder()
                .languageImports("mvel", "a\nb", "m".repeat(300)).build();

        String message = assertThrows(RuleCompilationException.class, () -> engine.load(List.of(MVEL_RULE)))
                .getMessage();

        assertEquals(REJECTED + "'a\\nb', '" + "m".repeat(200) + "... (100 more characters)'", message);
    }

    // #913: 8 names of 60 zero-width spaces each quote to 363 characters, so all of them took 2,987, and the engine cut
    // the message at 1,000, inside an escape, counting escaped characters.
    @Test
    @DisplayName("the imports listed fit in 1,000 characters, whole, and the message says how many more there are")
    void manyImportsFit() {
        String name = "a" + "​".repeat(60);
        RulesEngine<Map<String, Object>> engine = builder().languageImports("mvel", List.of(name, name, name, name,
                name, name, name, name)).build();

        RuleCompilationException thrown = assertThrows(RuleCompilationException.class,
                () -> engine.load(List.of(MVEL_RULE)));

        String quoted = "'a" + "\\u200b".repeat(60) + "'";
        String cause = thrown.getCause().getMessage();
        assertTrue(cause.length() <= FactNames.MAX_DESCRIPTION_LENGTH, cause.length() + ": " + cause);
        assertEquals(REJECTED + quoted + ", " + quoted + ", and 6 more", thrown.getMessage());
    }

    // The message before the names is 69 characters, which leaves 931 for them: four names of 200 characters quote to
    // 814 with the commas between them, and a last of 113 to the 117 left, with its comma and quotes.
    @Test
    @DisplayName("#1019: names that fill the 1,000 characters exactly are all listed")
    void namesThatFitExactlyAllListed() {
        String name = "m".repeat(200);
        String last = "m".repeat(113);
        RulesEngine<Map<String, Object>> engine = builder().languageImports("mvel", name, name, name, name, last)
                .build();

        RuleCompilationException thrown = assertThrows(RuleCompilationException.class,
                () -> engine.load(List.of(MVEL_RULE)));

        String quoted = "'" + name + "'";
        String cause = thrown.getCause().getMessage();
        assertEquals(FactNames.MAX_DESCRIPTION_LENGTH, cause.length(), cause);
        assertEquals(REJECTED + String.join(", ", quoted, quoted, quoted, quoted, "'" + last + "'"),
                thrown.getMessage());
    }

    // A name is listed only when the count of the names after it fits too: a fifth name of 110 characters fits on its
    // own, but not with ", and 1 more" after it.
    @Test
    @DisplayName("a name is listed only if the count of those left out after it fits as well")
    void listedNameLeavesRoomForCount() {
        String name = "m".repeat(200);
        RulesEngine<Map<String, Object>> engine = builder().languageImports("mvel", name, name, name, name,
                "m".repeat(110), "x").build();

        RuleCompilationException thrown = assertThrows(RuleCompilationException.class,
                () -> engine.load(List.of(MVEL_RULE)));

        String quoted = "'" + name + "'";
        String cause = thrown.getCause().getMessage();
        assertTrue(cause.length() <= FactNames.MAX_DESCRIPTION_LENGTH, cause.length() + ": " + cause);
        assertEquals(REJECTED + String.join(", ", quoted, quoted, quoted, quoted) + ", and 2 more",
                thrown.getMessage());
    }

    // The first name is listed whatever its length, within the room the rest leaves: a name of 199 control characters
    // and 800 more, the longest an import may be, quoted to 1,220 characters, which the engine cut inside an escape.
    @Test
    @DisplayName("a first import too long to list whole is cut between escapes, and the count counts its characters")
    void longFirstImportCut() {
        String name = "a" + String.valueOf((char) 1).repeat(199) + "b".repeat(800);
        RulesEngine<Map<String, Object>> engine = builder().languageImports("mvel", name, "lodash").build();

        RuleCompilationException thrown = assertThrows(RuleCompilationException.class,
                () -> engine.load(List.of(MVEL_RULE)));

        String cause = thrown.getCause().getMessage();
        assertTrue(cause.length() <= FactNames.MAX_DESCRIPTION_LENGTH, cause.length() + ": " + cause);
        assertTrue(thrown.getMessage().endsWith("failed to create a compiler: " + cause), thrown.getMessage());
        Matcher listed = Pattern.compile(Pattern.quote(REJECTED.substring(REJECTED.indexOf("MVEL takes")))
                + "'a((?:\\\\u0001)+)\\.\\.\\. \\((\\d+) more characters\\)', and 1 more").matcher(cause);
        assertTrue(listed.matches(), cause);
        int shown = 1 + listed.group(1).length() / "\\u0001".length();
        assertEquals(name.length() - shown, Integer.parseInt(listed.group(2)), cause);
    }

    @Test
    @DisplayName("MVEL given no language imports loads and runs with its Java imports as before")
    void unaffectedWithoutThem() {
        RulesEngine<Map<String, Object>> engine = builder().imports("java.util").build();
        engine.load(List.of(Rule.builder().ruleName("r").condition("x == 1")
                .action("output.put('size', new ArrayList().size())").build()));

        assertEquals(Map.of("size", 0), engine.run(xIsOne()));
    }

    @Test
    @DisplayName("another language's imports on the same engine don't affect MVEL")
    void otherLanguagesImportsIgnored() {
        RulesEngine<Map<String, Object>> engine = builder().languageImports(ToyExpressionLanguage.LANGUAGE_NAME,
                "lodash/fp", "@acme/pricing").build();
        engine.load(List.of(MVEL_RULE, Rule.builder().ruleName("toy-rule").language(ToyExpressionLanguage.LANGUAGE_NAME)
                .condition("true").action("put k 1").build()));

        assertEquals(1, engine.run(xIsOne()).get("seen"));
    }
}
