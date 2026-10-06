package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.ChildJvm;
import io.github.brantunger.unruly.TestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static io.github.brantunger.unruly.TestSupport.await;
import static io.github.brantunger.unruly.TestSupport.throwIfSet;
import static org.junit.jupiter.api.Assertions.*;

/**
 * #1038: a run's scope runs its closing inits one at a time, holding a turn it takes in short synchronized sections
 * and gives back with plain stores, so an overflow of the stack, which a language that catches
 * {@link StackOverflowError} goes on from, never leaves the turn taken, a made value unkept or a key being made (see
 * {@link DeepRunScopeScenario}); and {@code runScoped} and {@code runScopedClosing} share one guarded map, so a request
 * on another thread never loses a value. Every wait polls, holding no monitor, and keeps the thread's interrupt status.
 */
// Longer than ChildJvm.TIMEOUT, so a scan that hangs is killed by ChildJvm, which reports its output, before JUnit
// interrupts the wait for it.
@Timeout(value = 120, unit = TimeUnit.SECONDS)
@DisplayName("#1038: a run's scope gives its turn back, keeps every value made and shares its map across threads")
class RunScopeTurnTest {

    private static final String ENDED = "runScopedClosing was called for a key (java.lang.String) after the run ended,"
            + " when its value would never be closed";
    private static final String RUNNING = "runScoped was called for a key (java.lang.String) while that key's init is"
            + " running";

    @AfterEach
    void clearFaults() {
        Faults.clear();
    }

    /** A value a language keeps for a run, known by its name. */
    private static final class Value implements AutoCloseable {
        private final String name;

        Value(String name) {
            this.name = name;
        }

        @Override
        public void close() {
            // Nothing to release.
        }

        @Override
        public String toString() {
            return name;
        }
    }

    @Test
    @DisplayName("#1047: a thread with as many closing inits running as the turn can count is refused another, which"
            + " takes no turn")
    void turnCountedToItsLimit() throws ReflectiveOperationException {
        RunScope scope = new RunScope();
        Field owner = RunScope.class.getDeclaredField("owner");
        Field holds = RunScope.class.getDeclaredField("holds");
        owner.setAccessible(true);
        holds.setAccessible(true);
        // As if this thread were MAX_HOLDS inits deep.
        owner.set(scope, Thread.currentThread());
        holds.setChar(scope, RunScope.MAX_HOLDS);

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> scope.getClosing("key", () -> fail("init ran")));

