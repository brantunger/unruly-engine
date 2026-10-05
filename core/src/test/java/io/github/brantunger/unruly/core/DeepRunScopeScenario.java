package io.github.brantunger.unruly.core;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Run by {@link RunScopeTurnTest} in a new JVM, where the scope's code is new to the JIT, as it is in a JVM's first
 * runs, and goes through its tiers of compilation as the scan runs: a deep expression, as a script a language runs,
 * recurses to the end of the stack and asks for a closing value at every depth on the way back, catching each
 * {@link StackOverflowError}, so each request overflows at another point of the scope's code. Its init takes the next
 * value of a pool, so every value made is known. The property {@code unruly.scan} chooses the scan: {@code worker}
 * asks for one key of a new scope at each depth, on a worker thread, then ends every scope on another thread;
 * {@code own} asks for a new key of one scope at each depth, then asks for each key again and ends the scope, on that
 * thread. It prints whether ending returned, which values made weren't closed exactly once, and how many keys were
 * still being made when asked for again.
 */
final class DeepRunScopeScenario {

    /** The line that tells whether every scope was ended: {@code true} or {@code false}. */
    static final String ENDED = "ended: ";
    /** The line that tells how many values were made. */
    static final String MADE = "made: ";
    /** The line that lists the values made that weren't closed exactly once. */
    static final String WRONG = "not closed once: ";
    /** The line that tells how many keys asked for again were still being made, in the {@code own} scan. */
    static final String BEING_MADE = "being made: ";

    // The scan thread's stack: small, so a scan is quick.
    private static final long STACK = 512 * 1024;
    // Each round starts the deep expression below a few more frames of another size, so it meets the end of the stack
    // at another offset from its own frames.
    private static final int ROUNDS = Integer.getInteger("unruly.rounds", 128);
    private static final int OFFSETS = 16;
    private static final int LIMIT = 1_000_000;

    private static final Value[] POOL = new Value[2 * LIMIT];
    private static final Object[] KEYS = new Object[LIMIT];
    private static final RunScope[] SCOPES = new RunScope[LIMIT];
    private static RunScope scope;
    private static boolean worker;
    private static int calls;
    private static int made;
    private static int beingMade;

    // Field and array stores only, so a value taken from the pool is a value made.
    private static final Supplier<Value> INIT = () -> {
        Value value = POOL[made++];
        value.made = true;
        return value;
    };

    /** A value the scan keeps, which counts its close() calls. */
    private static final class Value implements AutoCloseable {
        private boolean made;
        private int closes;

        @Override
        public void close() {
            closes++;
        }
    }

    private DeepRunScopeScenario() {
    }

    public static void main(String[] args) throws InterruptedException {
        worker = "worker".equals(System.getProperty("unruly.scan"));
        for (int i = 0; i < POOL.length; i++) {
            POOL[i] = new Value();
        }
        for (int i = 0; i < LIMIT; i++) {
            if (worker) {
                SCOPES[i] = new RunScope();
            } else {
                KEYS[i] = "k" + i;
            }
        }
        scope = new RunScope();
        // The classes the scope uses initialized at the top of the stack, as an engine's first build does, since one
        // whose static initializer overflowed would be unusable for the life of the JVM.
        RunScope first = new RunScope();
        first.get("plain", Object::new);
        first.getClosing("closing", () -> () -> {
        });
        first.end();

        AtomicReference<Throwable> failed = new AtomicReference<>();
        Thread scan = new Thread(null, () -> {
            try {
                for (int i = 0; i < ROUNDS; i++) {
                    below(i % OFFSETS);
                }
                if (!worker) {
                    // Each key asked for again, away from the end of the stack: one left being made fails, and one
                    // never made is made now. Then the run ends, on its own thread.
                    for (int i = 0; i < calls; i++) {
                        try {
                            scope.getClosing(KEYS[i], INIT);
                        } catch (IllegalStateException e) {
                            beingMade++;
                        }
                    }
                    closeAll(scope.end());
                }
            } catch (Throwable e) {
                failed.set(e);
            }
        }, "deep", STACK);
        scan.start();
        scan.join();
        boolean ended = true;
        if (worker) {
            ended = endOnAnotherThread();
        }
        StringBuilder wrong = new StringBuilder();
        for (int i = 0; i < made; i++) {
            if (POOL[i].closes != 1) {
                wrong.append(" v").append(i).append(" closed ").append(POOL[i].closes).append(" times");
            }
        }
        System.out.println(ENDED + ended);
        System.out.println(MADE + made);
        System.out.println(WRONG + wrong.toString().trim());
        System.out.println(BEING_MADE + beingMade);
        if (failed.get() != null) {
            failed.get().printStackTrace(System.out);
        }
        // Not waiting for a thread left waiting on a turn that is never given back.
        System.exit(0);
    }

    // Runs the deep expression below {@code frames} frames of its own.
    private static void below(int frames) {
        if (frames == 0) {
            deep();
        } else {
            below(frames - 1);
        }
    }

    private static void deep() {
        try {
            deep();
        } catch (StackOverflowError e) {
            // Unwinds one frame, and asks from there.
        }
        if (calls < LIMIT) {
            RunScope asked = worker ? SCOPES[calls] : scope;
            Object key = worker ? "k" : KEYS[calls];
            calls++;
            try {
                asked.getClosing(key, INIT);
            } catch (StackOverflowError e) {
                // The expression catches it, as a script runtime might.
            }
        }
    }

    // Ends every scope the scan asked on another thread, closing the values handed back, and tells whether that
    // finished in time: a scope whose turn was left taken would keep it waiting.
    private static boolean endOnAnotherThread() throws InterruptedException {
        AtomicReference<Throwable> failed = new AtomicReference<>();
        Thread ender = new Thread(() -> {
            try {
                for (int i = 0; i < calls; i++) {
                    closeAll(SCOPES[i].end());
                }
            } catch (Throwable e) {
                failed.set(e);
            }
        });
        ender.setDaemon(true);
        ender.start();
        ender.join(TimeUnit.SECONDS.toMillis(10));
        if (failed.get() != null) {
            failed.get().printStackTrace(System.out);
        }
        return !ender.isAlive();
    }

    private static void closeAll(List<AutoCloseable> values) throws Exception {
        for (int i = values.size() - 1; i >= 0; i--) {
            values.get(i).close();
        }
    }
}
