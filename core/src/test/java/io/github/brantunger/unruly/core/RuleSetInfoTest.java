package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RuleSetInfo;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
import io.github.brantunger.unruly.api.language.ToyExpressionLanguage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("an engine reports the rules it has loaded and a checksum that identifies them")
class RuleSetInfoTest {

    private static final Rule HIGH = Rule.builder().ruleName("high").priority(2).condition("true")
            .action("put high 1").build();
    private static final Rule LOW = Rule.builder().ruleName("low").priority(1).condition("true")
            .action("put low 1").build();

    private static RulesEngine<Map<String, Object>> engine() {
        return RulesEngineBuilder.<Map<String, Object>>firstMatch(HashMap::new)
                .language(new ToyExpressionLanguage()).build();
    }

    private static String checksumOf(List<Rule> rules) {
        RulesEngine<Map<String, Object>> engine = engine();
        engine.load(rules);
        return engine.rules().checksum();
    }

    /** An engine with two toy languages, so the same rules can be loaded with either as the default. */
    private static String checksumWithDefault(String defaultLanguage, List<Rule> rules) {
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>firstMatch(HashMap::new)
                .language(new ToyExpressionLanguage()).language(new ToyExpressionLanguage("other"))
                .defaultLanguage(defaultLanguage).build();
        engine.load(rules);
        return engine.rules().checksum();
    }

    @Test
    @DisplayName("before the first load there are no rules, no load time, and the checksum of an empty rule list")
    void beforeTheFirstLoad() {
        RuleSetInfo info = engine().rules();

        assertEquals(List.of(), info.rules());
        assertNull(info.loadedAt());
        assertEquals(checksumOf(List.of()), info.checksum());
    }

    @Test
    @DisplayName("the loaded rules are reported in evaluation order, with the time they were loaded")
    void loadedRules() {
        RulesEngine<Map<String, Object>> engine = engine();
        Instant before = Instant.now();

        engine.load(List.of(LOW, HIGH));
        RuleSetInfo info = engine.rules();
        Instant after = Instant.now();

        assertEquals(List.of(HIGH, LOW), info.rules(), "highest priority first");
        assertNotNull(info.loadedAt());
        assertFalse(info.loadedAt().isBefore(before), info.toString());
        assertFalse(info.loadedAt().isAfter(after), info.toString());
    }

    @Test
    @DisplayName("the same rules always have the same checksum, and changing one changes it")
    void checksumIdentifiesTheRules() {
        assertEquals(checksumOf(List.of(HIGH, LOW)), checksumOf(List.of(HIGH, LOW)));
        assertNotEquals(checksumOf(List.of(HIGH, LOW)),
                checksumOf(List.of(HIGH.toBuilder().condition("false").build(), LOW)));
        assertNotEquals(checksumOf(List.of(HIGH, LOW)),
                checksumOf(List.of(HIGH.toBuilder().priority(9).build(), LOW)));
        assertNotEquals(checksumOf(List.of(HIGH, LOW)),
                checksumOf(List.of(HIGH.toBuilder().action("put high 2").build(), LOW)),
                "a rule whose action alone changed is a different rule list");
        assertNotEquals(checksumOf(List.of(HIGH, LOW)),
                checksumOf(List.of(HIGH.toBuilder().ruleName("other").build(), LOW)),
                "so is one whose name alone changed");
        assertNotEquals(checksumOf(List.of(HIGH, LOW)), checksumOf(List.of(HIGH)));
    }

    @Test
    @DisplayName("a rule's description doesn't change the checksum, because it doesn't change what the rules do")
    void descriptionIgnored() {
        assertEquals(checksumOf(List.of(HIGH)), checksumOf(List.of(HIGH.toBuilder().description("why").build())));
    }

    @Test
    @DisplayName("a rule without a language hashes as the engine's default language")
    void languageResolvedBeforeHashing() {
        Rule withoutLanguage = Rule.builder().ruleName("r").condition("true").action("put k 1").build();
        Rule named = withoutLanguage.toBuilder().language(ToyExpressionLanguage.LANGUAGE_NAME).build();

        assertEquals(checksumWithDefault(ToyExpressionLanguage.LANGUAGE_NAME, List.of(named)),
                checksumWithDefault(ToyExpressionLanguage.LANGUAGE_NAME, List.of(withoutLanguage)),
                "a null language is the default language");
        assertNotEquals(checksumWithDefault("other", List.of(withoutLanguage)),
                checksumWithDefault(ToyExpressionLanguage.LANGUAGE_NAME, List.of(withoutLanguage)),
                "the same rules run by a different default language are a different rule set");
    }

    @Test
    @DisplayName("a closed engine reports no rules")
    void closedEngine() {
        RulesEngine<Map<String, Object>> engine = engine();
        engine.load(List.of(HIGH));
        engine.close();

        IllegalStateException ex = assertThrows(IllegalStateException.class, engine::rules);

        assertEquals("The engine is closed", ex.getMessage());
    }

    @Test
    @DisplayName("the information names its rules in toString")
    void readableToString() {
        RulesEngine<Map<String, Object>> engine = engine();
        engine.load(List.of(HIGH));

        assertTrue(engine.rules().toString().startsWith("RuleSetInfo(rules=[high], checksum="),
                engine.rules().toString());
    }
}
