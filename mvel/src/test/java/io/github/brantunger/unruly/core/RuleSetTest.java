package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.language.ActionContext;
import io.github.brantunger.unruly.api.language.ActionResult;
import io.github.brantunger.unruly.api.language.CompiledAction;
import io.github.brantunger.unruly.api.language.CompiledCondition;
import io.github.brantunger.unruly.api.language.EvaluationContext;
import io.github.brantunger.unruly.api.language.Expression;
import io.github.brantunger.unruly.api.language.ExpressionCompiler;
import io.github.brantunger.unruly.api.language.Session;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

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
        public ActionResult execute(ActionContext context, Session session) {
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
    void copiesLentOneAtATime() throws InterruptedException, TimeoutException {
        AtomicInteger sessions = new AtomicInteger();
        RuleSet rules = new RuleSet(List.of(RULE), Map.of("a", compiler("a", sessions, new CopyOnWriteArrayList<>())));

        RuleSet.Copy first = rules.borrow(null);
        RuleSet.Copy second = rules.borrow(null);

        assertEquals(Map.of("a", new NumberedSession("a", 1)), first.sessions());
        assertEquals(Map.of("a", new NumberedSession("a", 2)), second.sessions(), "an overlapping run gets new sessions");
        assertTrue(first.kept() && second.kept(), "without a limit every copy is kept");
        assertEquals(List.of(RULE), rules.rules(), "every copy shares the compiled rules");
        assertEquals(RuleSet.UNLIMITED, rules.limit());

        rules.release(second);

        assertSame(second.sessions(), rules.borrow(null).sessions());
        assertEquals(2, sessions.get(), "sessions created");
    }

    @Test
    @DisplayName("a copy has one session for each language the rules use, created in the order of the compilers")
    void oneSessionPerLanguage() throws InterruptedException, TimeoutException {
        AtomicInteger sessions = new AtomicInteger();
        List<String> created = new CopyOnWriteArrayList<>();
        Map<String, ExpressionCompiler> compilers = new LinkedHashMap<>();
        compilers.put("b", compiler("b", sessions, created));
        compilers.put("a", compiler("a", sessions, created));
        RuleSet rules = new RuleSet(List.of(RULE), compilers);

        RuleSet.Copy copy = rules.borrow(null);

        assertEquals(List.of("b", "a"), created, "sessions created, by language");
        assertEquals(List.of("b", "a"), List.copyOf(copy.sessions().keySet()));
        assertEquals(new NumberedSession("b", 1), copy.sessions().get("b"));
        assertEquals(new NumberedSession("a", 2), copy.sessions().get("a"));
    }

    @Test
    @DisplayName("with a limit, a run nested on the same thread gets an extra copy that isn't kept")
    void nestedCopyNotKept() throws InterruptedException, TimeoutException {
        AtomicInteger sessions = new AtomicInteger();
        RuleSet rules = new RuleSet(List.of(RULE), Map.of("a", compiler("a", sessions, new CopyOnWriteArrayList<>())),
                CopyLimit.of(1));

        RuleSet.Copy outer = rules.borrow(null);
        RuleSet.Copy nested = rules.borrow(null);

        assertEquals(1, rules.limit());
        assertTrue(outer.kept(), "the copy within the limit is kept");
        assertFalse(nested.kept(), "the extra copy isn't kept");
        assertNotEquals(outer.sessions(), nested.sessions());

        rules.release(nested);
        rules.release(outer);

        assertSame(outer.sessions(), rules.borrow(null).sessions(), "the kept copy is reused");
        assertEquals(2, sessions.get(), "sessions created");
    }

    @Test
    @DisplayName("a run that waits keeps waiting while copies are given back, however long that takes")
    void waitingWhileCopiesComeBack() throws InterruptedException, TimeoutException {
        Semaphore permits = new Semaphore(0);
        AtomicLong copiesReturned = new AtomicLong();
        // Reports one copy coming back after the first window, and frees a permit with it, so the run waits again
        // instead of making an extra copy, and the second window finds the permit.
        LongSupplier returned = () -> {
            long value = copiesReturned.getAndIncrement();
            if (value == 1) {
                permits.release();
            }
            return value;
        };

        assertTrue(RuleSet.awaitPermit(permits, returned, 10, null), "the run should have taken the freed permit");
        assertEquals(0, permits.availablePermits(), "it took the permit it waited for");
    }

    @Test
    @DisplayName("a run gives up waiting when a whole window passes with no copy given back")
    void givingUpWhenNothingComesBack() throws InterruptedException, TimeoutException {
        Semaphore permits = new Semaphore(0);

        assertFalse(RuleSet.awaitPermit(permits, () -> 7L, 10, null),
                "nothing came back, so the run makes an extra copy");
    }

    @Test
    @DisplayName("a run that finds a free copy takes it without waiting at all")
    void takingAFreeCopy() throws InterruptedException, TimeoutException {
        Semaphore permits = new Semaphore(1);

        assertTrue(RuleSet.awaitPermit(permits, () -> 0L, 10, null));
        assertEquals(0, permits.availablePermits());
    }

    @Test
    @DisplayName("a run stops waiting at its deadline when that comes before the window ends")
    void waitingStopsAtTheDeadline() {
        Semaphore permits = new Semaphore(0);
        Instant deadline = Instant.now().plusMillis(50);

        TimeoutException thrown = assertThrows(TimeoutException.class,
                () -> RuleSet.awaitPermit(permits, () -> 0L, 60_000, deadline));

        assertFalse(Instant.now().isBefore(deadline), "it gave up before the deadline");
        assertTrue(thrown.getMessage().contains(deadline.toString()), thrown.getMessage());
    }

    @Test
    @DisplayName("a run whose deadline has already passed doesn't wait at all")
    void aPassedDeadlineDoesntWait() {
        Semaphore permits = new Semaphore(0);

        assertThrows(TimeoutException.class,
                () -> RuleSet.awaitPermit(permits, () -> 0L, 60_000, Instant.now().minusSeconds(1)));
    }

    @Test
    @DisplayName("a copy given back before the deadline is taken, even inside the last, shortened wait")
    void aCopyBeforeTheDeadlineIsTaken() throws InterruptedException, TimeoutException {
        Semaphore permits = new Semaphore(0);
        Thread giver = new Thread(() -> {
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            permits.release();
        });
        giver.start();

        assertTrue(RuleSet.awaitPermit(permits, () -> 0L, 60_000, Instant.now().plusSeconds(30)));
        giver.join();
    }

    @Test
    @DisplayName("a deadline later than the window still lets a run give up after a window with nothing given back")
    void aLaterDeadlineKeepsTheWindow() throws InterruptedException, TimeoutException {
        Semaphore permits = new Semaphore(0);

        assertFalse(RuleSet.awaitPermit(permits, () -> 7L, 10, Instant.now().plusSeconds(60)));
    }

    @Test
    @DisplayName("a rule list that needs no copies learns it from an extra copy when the shared permits are all held")
    void statelessListLearnsFromAnExtraCopy() throws Exception {
        CopyPermits permits = new CopyPermits(1);
        RuleSet needsCopies = new RuleSet(List.of(RULE),
                Map.of("a", compiler("a", new AtomicInteger(), new CopyOnWriteArrayList<>())), CopyLimit.of(1), permits, 1);
        ExpressionCompiler stateless = new ExpressionCompiler() {
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
                return Session.none();
            }
        };
        // The rule list a reload loaded, sharing the engine's permits with the one it replaced.
        RuleSet noCopies = new RuleSet(List.of(RULE), Map.of("n", stateless), CopyLimit.of(1), permits, 1);
        RuleSet.Copy held = needsCopies.borrow(null);

        // The only permit is held, so the first run of the new list can't take one and makes an extra copy.
        RuleSet.Copy first = noCopies.borrow(null);
        RuleSet.Copy second = noCopies.borrow(null);

        assertEquals(RuleSet.Kind.SHARED, first.kind(), "the extra copy showed the rules need none");
        assertEquals(RuleSet.Kind.SHARED, second.kind(), "later runs share the sessions without a permit");
        assertSame(first.sessions(), second.sessions());
        noCopies.release(second);
        noCopies.release(first);
        needsCopies.release(held);
    }

    @Test
    @DisplayName("more copies than the limit are warned about once for each rule list")
    void overflowWarnedOnce() throws Exception {
        AtomicInteger sessions = new AtomicInteger();
        // A window of one millisecond, so the two runs that overflow don't wait the five seconds a real one does.
        RuleSet rules = new RuleSet(List.of(RULE), Map.of("a", compiler("a", sessions, new CopyOnWriteArrayList<>())),
                CopyLimit.of(1), 1);
        RuleSet.Copy held = rules.borrow(null);

        String logs = EngineLoggingTest.logsOf(() -> {
            try {
                // Another thread, so the runs aren't nested: a nested run doesn't wait, and doesn't warn.
                Thread other = new Thread(() -> {
                    try {
                        rules.release(rules.borrow(null));
                        rules.release(rules.borrow(null));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } catch (TimeoutException e) {
                        throw new AssertionError("a run without a deadline can't time out", e);
                    }
                });
                other.start();
                other.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        rules.release(held);
        assertEquals(1, logs.lines().filter(line -> line.contains("made an extra copy")).count(), logs);
        assertEquals(3, sessions.get(), "the copy held, and one for each overflowing run");
    }

    @Test
    @DisplayName("the fact-name checks are kept with the rules they belong to")
    void factChecksKeptWithRules() {
        ExpressionCompiler check = compiler("x", new AtomicInteger(), new CopyOnWriteArrayList<>());

        RuleSet rules = new RuleSet(List.of(RULE), Map.of("x", check));

        assertEquals(Map.of("x", check), rules.factChecks());
    }
}
