package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.Fact;
import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.FactStore;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RuleListener;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
import io.github.brantunger.unruly.api.RunContext;
import io.github.brantunger.unruly.api.RunResult;
import io.github.brantunger.unruly.api.exception.RuleCompilationException;
import io.github.brantunger.unruly.api.language.CompileContext;
import io.github.brantunger.unruly.api.language.CompiledAction;
import io.github.brantunger.unruly.api.language.CompiledCondition;
import io.github.brantunger.unruly.api.language.Expression;
import io.github.brantunger.unruly.api.language.ExpressionCompiler;
import io.github.brantunger.unruly.api.language.ExpressionLanguage;
import io.github.brantunger.unruly.api.language.ForwardingExpressionCompiler;
import io.github.brantunger.unruly.api.language.ForwardingExpressionLanguage;
import io.github.brantunger.unruly.api.language.ToyExpressionLanguage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.github.brantunger.unruly.core.EngineLogs.assertLoggedAtError;
import static org.junit.jupiter.api.Assertions.*;

/**
 * #1046: a language can have its fact-name rules apply only where its own rules could see the fact. One that returns
 * {@code false} from {@link ExpressionLanguage#reservesForEveryRuleList()} has its reserved names rejected only for a
 * rule list that uses it, a declared one at {@code load()}; a compiler that returns a set from
 * {@link ExpressionCompiler#factNamesRead()} checks only the facts in it. By default, neither changes anything.
 */
@DisplayName("a language's fact-name rules can apply only to the facts its own rules can see (#1046)")
class ScopedFactNamesTest {

    /** An identifier in a toy expression, or the fact before the dot of a property read. */
    private static final Pattern NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    /**
     * Stands in for a Lua adapter, as the issue's reproduction does: it's named {@code kw}, reserves {@code self},
     * which its rules bind, and rejects a fact named {@code end}, a keyword. Its rules are toy expressions.
     *
     * @param everyRuleList What {@code reservesForEveryRuleList()} returns
     * @param tellsNamesRead Whether {@code factNamesRead()} returns the identifiers its expressions hold, rather than
     *                       {@code null}
     */
    private static ExpressionLanguage keywordy(boolean everyRuleList, boolean tellsNamesRead) {
        return keywordy(everyRuleList, tellsNamesRead, new ArrayList<>());
    }

