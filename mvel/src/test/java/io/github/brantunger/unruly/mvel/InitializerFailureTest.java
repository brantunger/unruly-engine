package io.github.brantunger.unruly.mvel;

import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
import io.github.brantunger.unruly.api.exception.InvalidExpressionException;
import io.github.brantunger.unruly.api.exception.InvalidExpressionException.Issue;
import io.github.brantunger.unruly.api.exception.InvalidExpressionException.Issue.Severity;
import io.github.brantunger.unruly.api.exception.RuleCompilationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A rule that uses a class whose static initializer throws fails to compile. MVEL reports it as
 * {@code [Error: null]}, copying the {@link ExceptionInInitializerError}'s missing message, so the compile error names
 * the root cause itself. Each class here is used by one test only: the JVM runs a static initializer once, and every
 * later use of the class throws {@code NoClassDefFoundError: Could not initialize class}.
 */
@DisplayName("a class whose static initializer throws fails the rule, naming the root cause")
class InitializerFailureTest {

    private static final String PREFIX = "Condition for rule 'r' failed to compile at line 1, column 1: ";

    public static class PlainInit {
        public static int x = 1;

        static {
            if (x == 1) {
                throw new RuntimeException("plain init failure");
            }
        }
    }

    public static class LaterUseInit {
        public static int x = 1;

        static {
            if (x == 1) {
                throw new RuntimeException("plain init failure");
            }
        }
    }

    public static class LineBreakInit {
        public static int x = 1;

        static {
            if (x == 1) {
                throw new RuntimeException("disk" + (char) 0x0a + "full" + (char) 0x202e);
            }
        }
    }

    public static class NoMessageInit {
        public static int x = 1;

        static {
            if (x == 1) {
                throw new IllegalStateException();
            }
        }
    }

    public static class WrappedInit {
        public static int x = 1;

        static {
            if (x == 1) {
                throw new RuntimeException(new IOException("disk full"));
            }
        }
    }

    public static class ErrorInit {
        public static int x = 1;

        static {
            if (x == 1) {
                throw new AssertionError("assert in init");
            }
        }
    }

    /** An exception whose {@code getMessage()} throws. */
    public static class UnreadableMessage extends RuntimeException {
        private static final long serialVersionUID = 1L;

        @Override
        public String getMessage() {
            throw new IllegalStateException("message accessor broke");
        }
    }

    public static class UnreadableMessageInit {
        public static int x = 1;

        static {
            if (x == 1) {
                throw new UnreadableMessage();
            }
        }
    }

    public static class CycleInit {
        public static int x = 1;

        static {
            if (x == 1) {
                RuntimeException a = new RuntimeException("a");
                RuntimeException b = new RuntimeException("b");
                a.initCause(b);
                b.initCause(a);
                throw a;
            }
        }
    }

    /** An exception whose {@code getCause()} throws. */
    public static class UnreadableCause extends RuntimeException {
        private static final long serialVersionUID = 1L;

        UnreadableCause() {
            super("the real failure");
        }

        @Override
        public synchronized Throwable getCause() {
            throw new IllegalStateException("getCause accessor blew up");
        }
    }

    public static class UnreadableCauseInit {
        public static int x = 1;

        static {
            if (x == 1) {
                throw new UnreadableCause();
            }
        }
    }

    /** An exception whose {@code getCause()} returns a new one every time, so its cause chain never ends. */
    public static class EndlessCause extends RuntimeException {
        private static final long serialVersionUID = 1L;

        @Override
        public synchronized Throwable getCause() {
            return new EndlessCause();
        }
    }

    public static class EndlessCauseInit {
        public static int x = 1;

        static {
            if (x == 1) {
                throw new EndlessCause();
            }
        }
    }

    /** An exception whose {@code getMessage()} throws an {@link Error}. */
    public static class ErrorMessage extends RuntimeException {
        private static final long serialVersionUID = 1L;

        @Override
        public String getMessage() {
            throw new AssertionError("accessor asserted");
        }
    }

    public static class ErrorMessageInit {
        public static int x = 1;

        static {
            if (x == 1) {
                throw new RuntimeException(null, new ErrorMessage());
            }
        }
    }

    public static class LongCycleInit {
        public static int x = 1;

