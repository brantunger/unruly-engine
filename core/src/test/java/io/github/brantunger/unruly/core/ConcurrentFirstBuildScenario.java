package io.github.brantunger.unruly.core;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.security.Provider;
import java.security.Security;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Run by {@link ConcurrentFirstBuildTest} in a new JVM, so the classes engines use are initialized by the scenario's
 * calls of {@link Engines#initialize()} and no one's before. The warm-up computes a checksum, which looks the SHA-256
 * digest up in the JDK's security providers, so the scenario puts a provider of its own first, which counts the
 * lookups of each thread it watches and serves none, leaving them to the JDK's own. Its threads are daemons, and
 * all the waits of a mode share one deadline, {@link #TIMEOUT_SECONDS} seconds after it starts, well within the time
 * {@link io.github.brantunger.unruly.ChildJvm} waits for it, so a scenario that hangs still prints what it saw and
 * exits. {@value #MODE} chooses what it does.
 *
 * <ul>
 *     <li>{@code concurrent}: a first thread initializes, and its provider holds the thread in its lookup until a
 *     second thread, started then, has either finished initializing too or is blocked on RunClasses' lock, held by
 *     the first. It prints {@link #SECOND_WHILE_HELD} and which of the two, {@link #BLOCKED_ON_FIRST} or the second
 *     thread's state when it finished or the deadline passed, then lets the first go on and prints
 *     {@link #FIRST_LOOKUPS} and {@link #SECOND_LOOKUPS}, how many times each looked the digest up, and
 *     {@link #FIRST_THREW} and {@link #SECOND_THREW}, what each threw, or {@link #STILL_RUNNING}. If the first doesn't
 *     look the digest up by the deadline, it prints {@link #SECOND_WHILE_HELD} and {@link #NEVER_LOOKED_UP}, starts
 *     no second thread and exits.</li>
 *     <li>{@code retry}: the main thread initializes, and its provider throws {@link #FAILED_LOOKUP} from the first
 *     lookup; then a new thread initializes twice, so a lock the main thread still held would leave it waiting. It
 *     prints {@link #THREW} and what each call threw, or {@link #STILL_RUNNING}, and {@link #LOOKUPS} and how many
 *     lookups the two threads had made after it.</li>
 *     <li>{@code once}: the main thread initializes once, for a test that reads the JVM's log of the classes it
 *     initializes.</li>
 * </ul>
 */
final class ConcurrentFirstBuildScenario {

    /** The system property that chooses what the scenario does. */
    static final String MODE = "concurrent.build";
    static final String SECOND_WHILE_HELD = "SCENARIO second while the first is held: ";
    static final String BLOCKED_ON_FIRST = "blocked on RunClasses, held by the first";
    static final String FIRST_LOOKUPS = "SCENARIO first's lookups: ";
    static final String SECOND_LOOKUPS = "SCENARIO second's lookups: ";
    static final String FIRST_THREW = "SCENARIO first threw: ";
    static final String SECOND_THREW = "SCENARIO second threw: ";
    static final String THREW = "SCENARIO threw ";
    static final String LOOKUPS = "SCENARIO lookups after ";
    static final String FAILED_LOOKUP = "the scenario's provider failed the lookup";
    static final String STILL_RUNNING = "still running";
    static final String NEVER_LOOKED_UP = "the first never looked the digest up";

    /** How long all the waits of a mode take at most, well within the 60 seconds the test waits for the scenario. */
    private static final long TIMEOUT_SECONDS = 30;
    private static final String DIGEST = "MessageDigest";

    private ConcurrentFirstBuildScenario() {
    }

    public static void main(String[] args) throws InterruptedException {
        String mode = System.getProperty(MODE);
        if ("concurrent".equals(mode)) {
            concurrent();
        } else if ("retry".equals(mode)) {
            retry();
        } else {
            Engines.initialize();
        }
    }

    private static void concurrent() throws InterruptedException {
        long end = deadline();
        // Before either thread starts, so neither shares its work with them.
        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        Throwable[] threw = new Throwable[2];
        Thread first = daemon(() -> threw[0] = initialize(), "first");
        Thread second = daemon(() -> threw[1] = initialize(), "second");
        Lookups lookups = new Lookups(first, second, false, end);
        Security.insertProviderAt(lookups, 1);

        first.start();
        if (!lookups.held.await(remaining(end), TimeUnit.MILLISECONDS)) {
            System.out.println(SECOND_WHILE_HELD + NEVER_LOOKED_UP);
            lookups.release.countDown();
            return;
        }
        second.start();
        System.out.println(SECOND_WHILE_HELD + whileHeld(threads, first, second, end));
        lookups.release.countDown();
        first.join(remaining(end));
        second.join(remaining(end));
        System.out.println(FIRST_LOOKUPS + lookups.first.get());
        System.out.println(SECOND_LOOKUPS + lookups.second.get());
        System.out.println(FIRST_THREW + (first.isAlive() ? STILL_RUNNING : threw[0]));
        System.out.println(SECOND_THREW + (second.isAlive() ? STILL_RUNNING : threw[1]));
    }

    /**
     * Waits until the second thread has finished, or is blocked on RunClasses' lock with the first thread holding it.
     *
     * @return {@link #BLOCKED_ON_FIRST}, or the second thread's state when it finished or the deadline passed
     */
    private static String whileHeld(ThreadMXBean threads, Thread first, Thread second, long end)
            throws InterruptedException {
        while (second.getState() != Thread.State.TERMINATED && System.nanoTime() - end < 0) {
            ThreadInfo info = threads.getThreadInfo(second.threadId());
            if (info != null && info.getThreadState() == Thread.State.BLOCKED
                    && info.getLockOwnerId() == first.threadId() && info.getLockInfo() != null
                    && info.getLockInfo().getIdentityHashCode() == System.identityHashCode(RunClasses.class)) {
                return BLOCKED_ON_FIRST;
            }
            second.join(1);
        }
        return second.getState().toString();
    }

    private static void retry() throws InterruptedException {
        long end = deadline();
        Lookups[] lookups = new Lookups[1];
        Thread again = daemon(() -> {
            for (int call = 2; call <= 3; call++) {
                printCall(call, initialize(), lookups[0]);
            }
        }, "again");
        lookups[0] = new Lookups(Thread.currentThread(), again, true, end);
        Security.insertProviderAt(lookups[0], 1);
        printCall(1, initialize(), lookups[0]);
        again.start();
        again.join(remaining(end));
        if (again.isAlive()) {
            System.out.println(THREW + "2: " + STILL_RUNNING);
        }
    }

    private static void printCall(int call, Throwable threw, Lookups lookups) {
        System.out.println(THREW + call + ": " + threw);
        System.out.println(LOOKUPS + call + ": " + (lookups.first.get() + lookups.second.get()));
    }

    // When the waits of a mode end, at the latest.
    private static long deadline() {
        return System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
    }

    // The milliseconds left until a deadline, at least one, as a wait of none would wait for ever.
    private static long remaining(long end) {
        return Math.max(1, TimeUnit.NANOSECONDS.toMillis(end - System.nanoTime()));
    }

    // A thread that doesn't keep the JVM running, so the scenario exits when its main thread does.
    private static Thread daemon(Runnable task, String name) {
        Thread thread = new Thread(task, name);
        thread.setDaemon(true);
        return thread;
    }

    // What Engines.initialize() threw, or null.
    private static Throwable initialize() {
        try {
            Engines.initialize();
            return null;
        } catch (RuntimeException | Error e) {
            return e;
        }
    }

    /**
     * A provider with no services, first in the JDK's list, so every lookup of an algorithm asks it first. It counts
     * the SHA-256 digest lookups of the two threads it watches, and holds the first thread in its first one until it
     * is released, or throws from it. It holds no lock while it holds the thread, so a thread that isn't held looks
     * the digest up meanwhile, from the JDK's own provider.
     */
    private static final class Lookups extends Provider {

        private static final long serialVersionUID = 1L;

        final transient CountDownLatch held = new CountDownLatch(1);
        final transient CountDownLatch release = new CountDownLatch(1);
        final AtomicInteger first = new AtomicInteger();
        final AtomicInteger second = new AtomicInteger();
        private final transient Thread firstThread;
        private final transient Thread secondThread;
        private final boolean fail;
        private final long end;

        Lookups(Thread firstThread, Thread secondThread, boolean fail, long end) {
            super("ConcurrentFirstBuildScenario", "1", "counts digest lookups");
            this.firstThread = firstThread;
            this.secondThread = secondThread;
            this.fail = fail;
            this.end = end;
        }

        @Override
        public Service getService(String type, String algorithm) {
            if (!DIGEST.equals(type) || !Checksums.SHA_256.equals(algorithm)) {
                return null;
            }
            Thread current = Thread.currentThread();
            if (current == secondThread) {
                second.incrementAndGet();
            } else if (current == firstThread && first.incrementAndGet() == 1) {
                if (fail) {
                    throw new RuntimeException(FAILED_LOOKUP);
                }
                held.countDown();
                try {
                    if (!release.await(remaining(end), TimeUnit.MILLISECONDS)) {
                        System.out.println("SCENARIO the first was never released");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return null;
        }
    }
}