    /**
     * Like {@link #keywordy(boolean, boolean)}, adding to {@code contexts} the context each compiler is created with.
     */
    private static ExpressionLanguage keywordy(boolean everyRuleList, boolean tellsNamesRead,
                                               List<CompileContext> contexts) {
        return new ForwardingExpressionLanguage(new ToyExpressionLanguage("kw")) {
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
                contexts.add(context);
                return new Reading(super.newCompiler(context), tellsNamesRead) {
                    @Override
                    public void checkFactName(String name) {
                        if (name.equals("end")) {
                            throw new IllegalArgumentException("'end' is a keyword: a kw rule can't refer to it");
                        }
                    }
                };
            }
        };
    }

    /** A toy compiler that can say which identifiers its expressions hold, every fact they read among them. */
    private static class Reading extends ForwardingExpressionCompiler {

        private final boolean tells;
        private final Set<String> read = Collections.synchronizedSet(new HashSet<>());

        Reading(ExpressionCompiler compiler, boolean tells) {
            super(compiler);
            this.tells = tells;
        }

        @Override
        public CompiledCondition compileCondition(Expression expression) {
            record(expression);
            return super.compileCondition(expression);
        }

        @Override
        public CompiledAction compileAction(Expression expression) {
            record(expression);
            return super.compileAction(expression);
        }

        private void record(Expression expression) {
            Matcher matcher = NAME.matcher(expression.text());
            while (matcher.find()) {
                read.add(matcher.group());
            }
        }

        @Override
        public Set<String> factNamesRead() {
            return tells ? read : null;
        }
    }

    /** A toy language named {@code name}, which reserves nothing, as MVEL stands in the issue's reproduction. */
    private static ExpressionLanguage plain(String name) {
        return reserving(name, Set.of());
    }

    private static ExpressionLanguage reserving(String name, Set<String> reserved) {
        return new ForwardingExpressionLanguage(new ToyExpressionLanguage(name)) {
            @Override
            public Set<String> reservedFactNames() {
                return reserved;
            }
        };
    }

    private static RulesEngine<Map<String, Object>> engine(ExpressionLanguage kw) {
        return builder(kw).build();
    }

    private static RulesEngineBuilder<Map<String, Object>> builder(ExpressionLanguage kw) {
        return RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new).language(plain("toy")).language(kw)
                .defaultLanguage("toy");
    }

    private static Rule rule(String name, int priority, String language, String condition, String action) {
        return Rule.builder().ruleName(name).priority(priority).language(language).condition(condition).action(action)
                .build();
    }

    private static FactStore<Object> fact(String name) {
        return new FactMap<>(new Fact<>(name, 1));
    }

    private static String run(RulesEngine<Map<String, Object>> engine, String name) {
        try {
            return String.valueOf(engine.run(fact(name)));
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
    }

    // The issue's reproduction, first part: kw is on the engine, but no rule is written in it.
    @Test
    @DisplayName("a name a language reserves only for the rule lists that use it is a fact's when no rule uses it")
    void reservedOnlyWhereUsed() {
        try (RulesEngine<Map<String, Object>> engine = engine(keywordy(false, false))) {
            engine.load(List.of(rule("m", 2, "toy", "self == 1", "put seen self")));

            assertEquals("{seen=1}", run(engine, "self"));

            engine.load(List.of(rule("m", 2, "toy", "self == 1", "put seen self"),
                    rule("k", 1, "kw", "true", "put k 1")));

            assertEquals("'self' is reserved by the 'kw' expression language and cannot be used as a fact name",
                    run(engine, "self"));
        }
    }

    @Test
    @DisplayName("by default, a name a language reserves is rejected for every rule list, as before")
    void reservedForEveryRuleListByDefault() {
        try (RulesEngine<Map<String, Object>> engine = engine(keywordy(true, true))) {
            engine.load(List.of(rule("m", 2, "toy", "self == 1", "put seen self")));

            assertEquals("'self' is reserved by the 'kw' expression language and cannot be used as a fact name",
                    run(engine, "self"));
        }
    }

    // The issue's reproduction, second part: a toy rule reads 'end'; a kw rule in the same list doesn't.
    @Test
    @DisplayName("a compiler that says which facts its rules read checks only those, in a mixed rule list")
    void checksOnlyTheFactsRead() {
        List<Rule> mixed = List.of(rule("m", 2, "toy", "end == 1", "put seen end"),
                rule("k", 1, "kw", "true", "put k 1"));
        try (RulesEngine<Map<String, Object>> engine = engine(keywordy(true, true))) {
            engine.load(mixed);

            assertEquals(Map.of("seen", 1, "k", 1), engine.run(fact("end")));

            // A kw rule that reads it has it checked.
            engine.load(List.of(rule("m", 2, "toy", "end == 1", "put seen end"),
                    rule("k", 1, "kw", "true", "put k end")));

            assertEquals("'end' is a keyword: a kw rule can't refer to it", run(engine, "end"));
        }
        // A compiler that can't tell checks every fact, as before.
        try (RulesEngine<Map<String, Object>> engine = engine(keywordy(true, false))) {
            engine.load(mixed);

            assertEquals("'end' is a keyword: a kw rule can't refer to it", run(engine, "end"));
        }
    }

    @Test
    @DisplayName("the facts a compiler's rules read narrow its checkFactName only: its reserved names still apply")
    void namesReadDoNotNarrowReservedNames() {
        try (RulesEngine<Map<String, Object>> engine = engine(keywordy(false, true))) {
            engine.load(List.of(rule("m", 2, "toy", "self == 1", "put seen self"),
                    rule("k", 1, "kw", "true", "put k 1")));

            assertEquals("'self' is reserved by the 'kw' expression language and cannot be used as a fact name",
                    run(engine, "self"));
            // Both opted in, and no rule reads end or self: an MVEL-only list, in the issue's words.
            engine.load(List.of(rule("m", 2, "toy", "true", "put seen x")));

            assertEquals("{seen=1}", String.valueOf(engine.run(new FactMap<>(new Fact<>("x", 1),
                    new Fact<>("end", 1), new Fact<>("self", 1)))));
        }
    }

    @Test
    @DisplayName("a declared fact a language reserves only for the rule lists that use it is rejected by load()")
    void declaredReservedRejectedAtLoad() {
        // build() accepts it, where it rejects a name reserved for every rule list.
        try (RulesEngine<Map<String, Object>> engine = builder(keywordy(false, false)).fact("self", Integer.class)
                .build()) {
            engine.load(List.of(rule("m", 1, "toy", "self == 1", "put seen self")));
            assertEquals("{seen=1}", run(engine, "self"));
            List<Rule> usingKw = List.of(rule("k", 1, "kw", "true", "put k 1"));

            RuleCompilationException thrown = assertThrows(RuleCompilationException.class,
                    () -> engine.load(usingKw));

            assertEquals("'self' is reserved by the 'kw' expression language and cannot be declared as a fact",
                    thrown.getMessage());
            assertNull(thrown.getRuleName());
            List<RuleCompilationException> validated = engine.validate(usingKw);
            assertEquals(1, validated.size());
            assertEquals(thrown.getMessage(), validated.get(0).getMessage());
            // The rules loaded before stay loaded.
            assertEquals("{seen=1}", run(engine, "self"));
        }
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> builder(keywordy(true, false)).fact("self", Integer.class).build());
        assertEquals("'self' is reserved by the 'kw' expression language and cannot be declared as a fact",
                thrown.getMessage());
    }

    @Test
    @DisplayName("a declared fact is checked only by the compilers whose rules read it, when they can tell")
    void declaredFactCheckedOnlyWhereRead() {
        try (RulesEngine<Map<String, Object>> engine = builder(keywordy(true, true)).fact("end", Integer.class)
                .build()) {
            engine.load(List.of(rule("m", 2, "toy", "end == 1", "put seen end"),
                    rule("k", 1, "kw", "true", "put k 1")));

            RuleCompilationException thrown = assertThrows(RuleCompilationException.class,
                    () -> engine.load(List.of(rule("k", 1, "kw", "true", "put k end"))));

            assertEquals("Declared fact 'end' can't be used: 'end' is a keyword: a kw rule can't refer to it",
                    thrown.getMessage());
        }
    }

    @Test
    @DisplayName("a rule list's compile contexts reserve the names its languages reserve, and those reserved for every"
            + " rule list")
    void compileContextReservesTheRuleListsNames() {
        List<CompileContext> contexts = new ArrayList<>();
        List<CompileContext> toyContexts = new ArrayList<>();
        ExpressionLanguage toy = new ForwardingExpressionLanguage(reserving("toy", Set.of("ctx"))) {
            @Override
            public ExpressionCompiler newCompiler(CompileContext context) {
                toyContexts.add(context);
                return super.newCompiler(context);
            }
        };
        ExpressionLanguage unused = new ForwardingExpressionLanguage(reserving("zed", Set.of("zz"))) {
            @Override
            public boolean reservesForEveryRuleList() {
                return false;
            }
        };
        try (RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>allMatches(
                HashMap::new).language(toy).language(keywordy(false, false, contexts)).language(unused)
                .defaultLanguage("toy").build()) {
            engine.load(List.of(rule("m", 1, "toy", "true", "put seen x")));

            assertEquals(Set.of("ctx"), reserved(toyContexts.get(0)));

            engine.load(List.of(rule("m", 2, "toy", "true", "put seen x"), rule("k", 1, "kw", "true", "put k 1")));

            assertEquals(Set.of("ctx", "self"), reserved(toyContexts.get(1)));
            assertEquals(Set.of("ctx", "self"), reserved(contexts.get(0)));
        }
    }

    private static Set<String> reserved(CompileContext context) {
        return assertInstanceOf(EngineCompileContext.class, context).reservedFactNames();
    }

    @Test
    @DisplayName("a rule list without rules is the default language's, whose names are reserved for it")
    void emptyRuleListIsTheDefaultLanguages() {
        List<CompileContext> contexts = new ArrayList<>();
        try (RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>allMatches(
                HashMap::new).language(plain("toy")).language(keywordy(false, false, contexts)).defaultLanguage("kw")
                .build()) {
            engine.load(List.of());

            assertEquals("'self' is reserved by the 'kw' expression language and cannot be used as a fact name",
                    run(engine, "self"));
            assertEquals(Set.of("self"), reserved(contexts.get(0)));
        }
        try (RulesEngine<Map<String, Object>> engine = engine(keywordy(false, false))) {
            engine.load(List.of());

            assertNull(engine.run(fact("self")));
        }
    }

    @Test
    @DisplayName("a rule list that fails to compile checks its declared facts against the languages it got to")
    void failedRuleListChecksTheLanguagesItGotTo() {
        try (RulesEngine<Map<String, Object>> engine = builder(keywordy(false, true)).fact("self", Integer.class)
                .build()) {
            RuleCompilationException thrown = assertThrows(RuleCompilationException.class, () -> engine.load(List.of(
                    rule("k", 2, "kw", "true", "put k 1"), rule("m", 1, "toy", "x ==", "put seen x"))));

            List<String> messages = thrown.failures().stream().map(RuleCompilationException::getMessage).toList();
            assertEquals(2, messages.size(), messages.toString());
            assertTrue(messages.contains(
                    "'self' is reserved by the 'kw' expression language and cannot be declared as a fact"), messages
                    .toString());
        }
    }

    @Test
    @DisplayName("each language is asked once at build whether it reserves its names for every rule list, and each"
            + " compiler once at load which facts its rules read, unless a rule failed")
    void askedOnce() {
        AtomicInteger everyRuleList = new AtomicInteger();
        AtomicInteger namesRead = new AtomicInteger();
        ExpressionLanguage counted = new ForwardingExpressionLanguage(new ToyExpressionLanguage("kw")) {
            @Override
            public boolean reservesForEveryRuleList() {
                everyRuleList.incrementAndGet();
                return false;
            }

            @Override
            public ExpressionCompiler newCompiler(CompileContext context) {
                return new ForwardingExpressionCompiler(super.newCompiler(context)) {
                    @Override
                    public Set<String> factNamesRead() {
                        namesRead.incrementAndGet();
                        return Set.of("x");
                    }
                };
            }
        };
        try (RulesEngine<Map<String, Object>> engine = engine(counted)) {
            assertEquals(1, everyRuleList.get());
            engine.load(List.of(rule("k", 1, "kw", "x == 1", "put seen x")));
            engine.run(fact("x"));
            engine.run(new FactMap<>(new Fact<>("x", 1), new Fact<>("y", 1)));

            assertEquals(1, everyRuleList.get());
            assertEquals(1, namesRead.get());
            assertThrows(RuleCompilationException.class, () -> engine.load(List.of(
                    rule("k", 2, "kw", "x == 1", "put seen x"), rule("m", 1, "toy", "x ==", "put seen x"))));
            assertEquals(1, namesRead.get(), "asked although a rule failed");
        }
    }

    @Test
    @DisplayName("a compiler whose factNamesRead() throws, or returns a set holding null, fails the load, named")
    void brokenNamesReadFailsTheLoad() {
        ExpressionLanguage throwing = answering(() -> {
            throw new IllegalStateException("can't tell");
        });
        List<Rule> rules = List.of(rule("k", 1, "kw", "true", "put k 1"));
        try (RulesEngine<Map<String, Object>> engine = engine(throwing)) {
            RuleCompilationException thrown = assertThrows(RuleCompilationException.class, () -> engine.load(rules));

            assertEquals("The 'kw' expression language failed to tell which facts its rules read:"
                    + " can't tell", thrown.getMessage());
            assertInstanceOf(IllegalStateException.class, thrown.getCause());
            assertValidatedAs(thrown, engine.validate(rules));
        }
        Set<String> withNull = new HashSet<>();
        withNull.add(null);
        try (RulesEngine<Map<String, Object>> engine = engine(answering(() -> withNull))) {
            RuleCompilationException thrown = assertThrows(RuleCompilationException.class, () -> engine.load(rules));

            assertEquals("The 'kw' expression language returned a null name from factNamesRead()",
                    thrown.getMessage());
            assertValidatedAs(thrown, engine.validate(rules));
        }
    }

    /** Asserts that {@code validate()} returned the one failure {@code load()} threw. */
    private static void assertValidatedAs(RuleCompilationException thrown, List<RuleCompilationException> validated) {
        assertEquals(1, validated.size(), validated.toString());
        assertEquals(thrown.getMessage(), validated.get(0).getMessage());
    }

    @Test
    @DisplayName("a name reserved only for the rule lists that use its language reaches a run's listeners, after"
            + " beforeRun, and is logged at ERROR")
    void perListReservedNameReachesOnRunError() {
        List<String> calls = new ArrayList<>();
        RuleListener listener = new RuleListener() {
            @Override
            public void beforeRun(RunContext run) {
                calls.add("beforeRun");
            }

            @Override
            public void onRunError(RunContext run, RuntimeException error) {
                calls.add("onRunError: " + error.getMessage());
            }
        };
        try (RulesEngine<Map<String, Object>> engine = builder(keywordy(false, false)).listener(listener).build()) {
            engine.load(List.of(rule("k", 1, "kw", "true", "put k 1")));

            IllegalArgumentException thrown = assertLoggedAtError(IllegalArgumentException.class,
                    () -> engine.run(fact("self")));

            assertEquals("'self' is reserved by the 'kw' expression language and cannot be used as a fact name",
                    thrown.getMessage());
            assertEquals(List.of("beforeRun", "onRunError: " + thrown.getMessage()), calls);
        }
    }

    @Test
    @DisplayName("the engine keeps a copy of the names a compiler says its rules read")
    void namesReadCopied() {
        Set<String> read = new HashSet<>(Set.of("x"));
        try (RulesEngine<Map<String, Object>> engine = engine(new ForwardingExpressionLanguage(keywordy(true, false)) {
            @Override
            public ExpressionCompiler newCompiler(CompileContext context) {
                return new ForwardingExpressionCompiler(super.newCompiler(context)) {
                    @Override
                    public Set<String> factNamesRead() {
                        return read;
                    }
                };
            }
        })) {
            engine.load(List.of(rule("k", 1, "kw", "true", "put k 1")));
            read.add("end");

            assertEquals("{k=1}", run(engine, "end"));
        }
    }

    /**
     * An unmodifiable set a compiler returns, such as {@code Set.of}'s, is kept as is: {@code Set.copyOf} returns it,
     * as nothing can change it, so a loaded rule list holds no second copy of the names (#1129).
     */
    @Test
    @DisplayName("the engine keeps an unmodifiable set of the names a compiler says its rules read without copying it")
    void unmodifiableNamesReadKeptAsIs() {
        Set<String> read = Set.of("x", "y");
        try (RulesEngine<Map<String, Object>> engine = engine(answering(() -> read))) {
            engine.load(List.of(rule("k", 1, "kw", "true", "put k 1")));

            assertSame(read, ((AbstractRulesEngine<?>) engine).currentRules().factNamesRead().get("kw"));
        }
    }

    /** A kw language whose compiler's {@code factNamesRead()} answers with what {@code answer} gives. */
    private static ExpressionLanguage answering(Supplier<Set<String>> answer) {
        return new ForwardingExpressionLanguage(new ToyExpressionLanguage("kw")) {
            @Override
            public ExpressionCompiler newCompiler(CompileContext context) {
                return new ForwardingExpressionCompiler(super.newCompiler(context)) {
                    @Override
                    public Set<String> factNamesRead() {
                        return answer.get();
                    }
                };
            }
        };
    }

    /**
     * An engine whose {@code currentRules()} hands back {@code first}, closed, at the first reading, and the rules it
     * loaded after that, as a reload between a run's reading and its borrowing does.
     */
    private static AbstractRulesEngine<String> engineReading(RuleSet first, ExpressionLanguage kw) {
        Map<String, ExpressionLanguage> languages = Map.of("toy", plain("toy"), "kw", kw);
        EngineConfiguration<String> configuration = TestConfigurations.engineConfiguration(languages)
                .withDefaultLanguage("toy").build();
        AtomicInteger reads = new AtomicInteger();
        return new AbstractRulesEngine<>(String::new, configuration) {
            @Override
            RuleSet currentRules() {
                return reads.getAndIncrement() == 0 ? first : super.currentRules();
            }

            @Override
            RunResult<String> runRules(FactStore<?> facts, Duration timeout, Set<String> tags) {
                return runInScope(facts, timeout, tags, (rules, copy, runFacts) ->
                        RunResult.of("ran", List.of(), rules.checksum()));
            }

            @Override
            String matchPolicy() {
                return "firstMatch";
            }
        };
    }

    private static RuleSet closed(Map<String, String> reserved) {
        RuleSet rules = TestRuleSets.ruleSet(List.of(), Map.of()).withReservedFactNames(reserved).build();
        rules.retire();
        return rules;
    }

    @Test
    @DisplayName("a run checks its facts against the rule list it borrowed, not one a reload replaced before it could")
    void checkedAgainstTheRulesBorrowed() {
        // The run first reads a list that uses kw, closed by a reload; the list that replaced it doesn't use kw.
        try (AbstractRulesEngine<String> engine = engineReading(closed(Map.of("self", "kw")),
                keywordy(false, false))) {
            engine.load(List.of(rule("m", 1, "toy", "true", "put seen x")));

            assertEquals("ran", engine.run(fact("self")));
        }
        // And the other way round.
        try (AbstractRulesEngine<String> engine = engineReading(closed(Map.of()), keywordy(false, false))) {
            engine.load(List.of(rule("k", 1, "kw", "true", "put k 1")));

            IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                    () -> engine.run(fact("self")));
            assertEquals("'self' is reserved by the 'kw' expression language and cannot be used as a fact name",
                    thrown.getMessage());
        }
    }
}
