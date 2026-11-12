package io.github.brantunger.unruly.api;

import io.github.brantunger.unruly.api.language.ExpressionKind;
import io.github.brantunger.unruly.api.language.InvalidExpressionException;
import io.github.brantunger.unruly.api.language.UnrulyException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InvalidObjectException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The engine's exceptions are {@link java.io.Serializable}, and 3.0 moved them out of the package
 * {@code api.exception}. One this version serialized reads back in full. One unruly-engine 2.x serialized is never read
 * with fields missing: the streams in {@code src/test/resources/serialized-2.35.4} are what 2.35.4 wrote (see the
 * {@code generator} folder there), and reading each one throws. What {@code run()} threw is in
 * {@code ReportedFailureTest}, next to the class it is.
 */
@DisplayName("an exception serialized by this version reads in full, and one serialized by 2.x is refused")
class SerializedExceptionsTest {

    /** The start of what {@link UnrulyException} says of a stream that has no data for it. */
    private static final String REFUSAL = "The serialized form holds no "
            + "io.github.brantunger.unruly.api.language.UnrulyException, as one written by unruly-engine 2.x doesn't";

    /**
     * Serializes an exception and reads it back, as one crossing a process boundary is.
     *
     * @param thrown The exception
     * @param <T>    Its type
     * @return The copy read back
     */
    @SuppressWarnings("unchecked")
    private static <T extends Throwable> T roundTrip(T thrown) throws IOException, ClassNotFoundException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(thrown);
        }
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            return (T) in.readObject();
        }
    }

    /**
     * Opens a stream unruly-engine 2.35.4 wrote.
     *
     * @param file The file's name in {@code src/test/resources/serialized-2.35.4}
     * @return The stream, at the object
     */
    private static ObjectInputStream from2x(String file) throws IOException {
        InputStream stored = SerializedExceptionsTest.class.getResourceAsStream("/serialized-2.35.4/" + file);
        return new ObjectInputStream(stored);
    }

    // Each class in these streams has the same name here, and its superclass was in api.exception. Read as the same
    // class, it would have its message and stack trace and none of the fields the engine's classes hold, so
    // getRuleName() and issues() would return null and failures() would throw NullPointerException. The subclass of
    // the base exception has no such field to lose, and is refused like the others.
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"base-subclass.ser", "compile-failure-subclass.ser", "run-failure-subclass.ser",
            "rejection-subclass.ser"})
    @DisplayName("an application's subclass serialized by 2.x is refused, not read without its fields (#1404)")
    void subclassFrom2xIsRefused(String file) throws Exception {
        try (ObjectInputStream in = from2x(file)) {
            InvalidObjectException refused = assertThrows(InvalidObjectException.class, in::readObject);

            assertTrue(refused.getMessage().startsWith(REFUSAL), refused.getMessage());
        }
    }

    // A guard, true before #1404 too: the stream names the class in the package it had.
    @Test
    @DisplayName("what load() threw on 2.x isn't found, since its class moved")
    void loadFailureFrom2xIsNotFound() throws Exception {
        try (ObjectInputStream in = from2x("load-failure.ser")) {
            ClassNotFoundException missing = assertThrows(ClassNotFoundException.class, in::readObject);

            assertEquals("io.github.brantunger.unruly.api.exception.RuleCompilationException", missing.getMessage());
        }
    }

    @Test
    @DisplayName("the base exception reads back with its message and cause")
    void baseExceptionRoundTrips() throws Exception {
        UnrulyException copy = roundTrip(new UnrulyException("base", new IllegalStateException("why")));

        assertEquals("base", copy.getMessage());
        assertEquals("why", copy.getCause().getMessage());
    }

    @Test
    @DisplayName("a load failure reads back with its rule name, kind, issues and every failure of the load")
    void compilationExceptionRoundTrips() throws Exception {
        RuleCompilationException first = new RuleCompilationException("first", new IllegalStateException("why"), "r1",
                ExpressionKind.CONDITION, List.of(SerializedSubclasses.issue()));
        RuleCompilationException second =
                new RuleCompilationException("second", null, "r2", ExpressionKind.ACTION, List.of());

        RuleCompilationException copy = roundTrip(new RuleCompilationException("both", List.of(first, second)));

        assertEquals("both", copy.getMessage());
        assertEquals("r1", copy.getRuleName());
        assertEquals(ExpressionKind.CONDITION, copy.getExpressionKind());
        assertEquals(List.of(SerializedSubclasses.issue()), copy.issues());
        assertEquals(List.of("first", "second"), copy.failures().stream().map(Throwable::getMessage).toList());
        assertEquals("why", copy.failures().get(0).getCause().getMessage());
        assertEquals("r2", copy.failures().get(1).getRuleName());
        assertEquals(ExpressionKind.ACTION, copy.failures().get(1).getExpressionKind());
    }

    @Test
    @DisplayName("a run failure reads back with its rule name and kind")
    void executionExceptionRoundTrips() throws Exception {
        RuleExecutionException copy = roundTrip(
                new RuleExecutionException("failed", new IllegalStateException("why"), "r1", ExpressionKind.ACTION));

        assertEquals("failed", copy.getMessage());
        assertEquals("why", copy.getCause().getMessage());
        assertEquals("r1", copy.getRuleName());
        assertEquals(ExpressionKind.ACTION, copy.getExpressionKind());
    }

    @Test
    @DisplayName("a rejection reads back with its issues")
    void invalidExpressionExceptionRoundTrips() throws Exception {
        InvalidExpressionException copy = roundTrip(new InvalidExpressionException("rejected",
                List.of(SerializedSubclasses.issue()), new IllegalStateException("why")));

        assertEquals("rejected", copy.getMessage());
        assertEquals("why", copy.getCause().getMessage());
        assertEquals(List.of(SerializedSubclasses.issue()), copy.issues());
    }

    @Test
    @DisplayName("an application's subclass of each exception reads back in full")
    void subclassesRoundTrip() throws Exception {
        SerializedSubclasses.Base base = roundTrip(new SerializedSubclasses.Base());
        SerializedSubclasses.CompileFailure compileFailure = roundTrip(new SerializedSubclasses.CompileFailure());
        SerializedSubclasses.RunFailure runFailure = roundTrip(new SerializedSubclasses.RunFailure());
        SerializedSubclasses.Rejection rejection = roundTrip(new SerializedSubclasses.Rejection());

        assertEquals("my exception", base.getMessage());
        assertEquals("my compile failure", compileFailure.getMessage());
        assertEquals("r7", compileFailure.getRuleName());
        assertEquals(ExpressionKind.ACTION, compileFailure.getExpressionKind());
        assertEquals(List.of(SerializedSubclasses.issue()), compileFailure.issues());
        assertEquals(List.of(compileFailure), compileFailure.failures());
        assertEquals("my run failure", runFailure.getMessage());
        assertEquals("r8", runFailure.getRuleName());
        assertEquals(ExpressionKind.CONDITION, runFailure.getExpressionKind());
        assertEquals("my rejection", rejection.getMessage());
        assertEquals(List.of(SerializedSubclasses.issue()), rejection.issues());
    }
}