        static {
            if (x == 1) {
                RuntimeException a = new RuntimeException("a");
                RuntimeException b = new RuntimeException("b");
                RuntimeException c = new RuntimeException("c");
                a.initCause(b);
                b.initCause(c);
                c.initCause(a);
                throw a;
            }
        }
    }

    public static class LongChainInit {
        public static int x = 1;

        static {
            if (x == 1) {
                RuntimeException e = new RuntimeException("m150");
                for (int i = 149; i >= 1; i--) {
                    e = new RuntimeException("m" + i, e);
                }
                throw e;
            }
        }
    }

    public static class LongMessageInit {
        public static int x = 1;

        static {
            if (x == 1) {
                throw new IllegalStateException("L".repeat(5_000));
            }
        }
    }

    /**
     * Loads a rule whose condition reads {@code x} of the class, on a thread of its own: an unbounded walk of a cause
     * chain that never ends can't be interrupted, so a daemon thread that is left running fails the test instead of
     * hanging the build.
     */
    private static RuleCompilationException loadFailure(Class<?> initializer) throws InterruptedException {
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new)
                .build();
        // By its binary name: MVEL initializes a class it looks up by name, and not one imported.
        Rule rule = Rule.builder().ruleName("r").condition(initializer.getName() + ".x == 1")
                .action("output.put('a', 1)").build();
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Thread loading = new Thread(() -> {
            try {
                engine.load(List.of(rule));
            } catch (Throwable e) {
                thrown.set(e);
            }
        }, "InitializerFailureTest-load-" + initializer.getSimpleName());
        loading.setDaemon(true);
        loading.start();
        // A load() that doesn't return can't be stopped: the walk ignores interrupts, and Thread.stop() no longer
        // works. Only a regression leaves the thread running, spinning until the test JVM exits, and the test fails.
        loading.join(TimeUnit.SECONDS.toMillis(10));
        assertFalse(loading.isAlive(), "load() is still running after 10 seconds");
        return assertInstanceOf(RuleCompilationException.class, thrown.get());
    }

    @Test
    @DisplayName("the exception the initializer threw is named, in the message and the issue")
    void rootCauseNamed() throws InterruptedException {
        RuleCompilationException thrown = loadFailure(PlainInit.class);

        assertEquals(PREFIX + "null (caused by java.lang.RuntimeException: plain init failure)", thrown.getMessage());
        assertEquals(List.of(new Issue(Severity.ERROR, 1, 1,
                "null (caused by java.lang.RuntimeException: plain init failure)")), thrown.issues());
        assertInstanceOf(InvalidExpressionException.class, thrown.getCause());
    }

    @Test
    @DisplayName("the root cause's message is escaped in the issue as in the message, and escaped only once")
    void rootCauseMessageEscaped() throws InterruptedException {
        RuleCompilationException thrown = loadFailure(LineBreakInit.class);

        String description = "null (caused by java.lang.RuntimeException: disk\\nfull\\u202e)";
        assertEquals(PREFIX + description, thrown.getMessage());
        assertEquals(List.of(new Issue(Severity.ERROR, 1, 1, description)), thrown.issues());
        assertEquals("failed to compile at line 1, column 1: " + description, thrown.getCause().getMessage());
    }

    @Test
    @DisplayName("a later use of the class reads MVEL's own message, with no note")
    void laterUseUnchanged() throws InterruptedException {
        RuleCompilationException first = loadFailure(LaterUseInit.class);
        RuleCompilationException later = loadFailure(LaterUseInit.class);

        assertTrue(first.getMessage().contains("(caused by "), first.getMessage());
        assertTrue(later.getMessage().startsWith(PREFIX + "Could not initialize class " + LaterUseInit.class.getName()),
                later.getMessage());
        assertFalse(later.getMessage().contains("(caused by "), later.getMessage());
    }

    @Test
    @DisplayName("a root cause without a message is named by its class")
    void rootCauseWithoutAMessage() throws InterruptedException {
        assertEquals(PREFIX + "null (caused by java.lang.IllegalStateException)",
                loadFailure(NoMessageInit.class).getMessage());
    }

    @Test
    @DisplayName("the root cause is named, not the exception the initializer threw")
    void wrappedRootCause() throws InterruptedException {
        assertEquals(PREFIX + "null (caused by java.io.IOException: disk full)",
                loadFailure(WrappedInit.class).getMessage());
    }

    @Test
    @DisplayName("an error thrown by the initializer keeps MVEL's description, which is its message")
    void errorUnchanged() throws InterruptedException {
        assertEquals(PREFIX + "assert in init", loadFailure(ErrorInit.class).getMessage());
    }

    @Test
    @DisplayName("a root cause whose message can't be read is named with the note that it's unavailable")
    void rootCauseMessageUnreadable() throws InterruptedException {
        assertEquals(PREFIX + "null (caused by " + UnreadableMessage.class.getName()
                        + ": (message unavailable: java.lang.IllegalStateException))",
                loadFailure(UnreadableMessageInit.class).getMessage());
    }

    @Test
    @DisplayName("a cause chain that loops back on itself ends, naming the last exception before the loop")
    void cyclicCauseChain() throws InterruptedException {
        assertEquals(PREFIX + "null (caused by java.lang.RuntimeException: b)",
                loadFailure(CycleInit.class).getMessage());
    }

    @Test
    @DisplayName("a getCause() that throws doesn't replace the compile error, whose root cause is the exception")
    void unreadableCause() throws InterruptedException {
        RuleCompilationException thrown = loadFailure(UnreadableCauseInit.class);

        assertEquals(PREFIX + "null (caused by " + UnreadableCause.class.getName() + ": the real failure)",
                thrown.getMessage());
        assertEquals(List.of(new Issue(Severity.ERROR, 1, 1,
                "null (caused by " + UnreadableCause.class.getName() + ": the real failure)")), thrown.issues());
        assertInstanceOf(InvalidExpressionException.class, thrown.getCause());
    }

    @Test
    @DisplayName("a cause chain that never ends is read for a limited number of links")
    void endlessCauseChain() throws InterruptedException {
        assertEquals(PREFIX + "null (caused by " + EndlessCause.class.getName() + ")",
                loadFailure(EndlessCauseInit.class).getMessage());
    }

    // #661: an Error from getMessage() escaped the compile path, and the rule failed with "accessor asserted" and no
    // issue.
    @Test
    @DisplayName("a root cause whose getMessage() throws an Error is named with the note that it's unavailable")
    void rootCauseMessageThrowsAnError() throws InterruptedException {
        RuleCompilationException thrown = loadFailure(ErrorMessageInit.class);

        String description = "null (caused by " + ErrorMessage.class.getName()
                + ": (message unavailable: java.lang.AssertionError))";
        assertEquals(PREFIX + description, thrown.getMessage());
        assertEquals(List.of(new Issue(Severity.ERROR, 1, 1, description)), thrown.issues());
    }

    // #661: a cycle of two links ends on the same exception whether the loop or the cap stops the walk.
    @Test
    @DisplayName("a cause chain that loops back on itself after three links names the last exception before the loop")
    void threeLinkCauseCycle() throws InterruptedException {
        assertEquals(PREFIX + "null (caused by java.lang.RuntimeException: c)",
                loadFailure(LongCycleInit.class).getMessage());
    }

    // #661: a chain of one repeated exception can't tell which link the cap stopped on.
    @Test
    @DisplayName("a cause chain of more than 100 links is read for 100: MVEL's error, the initializer's and 98 more")
    void longCauseChainReadFor100Links() throws InterruptedException {
        assertEquals(PREFIX + "null (caused by java.lang.RuntimeException: m98)",
                loadFailure(LongChainInit.class).getMessage());
    }

    // #652: the root cause's message was copied into the issue whole.
    @Test
    @DisplayName("a root cause's long message is shortened in the issue, whose note isn't cut off")
    void longRootCauseMessageShortened() throws InterruptedException {
        RuleCompilationException thrown = loadFailure(LongMessageInit.class);

        String description = "null (caused by java.lang.IllegalStateException: " + "L".repeat(1_000)
                + "... (4000 more characters))";
        assertEquals(List.of(new Issue(Severity.ERROR, 1, 1, description)), thrown.issues());
        assertEquals("failed to compile at line 1, column 1: " + description, thrown.getCause().getMessage());
    }
}
