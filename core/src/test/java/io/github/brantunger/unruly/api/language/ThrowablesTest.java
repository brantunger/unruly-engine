package io.github.brantunger.unruly.api.language;

import io.github.brantunger.unruly.LinkageMissingRegistration;
import org.graalvm.nativeimage.MissingReflectionRegistrationError;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * #1241: the readers an expression language and the test kit read what they catch with, as the engine does. What each
 * answers is a contract for 2.x, so every case here is written out rather than read from the engine: the cases the
 * copies in mvel and the kit were checked against, with the OpenJ9 message order and a message that can't be read for
 * {@link Throwables#isWrongName}, the 100-link limit and the LinkageError-before-overflow rule for
 * {@link Throwables#isOverflow}, and what each answers for {@code null}. A suppressed exception the engine itself
 * added, which {@link Throwables#fatalError} and {@link Throwables#isInterrupt} skip, is checked in
 * {@code core.ThrowablesEngineAddedTest}, which can make one.
 */
@DisplayName("Throwables: reading what was thrown as the engine reads it")
class ThrowablesTest {

    /** How many links of a cause chain the engine reads, which the contract fixes. */
    private static final int MAX_CAUSE_CHAIN_LENGTH = 100;

    /** Throws what an accessor is given to throw, checked or not. */
    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void sneakyThrow(Throwable thrown) throws T {
        throw (T) thrown;
    }

    /** An exception whose {@code getMessage()} throws. */
    private static final class MessageThrows extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private final Throwable thrown;

        MessageThrows(Throwable thrown) {
            this.thrown = thrown;
        }

        @Override
        public String getMessage() {
            ThrowablesTest.<RuntimeException>sneakyThrow(thrown);
            return null;
        }
    }

    /** An exception whose {@code getCause()} throws. */
    private static final class CauseThrows extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private final Throwable thrown;

        CauseThrows(Throwable thrown) {
            super("cause throws");
            this.thrown = thrown;
        }

        @Override
        public synchronized Throwable getCause() {
            ThrowablesTest.<RuntimeException>sneakyThrow(thrown);
            return null;
        }
    }

    /**
     * An exception whose {@code getCause()} returns a new exception every time, one link further down, until the
     * link at a depth, which is an overflow.
     */
    private static final class Endless extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private final int depth;
        private final int overflowAt;

        Endless(int depth, int overflowAt) {
            super("link " + depth);
            this.depth = depth;
            this.overflowAt = overflowAt;
        }

        @Override
        public synchronized Throwable getCause() {
            return depth + 1 < overflowAt ? new Endless(depth + 1, overflowAt)
                    : new StackOverflowError("at " + overflowAt);
        }
    }

    /** An exception equal to every other one of its class, whose {@code hashCode()} throws if it's made to. */
    private static final class AllEqual extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private final boolean hashCodeThrows;

        AllEqual(String message, boolean hashCodeThrows) {
            super(message);
            this.hashCodeThrows = hashCodeThrows;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof AllEqual;
        }

        @Override
        public int hashCode() {
            if (hashCodeThrows) {
                throw new IllegalStateException("hashCode");
            }
            return 0;
        }
    }

    /** A {@code NoClassDefFoundError} whose {@code getMessage()} throws, as an application's class loader might. */
    private static final class UnreadableNoClassDefFoundError extends NoClassDefFoundError {
        private static final long serialVersionUID = 1L;

        @Override
        public String getMessage() {
            throw new IllegalStateException("message accessor broke");
        }
    }

    /**
     * The kinds of throwable an accessor may throw: an unchecked exception, a checked one, the one fatal {@link Error}
     * the engine absorbs and one it rethrows elsewhere, and one whose class is nested and whose own message can't be
     * read either. The class's name is what a note that a message is unavailable names.
     */
    private static Stream<Arguments> thrown() {
        return Stream.of(
                Arguments.of("java.lang.IllegalStateException",
                        (Supplier<Throwable>) () -> new IllegalStateException("unreadable")),
                Arguments.of("java.io.IOException", (Supplier<Throwable>) () -> new IOException("unreadable")),
                Arguments.of("java.lang.StackOverflowError", (Supplier<Throwable>) StackOverflowError::new),
                Arguments.of("java.lang.InternalError", (Supplier<Throwable>) InternalError::new),
                Arguments.of(MessageThrows.class.getName(),
                        (Supplier<Throwable>) () -> new MessageThrows(new IllegalStateException("unreadable"))));
    }

    /** A throwable whose cause is {@code cause}. */
    private static <T extends Throwable> T causedBy(T thrown, Throwable cause) {
        thrown.initCause(cause);
        return thrown;
    }

    /** Asserts that a chain is the given links, the same objects in the same order, whatever their equals() says. */
    private static void assertLinks(List<Throwable> expected, List<Throwable> chain, String message) {
        assertEquals(expected.size(), chain.size(), message);
        for (int i = 0; i < expected.size(); i++) {
            assertSame(expected.get(i), chain.get(i), message + ", link " + i);
        }
    }

    @Test
    @DisplayName("isOverflow: an overflow is told by the first StackOverflowError or LinkageError in the cause chain")
    void overflowsAreTold() {
        assertTrue(Throwables.isOverflow(new StackOverflowError()), "a bare overflow");
        assertTrue(Throwables.isOverflow(new IllegalStateException(new StackOverflowError())), "an overflow wrapped");
        assertTrue(Throwables.isOverflow(new InternalError(new InvocationTargetException(new StackOverflowError()))),
                "an InternalError around an overflow, as the JDK wraps one");
        assertFalse(Throwables.isOverflow(
                causedBy(new NoClassDefFoundError("Could not initialize class X"), new StackOverflowError())),
                "a class an overflow left unusable");
        assertFalse(Throwables.isOverflow(new InternalError(
                causedBy(new NoClassDefFoundError("Could not initialize class X"), new StackOverflowError()))),
                "an InternalError around a class an overflow left unusable");
        assertFalse(Throwables.isOverflow(
                new IllegalStateException(new ExceptionInInitializerError(new StackOverflowError()))),
                "an initializer that overflowed");
        assertTrue(Throwables.isOverflow(causedBy(new StackOverflowError(), new NoClassDefFoundError("after"))),
                "an overflow before a LinkageError");
        assertFalse(Throwables.isOverflow(new IllegalStateException(new InternalError("no overflow"))),
                "no overflow");
        assertFalse(Throwables.isOverflow(new IllegalStateException("plain")), "no cause");
        assertFalse(Throwables.isOverflow(null), "null");
    }

    @Test
    @DisplayName("isOverflow: a chain that loops back on itself is read as its first links, and one that never ends "
            + "to exactly 100 links")
    void overflowsInOddChains() {
        IllegalStateException first = new IllegalStateException("first");
        IllegalArgumentException second = new IllegalArgumentException("second");
        first.initCause(second);
        second.initCause(first);
        assertFalse(Throwables.isOverflow(first), "a loop with no overflow");

        IllegalStateException top = new IllegalStateException("top");
        IllegalArgumentException middle = new IllegalArgumentException("middle");
        StackOverflowError bottom = new StackOverflowError("bottom");
        top.initCause(middle);
        middle.initCause(bottom);
        bottom.initCause(middle);
        assertTrue(Throwables.isOverflow(top), "a loop through an overflow");

        // The overflow is the hundredth link, counting the exception itself as the first, so it's read; one link
        // further, it isn't.
        assertTrue(Throwables.isOverflow(new Endless(0, MAX_CAUSE_CHAIN_LENGTH - 1)),
                "an overflow as the last link read");
        assertFalse(Throwables.isOverflow(new Endless(0, MAX_CAUSE_CHAIN_LENGTH)),
                "an overflow one past the links read");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("thrown")
    @DisplayName("isOverflow: an overflow past a getCause() that throws isn't read, a fatal Error too")
    void overflowsPastUnreadableCauses(String thrownClass, Supplier<Throwable> thrown) {
        assertFalse(Throwables.isOverflow(new IllegalStateException("outer", new CauseThrows(thrown.get()))),
                "read past a getCause() that threw " + thrownClass);
    }

    @Test
    @DisplayName("isFatal: a VirtualMachineError other than an overflow, or an InternalError around one, is fatal")
    void fatalErrorsAreTold() {
        assertTrue(Throwables.isFatal(new OutOfMemoryError()), "OutOfMemoryError");
        assertTrue(Throwables.isFatal(new InternalError("no overflow")), "a plain InternalError");
        assertTrue(Throwables.isFatal(new UnknownError()), "UnknownError");
        assertTrue(Throwables.isFatal(new InternalError(
                causedBy(new NoClassDefFoundError("Could not initialize class X"), new StackOverflowError()))),
                "an InternalError around a class an overflow left unusable");
        assertFalse(Throwables.isFatal(new StackOverflowError()), "StackOverflowError");
        assertFalse(Throwables.isFatal(new InternalError(new InvocationTargetException(new StackOverflowError()))),
                "an InternalError around an overflow");
        assertFalse(Throwables.isFatal(new AssertionError()), "AssertionError");
        assertFalse(Throwables.isFatal(new NoClassDefFoundError("X")), "a LinkageError");
        assertFalse(Throwables.isFatal(new IllegalStateException(new OutOfMemoryError())), "only the throwable itself");
        assertFalse(Throwables.isFatal(null), "null");
    }

    @Test
    @DisplayName("messageOf: a message is read as it is, and a null one as null")
    void messagesAreRead() {
        assertEquals("boom", Throwables.messageOf(new IllegalStateException("boom")));
        assertNull(Throwables.messageOf(new IllegalStateException()));
        assertThrows(NullPointerException.class, () -> Throwables.messageOf(null));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("thrown")
    @DisplayName("messageOf: a message that can't be read names the class of what reading it threw")
    void unreadableMessagesAreRead(String thrownClass, Supplier<Throwable> thrown) {
        assertEquals("(message unavailable: " + thrownClass + ")",
                Throwables.messageOf(new MessageThrows(thrown.get())),
                "a getMessage() that threw " + thrownClass);
    }

    @Test
    @DisplayName("causeChain and rootCause: a plain cause chain is read to its end, into a new list")
    void plainChainsAreRead() {
        IllegalStateException alone = new IllegalStateException("alone");
        assertLinks(List.of(alone), Throwables.causeChain(alone), "alone");
        assertSame(alone, Throwables.rootCause(alone));

        IllegalArgumentException cause = new IllegalArgumentException("cause");
        IllegalStateException outer = new IllegalStateException("outer", cause);
        List<Throwable> chain = Throwables.causeChain(outer);
        assertLinks(List.of(outer, cause), chain, "outer");
        assertSame(cause, Throwables.rootCause(outer));
        assertNotSame(chain, Throwables.causeChain(outer), "a new list each time");
        chain.clear();
        assertLinks(List.of(outer, cause), Throwables.causeChain(outer), "the caller's list, changed");
    }

    @Test
    @DisplayName("causeChain: null has an empty chain, and rootCause rejects null")
    void nullChain() {
        assertEquals(List.of(), Throwables.causeChain(null));
        assertThrows(NullPointerException.class, () -> Throwables.rootCause(null));
    }

    @Test
    @DisplayName("causeChain: a cause chain that loops back on itself stops before the first link it repeats")
    void cyclicChainsStop() {
        IllegalStateException first = new IllegalStateException("first");
        IllegalArgumentException second = new IllegalArgumentException("second");
        first.initCause(second);
        second.initCause(first);
        assertLinks(List.of(first, second), Throwables.causeChain(first), "a loop back to the top");
        assertSame(second, Throwables.rootCause(first));

        IllegalStateException top = new IllegalStateException("top");
        IllegalArgumentException middle = new IllegalArgumentException("middle");
        UnsupportedOperationException bottom = new UnsupportedOperationException("bottom");
        top.initCause(middle);
        middle.initCause(bottom);
        bottom.initCause(middle);
        assertLinks(List.of(top, middle, bottom), Throwables.causeChain(top), "a loop back to a middle link");
        assertSame(bottom, Throwables.rootCause(top));
    }

    @Test
    @DisplayName("causeChain: links are told apart by identity, whatever their equals() and hashCode() do")
    void linksAreToldApart() {
        for (boolean hashCodeThrows : List.of(false, true)) {
            AllEqual top = new AllEqual("top", hashCodeThrows);
            AllEqual middle = new AllEqual("middle", hashCodeThrows);
            AllEqual bottom = new AllEqual("bottom", hashCodeThrows);
            top.initCause(middle);
            middle.initCause(bottom);
            String message = "links that are equal" + (hashCodeThrows ? ", with a hashCode() that throws" : "");
            assertLinks(List.of(top, middle, bottom), Throwables.causeChain(top), message);
            assertSame(bottom, Throwables.rootCause(top), message);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("thrown")
    @DisplayName("causeChain: a cause chain stops at a link whose getCause() throws, a fatal Error too")
    void unreadableCausesStop(String thrownClass, Supplier<Throwable> thrown) {
        CauseThrows link = new CauseThrows(thrown.get());
        IllegalStateException outer = new IllegalStateException("outer", link);
        assertLinks(List.of(outer, link), Throwables.causeChain(outer), "read past a getCause() that threw "
                + thrownClass);
        assertSame(link, Throwables.rootCause(outer));
    }

    @Test
    @DisplayName("causeChain: a cause chain that never ends is read to 100 links")
    void endlessChainsStop() {
        List<Throwable> chain = Throwables.causeChain(new Endless(0, 150));
        assertEquals(MAX_CAUSE_CHAIN_LENGTH, chain.size());
        for (int i = 0; i < chain.size(); i++) {
            assertEquals(i, ((Endless) chain.get(i)).depth);
        }
        assertEquals(MAX_CAUSE_CHAIN_LENGTH - 1, ((Endless) Throwables.rootCause(new Endless(0, 150))).depth);
    }

    @Test
    @DisplayName("fatalError: the fatal error is the throwable, a cause, or else suppressed on a link or deeper")
    void fatalErrorsAreFound() {
        OutOfMemoryError oom = new OutOfMemoryError("oom");
        assertSame(oom, Throwables.fatalError(oom), "itself");
        assertSame(oom, Throwables.fatalError(new IllegalStateException("wrapped", oom)), "a cause");
        assertSame(oom, Throwables.fatalError(new IllegalStateException("outer", new IllegalStateException(oom))),
                "a cause's cause");

        IllegalStateException body = new IllegalStateException("body", new IllegalArgumentException("inner"));
        body.getCause().addSuppressed(oom);
        assertSame(oom, Throwables.fatalError(body), "suppressed on a link");

        IllegalStateException carrier = new IllegalStateException("carrier", new IllegalStateException("wraps", oom));
        IllegalStateException top = new IllegalStateException("top");
        top.addSuppressed(carrier);
        assertSame(oom, Throwables.fatalError(top), "in the cause chain of a suppressed exception");

        // In the cause chain first, before a fatal error suppressed on a link above it.
        OutOfMemoryError suppressedFirst = new OutOfMemoryError("suppressed");
        IllegalStateException both = new IllegalStateException("both", new IllegalStateException(oom));
        both.addSuppressed(suppressedFirst);
        assertSame(oom, Throwables.fatalError(both), "the cause chain comes first");

        assertNull(Throwables.fatalError(new IllegalStateException("plain", new StackOverflowError())), "none");
        assertNull(Throwables.fatalError(null), "null");
    }

    @Test
    @DisplayName("isInterrupt: an InterruptedException is the throwable, a cause, or suppressed on a link or deeper")
    void interruptsAreFound() {
        InterruptedException interrupt = new InterruptedException("stop");
        assertTrue(Throwables.isInterrupt(interrupt), "itself");
        assertTrue(Throwables.isInterrupt(new IllegalStateException("wrapped", interrupt)), "a cause");

        IllegalStateException body = new IllegalStateException("body", new IllegalArgumentException("inner"));
        body.getCause().addSuppressed(interrupt);
        assertTrue(Throwables.isInterrupt(body), "suppressed on a link");

        IllegalStateException top = new IllegalStateException("top");
        top.addSuppressed(new IllegalStateException("carrier", interrupt));
        assertTrue(Throwables.isInterrupt(top), "in the cause chain of a suppressed exception");

        assertFalse(Throwables.isInterrupt(new InterruptedIOException("not one")), "an InterruptedIOException");
        assertFalse(Throwables.isInterrupt(new IllegalStateException("plain")), "none");
        assertFalse(Throwables.isInterrupt(null), "null");
    }

    /** One isWrongName case: the error, the name looked up, and whether it's the "wrong name" error for it. */
    private record WrongNameCase(Throwable error, String name, boolean wrongName) {
    }

    @Test
    @DisplayName("isWrongName: the JVM's \"wrong name\" error is told with the name first, as HotSpot writes it, or "
            + "last, as OpenJ9 does, and from no other error or message")
    void wrongNamesAreTold() {
        List<WrongNameCase> cases = List.of(
                new WrongNameCase(new NoClassDefFoundError("applicant (wrong name: Applicant)"), "applicant", true),
                new WrongNameCase(new NoClassDefFoundError("p/a (wrong name: p/A)"), "p.a", true),
                new WrongNameCase(new NoClassDefFoundError("p/Rules$Limit (wrong name: p/rules$Limit)"),
                        "p.Rules$Limit", true),
                new WrongNameCase(new NoClassDefFoundError("p/A (wrong name: p/a)"), "p.a", true),
                new WrongNameCase(new NoClassDefFoundError("p/rules$Limit (wrong name: p/Rules$Limit)"),
                        "p.Rules$Limit", true),
                new WrongNameCase(new NoClassDefFoundError("p/Base (wrong name: p/BASE)"), "p.Sub", false),
                new WrongNameCase(new NoClassDefFoundError("p/BASE (wrong name: p/Base)"), "p.Sub", false),
                new WrongNameCase(new NoClassDefFoundError("p/SUBX (wrong name: p/SubX)"), "p.Sub", false),
                new WrongNameCase(new NoClassDefFoundError("p/A (wrong name: p/a) x"), "p.a", false),
                new WrongNameCase(new NoClassDefFoundError("p/SubX (wrong name: p/SUBX)"), "p.Sub", false),
                new WrongNameCase(new NoClassDefFoundError("p/Sub$In (wrong name: p/SUB$In)"), "p.Sub", false),
                new WrongNameCase(new NoClassDefFoundError("x p/a (wrong name: p/A)"), "p.a", false),
                new WrongNameCase(new NoClassDefFoundError("p/Base"), "p.Base", false),
                new WrongNameCase(new NoClassDefFoundError(), "p.a", false),
                new WrongNameCase(new UnreadableNoClassDefFoundError(), "p.a", false),
                new WrongNameCase(new IllegalAccessError("p/a (wrong name: p/A)"), "p.a", false),
                new WrongNameCase(new ClassNotFoundException("p/a (wrong name: p/A)"), "p.a", false),
                new WrongNameCase(null, "p.a", false));
        for (int i = 0; i < cases.size(); i++) {
            WrongNameCase expected = cases.get(i);
            // Named by its place, as an error whose message can't be read can't be printed either.
            assertEquals(expected.wrongName(), Throwables.isWrongName(expected.error(), expected.name()),
                    "case " + i + ", for " + expected.name());
        }
        assertThrows(NullPointerException.class,
                () -> Throwables.isWrongName(new NoClassDefFoundError("p/a (wrong name: p/A)"), null));
    }

    @Test
    @DisplayName("isMissingRegistration: GraalVM's error is told by its class's name, as an Error and as a "
            + "LinkageError, and no other error is taken for it")
    void missingRegistrationsAreTold() {
        assertTrue(Throwables.isMissingRegistration(new MissingReflectionRegistrationError("p.A")), "an Error");
        assertTrue(Throwables.isMissingRegistration(LinkageMissingRegistration.of("p.A")), "a LinkageError");
        assertFalse(Throwables.isMissingRegistration(new NoClassDefFoundError("p/A")), "a NoClassDefFoundError");
        assertFalse(Throwables.isMissingRegistration(new StackOverflowError()), "a StackOverflowError");
        assertFalse(Throwables.isMissingRegistration(new ClassNotFoundException("p.A")), "an exception");
        assertFalse(Throwables.isMissingRegistration(null), "null");
    }
}
