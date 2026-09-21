package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.FactStore;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
import io.github.brantunger.unruly.api.exception.RuleCompilationException;
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** #294: an engine built with copiesAtLoad(n) makes and warms n copies of the rules when they load. */
@DisplayName("copiesAtLoad(n) makes and warms up n copies of the rules when they load")
class CopiesAtLoadTest {

    private static final String NAME = "scripted";

    /** What the scripted language does when the engine asks it for a session, or to warm one up. */
    private enum Script {
        WORK, STATELESS, SECOND_SESSION_THROWS, SECOND_SESSION_NULL, WARM_UP_THROWS, WARM_UP_OOM
    }

    /**
     * A language that records every session it creates, warms up and closes, and every session a condition ran with.
     * Its condition waits on {@link #gate} while it's set, so a test can hold runs at once.
     */
    private static final class ScriptedLanguage implements ExpressionLanguage {

        private final String languageName;
        private final Script script;
        private final List<Session> created = new CopyOnWriteArrayList<>();
        private final List<Session> warmed = new CopyOnWriteArrayList<>();
        private final List<Session> closed = new CopyOnWriteArrayList<>();
        private final List<Session> ranWith = new CopyOnWriteArrayList<>();
        private final AtomicInteger compilersClosed = new AtomicInteger();
        private volatile CountDownLatch gate;

        ScriptedLanguage(Script script) {
            this(NAME, script);
        }

        ScriptedLanguage(String languageName, Script script) {
            this.languageName = languageName;
            this.script = script;
        }

        @Override
        public String name() {
            return languageName;
        }

        @Override
        public ExpressionCompiler newCompiler(CompileContext context) {
            return new ExpressionCompiler() {
                @Override
                public CompiledCondition compileCondition(Expression expression) {
                    return (facts, session) -> {
                        ranWith.add(session);
                        CountDownLatch waitFor = gate;
                        if (waitFor != null) {
                            waitFor.countDown();
                            assertTrue(waitFor.await(10, TimeUnit.SECONDS), "the other runs didn't arrive");
                        }
                        return true;
                    };
                }

                @Override
                public CompiledAction compileAction(Expression expression) {
                    return (actionContext, session) -> ActionResult.done();
                }

                @Override
                public Session newSession() {
                    if (script == Script.STATELESS) {
                        created.add(Session.none());
                        return Session.none();
                    }
                    if (!created.isEmpty() && script == Script.SECOND_SESSION_THROWS) {
                        throw new IllegalStateException("no second session");
                    }
                    if (!created.isEmpty() && script == Script.SECOND_SESSION_NULL) {
                        return null;
                    }
                    Session session = new Session() {
                        @Override
                        public void close() {
                            closed.add(this);
                        }
                    };
                    created.add(session);
                    return session;
                }

                @Override
                public void warmUp(Session session) {
                    if (script == Script.WARM_UP_THROWS && !warmed.isEmpty()) {
                        throw new IllegalStateException("can't warm up");
                    }
                    if (script == Script.WARM_UP_OOM) {
                        throw new OutOfMemoryError("warming up");
                    }
                    warmed.add(session);
                }

                @Override
                public void close() {
                    compilersClosed.incrementAndGet();
                }
            };
        }
    }

    private static Rule rule(String name) {
        return Rule.builder().ruleName(name).language(NAME).condition("c").action("a").build();
    }

