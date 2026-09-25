package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.exception.ExpressionKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A {@link ReportedFailure} records, when it's built, the innermost failure and the first {@link Error} in its cause
 * chain, so a chain deeper than the engine reads can still be described once. It's {@link java.io.Serializable}, and
 * one serialized before it recorded them has neither: the chain must then be read as it was before.
 */
@DisplayName("a failure of a nested run records what's below it, and one serialized before it did is read as before")
class ReportedFailureTest {

    /** The fields a {@code ReportedFailure} had before it recorded anything, which an old form has values for. */
    private static final Set<String> FIELDS_BEFORE_RECORDING = Set.of("stopped", "deadline");

    private static ReportedFailure failure(String message, Throwable cause) {
        return new ReportedFailure(message, cause, "r", ExpressionKind.ACTION);
    }

    /**
     * Serializes a failure and reads it back, as a failure crossing a process boundary is.
     *
     * @param failure The failure
     * @return The copy read back
     */
    private static ReportedFailure roundTrip(ReportedFailure failure) throws IOException, ClassNotFoundException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(failure);
        }
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            return (ReportedFailure) in.readObject();
        }
    }

    /**
     * Makes a failure look as one serialized before it recorded anything reads: every field added since holds its
     * default, as deserialization leaves a field the stream has no value for.
     *
     * @param failure The failure, changed in place
     * @return {@code failure}
     */
    private static ReportedFailure asOldForm(ReportedFailure failure) throws IllegalAccessException {
        for (Field field : ReportedFailure.class.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers()) && !FIELDS_BEFORE_RECORDING.contains(field.getName())) {
                field.setAccessible(true);
                field.set(failure, field.getType() == boolean.class ? Boolean.FALSE : null);
            }
        }
        return failure;
    }

    @Test
    @DisplayName("what a failure recorded survives serialization")
    void recordedSurvivesSerialization() throws Exception {
        AssertionError error = new AssertionError("bottom error");
        ReportedFailure top = failure("top", failure("middle", failure("bottom", error)));

        ReportedFailure copy = roundTrip(top);

        assertEquals("bottom", Failures.innermostReported(copy).getMessage());
        assertEquals("bottom error", Failures.errorInChain(copy).getMessage());
        assertEquals("a nested run() failed: bottom", Failures.describe(new IllegalStateException(copy)));
    }

    @Test
    @DisplayName("failures serialized before they recorded anything are read down the chain, as they were then")
    void formsFromBeforeReadAsBefore() throws Exception {
        ReportedFailure bottom = failure("bottom", new AssertionError("bottom error"));
        ReportedFailure top = asOldForm(failure("top", asOldForm(failure("middle", asOldForm(bottom)))));

        ReportedFailure copy = roundTrip(top);

        assertEquals("bottom", Failures.innermostReported(copy).getMessage());
        assertEquals("bottom error", Failures.errorInChain(copy).getMessage());
        assertEquals("a nested run() failed: bottom", Failures.describe(new IllegalStateException(copy)));
    }

    @Test
    @DisplayName("an old form above a new one reads what the new one recorded")
    void oldFormAboveANewOne() throws Exception {
        ReportedFailure bottom = failure("bottom", new AssertionError("bottom error"));
        ReportedFailure top = asOldForm(failure("top", failure("middle", bottom)));

        assertEquals("a nested run() failed: bottom", Failures.describe(new IllegalStateException(roundTrip(top))));
    }

    @Test
    @DisplayName("an old form wrapping a nested stop still tells the run around it that the nested run stopped")
    void oldFormWrappingAStop() throws Exception {
        ReportedFailure stop = ReportedFailure.stop("stopped", new InterruptedException(), null);
        ReportedFailure outer = asOldForm(failure("outer", stop));

        assertTrue(Failures.nestedRunStopped(roundTrip(outer), null));
    }

    @Test
    @DisplayName("an Error above a nested failure is the first error, and a second Error below it isn't")
    void firstErrorIsTheOneNearestTheTop() {
        ReportedFailure nested = failure("nested", new AssertionError("nested error"));
        AssertionError above = new AssertionError("above", nested);

        assertSame(above, Failures.errorInChain(failure("outer", above)));
        AssertionError first = new AssertionError("first", new IllegalStateException(new AssertionError("second")));
        assertSame(first, Failures.errorInChain(first));
    }

    @Test
    @DisplayName("an Error next to an old form, above or below it, is still the first error past a failure that "
            + "recorded none")
    void errorPastAnOldFormKept() throws Exception {
        AssertionError between = new AssertionError("between", failure("recorded", new IllegalStateException()));
        ReportedFailure old = asOldForm(failure("old", between));
        AssertionError above = new AssertionError("above",
                asOldForm(failure("old", failure("recorded", new IllegalStateException()))));

        assertSame(between, Failures.errorInChain(old));
        assertSame(above, Failures.errorInChain(above));
    }
}
