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
