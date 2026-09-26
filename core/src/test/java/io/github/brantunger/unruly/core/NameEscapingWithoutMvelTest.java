package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RuleListener;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
import io.github.brantunger.unruly.api.RunContext;
import io.github.brantunger.unruly.api.RunOptions;
import io.github.brantunger.unruly.api.RunResult;
import io.github.brantunger.unruly.api.language.ToyExpressionLanguage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static io.github.brantunger.unruly.TestLogs.logsOf;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The engine escapes the names it puts in its messages whatever language it runs, so a language that depends on the
 * core alone gets the same messages MVEL does. The core has no language of its own, so every engine here names the
 * toy language.
 */
@DisplayName("names in the engine's messages are shortened, then escaped, with a language other than MVEL too")
class NameEscapingWithoutMvelTest {

    private static final String FORGED = "[main] INFO com.example.Audit - forged entry";

    /** A language name an application could give, which would forge a log line if a message put it in raw. */
    private static final String EVIL = "evil\n" + FORGED;

    private static final String RLO = String.valueOf((char) 0x202e);

    /** The exception {@code action} throws, and the engine's log lines while it ran. */
    private record Failure(Throwable thrown, List<String> logLines) {
    }

    private static Failure failure(Executable action) {
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        String logs = logsOf(() -> thrown.set(assertThrows(RuntimeException.class, action)));
        return new Failure(thrown.get(), Arrays.asList(logs.split("\\R")));
    }

    private static void assertNoForgedLine(Failure failure) {
        assertTrue(failure.logLines().stream().noneMatch(line -> line.startsWith("[main] INFO com.example")),
                String.join("\n", failure.logLines()));
    }

    private static Rule rule(String name) {
        return Rule.builder().ruleName(name).condition("true").action("put k 1").build();
    }

    @Test
    @DisplayName("the engine's language names in a rule's load() failure are escaped, and it logs them on one line")
    void languageNamesInLoadFailure() {
        RulesEngine<Map<String, Object>> twoLanguages = RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new)
                .language(new ToyExpressionLanguage()).language(new ToyExpressionLanguage(EVIL))
                .defaultLanguage(ToyExpressionLanguage.LANGUAGE_NAME).build();

        Failure failure = failure(() -> twoLanguages.load(List.of(Rule.builder().ruleName("r").language("nope")
                .condition("true").action("put k 1").build())));

        assertEquals("Rule 'r' is written in 'nope', which isn't one of the engine's expression languages: "
                + "[evil\\n" + FORGED + ", toy]", failure.thrown().getMessage());
        assertNoForgedLine(failure);
    }

    @Test
    @DisplayName("language names are escaped when the engine can't be built")
    void languageNamesWhenBuilding() {
        Supplier<RulesEngineBuilder<Map<String, Object>>> builder = () -> RulesEngineBuilder
                .<Map<String, Object>>allMatches(HashMap::new).language(new ToyExpressionLanguage())
                .language(new ToyExpressionLanguage(EVIL));
        String languages = "[evil\\n" + FORGED + ", toy]";

        assertEquals("The default language 'nope' isn't one of the engine's expression languages: " + languages,
                assertThrows(IllegalStateException.class, () -> builder.get().defaultLanguage("nope").build())
                        .getMessage());
        assertEquals("The engine has several expression languages, " + languages + ", so name the language of rules "
                + "without one with defaultLanguage()",
                assertThrows(IllegalStateException.class, () -> builder.get().build()).getMessage());
        assertEquals("Options are given for the expression language 'nope', which isn't one of the engine's "
                + "expression languages: " + languages, assertThrows(IllegalStateException.class,
                () -> builder.get().defaultLanguage("toy").option("nope", "k", "v").build()).getMessage());
        String twice = assertThrows(IllegalArgumentException.class,
                () -> builder.get().language(new ToyExpressionLanguage(EVIL))).getMessage();
        assertTrue(twice.startsWith("Two expression languages are named 'evil\\n" + FORGED + "': "), twice);
    }

    @Test
    @DisplayName("an invisible Hangul filler in a rule's language name is escaped, so it can't read as another name")
    void hangulFillerInLanguageNameEscaped() {
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new)
                .language(new ToyExpressionLanguage()).build();

        Failure failure = failure(() -> engine.load(List.of(Rule.builder().ruleName("r" + (char) 0x3164)
                .language("toy" + (char) 0x3164).condition("true").action("put k 1").build())));

        assertEquals("Rule 'r\\u3164' is written in 'toy\\u3164', which isn't one of the engine's expression "
                + "languages: [toy]", failure.thrown().getMessage());
    }

    @Test
    @DisplayName("an import that is neither a class nor a package is escaped in its rejection")
    void importNameEscaped() {
        RulesEngineBuilder<Map<String, Object>> builder = RulesEngineBuilder
                .<Map<String, Object>>allMatches(HashMap::new).language(new ToyExpressionLanguage())
                .imports("com.example\n" + FORGED);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, builder::build);

        assertEquals("'com.example\\n" + FORGED + "' is neither a class nor a valid package name", ex.getMessage());
    }

    @Test
    @DisplayName("the names in a unique-match failure are cut at 1,000 characters before they're escaped")
    void uniqueMatchNamesCutBeforeEscaping() {
        RulesEngine<Map<String, Object>> unique = RulesEngineBuilder.<Map<String, Object>>uniqueMatch(HashMap::new)
                .language(new ToyExpressionLanguage()).build();
        String rlo = RLO.repeat(250);
        unique.load(Stream.of("a", "b", "c", "d", "e").map(letter -> rule(letter + rlo)).toList());

        Failure failure = failure(() -> unique.run(new FactMap<>()));

        // Each name is cut to 200 characters, which makes the list 1,138; its first 1,000 end inside the fifth name.
        String rest = "\\u202e".repeat(199) + "... (51 more characters)'";
        assertEquals("5 rules matched, but a unique-match engine allows one: 'a" + rest + ", 'b" + rest + ", 'c" + rest
                + ", 'd" + rest + ", 'e" + "\\u202e".repeat(86) + "... (138 more characters)",
                failure.thrown().getMessage());
    }

    @Test
    @DisplayName("toString() of a result, a rule set, an evaluation and a run's context escapes rule names and tags")
    void toStringsEscapeNamesAndTags() {
        List<String> contexts = new ArrayList<>();
        RulesEngine<Map<String, Object>> tagged = RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new)
                .language(new ToyExpressionLanguage()).listener(new RuleListener() {
                    @Override
                    public void beforeRun(RunContext run) {
                        contexts.add(run.toString());
                    }
                }).build();
        tagged.load(List.of(rule("r\n" + FORGED).toBuilder().tags(List.of("t\n" + FORGED)).build()));

        RunResult<Map<String, Object>> result = tagged.runWithResult(new FactMap<>(),
                RunOptions.defaults().withTags(List.of("t\n" + FORGED)));

        String name = "r\\n" + FORGED;
        String tags = "tags=[t\\n" + FORGED + "]";
        assertTrue(result.toString().contains("firedRules=[" + name + "], evaluations=[" + name + "=MATCHED], "),
                result.toString());
        assertTrue(result.toString().contains(", " + tags + ", "), result.toString());
        assertTrue(tagged.rules().toString().startsWith("RuleSetInfo(rules=[" + name + "], checksum="),
                tagged.rules().toString());
        assertTrue(contexts.get(0).contains(", " + tags + ", "), contexts.get(0));
        for (String text : List.of(result.toString(), tagged.rules().toString(), contexts.get(0))) {
            assertFalse(text.contains("\n"), text);
        }
    }
}
