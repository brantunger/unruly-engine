package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.FactStore;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RuleListener;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
import io.github.brantunger.unruly.api.RunContext;
import io.github.brantunger.unruly.api.RunOptions;
import io.github.brantunger.unruly.api.RunResult;
import io.github.brantunger.unruly.api.exception.RuleCompilationException;
import io.github.brantunger.unruly.api.exception.RuleExecutionException;
import io.github.brantunger.unruly.api.language.CompileContext;
import io.github.brantunger.unruly.api.language.ExpressionCompiler;
import io.github.brantunger.unruly.api.language.ExpressionLanguage;
import io.github.brantunger.unruly.mvel.MvelExpressionLanguage;
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

import static io.github.brantunger.unruly.core.EngineLogs.ENGINE_LOGGER;
import static io.github.brantunger.unruly.TestLogs.logsOf;
import static org.junit.jupiter.api.Assertions.*;

@DisplayName("names in the engine's messages are escaped and shortened, so they can't forge log lines")
class NameEscapingTest {

    private static final String FORGED = "[main] INFO com.example.Audit - forged entry";

    /** A language name an application could give, which would forge a log line if a message put it in raw. */
    private static final String EVIL = "evil\n" + FORGED;

    private static final String RLO = String.valueOf((char) 0x202e);

