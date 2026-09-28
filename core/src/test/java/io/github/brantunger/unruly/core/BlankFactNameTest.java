package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.Fact;
import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RuleListener;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
import io.github.brantunger.unruly.api.RunContext;
import io.github.brantunger.unruly.api.RunOptions;
import io.github.brantunger.unruly.api.language.StubExpressionLanguage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

import static io.github.brantunger.unruly.core.EngineLogs.assertLoggedAtError;
import static org.junit.jupiter.api.Assertions.*;

/**
 * A blank fact name is refused by the engine itself, as a blank rule name or tag is, so no language has to reject it,
 * and one that accepts every name, as the default {@code checkFactName} does, can't let it through.
 */
@DisplayName("a blank fact name is rejected at run(), before any language is asked")
class BlankFactNameTest {

    private static final Rule RULE = Rule.builder().ruleName("r").condition("c").action("a").build();

    /** The names the language was asked to check, in order. */
    private final List<String> checked = new ArrayList<>();

    private StatelessRulesEngine<Map<String, Object>> engine(RuleListener listener) {
        StatelessRulesEngine<Map<String, Object>> engine = TestEngines.firstMatch(HashMap::new,
                builder -> builder.language(new StubExpressionLanguage().checkFactName(checked::add))
                        .listener(listener));
        engine.load(List.of(RULE));
        return engine;
    }

    @ParameterizedTest(name = "\"{0}\"")
    @ValueSource(strings = {"", " ", "\t"})
    @DisplayName("a blank name is rejected with IllegalArgumentException, logged at ERROR, and never reaches the"
            + " language")
    void blankNameRejected(String name) {
        StatelessRulesEngine<Map<String, Object>> engine = engine(new RuleListener() {
        });

        IllegalArgumentException ex = assertLoggedAtError(IllegalArgumentException.class,
                () -> engine.run(new FactMap<>(new Fact<>(name, 1))));

        assertEquals("fact name must not be blank", ex.getMessage());
        assertEquals(List.of(), checked);
    }

    @Test
    @DisplayName("a blank name reaches onRunError, after beforeRun, as a null name or output does")
    void blankNameReachesOnRunError() {
        List<String> calls = new ArrayList<>();
        StatelessRulesEngine<Map<String, Object>> engine = engine(new RuleListener() {
            @Override
            public void beforeRun(RunContext run) {
                calls.add("beforeRun");
            }

            @Override
            public void onRunError(RunContext run, RuntimeException error) {
                calls.add("onRunError: " + error.getMessage());
            }
        });

        assertThrows(IllegalArgumentException.class, () -> engine.run(new FactMap<>(new Fact<>(" ", 1))));

        assertEquals(List.of("beforeRun", "onRunError: fact name must not be blank"), calls);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"firstMatch", "allMatches", "uniqueMatch"})
    @DisplayName("each kind of engine rejects a blank name given to runWithResult with run options")
    void blankNameRejectedWithRunOptions(String kind) {
        Function<Supplier<Map<String, Object>>, RulesEngineBuilder<Map<String, Object>>> start = switch (kind) {
            case "firstMatch" -> RulesEngineBuilder::firstMatch;
            case "allMatches" -> RulesEngineBuilder::allMatches;
            default -> RulesEngineBuilder::uniqueMatch;
        };
        try (RulesEngine<Map<String, Object>> engine = start.apply(HashMap::new)
                .language(new StubExpressionLanguage().checkFactName(checked::add)).build()) {
            engine.load(List.of(RULE));

            IllegalArgumentException ex = assertLoggedAtError(IllegalArgumentException.class,
                    () -> engine.runWithResult(new FactMap<>(new Fact<>(" ", 1)),
                            RunOptions.withTimeoutOf(Duration.ofMinutes(1))));

            assertEquals("fact name must not be blank", ex.getMessage());
        }
        assertEquals(List.of(), checked);
    }

    @Test
    @DisplayName("a name that isn't blank is still the language's to check, even one of spaces Java doesn't count as"
            + " whitespace")
    void nonBlankNameReachesTheLanguage() {
        StatelessRulesEngine<Map<String, Object>> engine = engine(new RuleListener() {
        });
        // Character.isWhitespace accepts neither a no-break space nor a zero-width space, so neither is blank.
        List<String> names = List.of(" a ", "\u00A0", "\u200B");

        for (String name : names) {
            assertEquals(Map.of(), engine.run(new FactMap<>(new Fact<>(name, 1))));
        }

        assertEquals(names, checked);
    }
}
