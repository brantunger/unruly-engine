package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.language.ActionContext;
import io.github.brantunger.unruly.api.language.CompiledAction;
import io.github.brantunger.unruly.api.language.CompiledCondition;
import io.github.brantunger.unruly.api.language.EvaluationContext;
import io.github.brantunger.unruly.api.language.Expression;
import io.github.brantunger.unruly.api.language.ExpressionCompiler;
import io.github.brantunger.unruly.api.language.Session;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("RuleSet lends each run its own sessions for the shared compiled rules")
class RuleSetTest {

    /** A compiled expression that is never run: RuleSet only lends sessions for it. */
    private record Stub(String name) implements CompiledCondition, CompiledAction {
        @Override
        public Object evaluate(EvaluationContext context, Session session) {
            throw new AssertionError("not run");
        }

        @Override
        public void execute(ActionContext context, Session session) {
            throw new AssertionError("not run");
        }
    }

    /** A session named after its language and numbered in the order sessions were created. */
    private record NumberedSession(String language, int number) implements Session {
    }

    private static final CompiledRule RULE = new CompiledRule(
            Rule.builder().ruleName("r").condition("true").action("1").build(), "r", "a", new Stub("condition"),
            new Stub("action"));

    /** A compiler that only creates sessions, numbering them with {@code counter} and recording the language. */
    private static ExpressionCompiler compiler(String language, AtomicInteger counter, List<String> created) {
        return new ExpressionCompiler() {
            @Override
            public CompiledCondition compileCondition(Expression expression) {
                throw new AssertionError("not compiled");
            }

            @Override
            public CompiledAction compileAction(Expression expression) {
                throw new AssertionError("not compiled");
            }

            @Override
            public Session newSession() {
                created.add(language);
                return new NumberedSession(language, counter.incrementAndGet());
            }
        };
    }

    @Test
    @DisplayName("a copy in use is never lent twice, and one given back is reused instead of creating sessions again")
    void copiesLentOneAtATime() throws InterruptedException {
        AtomicInteger sessions = new AtomicInteger();
        RuleSet rules = new RuleSet(List.of(RULE), Map.of("a", compiler("a", sessions, new CopyOnWriteArrayList<>())));

        RuleSet.Copy first = rules.borrow();
        RuleSet.Copy second = rules.borrow();

        assertEquals(Map.of("a", new NumberedSession("a", 1)), first.sessions());
        assertEquals(Map.of("a", new NumberedSession("a", 2)), second.sessions(), "an overlapping run gets new sessions");
        assertTrue(first.kept() && second.kept(), "without a limit every copy is kept");
        assertEquals(List.of(RULE), rules.rules(), "every copy shares the compiled rules");
        assertEquals(RuleSet.UNLIMITED, rules.limit());

        rules.release(second);

        assertSame(second.sessions(), rules.borrow().sessions());
        assertEquals(2, sessions.get(), "sessions created");
    }

    @Test
    @DisplayName("a copy has one session for each language the rules use, created in the order of the compilers")
    void oneSessionPerLanguage() throws InterruptedException {
        AtomicInteger sessions = new AtomicInteger();
        List<String> created = new CopyOnWriteArrayList<>();
        Map<String, ExpressionCompiler> compilers = new LinkedHashMap<>();
        compilers.put("b", compiler("b", sessions, created));
        compilers.put("a", compiler("a", sessions, created));
        RuleSet rules = new RuleSet(List.of(RULE), compilers);

        RuleSet.Copy copy = rules.borrow();

        assertEquals(List.of("b", "a"), created, "sessions created, by language");
        assertEquals(List.of("b", "a"), List.copyOf(copy.sessions().keySet()));
        assertEquals(new NumberedSession("b", 1), copy.sessions().get("b"));
        assertEquals(new NumberedSession("a", 2), copy.sessions().get("a"));
    }

    @Test
    @DisplayName("with a limit, a run nested on the same thread gets an extra copy that isn't kept")
    void nestedCopyNotKept() throws InterruptedException {
        AtomicInteger sessions = new AtomicInteger();
        RuleSet rules = new RuleSet(List.of(RULE), Map.of("a", compiler("a", sessions, new CopyOnWriteArrayList<>())),
                1);

        RuleSet.Copy outer = rules.borrow();
        RuleSet.Copy nested = rules.borrow();

        assertEquals(1, rules.limit());
        assertTrue(outer.kept(), "the copy within the limit is kept");
        assertFalse(nested.kept(), "the extra copy isn't kept");
        assertNotEquals(outer.sessions(), nested.sessions());

        rules.release(nested);
        rules.release(outer);

        assertSame(outer.sessions(), rules.borrow().sessions(), "the kept copy is reused");
        assertEquals(2, sessions.get(), "sessions created");
    }

    @Test
    @DisplayName("the fact-name checks are kept with the rules they belong to")
    void factChecksKeptWithRules() {
        ExpressionCompiler check = compiler("x", new AtomicInteger(), new CopyOnWriteArrayList<>());

        RuleSet rules = new RuleSet(List.of(RULE), Map.of("x", check));

        assertEquals(Map.of("x", check), rules.factChecks());
    }
}