    private static RulesEngine<Map<String, Object>> engine(ScriptedLanguage language, int copies) {
        return RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new).language(language)
                .copiesAtLoad(copies).build();
    }

    private static FactStore<Object> noFacts() {
        return new FactMap<>();
    }

    @Test
    @DisplayName("load() makes and warms up n copies, and runs use them without making or warming any more")
    void copiesMadeAndWarmed() {
        ScriptedLanguage language = new ScriptedLanguage(Script.WORK);
        RulesEngine<Map<String, Object>> engine = engine(language, 3);

        engine.load(List.of(rule("r")));

        assertEquals(3, language.created.size());
        assertEquals(language.created, language.warmed, "each session made at load is warmed up once, in order");
        for (int i = 0; i < 5; i++) {
            engine.run(noFacts());
        }
        assertEquals(3, language.created.size(), "a run borrows a copy made at load");
        assertTrue(language.created.containsAll(language.ranWith));
    }

    @Test
    @DisplayName("without copiesAtLoad, load() makes no copy, and the copy a run makes isn't warmed up")
    void noneByDefault() {
        ScriptedLanguage language = new ScriptedLanguage(Script.WORK);
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new)
                .language(language).build();

        engine.load(List.of(rule("r")));
        assertEquals(List.of(), language.created);
        engine.run(noFacts());

        assertEquals(1, language.created.size());
        assertEquals(List.of(), language.warmed);
    }

    @Test
    @DisplayName("a copy a run makes because every copy made at load is in use isn't warmed up")
    // Shut down in the finally block, which also releases runs a failed assertion leaves waiting.
    @SuppressWarnings("PMD.CloseResource")
    void extraCopyNotWarmed() throws Exception {
        ScriptedLanguage language = new ScriptedLanguage(Script.WORK);
        RulesEngine<Map<String, Object>> engine = engine(language, 2);
        engine.load(List.of(rule("r")));
        CountDownLatch threeAtOnce = new CountDownLatch(3);
        language.gate = threeAtOnce;
        ExecutorService platform = Executors.newFixedThreadPool(3);
        try {
            List<Future<?>> runs = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                runs.add(platform.submit(() -> engine.run(noFacts())));
            }
            for (Future<?> run : runs) {
                run.get(30, TimeUnit.SECONDS);
            }
        } finally {
            language.gate = null;
            platform.shutdownNow();
        }

        assertEquals(3, language.created.size());
        assertEquals(language.created.subList(0, 2), language.warmed, "only the two copies made at load");
    }

    @Test
    @DisplayName("a language whose sessions keep no state gets one shared set of sessions, and nothing to warm up")
    void statelessLanguageShared() {
        ScriptedLanguage language = new ScriptedLanguage(Script.STATELESS);
        RulesEngine<Map<String, Object>> engine = engine(language, 4);

        engine.load(List.of(rule("r")));
        engine.run(noFacts());

        assertEquals(List.of(Session.none()), language.created, "one newSession() call, at load");
        assertEquals(List.of(), language.warmed);
    }

    @Test
    @DisplayName("in a copy of rules in two languages, only the session of the language that keeps state is warmed up")
    void mixedLanguages() {
        ScriptedLanguage stateful = new ScriptedLanguage("stateful", Script.WORK);
        ScriptedLanguage stateless = new ScriptedLanguage("stateless", Script.STATELESS);
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new)
                .language(stateful).language(stateless).defaultLanguage("stateful").copiesAtLoad(2).build();

        engine.load(List.of(rule("a").toBuilder().language("stateful").build(),
                rule("b").toBuilder().language("stateless").build()));

        assertEquals(stateful.created, stateful.warmed);
        assertEquals(2, stateful.warmed.size());
        assertEquals(List.of(), stateless.warmed, "Session.none() has nothing to warm up");
    }

    @Test
    @DisplayName("a language that doesn't warm up its sessions still gets its copies made at load")
    void defaultWarmUp() {
        AtomicInteger sessions = new AtomicInteger();
        ExpressionLanguage plain = new ExpressionLanguage() {
            @Override
            public String name() {
                return NAME;
            }

            @Override
            public ExpressionCompiler newCompiler(CompileContext context) {
                return new ExpressionCompiler() {
                    @Override
                    public CompiledCondition compileCondition(Expression expression) {
                        return (facts, session) -> true;
                    }

                    @Override
                    public CompiledAction compileAction(Expression expression) {
                        return (actionContext, session) -> ActionResult.done();
                    }

                    @Override
                    public Session newSession() {
                        sessions.incrementAndGet();
                        return new Session() {
                        };
                    }
                };
            }
        };
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new)
                .language(plain).copiesAtLoad(2).build();

        engine.load(List.of(rule("r")));
        engine.run(noFacts());

        assertEquals(2, sessions.get());
    }

    @Test
    @DisplayName("an empty rule list loads and runs with copiesAtLoad")
    void emptyRuleList() {
        RulesEngine<Map<String, Object>> engine = engine(new ScriptedLanguage(Script.WORK), 4);

        engine.load(List.of());

        assertNull(engine.run(noFacts()));
    }

    @Test
    @DisplayName("a reload makes new copies, and closes the idle copies of the rules it replaced")
    void reloadClosesIdleCopies() {
        ScriptedLanguage language = new ScriptedLanguage(Script.WORK);
        RulesEngine<Map<String, Object>> engine = engine(language, 2);
        engine.load(List.of(rule("first")));
        List<Session> first = List.copyOf(language.created);

        engine.load(List.of(rule("second")));

        assertEquals(4, language.created.size());
        assertEquals(first, language.closed);
        assertEquals(1, language.compilersClosed.get());
    }

    @Test
    @DisplayName("a session that can't be created fails load() naming the language; the rules loaded before stay")
    void sessionThrows() {
        assertLoadFails(Script.SECOND_SESSION_THROWS,
                "The 'scripted' expression language failed to create a session: no second session");
    }

    @Test
    @DisplayName("a language that returns no session fails load() naming the language; the rules loaded before stay")
    void sessionNull() {
        assertLoadFails(Script.SECOND_SESSION_NULL, "The 'scripted' expression language returned no session");
    }

    @Test
    @DisplayName("a session that can't be warmed up fails load() naming the language; the rules loaded before stay")
    void warmUpThrows() {
        assertLoadFails(Script.WARM_UP_THROWS,
                "The 'scripted' expression language failed to warm up a session: can't warm up");
    }

    /**
     * Loads rules with copiesAtLoad(1), which works because the language fails only from its second session or warm-up,
     * then loads again with the failure, and checks what the failed load did.
     */
    private static void assertLoadFails(Script script, String message) {
        ScriptedLanguage language = new ScriptedLanguage(script);
        RulesEngine<Map<String, Object>> first = engine(language, 1);
        first.load(List.of(rule("old")));
        int sessionsBefore = language.created.size();

        RuleCompilationException ex = assertThrows(RuleCompilationException.class,
                () -> first.load(List.of(rule("new"))));

        assertEquals(message, ex.getMessage());
        assertNull(ex.getRuleName(), "the failure is the language's, not a rule's");
        assertEquals("old", first.rules().rules().get(0).getRuleName(), "the rules loaded before stay loaded");
        assertEquals(1, language.compilersClosed.get(), "the failed load's compiler, closed once");
        assertEquals(language.created.subList(sessionsBefore, language.created.size()), language.closed,
                "every session the failed load made is closed");
        first.run(noFacts());
    }

    @Test
    @DisplayName("validate() doesn't see a session that can't be created, which fails the same load()")
    // #456: load() calls prepareCopies() after compiling, and validate() doesn't: it compiles the rules, closes the
    // compilers it made and never asks a language for a session, so this failure is load()'s alone.
    void validateMissesSessionFailure() {
        ScriptedLanguage language = new ScriptedLanguage(Script.SECOND_SESSION_THROWS);
        RulesEngine<Map<String, Object>> engine = engine(language, 2);
        List<Rule> rules = List.of(rule("r"));

        assertEquals(List.of(), engine.validate(rules), "the session the second copy can't create isn't validate()'s");
        assertEquals(List.of(), language.created, "validate() made no session to fail on");
        assertEquals(1, language.compilersClosed.get(), "it compiled the rules, and closed the compiler it made");

        RuleCompilationException ex = assertThrows(RuleCompilationException.class, () -> engine.load(rules));

        assertEquals("The 'scripted' expression language failed to create a session: no second session",
                ex.getMessage());
        assertNull(ex.getRuleName(), "the failure is the language's, not a rule's");
    }

    @Test
    @DisplayName("when a later copy fails, the copies the load already made are closed with its compiler")
    void laterCopyFails() {
        ScriptedLanguage language = new ScriptedLanguage(Script.SECOND_SESSION_THROWS);
        RulesEngine<Map<String, Object>> engine = engine(language, 2);

        assertThrows(RuleCompilationException.class, () -> engine.load(List.of(rule("r"))));

        assertEquals(1, language.created.size(), "the first copy was made");
        assertEquals(language.created, language.closed, "and closed when the load failed");
        assertEquals(1, language.compilersClosed.get());
    }

    @Test
    @DisplayName("a fatal error while warming up is rethrown unchanged, and the failed load's compiler is closed")
    void warmUpFatalError() {
        ScriptedLanguage language = new ScriptedLanguage(Script.WARM_UP_OOM);
        RulesEngine<Map<String, Object>> engine = engine(language, 1);

        OutOfMemoryError error = assertThrows(OutOfMemoryError.class, () -> engine.load(List.of(rule("r"))));

        assertEquals("warming up", error.getMessage());
        assertEquals(1, language.compilersClosed.get());
        assertEquals(language.created, language.closed);
    }

    @Test
    @DisplayName("MVEL rules give the same results from copies made and warmed up at load")
    void mvelCopies() {
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>firstMatch(HashMap::new)
                .copiesAtLoad(2).build();
        engine.load(List.of(Rule.builder().ruleName("big").priority(2).condition("x > 10")
                        .action("output.put('size', 'big')").build(),
                Rule.builder().ruleName("small").priority(1).condition("x <= 10")
                        .action("output.put('size', 'small')").build()));

        FactStore<Object> facts = new FactMap<>();
        facts.setValue("x", 20);
        assertEquals(Map.of("size", "big"), engine.run(facts));
        facts.setValue("x", 5);
        assertEquals(Map.of("size", "small"), engine.run(facts));
    }

    @Test
    @DisplayName("copiesAtLoad can't be negative, or more than maxCopies; the default limit and no limit allow any")
    void builderChecks() {
        RulesEngineBuilder<Map<String, Object>> builder = RulesEngineBuilder.allMatches(HashMap::new);
        assertEquals("copiesAtLoad must not be negative, but was -1",
                assertThrows(IllegalArgumentException.class, () -> builder.copiesAtLoad(-1)).getMessage());

        RulesEngineBuilder<Map<String, Object>> over = RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new)
                .copiesAtLoad(3).maxCopies(2);
        assertEquals("copiesAtLoad(3) is more than maxCopies(2): no more copies than that are used at once",
                assertThrows(IllegalArgumentException.class, over::build).getMessage());

        assertDoesNotThrow(() -> RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new).maxCopies(3)
                .copiesAtLoad(3).build().close());
        assertDoesNotThrow(() -> RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new)
                .copiesAtLoad(1_000).build().close(), "the default limit is on virtual threads only");
        assertDoesNotThrow(() -> RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new).unlimitedCopies()
                .copiesAtLoad(1_000).build().close());
    }
}
