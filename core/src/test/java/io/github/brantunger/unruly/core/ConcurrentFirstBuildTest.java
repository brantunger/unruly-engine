package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.ChildJvm;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * #1153: the JVM's first build initializes the classes engines use once, even when two threads build at the same
 * time. A build on another thread meanwhile waits for the first to finish, rather than repeating its work or going on
 * before the classes are initialized, and a first build that throws leaves the next to initialize them. Each test but
 * one runs {@link ConcurrentFirstBuildScenario} in a new JVM, so its builds are the JVM's first. RunClasses locks on
 * itself, as a lock of its own would give it a static initializer, which a test checks it hasn't.
 */
@DisplayName("two threads that build the JVM's first engine at the same time initialize the classes once (#1153)")
class ConcurrentFirstBuildTest {

    // What -Xlog:class+init prints for RunClasses, and not for a class nested in it, followed at once by "(no method)"
    // for a class with no static initializer, as in "Initializing 'java/lang/CharSequence'(no method) (0x...)", unlike
    // "Initializing 'java/lang/String' (0x...)".
    private static final String RUN_CLASSES = "Initializing '" + RunClasses.class.getName().replace('.', '/') + "'";
    private static final String NO_INITIALIZER = "(no method)";
    private static final long TIMEOUT_SECONDS = 20;

    @Test
    @DisplayName("a second thread's first build waits for the first thread's, then initializes nothing")
    void secondWaitsForFirst(@TempDir Path dir) throws IOException, InterruptedException {
        List<String> lines = scenario(dir, "concurrent");

        assertEquals(ConcurrentFirstBuildScenario.BLOCKED_ON_FIRST,
                value(lines, ConcurrentFirstBuildScenario.SECOND_WHILE_HELD),
                "the second thread while the first was held in the warm-up:\n" + String.join("\n", lines));
        assertEquals("1", value(lines, ConcurrentFirstBuildScenario.FIRST_LOOKUPS),
                "the first thread's lookups of the digest:\n" + String.join("\n", lines));
        assertEquals("0", value(lines, ConcurrentFirstBuildScenario.SECOND_LOOKUPS),
                "the second thread's lookups of the digest:\n" + String.join("\n", lines));
        assertEquals("null", value(lines, ConcurrentFirstBuildScenario.FIRST_THREW),
                "what the first thread threw:\n" + String.join("\n", lines));
        assertEquals("null", value(lines, ConcurrentFirstBuildScenario.SECOND_THREW),
                "what the second thread threw:\n" + String.join("\n", lines));
    }

    @Test
    @DisplayName("a first build that throws in the warm-up leaves the next build, on another thread, to initialize"
            + " the classes")
    void throwLeavesNextToInitialize(@TempDir Path dir) throws IOException, InterruptedException {
        List<String> lines = scenario(dir, "retry");

        assertEquals(RuntimeException.class.getName() + ": " + ConcurrentFirstBuildScenario.FAILED_LOOKUP,
                value(lines, ConcurrentFirstBuildScenario.THREW + "1: "),
                "what the first call threw:\n" + String.join("\n", lines));
        assertEquals("1", value(lines, ConcurrentFirstBuildScenario.LOOKUPS + "1: "),
                "lookups after the first call:\n" + String.join("\n", lines));
        assertEquals("null", value(lines, ConcurrentFirstBuildScenario.THREW + "2: "),
                "what the second call threw:\n" + String.join("\n", lines));
        assertEquals("2", value(lines, ConcurrentFirstBuildScenario.LOOKUPS + "2: "),
                "lookups after the second call:\n" + String.join("\n", lines));
        assertEquals("null", value(lines, ConcurrentFirstBuildScenario.THREW + "3: "),
                "what the third call threw:\n" + String.join("\n", lines));
        assertEquals("2", value(lines, ConcurrentFirstBuildScenario.LOOKUPS + "3: "),
                "lookups after the third call:\n" + String.join("\n", lines));
    }

    @Test
    @DisplayName("RunClasses' lock is no field of its own, so it has no static initializer")
    void noStaticInitializer(@TempDir Path dir) throws IOException, InterruptedException {
        List<String> lines = scenario(dir, "once", "-Xlog:class+init=info:stdout");
        List<String> runClasses = lines.stream().filter(line -> line.contains(RUN_CLASSES)).toList();

        assertEquals(1, runClasses.size(), "RunClasses' lines:\n" + String.join("\n", lines));
        assertTrue(runClasses.get(0).contains(RUN_CLASSES + NO_INITIALIZER), "RunClasses' line: " + runClasses.get(0));
    }

    /**
     * In this JVM, whose classes the test initializes first, if no test before has, so RunClasses is told they
     * aren't, by its flag, while this thread holds its lock, as a first build that initializes them does. Another
     * thread's build is blocked on that lock, RunClasses itself, held by this thread, and once it is let go, with the
     * flag set, returns without throwing.
     */
    @Test
    @DisplayName("a build is blocked on RunClasses' lock while another holds it, then returns without throwing once "
            + "the flag is set")
    void waiterFindsInitialized() throws ReflectiveOperationException, InterruptedException {
        // So the flag set again below tells the truth, whichever tests ran before.
        RunClasses.initialize();
        Field initialized = RunClasses.class.getDeclaredField("initialized");
        initialized.setAccessible(true);
        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        Throwable[] threw = new Throwable[1];
        Thread waiter = new Thread(() -> {
            try {
                RunClasses.initialize();
            } catch (RuntimeException | Error e) {
                threw[0] = e;
            }
        }, "waiter");
        boolean blocked = false;
        synchronized (RunClasses.class) {
            initialized.setBoolean(null, false);
            try {
                waiter.start();
                long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
                while (!blocked && waiter.getState() != Thread.State.TERMINATED && System.nanoTime() - end < 0) {
                    ThreadInfo info = threads.getThreadInfo(waiter.threadId());
                    blocked = info != null && info.getThreadState() == Thread.State.BLOCKED
                            && info.getLockOwnerId() == Thread.currentThread().threadId() && info.getLockInfo() != null
                            && info.getLockInfo().getIdentityHashCode() == System.identityHashCode(RunClasses.class);
                    waiter.join(1);
                }
            } finally {
                initialized.setBoolean(null, true);
            }
        }
        waiter.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));

        assertTrue(blocked, "the waiter wasn't blocked on RunClasses held by this thread: " + waiter.getState());
        assertFalse(waiter.isAlive(), "the waiter didn't finish");
        assertNull(threw[0], "what the waiter threw");
    }

    private static List<String> scenario(Path dir, String mode, String... options)
            throws IOException, InterruptedException {
        String[] command = new String[options.length + 1];
        command[0] = "-D" + ConcurrentFirstBuildScenario.MODE + "=" + mode;
        System.arraycopy(options, 0, command, 1, options.length);
        return ChildJvm.run(dir, ConcurrentFirstBuildScenario.class, command).lines().toList();
    }

    private static String value(List<String> lines, String start) {
        return lines.stream().filter(line -> line.startsWith(start)).map(line -> line.substring(start.length()))
                .findFirst().orElse("missing");
    }
}
