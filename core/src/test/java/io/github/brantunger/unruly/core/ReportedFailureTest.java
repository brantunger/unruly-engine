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
import java.util.List;
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
    private static final Set<String> FIELDS_BEFORE_RECORDING = Set.of("stopped");

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
    @DisplayName("a failure whose chain has a wrapper with a message of its own names itself, after serialization too")
    void failureAroundNewsNamesItselfAfterSerialization() throws Exception {
        ReportedFailure top = failure("top: fallback", new IllegalStateException("fallback", failure("bottom", null)));

        ReportedFailure copy = roundTrip(top);

        assertSame(top, Failures.nestedRunFailure(top));
        assertSame(copy, Failures.nestedRunFailure(copy));
        assertEquals("a nested run() failed: top: fallback", Failures.describe(new IllegalStateException(copy)));
    }

    @Test
    @DisplayName("an old form wrapping a nested stop still tells the run around it that the nested run stopped")
    void oldFormWrappingAStop() throws Exception {
        ReportedFailure stop = ReportedFailure.stop("stopped", new InterruptedException(), null);
        ReportedFailure outer = asOldForm(failure("outer", stop));

        assertTrue(Failures.nestedRunStopped(roundTrip(outer), null));
    }

    /**
     * Builds a failure around one a nested {@code load()} logged and threw as is, while the thread's record of it
     * lasts, as a rule whose action loads rules does.
     *
     * @param nested What the nested load threw
     * @return The failure, built once the nested load has ended but not the run around it
     */
    private static ReportedFailure aroundNestedLoad(RuntimeException nested) {
        LoggedFailures.enter();
        try {
            LoggedFailures.enter();
            try {
                LoggedFailures.loggedByLoad(nested);
            } finally {
                LoggedFailures.leave();
            }
            return failure("outer", failure("middle", nested));
        } finally {
            LoggedFailures.leave();
        }
    }

    @Test
    @DisplayName("the failure a nested load() logged is named by what was recorded, after the run and serialization")
    void loggedBelowSurvivesTheRunAndSerialization() throws Exception {
        ReportedFailure top = aroundNestedLoad(new IllegalStateException("Duplicate rule name 'dup'"));

        ReportedFailure copy = roundTrip(top);

        assertEquals("a nested load() failed: Duplicate rule name 'dup'", Failures.describe(top));
        assertEquals("a nested load() failed: Duplicate rule name 'dup'", Failures.describe(copy));
        assertEquals("Duplicate rule name 'dup'", Failures.nestedRunFailure(copy).getMessage());
    }

    @Test
    @DisplayName("failures serialized before they recorded what a nested load() logged are named by the innermost")
    void oldFormsNameTheirInnermost() throws Exception {
        ReportedFailure top = aroundNestedLoad(new IllegalStateException("Duplicate rule name 'dup'"));
        asOldForm((ReportedFailure) top.getCause());

        assertEquals("a nested run() failed: middle", Failures.describe(roundTrip(asOldForm(top))));
    }

    @Test
    @DisplayName("failures serialized after they recorded the innermost but before what a load() logged name the "
            + "innermost")
    void formsFromBeforeLoggedBelowNameTheInnermost() throws Exception {
        ReportedFailure top = aroundNestedLoad(new IllegalStateException("Duplicate rule name 'dup'"));
        for (ReportedFailure failure : List.of(top, (ReportedFailure) top.getCause())) {
            for (String name : List.of("loggedBelow", "loggedBelowByLoad")) {
                Field field = ReportedFailure.class.getDeclaredField(name);
                field.setAccessible(true);
                field.set(failure, field.getType() == boolean.class ? Boolean.FALSE : null);
            }
        }

        assertEquals("a nested run() failed: middle", Failures.describe(roundTrip(top)));
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

    @Test
    @DisplayName("records by identity the suppressed exceptions the engine added, and a copy read back records none")
    void recordsWhatTheEngineSuppressed() throws Exception {
        ReportedFailure failure = failure("failed", null);
        IllegalStateException outside = new IllegalStateException("outside");
        IllegalStateException first = new IllegalStateException("first");
        IllegalStateException second = new IllegalStateException("second");
        failure.addSuppressed(outside);

        assertFalse(failure.suppressedByEngine(outside));

        failure.addSuppressedByEngine(first);
        failure.addSuppressedByEngine(second);

        assertTrue(failure.suppressedByEngine(first));
        assertTrue(failure.suppressedByEngine(second));
        assertFalse(failure.suppressedByEngine(outside));
        assertEquals(List.of(outside, first, second), List.of(failure.getSuppressed()));
        ReportedFailure copy = roundTrip(failure);
        assertFalse(copy.suppressedByEngine(copy.getSuppressed()[1]));
    }

    @Test
    @DisplayName("#895: a nested stop only suppressed on what was thrown tells the run around it the nested run"
            + " stopped, and a nested failure suppressed there doesn't")
    void suppressedNestedStop() {
        IllegalStateException stopped = new IllegalStateException("body failed");
        stopped.addSuppressed(ReportedFailure.stop("run() was interrupted", new InterruptedException(), null));
        IllegalStateException failed = new IllegalStateException("body failed");
        failed.addSuppressed(new ReportedFailure("a nested rule failed", null));

        assertTrue(Failures.nestedRunStopped(new RuntimeException("wrapped", stopped), null));
        assertFalse(Failures.nestedRunStopped(failed, null));
    }
}
