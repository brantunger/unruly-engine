package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.test.KitFailuresCopies;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The test kit keeps its own copy of the engine's overflow reader, {@code core.Failures.isOverflow}, in
 * {@code test.KitFailures}, which tells an {@link InternalError} around an overflow apart from a fatal one, as the
 * engine does (#1141). The kit may not use {@code core}'s internals, and {@code Failures.isOverflow} is
 * package-private, so this test is in {@code core}'s package to reach it. A change to one copy that misses the other
 * makes the kit rethrow as fatal what the engine reports as a rule's failure, or the other way round. Every case here
 * runs on both.
 */
@DisplayName("the engine's overflow reader and the test kit's copy read identically")
class KitOverflowCopyTest {

    /** One copy of the reader, and the class it's kept in. */
    private record Copy(String name, Predicate<Throwable> isOverflow) {
    }

    private static final List<Copy> COPIES = List.of(
            new Copy("core.Failures", Failures::isOverflow),
            new Copy("test.KitFailures", KitFailuresCopies::isOverflow));

    /** The kinds of throwable a {@code getCause()} may throw. */
    private static final List<Supplier<Throwable>> THROWN = List.of(
            () -> new IllegalStateException("unreadable"), StackOverflowError::new, InternalError::new);

    private static Stream<Arguments> copies() {
        return COPIES.stream().map(copy -> Arguments.of(copy.name(), copy.isOverflow()));
    }

    /** A throwable whose cause is {@code cause}. */
    private static <T extends Throwable> T causedBy(T thrown, Throwable cause) {
        thrown.initCause(cause);
        return thrown;
    }

    /** Throws what a {@code getCause()} is given to throw, checked or not. */
    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void sneakyThrow(Throwable thrown) throws T {
        throw (T) thrown;
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
            KitOverflowCopyTest.<RuntimeException>sneakyThrow(thrown);
            return null;
        }
    }

    /**
     * An exception whose {@code getCause()} returns a new exception every time, one link further down, and an
     * overflow past the links either copy reads.
     */
    private static final class Endless extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private final int depth;

        Endless(int depth) {
            super("link " + depth);
            this.depth = depth;
        }

        @Override
        public synchronized Throwable getCause() {
            return depth < 150 ? new Endless(depth + 1) : new StackOverflowError("past the links read");
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("copies")
    @DisplayName("an overflow is told by the first StackOverflowError or LinkageError in the cause chain"
            + " (#1116, #1141)")
    void overflowsAreToldAlike(String name, Predicate<Throwable> isOverflow) {
        assertTrue(isOverflow.test(new StackOverflowError()), name + ": a bare overflow");
        assertTrue(isOverflow.test(new IllegalStateException(new StackOverflowError())),
                name + ": an overflow wrapped");
        assertTrue(isOverflow.test(new InternalError(new InvocationTargetException(new StackOverflowError()))),
                name + ": an InternalError around an overflow, as the JDK wraps one");
        assertFalse(isOverflow.test(
                causedBy(new NoClassDefFoundError("Could not initialize class X"), new StackOverflowError())),
                name + ": a class an overflow left unusable");
        assertFalse(isOverflow.test(new InternalError(
                causedBy(new NoClassDefFoundError("Could not initialize class X"), new StackOverflowError()))),
                name + ": an InternalError around a class an overflow left unusable");
        assertFalse(isOverflow.test(
                new IllegalStateException(new ExceptionInInitializerError(new StackOverflowError()))),
                name + ": an initializer that overflowed");
        assertTrue(isOverflow.test(causedBy(new StackOverflowError(), new NoClassDefFoundError("after"))),
                name + ": an overflow before a LinkageError");
        assertFalse(isOverflow.test(new IllegalStateException(new InternalError("no overflow"))),
                name + ": no overflow");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("copies")
    @DisplayName("an overflow is told the same in a cause chain that loops back on itself, or never ends")
    void overflowsInOddChainsAreToldAlike(String name, Predicate<Throwable> isOverflow) {
        // First, so a reader that reads past the links it may fails here rather than going round the loop below.
        assertFalse(isOverflow.test(new Endless(0)), name + ": an overflow past the links read");

        IllegalStateException first = new IllegalStateException("first");
        IllegalArgumentException second = new IllegalArgumentException("second");
        first.initCause(second);
        second.initCause(first);
        assertFalse(isOverflow.test(first), name + ": a loop with no overflow");

        IllegalStateException top = new IllegalStateException("top");
        IllegalArgumentException middle = new IllegalArgumentException("middle");
        StackOverflowError bottom = new StackOverflowError("bottom");
        top.initCause(middle);
        middle.initCause(bottom);
        bottom.initCause(middle);
        assertTrue(isOverflow.test(top), name + ": a loop through an overflow");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("copies")
    @DisplayName("an overflow past a getCause() that throws isn't read, a fatal Error too")
    void overflowsPastUnreadableCausesAreToldAlike(String name, Predicate<Throwable> isOverflow) {
        for (Supplier<Throwable> thrown : THROWN) {
            Throwable throwing = thrown.get();
            assertFalse(isOverflow.test(new IllegalStateException("outer", new CauseThrows(throwing))),
                    name + " read past a getCause() that threw " + throwing.getClass().getName());
        }
    }
}
