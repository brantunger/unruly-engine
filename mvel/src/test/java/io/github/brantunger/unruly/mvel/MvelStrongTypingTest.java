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
 * When the engine declares every fact its rules may use, MVEL compiles them with strong typing, so a misspelled
 * property or an unknown fact fails {@code load()} instead of a run. It's off unless the whole picture is known:
 * MVEL's strict mode rejects property access on a {@code Map} or an {@code Object}, so one dynamic declaration would
 * turn working rules into compile errors.
 */
@DisplayName("MVEL compiles against declared facts, so a typo fails when the rules load")
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

    /** An engine that knows everything MVEL needs: declared facts, a real output type, and a complete list. */
    private static RulesEngine<Decision> typedEngine() {
        return typedEngine(UnaryOperator.identity());
    }

    private static RulesEngine<Decision> typedEngine(UnaryOperator<RulesEngineBuilder<Decision>> extra) {
        return extra.apply(RulesEngineBuilder.allMatches(Decision::new)
                .outputType(Decision.class)
                .fact("applicant", Applicant.class)
                .requireDeclaredFacts()).build();
    }

    private static FactStore<Object> applicant() {
        FactStore<Object> facts = new FactMap<>();
        facts.setValue("applicant", new Applicant(760, "Alex"));
        return facts;
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
    @DisplayName("a fact declared as a Map turns typing off, because MVEL can't check a map's members")
    void aMapFactTurnsTypingOff() {
        RulesEngine<Decision> engine = typedEngine(builder -> builder.fact("order", Map.class));

        // The same typo that fails above now compiles: nothing is type-checked.
        assertDoesNotThrow(() -> engine.load(List.of(rule("applicant.creditScor >= 750", "output.score = 1"))));
    }

    @Test
    @DisplayName("a fact declared as Object turns typing off as well")
    void anObjectFactTurnsTypingOff() {
        RulesEngine<Decision> engine = typedEngine(builder -> builder.fact("thing", Object.class));

        assertDoesNotThrow(() -> engine.load(List.of(rule("applicant.creditScor >= 750", "output.score = 1"))));
    }

    @Test
    @DisplayName("without an output type, typing stays off: an action writes to the output")
    void noOutputTypeTurnsTypingOff() {
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new)
                .fact("applicant", Applicant.class).requireDeclaredFacts().build();

        assertDoesNotThrow(() -> engine.load(List.of(rule("applicant.creditScor >= 750", "output.put('k', 1)"))));
    }

    @Test
    @DisplayName("without requireDeclaredFacts, typing stays off: a run may supply facts nobody declared")
    void withoutRequiringDeclaredFactsTypingIsOff() {
        RulesEngine<Decision> engine = RulesEngineBuilder.allMatches(Decision::new)
                .outputType(Decision.class).fact("applicant", Applicant.class).build();

        assertDoesNotThrow(() -> engine.load(List.of(rule("applicant.creditScor >= 750", "output.score = 1"))));
    }

    @Test
    @DisplayName("declaring nothing leaves MVEL exactly as it was")
    void noDeclarationsAtAll() {
        RulesEngine<Decision> engine = RulesEngineBuilder.allMatches(Decision::new)
                .outputType(Decision.class).requireDeclaredFacts().build();

        assertDoesNotThrow(() -> engine.load(List.of(rule("applicant.creditScor >= 750", "output.score = 1"))));
    }
}
