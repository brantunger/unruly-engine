package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.mvel.ExceptionReadsCopies;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code mvel.ExceptionReads} keeps its own copy of the exception readers in {@code core.Failures}: {@code messageOf},
 * {@code causeChain} and {@code rootCause}, the {@code causeOf} that {@code causeChain} goes through, and the
 * {@code read} that both {@code messageOf} and {@code causeOf} go through. The {@code mvel} package may not depend on
 * {@code core}. In {@code core.Failures}, {@code read} is private, and is reached here only through {@code messageOf}
 * and {@code causeChain}; {@code messageOf}, {@code causeOf}, {@code rootCause}, {@code causeChain} and
 * {@code MAX_CAUSE_CHAIN_LENGTH} are package-private, so this test is in {@code core}'s package to reach them. The
 * copies read the same exceptions the same way, so a change to one that misses the other makes the engine and the MVEL
 * module describe one failure differently, or lets one of them throw what an accessor of an exception threw. Every
 * case here runs on both.
 */
@DisplayName("the engine's exception readers and the copies in ExceptionReads read identically")
class ExceptionReadsCopiesTest {

    /** The readers one copy has. */
    private record Readers(Function<Throwable, String> messageOf, Function<Throwable, Throwable> rootCause,
                           Function<Throwable, List<Throwable>> causeChain) {
    }

    /** One copy of the readers, and the class it's kept in. */
    private record Copy(String name, Readers readers) {
    }

    /** A kind of throwable an accessor may throw: the name of its class, and how to make one. */
    private record Thrown(String className, Supplier<Throwable> make) {
    }

    private static final List<Copy> COPIES = List.of(
            new Copy("core.Failures", new Readers(Failures::messageOf, Failures::rootCause, Failures::causeChain)),
            new Copy("mvel.ExceptionReads", new Readers(ExceptionReadsCopies::messageOf,
                    ExceptionReadsCopies::rootCause, ExceptionReadsCopies::causeChain)));

    /**
     * The kinds of throwable an accessor may throw: an unchecked exception, a checked one, the one fatal {@link Error}
     * the engine absorbs and one it rethrows elsewhere, and one whose class is nested and whose own message can't be
     * read either. The class's name is what a note that a message is unavailable names.
     */
    private static final List<Thrown> THROWN = List.of(
            new Thrown("java.lang.IllegalStateException", () -> new IllegalStateException("unreadable")),
            new Thrown("java.io.IOException", () -> new IOException("unreadable")),
            new Thrown("java.lang.StackOverflowError", StackOverflowError::new),
            new Thrown("java.lang.InternalError", InternalError::new),
            new Thrown(MessageThrows.class.getName(),
                    () -> new MessageThrows(new IllegalStateException("unreadable"))));

    private static Stream<Arguments> copies() {
        return COPIES.stream().map(copy -> Arguments.of(copy.name(), copy.readers()));
    }

    /** Each copy with each kind of throwable an accessor may throw. */
    private static Stream<Arguments> copiesAndThrown() {
        return COPIES.stream().flatMap(copy -> THROWN.stream()
                .map(thrown -> Arguments.of(copy.name(), copy.readers(), thrown.className(), thrown.make())));
    }