    private final RulesEngine<Map<String, Object>> engine =
            RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new).build();

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

    /** A language with the given name, which compiles expressions as MVEL does. */
    private static ExpressionLanguage named(String name) {
        ExpressionLanguage mvel = new MvelExpressionLanguage();
        return new ExpressionLanguage() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public ExpressionCompiler newCompiler(CompileContext context) {
                return mvel.newCompiler(context);
            }
        };
    }

    @Test
    @DisplayName("a fact name with \\n or \\r\\n is logged on one line, with the line break escaped")
    void factNameWithLineBreak() {
        engine.load(List.of(Rule.builder().ruleName("r").condition("true").action("x").build()));
        for (String lineBreak : List.of("\n", "\r\n")) {
            FactStore<Object> facts = new FactMap<>();
            facts.setValue("a" + lineBreak + FORGED, 1);

            Failure failure = failure(() -> engine.run(facts));

            String escaped = lineBreak.equals("\n") ? "a\\n" : "a\\r\\n";
            assertEquals("'" + escaped + FORGED + "' is not a valid fact name: rules can only refer to a fact named "
                    + "with a Java identifier", failure.thrown().getMessage());
            assertTrue(failure.logLines().stream()
                            .anyMatch(line -> line.endsWith("ERROR " + ENGINE_LOGGER + failure.thrown().getMessage())),
                    String.join("\n", failure.logLines()));
            assertNoForgedLine(failure);
        }
    }

    @Test
    @DisplayName("a 10,000-character fact name is shortened in the message")
    void longFactName() {
        engine.load(List.of(Rule.builder().ruleName("r").condition("true").action("x").build()));
        FactStore<Object> facts = new FactMap<>();
        facts.setValue("-".repeat(10_000), 1);

        Failure failure = failure(() -> engine.run(facts));

        assertEquals("'" + "-".repeat(Failures.MAX_NAME_LENGTH) + "... (9800 more characters)' is not a valid fact "
                + "name: rules can only refer to a fact named with a Java identifier", failure.thrown().getMessage());
    }

    @Test
    @DisplayName("a rule name with a line break is escaped in run-time and compile-time failures")
    void ruleNameWithLineBreak() {
        engine.load(List.of(Rule.builder().ruleName("bad\n" + FORGED).condition("missing > 1").action("x")
                .build()));
        Failure run = failure(() -> engine.run(new FactMap<>()));
        assertTrue(run.thrown().getMessage().startsWith("Failed to evaluate condition for rule 'bad\\n" + FORGED
                + "': "), run.thrown().getMessage());
        assertNoForgedLine(run);

        Failure duplicate = failure(() -> engine.load(List.of(
                Rule.builder().ruleName("dup\r\n" + FORGED).condition("true").action("x").build(),
                Rule.builder().ruleName("dup\r\n" + FORGED).condition("true").action("x").build())));
        assertInstanceOf(RuleCompilationException.class, duplicate.thrown());
        assertEquals("Duplicate rule name 'dup\\r\\n" + FORGED + "'", duplicate.thrown().getMessage());
        assertNoForgedLine(duplicate);

        Failure blank = failure(() -> engine.load(List.of(
                Rule.builder().ruleName("blank\n" + FORGED).condition(" ").action("x").build())));
        assertEquals("Rule 'blank\\n" + FORGED + "' has a blank condition expression",
                blank.thrown().getMessage());

        Failure language = failure(() -> engine.load(List.of(Rule.builder().ruleName("r")
                .language("lang\n" + FORGED).condition("true").action("x").build())));
        assertTrue(language.thrown().getMessage().startsWith("Rule 'r' is written in 'lang\\n" + FORGED + "', "),
                language.thrown().getMessage());
        assertNoForgedLine(language);
    }

    @Test
    @DisplayName("quote escapes control characters and line separators, and shortens names over the limit")
    void quote() {
        String controls = "a\tb" + (char) 0 + "c" + (char) 0x2028 + "d" + (char) 0x2029 + "e" + (char) 0x7f + "f"
                + (char) 0x85;

        assertEquals("a\\tb\\u0000c\\u2028d\\u2029e\\u007ff\\u0085", Failures.quote(controls));
        assertEquals("plain-name_1 é", Failures.quote("plain-name_1 é"));
        String limit = "x".repeat(Failures.MAX_NAME_LENGTH);
        assertEquals(limit, Failures.quote(limit));
        assertEquals(limit + "... (1 more characters)", Failures.quote(limit + "y"));
    }

    @Test
    @DisplayName("the engine's language names in a rule's load() failure are escaped, and it logs them on one line")
    void languageNamesInLoadFailure() {
        RulesEngine<Map<String, Object>> twoLanguages = RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new)
                .language(new MvelExpressionLanguage()).language(named(EVIL)).defaultLanguage("mvel").build();

        Failure failure = failure(() -> twoLanguages.load(List.of(Rule.builder().ruleName("r").language("nope")
                .condition("true").action("output").build())));

        assertEquals("Rule 'r' is written in 'nope', which isn't one of the engine's expression languages: "
                + "[evil\\n" + FORGED + ", mvel]", failure.thrown().getMessage());
        assertNoForgedLine(failure);
    }

    @Test
    @DisplayName("language names are escaped when the engine can't be built")
    void languageNamesWhenBuilding() {
        Supplier<RulesEngineBuilder<Map<String, Object>>> builder = () -> RulesEngineBuilder
                .<Map<String, Object>>allMatches(HashMap::new).language(new MvelExpressionLanguage())
                .language(named(EVIL));
        String languages = "[evil\\n" + FORGED + ", mvel]";

        assertEquals("The default language 'nope' isn't one of the engine's expression languages: " + languages,
                assertThrows(IllegalStateException.class, () -> builder.get().defaultLanguage("nope").build())
                        .getMessage());
        assertEquals("The engine has several expression languages, " + languages + ", so name the language of rules "
                + "without one with defaultLanguage()",
                assertThrows(IllegalStateException.class, () -> builder.get().build()).getMessage());
        assertEquals("Options are given for the expression language 'nope', which isn't one of the engine's "
                + "expression languages: " + languages, assertThrows(IllegalStateException.class,
                () -> builder.get().defaultLanguage("mvel").option("nope", "k", "v").build()).getMessage());
        String twice = assertThrows(IllegalArgumentException.class, () -> builder.get().language(named(EVIL)))
                .getMessage();
        assertTrue(twice.startsWith("Two expression languages are named 'evil\\n" + FORGED + "': "), twice);
    }

    @Test
    @DisplayName("an import that is neither a class nor a package is escaped in its rejection")
    void importNameEscaped() {
        RulesEngineBuilder<Map<String, Object>> builder = RulesEngineBuilder
                .<Map<String, Object>>allMatches(HashMap::new).imports("com.example\n" + FORGED);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, builder::build);

        assertEquals("'com.example\\n" + FORGED + "' is neither a class nor a valid package name", ex.getMessage());
    }

    @Test
    @DisplayName("a lone surrogate in a fact name is escaped, so the log can't show it as ? and differ from it")
    void loneSurrogateInFactName() {
        engine.load(List.of(Rule.builder().ruleName("r").condition("true").action("output").build()));
        FactStore<Object> facts = new FactMap<>();
        facts.setValue("a" + (char) 0xd800, 1);

        Failure failure = failure(() -> engine.run(facts));

        assertEquals("'a\\ud800' is not a valid fact name: rules can only refer to a fact named with a Java "
                + "identifier", failure.thrown().getMessage());
    }

    @Test
    @DisplayName("the names in a unique-match failure are cut at 1,000 characters before they're escaped")
    void uniqueMatchNamesCutBeforeEscaping() {
        RulesEngine<Map<String, Object>> unique = RulesEngineBuilder.<Map<String, Object>>uniqueMatch(HashMap::new)
                .build();
        String rlo = RLO.repeat(250);
        unique.load(Stream.of("a", "b", "c", "d", "e")
                .map(letter -> Rule.builder().ruleName(letter + rlo).condition("true").action("output").build())
                .toList());

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
                .listener(new RuleListener() {
                    @Override
                    public void beforeRun(RunContext run) {
                        contexts.add(run.toString());
                    }
                }).build();
        tagged.load(List.of(Rule.builder().ruleName("r\n" + FORGED).condition("true").action("output")
                .tags(List.of("t\n" + FORGED)).build()));

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

    @Test
    @DisplayName("the rule name on the exception stays unescaped")
    void exceptionKeepsRawName() {
        engine.load(List.of(Rule.builder().ruleName("raw\nname").condition("missing > 1").action("x").build()));

        Failure failure = failure(() -> engine.run(new FactMap<>()));

        assertEquals("raw\nname", assertInstanceOf(RuleExecutionException.class, failure.thrown()).getRuleName());
    }
}
