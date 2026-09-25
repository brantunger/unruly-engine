package io.github.brantunger.unruly.mvel;

import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.FactStore;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
import io.github.brantunger.unruly.api.exception.RuleCompilationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.*;

/**
 * With its {@code strongTyping} option on, MVEL compiles against the declared facts, so a misspelled property or an
 * unknown fact fails {@code load()} instead of a run. It's an option because strong typing also rejects rules that
 * work without it (#362), and it fails loading when it can't apply: MVEL's strict mode rejects property access on a
 * {@code Map}, a {@code Collection} or an {@code Object}, so one dynamic declaration would turn working rules into
 * compile errors.
 */
@DisplayName("with strongTyping on, MVEL compiles against declared facts, so a typo fails when the rules load")
class MvelStrongTypingTest {

    /** An applicant as a record, whose components MVEL reads as properties. */
    public record Applicant(int creditScore, String name) {
    }

    /** An output object with a real property, so an action can be type-checked. */
    public static final class Decision {

        private int score;

        public int getScore() {
            return score;
        }

        public void setScore(int score) {
            this.score = score;
        }
    }

    private static Rule rule(String condition, String action) {
        return Rule.builder().ruleName("r").condition(condition).action(action).build();
    }

    /** A builder with everything strong typing needs: the option, declared facts, an output type, a complete list. */
    private static RulesEngineBuilder<Decision> strongTyping(UnaryOperator<RulesEngineBuilder<Decision>> extra) {
        return extra.apply(RulesEngineBuilder.allMatches(Decision::new)
                .outputType(Decision.class)
                .fact("applicant", Applicant.class)
                .requireDeclaredFacts()
                .option("mvel", "strongTyping", "true"));
    }

    private static RulesEngine<Decision> typedEngine() {
        return strongTyping(UnaryOperator.identity()).build();
    }

    private static FactStore<Object> applicant() {
        FactStore<Object> facts = new FactMap<>();
        facts.setValue("applicant", new Applicant(760, "Alex"));
        return facts;
    }

    private static void assertCantApply(RulesEngineBuilder<?> builder, String action, String because) {
        RuleCompilationException thrown = assertThrows(RuleCompilationException.class,
                () -> builder.build().load(List.of(rule("true", action))));
        assertTrue(thrown.getMessage().contains("MVEL's strongTyping option is on, but strong typing can't apply, "
                + "because " + because), thrown.getMessage());
    }

    @Test
    @DisplayName("a misspelled property of a declared fact fails load(), with the line and column")
    void misspelledPropertyFailsLoading() {
        RulesEngine<Decision> engine = typedEngine();

        RuleCompilationException thrown = assertThrows(RuleCompilationException.class,
                () -> engine.load(List.of(rule("applicant.creditScor >= 750", "output.score = 1"))));

        assertTrue(thrown.getMessage().contains("creditScor"), thrown.getMessage());
    }

    @Test
    @DisplayName("a fact nobody declared fails load()")
    void unknownFactFailsLoading() {
        RulesEngine<Decision> engine = typedEngine();

        RuleCompilationException thrown = assertThrows(RuleCompilationException.class,
                () -> engine.load(List.of(rule("loan.amount > 5", "output.score = 1"))));

        assertTrue(thrown.getMessage().contains("loan"), thrown.getMessage());
    }

    @Test
    @DisplayName("a misspelled property of the output fails load() too")
    void misspelledOutputPropertyFailsLoading() {
        RulesEngine<Decision> engine = typedEngine();

        RuleCompilationException thrown = assertThrows(RuleCompilationException.class,
                () -> engine.load(List.of(rule("applicant.creditScore >= 750", "output.scor = 1"))));

        assertTrue(thrown.getMessage().contains("scor"), thrown.getMessage());
    }

    @Test
    @DisplayName("correct rules still load and run, reading components and calling methods")
    void correctRulesStillRun() {
        RulesEngine<Decision> engine = typedEngine();
        engine.load(List.of(rule("applicant.creditScore >= 750 && applicant.name.length() > 2",
                "output.score = applicant.creditScore")));

        assertEquals(760, engine.run(applicant()).getScore());
    }