    /** Throws what an accessor of one of these exceptions is given to throw, checked or not. */
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
            ExceptionReadsCopiesTest.<RuntimeException>sneakyThrow(thrown);
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
            ExceptionReadsCopiesTest.<RuntimeException>sneakyThrow(thrown);
            return null;
        }
    }

    /** An exception whose {@code getCause()} returns a new exception every time, one link further down. */
    private static final class Endless extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private final int depth;

        Endless(int depth) {
            super("link " + depth);
            this.depth = depth;
        }

        @Override
        public synchronized Throwable getCause() {
            return new Endless(depth + 1);
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

    /** Asserts that a chain is the given links, the same objects in the same order, whatever their equals() says. */
    private static void assertLinks(List<Throwable> expected, List<Throwable> chain, String message) {
        assertEquals(expected.size(), chain.size(), message);
        for (int i = 0; i < expected.size(); i++) {
            assertSame(expected.get(i), chain.get(i), message + ", link " + i);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("copies")
    @DisplayName("a message is read as it is")
    void messagesAreReadAlike(String name, Readers readers) {
        assertEquals("boom", readers.messageOf().apply(new IllegalStateException("boom")), name);
        assertNull(readers.messageOf().apply(new IllegalStateException()), name);
    }

    @ParameterizedTest(name = "{0}, {2}")
    @MethodSource("copiesAndThrown")
    @DisplayName("a message that can't be read names the class of what reading it threw")
    void unreadableMessagesAreReadAlike(String name, Readers readers, String thrownClass, Supplier<Throwable> thrown) {
        assertEquals("(message unavailable: " + thrownClass + ")",
                readers.messageOf().apply(new MessageThrows(thrown.get())),
                name + " read a getMessage() that threw " + thrownClass + " differently");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("copies")
    @DisplayName("a plain cause chain is read to its end")
    void plainChainsAreReadAlike(String name, Readers readers) {
        IllegalStateException alone = new IllegalStateException("alone");
        assertLinks(List.of(alone), readers.causeChain().apply(alone), name);
        assertSame(alone, readers.rootCause().apply(alone), name);

        IllegalArgumentException cause = new IllegalArgumentException("cause");
        IllegalStateException outer = new IllegalStateException("outer", cause);
        assertLinks(List.of(outer, cause), readers.causeChain().apply(outer), name);
        assertSame(cause, readers.rootCause().apply(outer), name);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("copies")
    @DisplayName("a cause chain that loops back on itself stops before the first link it repeats")
    void cyclicChainsStopAlike(String name, Readers readers) {
        IllegalStateException first = new IllegalStateException("first");
        IllegalArgumentException second = new IllegalArgumentException("second");
        first.initCause(second);
        second.initCause(first);
        assertLinks(List.of(first, second), readers.causeChain().apply(first), name);
        assertSame(second, readers.rootCause().apply(first), name);

        IllegalStateException top = new IllegalStateException("top");
        IllegalArgumentException middle = new IllegalArgumentException("middle");
        UnsupportedOperationException bottom = new UnsupportedOperationException("bottom");
        top.initCause(middle);
        middle.initCause(bottom);
        bottom.initCause(middle);
        assertLinks(List.of(top, middle, bottom), readers.causeChain().apply(top),
                name + " read a loop back to a middle link differently");
        assertSame(bottom, readers.rootCause().apply(top), name);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("copies")
    @DisplayName("a cause chain's links are told apart by identity, whatever their equals() and hashCode() do")
    void linksAreToldApartAlike(String name, Readers readers) {
        for (boolean hashCodeThrows : List.of(false, true)) {
            AllEqual top = new AllEqual("top", hashCodeThrows);
            AllEqual middle = new AllEqual("middle", hashCodeThrows);
            AllEqual bottom = new AllEqual("bottom", hashCodeThrows);
            top.initCause(middle);
            middle.initCause(bottom);
            String message = name + " read links that are equal"
                    + (hashCodeThrows ? ", with a hashCode() that throws," : "") + " differently";
            assertLinks(List.of(top, middle, bottom), readers.causeChain().apply(top), message);
            assertSame(bottom, readers.rootCause().apply(top), message);
        }
    }

    @ParameterizedTest(name = "{0}, {2}")
    @MethodSource("copiesAndThrown")
    @DisplayName("a cause chain stops at a link whose getCause() throws, a fatal Error too")
    void unreadableCausesStopAlike(String name, Readers readers, String thrownClass, Supplier<Throwable> thrown) {
        CauseThrows link = new CauseThrows(thrown.get());
        IllegalStateException outer = new IllegalStateException("outer", link);
        String message = name + " read past a getCause() that threw " + thrownClass;
        assertLinks(List.of(outer, link), readers.causeChain().apply(outer), message);
        assertSame(link, readers.rootCause().apply(outer), message);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("copies")
    @DisplayName("a cause chain that never ends is read to the same number of links")
    void endlessChainsStopAlike(String name, Readers readers) {
        List<Throwable> chain = readers.causeChain().apply(new Endless(0));
        assertEquals(Failures.MAX_CAUSE_CHAIN_LENGTH, chain.size(), name);
        for (int i = 0; i < chain.size(); i++) {
            assertEquals(i, ((Endless) chain.get(i)).depth, name);
        }
        Endless root = (Endless) readers.rootCause().apply(new Endless(0));
        assertEquals(Failures.MAX_CAUSE_CHAIN_LENGTH - 1, root.depth, name);
    }
}
