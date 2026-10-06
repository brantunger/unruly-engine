package io.github.brantunger.unruly.mvel;

import io.github.brantunger.unruly.api.Fact;
import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
import io.github.brantunger.unruly.api.language.ActionResult;
import io.github.brantunger.unruly.api.language.CompileContext;
import io.github.brantunger.unruly.api.language.CompiledAction;
import io.github.brantunger.unruly.api.language.CompiledCondition;
import io.github.brantunger.unruly.api.language.Expression;
import io.github.brantunger.unruly.api.language.ExpressionCompiler;
import io.github.brantunger.unruly.api.language.ExpressionLanguage;
import io.github.brantunger.unruly.api.language.Session;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * #1046, the issue's reproduction: MVEL rules beside a language that stands in for a Lua adapter, which rejects the
 * keyword {@code end} as a fact name and reserves {@code self}. MVEL keeps the defaults, so only the stand-in opts in.
 */
@DisplayName("a language that opts in has its fact-name rules apply only to the facts its rules can see, beside MVEL"
        + " (#1046)")
class ScopedFactNamesMvelTest {

    /**
     * Stands in for a Lua adapter: 'end' is a keyword, so its rules can't name a fact 'end'; it binds 'self'. Its
     * conditions are always true, its actions do nothing, and they read no fact.
     */
    private static final class Keywordy implements ExpressionLanguage {

        private final boolean everyRuleList;
        private final boolean tellsNamesRead;

        Keywordy(boolean everyRuleList, boolean tellsNamesRead) {
            this.everyRuleList = everyRuleList;
            this.tellsNamesRead = tellsNamesRead;
        }

        @Override
        public String name() {
            return "kw";
        }

        @Override
        public Set<String> reservedFactNames() {
            return Set.of("self");
        }

        @Override
        public boolean reservesForEveryRuleList() {
            return everyRuleList;
        }

        @Override
        public ExpressionCompiler newCompiler(CompileContext context) {
            return new ExpressionCompiler() {
                @Override
                public CompiledCondition compileCondition(Expression expression) {
                    return (evaluation, session) -> true;
                }

                @Override
                public CompiledAction compileAction(Expression expression) {
                    return (action, session) -> ActionResult.done();
                }

                @Override
                public Session newSession() {
                    return Session.none();
                }

                @Override
                public void checkFactName(String name) {
                    if (name.equals("end")) {
                        throw new IllegalArgumentException("'end' is a keyword: a kw rule can't refer to it");
                    }
                }

                @Override
                public Set<String> factNamesRead() {
                    return tellsNamesRead ? Set.of() : null;
                }
            };
        }
    }

    private static RulesEngine<Map<String, Object>> engine(Keywordy kw) {
        return RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new)
                .language(new MvelExpressionLanguage()).language(kw).defaultLanguage("mvel").build();
    }

    private static Object run(RulesEngine<Map<String, Object>> engine, String fact) {
        try {
            return engine.run(new FactMap<>(new Fact<>(fact, 1)));
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
    }

    private static final Rule READS_SELF = Rule.builder().ruleName("m").priority(2).condition("self == 1")
            .action("output.put(\"seen\", self)").build();
    private static final Rule READS_END = Rule.builder().ruleName("m").priority(2).condition("end == 1")
            .action("output.put(\"seen\", end)").build();
    private static final Rule KW = Rule.builder().ruleName("k").priority(1).language("kw").condition("true")
            .action("noop").build();

    @Test
    @DisplayName("by default, the issue's output: kw's reserved name and keyword block facts only MVEL rules read")
    void defaultsKeepTodaysBehaviour() {
        try (RulesEngine<Map<String, Object>> engine = engine(new Keywordy(true, false))) {
            engine.load(List.of(READS_SELF));
            assertEquals("'self' is reserved by the 'kw' expression language and cannot be used as a fact name",
                    run(engine, "self"));

            engine.load(List.of(READS_END, KW));
            assertEquals("'end' is a keyword: a kw rule can't refer to it", run(engine, "end"));
        }
    }

    @Test
    @DisplayName("an MVEL-only rule list reads facts named self and end once kw reserves its names only where used")
    void mvelOnlyListWhenKwOptsOut() {
        try (RulesEngine<Map<String, Object>> engine = engine(new Keywordy(false, false))) {
            engine.load(List.of(READS_SELF));
            assertEquals(Map.of("seen", 1), run(engine, "self"));

            engine.load(List.of(READS_END));
            assertEquals(Map.of("seen", 1), run(engine, "end"));
            // A list with a kw rule rejects self again, and end, as kw can't tell what its rules read.
            engine.load(List.of(READS_SELF, KW));
            assertEquals("'self' is reserved by the 'kw' expression language and cannot be used as a fact name",
                    run(engine, "self"));
        }
    }

    @Test
    @DisplayName("a mixed rule list reads a fact named end when kw says its rules read no fact")
    void mixedListWhenKwSaysWhatItReads() {
        try (RulesEngine<Map<String, Object>> engine = engine(new Keywordy(true, true))) {
            engine.load(List.of(READS_END, KW));
            assertEquals(Map.of("seen", 1), run(engine, "end"));
            // Its reserved name still applies, to every rule list, as kw reserves it so.
            engine.load(List.of(READS_SELF));
            assertEquals("'self' is reserved by the 'kw' expression language and cannot be used as a fact name",
                    run(engine, "self"));
        }
        try (RulesEngine<Map<String, Object>> engine = engine(new Keywordy(false, true))) {
            engine.load(List.of(READS_SELF));
            assertEquals(Map.of("seen", 1), run(engine, "self"));
            engine.load(List.of(READS_END, KW));
            assertEquals(Map.of("seen", 1), run(engine, "end"));
        }
    }

    @Test
    @DisplayName("MVEL keeps the defaults: it reserves output for every rule list, and can't tell which facts it reads")
    void mvelKeepsTheDefaults() {
        assertTrue(new MvelExpressionLanguage().reservesForEveryRuleList());
        try (RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>allMatches(
                HashMap::new).language(new MvelExpressionLanguage()).language(new Keywordy(false, false))
                .defaultLanguage("kw").build()) {
            engine.load(List.of(KW));
            assertEquals("'output' is reserved for the output object and cannot be used as a fact name",
                    run(engine, "output"));
        }
    }
}