    @Test
    @DisplayName("a fact declared long is compiled as a Long, and a run's Integer is widened before MVEL sees it")
    void aDeclaredLongIsWidenedFromAnInteger() {
        RulesEngine<Decision> engine = strongTyping(builder -> builder.fact("limit", long.class)).build();
        // MVEL compiles limit.compareTo(4L) as Long.compareTo, which fails to cast an Integer that wasn't widened.
        engine.load(List.of(rule("limit * 1000000000L > 4000000000L",
                "output.score = limit.compareTo(4L) + limit.intValue()")));
        FactStore<Object> facts = applicant();
        facts.setValue("limit", 5);

        assertEquals(6, engine.run(facts).getScore());
    }

    @Test
    @DisplayName("every compiled copy is type-checked, so a second concurrent run behaves like the first")
    void everyCopyIsTypeChecked() throws Exception {
        RulesEngine<Decision> engine = typedEngine();
        engine.load(List.of(rule("applicant.creditScore >= 750", "output.score = applicant.creditScore")));
        ExecutorService threads = Executors.newFixedThreadPool(2);

        Future<Decision> first = threads.submit(() -> engine.run(applicant()));
        Future<Decision> second = threads.submit(() -> engine.run(applicant()));

        assertEquals(760, first.get(30, TimeUnit.SECONDS).getScore());
        assertEquals(760, second.get(30, TimeUnit.SECONDS).getScore());
        threads.shutdownNow();
    }

    @Test
    @DisplayName("the strong-typing forms docs/languages/mvel.md suggests load and run")
    void documentedFormsWork() {
        RulesEngine<Decision> engine = typedEngine();
        engine.load(List.of(
                Rule.builder().ruleName("loop").priority(2).condition("true")
                        .action("total = 0; foreach (int n : [1, 2, 3]) { total += n }; output.score = total")
                        .build(),
                Rule.builder().ruleName("map").priority(1).condition("true")
                        .action("m = ['a': 1]; output.score = output.score + m['a']").build()));

        assertEquals(7, engine.run(applicant()).getScore());
    }

    @Test
    @DisplayName("an array of records is type-checked")
    void anArrayOfRecordsIsChecked() {
        RulesEngine<Decision> engine = strongTyping(builder -> builder.fact("others", Applicant[].class)).build();

        assertThrows(RuleCompilationException.class,
                () -> engine.load(List.of(rule("others[0].creditScor >= 750", "output.score = 1"))));
    }

    @Test
    @DisplayName("without the strongTyping option, rules load as MVEL compiles them, even with every fact declared")
    void strongTypingIsOffByDefault() {
        RulesEngine<Decision> engine = RulesEngineBuilder.allMatches(Decision::new)
                .outputType(Decision.class).fact("applicant", Applicant.class).requireDeclaredFacts().build();

        // An untyped foreach variable and a def function, which strong typing rejects, and a typo it would catch.
        assertDoesNotThrow(() -> engine.load(List.of(
                rule("applicant.creditScor >= 750", "output.score = 1"),
                Rule.builder().ruleName("loop").condition("true")
                        .action("total = 0; foreach (n : [1, 2, 3]) { total += n }; output.score = total").build(),
                Rule.builder().ruleName("def").condition("true")
                        .action("def bonus(s) { s / 100 }; output.score = bonus(applicant.creditScore)").build())));
    }

    @Test
    @DisplayName("strongTyping=false is the same as leaving the option out")
    void strongTypingFalse() {
        RulesEngine<Decision> engine = strongTyping(builder -> builder.option("mvel", "strongTyping", "false")).build();

        assertDoesNotThrow(() -> engine.load(List.of(rule("applicant.creditScor >= 750", "output.score = 1"))));
    }

    @Test
    @DisplayName("with strongTyping on, a fact declared as a Map fails load(): MVEL can't check a map's members")
    void aMapFactFailsLoading() {
        assertCantApply(strongTyping(builder -> builder.fact("order", HashMap.class)), "output.score = 1",
                "fact 'order' is declared as java.util.HashMap, whose members MVEL can't check");
    }

    @Test
    @DisplayName("with strongTyping on, a fact declared as Object fails load()")
    void anObjectFactFailsLoading() {
        assertCantApply(strongTyping(builder -> builder.fact("thing", Object.class)), "output.score = 1",
                "fact 'thing' is declared as java.lang.Object");
    }