        assertEquals("runScopedClosing was refused for a key (java.lang.String): 65535 runScopedClosing inits are"
                + " already running on its thread, the most there can be", thrown.getMessage());
        assertEquals(RunScope.MAX_HOLDS, holds.getChar(scope));
        assertSame(Thread.currentThread(), owner.get(scope));
    }

    @Test
    @DisplayName("#1038 item 1: when a closing value asked for on a worker thread at every depth near the end of the"
            + " stack overflows, ending the run on another thread still returns, and each value is closed once")
    void overflowOnAWorkerThreadNeverLeavesTheTurnTaken(@TempDir Path dir) throws Exception {
        List<String> lines = scan(dir, "worker");

        assertEquals("true", value(lines, DeepRunScopeScenario.ENDED), String.join("\n", lines));
        assertEquals("", value(lines, DeepRunScopeScenario.WRONG), String.join("\n", lines));
        assertScanned(lines);
    }

    @Test
    @DisplayName("#1038 item 2: when closing values asked for at every depth near the end of the stack overflow, each"
            + " value made is handed back and closed once, and no key is left being made")
    void overflowNeverLeavesAValueUnkeptOrAKeyBeingMade(@TempDir Path dir) throws Exception {
        List<String> lines = scan(dir, "own");

        assertEquals("0", value(lines, DeepRunScopeScenario.BEING_MADE), String.join("\n", lines));
        assertEquals("", value(lines, DeepRunScopeScenario.WRONG), String.join("\n", lines));
        assertScanned(lines);
    }

    // Runs a scan of DeepRunScopeScenario in a new JVM, where the scope's code is new to the JIT, as a JVM's first run
    // is, with the scan's own recursion kept interpreted, so its frames stay the same size from round to round.
    // HashSet.remove is kept interpreted too, as it is in a JVM's first runs, when the JDK's own startup has already
    // compiled the inserts into hash maps and sets: so code that removes a key after an init returns needs more stack
    // for that than for anything it did before, at every round. Left to the JIT, the removal is compiled a few rounds
    // in, at a time that depends on the machine's load, and a scope that left a key being made passed the scan under
    // load (13 runs in 64, run 32 at once); compiling in the foreground (-Xbatch) passed it every time.
    private static List<String> scan(Path dir, String which) throws Exception {
        String scenario = DeepRunScopeScenario.class.getName();
        return ChildJvm.run(dir, DeepRunScopeScenario.class, "-Dunruly.scan=" + which, "-XX:CompileCommand=quiet",
                "-XX:CompileCommand=exclude," + scenario + "::deep",
                "-XX:CompileCommand=exclude," + scenario + "::below",
                "-XX:CompileCommand=exclude,java.util.HashSet::remove").lines().toList();
    }

    // A scan makes about 4,000 values a round: with the shadow zone of 20 pages ChildJvm gives its JVM, 480,495 to
    // 483,480 in all on JDK 21, 25 and 26 on Windows. Far fewer means it stopped recursing, or overflowed before it
    // asked.
    private static void assertScanned(List<String> lines) {
        int made = Integer.parseInt(value(lines, DeepRunScopeScenario.MADE));
        assertTrue(made >= 100_000, made + " values made\n" + String.join("\n", lines));
    }

    private static String value(List<String> lines, String start) {
        return lines.stream().filter(line -> line.startsWith(start)).map(line -> line.substring(start.length()))
                .findFirst().orElseThrow(() -> new AssertionError("no line " + start + "\n"
                        + String.join("\n", lines)));
    }

    @Test
    @DisplayName("#1038 item 3: when two threads make a scope's first request at once, neither loses the value it"
            + " kept")
    void firstRequestsOnTwoThreadsShareOneMap() throws Exception {
        RunScope scope = new RunScope();
        CountDownLatch making = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Object> plain = new AtomicReference<>();
        AtomicReference<Value> closing = new AtomicReference<>();
        AtomicReference<Throwable> failed = new AtomicReference<>();
        // The run's thread asks for a runScoped value, and stops as the scope makes its map, until let go.
        Thread run = daemon(() -> {
            Faults.watch(Faults.Step.RUN_SCOPE_MAP_MAKING, () -> {
                making.countDown();
                TestSupport.await(release);
            });
            plain.set(scope.get("plain", Object::new));
        }, failed);
        TestSupport.await(making);
        // Meanwhile a thread holding the run's context asks for a closing value.
        Thread other = daemon(() -> closing.set(scope.getClosing("closing", () -> new Value("closing"))), failed);
        try {
            await(() -> other.getState() == Thread.State.BLOCKED || other.getState() == Thread.State.TERMINATED, 10,
                    "the other thread waits for the map, or has kept its value");
        } finally {
            release.countDown();
            run.join(TimeUnit.SECONDS.toMillis(10));
            other.join(TimeUnit.SECONDS.toMillis(10));
        }

        throwIfSet(failed.get());
        assertSame(plain.get(), scope.get("plain", () -> fail("the runScoped value was lost")));
        assertSame(closing.get(), scope.getClosing("closing", () -> fail("the closing value was lost")));
        assertEquals(List.of(closing.get()), scope.end());
    }

    @Test
    @DisplayName("#1038: two threads whose closing inits each ask for the other's key both finish, as under a lock")
    void closingInitsAskingForEachOthersKeysBothFinish() throws Exception {
        RunScope scope = new RunScope();
        CountDownLatch aRuns = new CountDownLatch(1);
        AtomicReference<Thread> b = new AtomicReference<>();
        AtomicReference<Value> a = new AtomicReference<>();
        AtomicReference<Value> bByB = new AtomicReference<>();
        AtomicReference<Throwable> failed = new AtomicReference<>();
        Thread first = daemon(() -> a.set(scope.getClosing("a", () -> {
            aRuns.countDown();
            awaitWaiting(b);
            scope.getClosing("b", () -> new Value("b made by a's init"));
            return new Value("a");
        })), failed);
        TestSupport.await(aRuns);
        b.set(daemon(() -> bByB.set(scope.getClosing("b", () -> {
            scope.getClosing("a", () -> new Value("a made by b's init"));
            return new Value("b");
        })), failed));
        first.join(TimeUnit.SECONDS.toMillis(10));
        b.get().join(TimeUnit.SECONDS.toMillis(10));

        assertFalse(first.isAlive(), "a's init never finished");
        assertFalse(b.get().isAlive(), "b's request never finished");
        throwIfSet(failed.get());
        // b's request waited for a's init, which made b, so it got that value.
        assertEquals("b made by a's init", bByB.get().name);
        assertEquals(List.of(bByB.get(), a.get()), scope.end());
    }

    @Test
    @DisplayName("#1038: a runScoped request for a key whose init runs on another thread throws, also when that init"
            + " waits for the closing turn the asking thread holds")
    void runScopedKeyMadeByAThreadWaitingForTheTurnThrows() throws Exception {
        RunScope scope = new RunScope();
        CountDownLatch xRuns = new CountDownLatch(1);
        AtomicReference<Thread> maker = new AtomicReference<>();
        AtomicReference<IllegalStateException> refused = new AtomicReference<>();
        AtomicReference<Value> x = new AtomicReference<>();
        AtomicReference<Object> plain = new AtomicReference<>();
        AtomicReference<Throwable> failed = new AtomicReference<>();
        Thread holder = daemon(() -> x.set(scope.getClosing("x", () -> {
            xRuns.countDown();
            awaitWaiting(maker);
            refused.set(assertThrows(IllegalStateException.class, () -> scope.get("plain", Object::new)));
            return new Value("x");
        })), failed);
        TestSupport.await(xRuns);
        maker.set(daemon(() -> plain.set(scope.get("plain", () -> scope.getClosing("y", () -> new Value("y")))),
                failed));
        holder.join(TimeUnit.SECONDS.toMillis(10));
        maker.get().join(TimeUnit.SECONDS.toMillis(10));

        assertFalse(holder.isAlive(), "the turn's holder never finished");
        assertFalse(maker.get().isAlive(), "the runScoped init never finished");
        throwIfSet(failed.get());
        assertEquals(RUNNING, refused.get().getMessage());
        assertEquals("y", plain.get().toString());
        assertEquals(List.of(x.get(), plain.get()), scope.end());
    }

    @Test
    @DisplayName("#1038: a runScoped request for a key whose init runs on another thread throws, also when that init"
            + " ends the run, which waits for the closing turn the asking thread holds")
    void runScopedKeyMadeByAThreadEndingTheRunThrows() throws Exception {
        RunScope scope = new RunScope();
        CountDownLatch xRuns = new CountDownLatch(1);
        AtomicReference<Thread> ender = new AtomicReference<>();
        AtomicReference<IllegalStateException> refused = new AtomicReference<>();
        AtomicReference<Value> x = new AtomicReference<>();
        AtomicReference<List<AutoCloseable>> handedBack = new AtomicReference<>();
        AtomicReference<Throwable> failed = new AtomicReference<>();
        Thread holder = daemon(() -> x.set(scope.getClosing("x", () -> {
            xRuns.countDown();
            awaitWaiting(ender);
            refused.set(assertThrows(IllegalStateException.class, () -> scope.get("plain", Object::new)));
            return new Value("x");
        })), failed);
        TestSupport.await(xRuns);
        ender.set(daemon(() -> scope.get("plain", () -> {
            handedBack.set(scope.end());
            return "plain";
        }), failed));
        holder.join(TimeUnit.SECONDS.toMillis(10));
        ender.get().join(TimeUnit.SECONDS.toMillis(10));

        assertFalse(holder.isAlive(), "the turn's holder never finished");
        assertFalse(ender.get().isAlive(), "end() never returned");
        throwIfSet(failed.get());
        assertEquals(RUNNING, refused.get().getMessage());
        assertEquals(List.of(x.get()), handedBack.get());
    }

    @Test
    @DisplayName("#1038: while end() waits for another thread's closing init, a third thread's closing request is"
            + " refused at once, the init's own thread can still make one, and end() hands back both")
    void endWaitingForTheTurnRefusesOtherThreadsButItsOwner() throws Exception {
        RunScope scope = new RunScope();
        CountDownLatch aRuns = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Value> a = new AtomicReference<>();
        AtomicReference<Value> nested = new AtomicReference<>();
        AtomicReference<List<AutoCloseable>> handedBack = new AtomicReference<>();
        AtomicReference<Throwable> refused = new AtomicReference<>();
        AtomicReference<Throwable> failed = new AtomicReference<>();
        Thread holder = daemon(() -> a.set(scope.getClosing("a", () -> {
            aRuns.countDown();
            TestSupport.await(release);
            nested.set(scope.getClosing("nested", () -> new Value("nested")));
            return new Value("a");
        })), failed);
        TestSupport.await(aRuns);
        Thread ender = daemon(() -> handedBack.set(scope.end()), failed);
        Thread third;
        try {
            await(() -> ender.getState() == Thread.State.TIMED_WAITING, 10, "end() waits for the turn");
            third = daemon(() -> scope.getClosing("third", () -> new Value("third")), refused);
            third.join(TimeUnit.SECONDS.toMillis(10));
            assertFalse(third.isAlive(), "the third thread waited for the turn");
        } finally {
            release.countDown();
            holder.join(TimeUnit.SECONDS.toMillis(10));
            ender.join(TimeUnit.SECONDS.toMillis(10));
        }

        throwIfSet(failed.get());
        assertEquals(ENDED, assertInstanceOf(IllegalStateException.class, refused.get()).getMessage());
        assertEquals(List.of(nested.get(), a.get()), handedBack.get());
    }

    @Test
    @DisplayName("#1038: a thread interrupted as it waits for the closing turn waits on, and has its interrupt status"
            + " set again before its init runs")
    void interruptedWaiterForTheTurnKeepsItsInterrupt() throws Exception {
        RunScope scope = new RunScope();
        CountDownLatch aRuns = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Boolean> initSawInterrupt = new AtomicReference<>();
        AtomicReference<Boolean> interruptedAfter = new AtomicReference<>();
        AtomicReference<Throwable> failed = new AtomicReference<>();
        Thread holder = daemon(() -> scope.getClosing("a", () -> {
            aRuns.countDown();
            TestSupport.await(release);
            return new Value("a");
        }), failed);
        TestSupport.await(aRuns);
        Thread waiter = daemon(() -> {
            Thread.currentThread().interrupt();
            scope.getClosing("b", () -> {
                initSawInterrupt.set(Thread.currentThread().isInterrupted());
                return new Value("b");
            });
            interruptedAfter.set(Thread.interrupted());
        }, failed);
        try {
            await(() -> waiter.getState() == Thread.State.TIMED_WAITING || waiter.getState() == Thread.State.WAITING,
                    10, "the interrupted thread waits for the turn");
        } finally {
            release.countDown();
            holder.join(TimeUnit.SECONDS.toMillis(10));
            waiter.join(TimeUnit.SECONDS.toMillis(10));
        }

        assertFalse(waiter.isAlive(), "the interrupted thread never got the turn");
        throwIfSet(failed.get());
        assertEquals(Boolean.TRUE, initSawInterrupt.get(), "the init didn't see the interrupt");
        assertEquals(Boolean.TRUE, interruptedAfter.get(), "the interrupt was lost");
        assertEquals(2, scope.end().size());
    }

    @Test
    @DisplayName("#1038: end() interrupted as it waits for another thread's closing init waits on, and keeps the"
            + " interrupt")
    void interruptedEndKeepsItsInterrupt() throws Exception {
        RunScope scope = new RunScope();
        CountDownLatch aRuns = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Value> a = new AtomicReference<>();
        AtomicReference<List<AutoCloseable>> handedBack = new AtomicReference<>();
        AtomicReference<Boolean> interruptedAfter = new AtomicReference<>();
        AtomicReference<Throwable> failed = new AtomicReference<>();
        Thread holder = daemon(() -> a.set(scope.getClosing("a", () -> {
            aRuns.countDown();
            TestSupport.await(release);
            return new Value("a");
        })), failed);
        TestSupport.await(aRuns);
        Thread ender = daemon(() -> {
            Thread.currentThread().interrupt();
            handedBack.set(scope.end());
            interruptedAfter.set(Thread.interrupted());
        }, failed);
        try {
            await(() -> ender.getState() == Thread.State.TIMED_WAITING || ender.getState() == Thread.State.WAITING,
                    10, "end() waits for the init");
            assertNull(handedBack.get(), "end() returned while the init ran");
        } finally {
            release.countDown();
            holder.join(TimeUnit.SECONDS.toMillis(10));
            ender.join(TimeUnit.SECONDS.toMillis(10));
        }

        assertFalse(ender.isAlive(), "end() never returned");
        throwIfSet(failed.get());
        assertEquals(Boolean.TRUE, interruptedAfter.get(), "the interrupt was lost");
        assertEquals(List.of(a.get()), handedBack.get());
    }

    @Test
    @DisplayName("#1038: end() allocates nothing, whether the scope kept no value or a closing one")
    void endAllocatesNothing() {
        com.sun.management.ThreadMXBean threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        int calls = 100_000;
        AutoCloseable value = () -> {
        };
        // Warmed up first, so class loading and the first compilations aren't counted.
        endAll(scopes(calls, null));
        endAll(scopes(calls, value));

        RunScope[] empty = scopes(calls, null);
        RunScope[] kept = scopes(calls, value);
        long before = threads.getCurrentThreadAllocatedBytes();
        int handedBack = endAll(empty);
        long afterEmpty = threads.getCurrentThreadAllocatedBytes();
        handedBack += endAll(kept);
        long afterKept = threads.getCurrentThreadAllocatedBytes();

        assertEquals(calls, handedBack);
        // Any object is at least 16 bytes, so a bound of under one byte a call leaves room only for what the
        // measurement itself allocates.
        assertTrue(afterEmpty - before < calls, (afterEmpty - before) + " bytes for " + calls + " calls");
        assertTrue(afterKept - afterEmpty < calls, (afterKept - afterEmpty) + " bytes for " + calls + " calls");
    }

    private static RunScope[] scopes(int count, AutoCloseable value) {
        RunScope[] scopes = new RunScope[count];
        for (int i = 0; i < count; i++) {
            scopes[i] = new RunScope();
            if (value != null) {
                scopes[i].getClosing("key", () -> value);
            }
        }
        return scopes;
    }

    private static int endAll(RunScope[] scopes) {
        int handedBack = 0;
        for (RunScope scope : scopes) {
            handedBack += scope.end().size();
        }
        return handedBack;
    }

    /** Starts {@code body} on a daemon thread, keeping the first thing it throws in {@code failed}. */
    private static Thread daemon(Runnable body, AtomicReference<Throwable> failed) {
        Thread thread = new Thread(() -> {
            try {
                body.run();
            } catch (Throwable e) {
                failed.compareAndSet(null, e);
            }
        });
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    /** Waits until the thread set in {@code thread} waits, polling for the scope's turn, or for a lock or monitor. */
    private static void awaitWaiting(AtomicReference<Thread> thread) {
        try {
            await(() -> {
                Thread waiting = thread.get();
                return waiting != null && (waiting.getState() == Thread.State.TIMED_WAITING
                        || waiting.getState() == Thread.State.WAITING || waiting.getState() == Thread.State.BLOCKED);
            }, 10, "the other thread waits");
        } catch (InterruptedException e) {
            throw new AssertionError(e);
        }
    }
}