    @Test
    @DisplayName("with strongTyping on, a fact declared as a List fails load(): MVEL has no type for its elements")
    void aListFactFailsLoading() {
        assertCantApply(strongTyping(builder -> builder.fact("items", List.class)), "output.score = 1",
                "fact 'items' is declared as java.util.List");
    }

    @Test
    @DisplayName("with strongTyping on, a fact declared as an array of Object fails load()")
    void anObjectArrayFactFailsLoading() {
        assertCantApply(strongTyping(builder -> builder.fact("things", Object[].class)), "output.score = 1",
                "fact 'things' is declared as [Ljava.lang.Object;");
    }

    @Test
    @DisplayName("with strongTyping on, no output type fails load(): an action writes to the output")
    void noOutputTypeFailsLoading() {
        assertCantApply(RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new)
                        .fact("applicant", Applicant.class).requireDeclaredFacts()
                        .option("mvel", "strongTyping", "true"), "output.put('k', 1)",
                "the output type is java.lang.Object");
    }

    @Test
    @DisplayName("with strongTyping on, leaving out requireDeclaredFacts fails load(): a run may supply other facts")
    void withoutRequiringDeclaredFactsFailsLoading() {
        assertCantApply(RulesEngineBuilder.allMatches(Decision::new).outputType(Decision.class)
                        .fact("applicant", Applicant.class).option("mvel", "strongTyping", "true"), "output.score = 1",
                "the engine wasn't built with requireDeclaredFacts()");
    }

    @Test
    @DisplayName("with strongTyping on, declaring no fact fails load()")
    void noDeclarationsFailsLoading() {
        assertCantApply(RulesEngineBuilder.allMatches(Decision::new).outputType(Decision.class)
                        .requireDeclaredFacts().option("mvel", "strongTyping", "true"), "output.score = 1",
                "no facts were declared");
    }

    @Test
    @DisplayName("a strongTyping value other than true or false fails load(), so a typo doesn't leave it off")
    void aBadValueFailsLoading() {
        RulesEngine<Decision> engine = strongTyping(builder -> builder.option("mvel", "strongTyping", "yes")).build();

        RuleCompilationException thrown = assertThrows(RuleCompilationException.class,
                () -> engine.load(List.of(rule("true", "output.score = 1"))));

        assertTrue(thrown.getMessage().contains("MVEL's strongTyping option must be true or false, but was 'yes'"),
                thrown.getMessage());
    }

    @Test
    @DisplayName("a declared fact's name, and an option's name and value, are escaped and shortened in their messages")
    void namesAndValuesEscaped() {
        String zeroWidthSpace = String.valueOf((char) 0x200b);
        assertCantApply(strongTyping(builder -> builder.fact("or" + zeroWidthSpace + "der", HashMap.class)),
                "output.score = 1", "fact 'or\\u200bder' is declared as java.util.HashMap, whose members MVEL can't "
                        + "check");
        RulesEngine<Decision> badKey = strongTyping(builder -> builder.option("mvel", "strong\nTyping", "true"))
                .build();
        RulesEngine<Decision> badValue = strongTyping(builder -> builder.option("mvel", "strongTyping",
                "yes\n" + "!".repeat(300))).build();

        String key = assertThrows(RuleCompilationException.class,
                () -> badKey.load(List.of(rule("true", "output.score = 1")))).getMessage();
        String value = assertThrows(RuleCompilationException.class,
                () -> badValue.load(List.of(rule("true", "output.score = 1")))).getMessage();

        assertTrue(key.contains("MVEL has no option 'strong\\nTyping'; its only option is strongTyping"), key);
        // A value is shown as a name is, so a long one is cut to 200 characters.
        assertTrue(value.contains("MVEL's strongTyping option must be true or false, but was 'yes\\n" + "!".repeat(196)
                + "... (104 more characters)'"), value);
    }

    @Test
    @DisplayName("an option MVEL doesn't have fails load(), so a misspelled key isn't silently ignored")
    void anUnknownOptionFailsLoading() {
        RulesEngine<Decision> engine = strongTyping(builder -> builder.option("mvel", "strongTypng", "true")).build();

        RuleCompilationException thrown = assertThrows(RuleCompilationException.class,
                () -> engine.load(List.of(rule("true", "output.score = 1"))));

        assertTrue(thrown.getMessage().contains("MVEL has no option 'strongTypng'; its only option is strongTyping"),
                thrown.getMessage());
    }
}
