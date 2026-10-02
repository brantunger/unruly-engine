package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.Fact;
import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
import io.github.brantunger.unruly.api.exception.InvalidExpressionException;
import io.github.brantunger.unruly.api.exception.RuleCompilationException;
import io.github.brantunger.unruly.api.exception.RuleExecutionException;
import io.github.brantunger.unruly.api.language.ActionResult;
import io.github.brantunger.unruly.api.language.CompiledAction;
import io.github.brantunger.unruly.api.language.ExpressionLanguage;
import io.github.brantunger.unruly.api.language.MessageText;
import io.github.brantunger.unruly.api.language.StubExpressionLanguage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * #946: the engine shortens the message of what a rule, the output factory or a language throws to 1,000 characters
 * before it escapes it. When that message holds escapes already, as the message of an exception the engine built does,
 * such as a write to read-only facts or a rule's tags it rejects, with a name in it, or a message of a rule's own that
 * copies one, the cut fell where the limit fell, inside an escape too, leaving half of it. It now leaves out an escape
 * the limit falls inside whole, for every message the engine shortens, the root cause's message in a note of a cause
 * it would otherwise hide included, and counts the message's characters left out as they're held. Raw text with no
 * escape at the limit, and the public {@link MessageText#truncate}, read as before.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
@DisplayName("a message the engine shortens is cut before an escape it holds, never inside one")
class OwnMessageCutTest {

    private static final String OUTER_ACTION = "Failed to execute action for rule 'outer-rule': ";
    private static final String OUTPUT_FACTORY = "Output factory threw ";
    private static final String NESTED_LOAD = "a nested load() failed: ";
    private static final String WRAPPED = "audit failed: ";

    /** The end of a text that holds the start of an escape {@link Failures#escape} writes and not the rest. */
    private static final Pattern HALF_AN_ESCAPE = Pattern.compile("\\\\(u[0-9a-f]{0,3})?$");

    private static RulesEngineBuilder<Map<String, Object>> builder(ExpressionLanguage language) {
        return RulesEngineBuilder.<Map<String, Object>>firstMatch(HashMap::new).language(language);
    }

    private static Rule rule(String name) {
        return Rule.builder().ruleName(name).condition("c").action("a").build();
    }

    /** An engine whose one rule runs {@code action} and fails with what it throws. */
    private static RulesEngine<Map<String, Object>> outer(CompiledAction action) {
        RulesEngine<Map<String, Object>> engine = builder(new StubExpressionLanguage().action(action)).build();
        engine.load(List.of(rule("outer-rule")));
        return engine;
    }

    /** An engine whose one rule runs {@code action}, which returns once it's done. */
    private static RulesEngine<Map<String, Object>> outer(Runnable action) {
        return outer((context, session) -> {
            action.run();
            return ActionResult.done();
        });
    }

    /** Runs the outer engine, and returns the message it failed with. */
    private static String outerFailure(RulesEngine<Map<String, Object>> engine) {
        return assertThrows(RuleExecutionException.class, () -> engine.run(new FactMap<>())).getMessage();
    }

    /**
     * A name of dashes and then characters that are escaped, each as 6 characters, long enough to be cut: each dash
     * moves the limit by one character within an escape.
     */
    private static String escapedName(int dashes) {
        return "-".repeat(dashes) + "\u0001".repeat(199);
    }

    /**
     * Asserts that the text shown of a message is its first characters, cut where the limit falls or before the escape
     * the limit falls inside, never inside one, with the count of the message's characters left out.
     */
    private static void assertCutBeforeEscape(String held, String shown) {
        int kept = shown.lastIndexOf("... (");
        assertTrue(kept > 994 && kept <= 1_000, shown);
        assertEquals(held.substring(0, kept) + "... (" + (held.length() - kept) + " more characters)", shown);
        assertFalse(HALF_AN_ESCAPE.matcher(shown.substring(0, kept)).find(),
                () -> "cut inside an escape: ..." + shown.substring(kept - 20));
    }

    /** Returns what follows a prefix a message has to start with. */
    private static String after(String prefix, String message) {
        assertTrue(message.startsWith(prefix), message);
        return message.substring(prefix.length());
    }

    /** Runs {@code thrower}, records the message of what it throws, and throws it on. */
    private static <T extends RuntimeException> void rethrow(AtomicReference<String> held, Class<T> type,
                                                             Runnable thrower) {
        T thrown = assertThrows(type, thrower::run);
        held.set(thrown.getMessage());
        throw thrown;
    }

    /** Runs the outer engine whose rule runs {@code thrower}, and asserts its failure is cut before an escape. */
    private static <T extends RuntimeException> void assertActionFailureCut(Class<T> type, Runnable thrower) {
        AtomicReference<String> held = new AtomicReference<>();

        String message = outerFailure(outer(() -> rethrow(held, type, thrower)));

        assertTrue(held.get().length() > 1_000, held.get());
        assertCutBeforeEscape(held.get(), after(OUTER_ACTION, message));
    }

    /** Builds a {@link FactMap} with two facts named {@code name}, which it rejects. */
    private static void duplicateFacts(String name) {
        new FactMap<>(new Fact<>(name, 1), new Fact<>(name, 2));
    }

    /** The rejection of two facts named {@code name}, which the engine escapes the name in. */
    private static IllegalArgumentException duplicateFactsRejection(String name) {
        return assertThrows(IllegalArgumentException.class, () -> duplicateFacts(name));
    }

    // The engine's own message, which a rule's action throws on: describe()

    @ParameterizedTest(name = "{0} dashes")
    @ValueSource(ints = {0, 1, 2, 3, 4, 5})
    @DisplayName("an action writing to the read-only facts with a long escaped name is cut before an escape")
    void actionWritingReadOnlyFacts(int dashes) {
        RulesEngine<Map<String, Object>> engine = outer((context, session) -> {
            context.facts().put(escapedName(dashes), 1);
            return ActionResult.done();
        });
        RuleExecutionException failure = assertThrows(RuleExecutionException.class,
                () -> engine.run(new FactMap<>()));
        String held = failure.getCause().getMessage();

        assertTrue(held.length() > 1_000, held);
        assertCutBeforeEscape(held, after(OUTER_ACTION, failure.getMessage()));
    }

    @ParameterizedTest(name = "{0} dashes")
    @ValueSource(ints = {0, 1, 2, 3, 4, 5})
    @DisplayName("a FactMap rejecting a duplicate long escaped name in an action is cut before an escape")
    void actionBuildingDuplicateFacts(int dashes) {
        assertActionFailureCut(IllegalArgumentException.class, () -> duplicateFacts(escapedName(dashes)));
    }

    @ParameterizedTest(name = "{0} dashes")
    @ValueSource(ints = {0, 1, 2, 3, 4, 5})
    @DisplayName("a rule's tags rejected in an action, listed escaped, are cut before an escape")
    void actionBuildingRuleWithTags(int dashes) {
        List<String> tags = new ArrayList<>();
        tags.add(escapedName(dashes));
        for (int i = 0; i < 5; i++) {
            tags.add(escapedName(0));
        }
        tags.add(" ");

        assertActionFailureCut(IllegalStateException.class,
                () -> Rule.builder().ruleName("r").condition("c").action("a").tags(tags).build());
    }

    @ParameterizedTest(name = "{0} dashes")
    @ValueSource(ints = {0, 1, 2, 3, 4, 5})
    @DisplayName("an engine built in an action with a long escaped default language is cut before an escape")
    void actionBuildingWithUnknownDefaultLanguage(int dashes) {
        assertActionFailureCut(IllegalStateException.class,
                () -> builder(new StubExpressionLanguage()).defaultLanguage(escapedName(dashes)).build());
    }

    @ParameterizedTest(name = "{0} dashes")
    @ValueSource(ints = {0, 1, 2, 3, 4, 5})
    @DisplayName("an engine built in an action with a long escaped import is cut before an escape")
    void actionBuildingWithBadImport(int dashes) {
        assertActionFailureCut(IllegalArgumentException.class,
                () -> builder(new StubExpressionLanguage()).imports(escapedName(dashes)).build());
    }

    // A rule's own message that copies the engine's: describe()

    @ParameterizedTest(name = "{0} dashes")
    @ValueSource(ints = {0, 1, 2, 3, 4, 5})
    @DisplayName("a wrapper whose own message copies an escaped one is cut before an escape")
    void wrapperCopyingEscapedMessage(int dashes) {
        assertActionFailureCut(RuntimeException.class, () -> {
            IllegalArgumentException e = duplicateFactsRejection(escapedName(dashes));
            throw new RuntimeException(WRAPPED + e.getMessage(), e);
        });
    }

    // A root cause's message, named in a note: rawCauseNote()

    @ParameterizedTest(name = "{0} dashes")
    @ValueSource(ints = {0, 1, 2, 3, 4, 5})
    @DisplayName("a hidden root cause's long escaped message, named in a note, is cut before an escape")
    void rootCauseNoteCut(int dashes) {
        IllegalArgumentException root = duplicateFactsRejection(escapedName(dashes));
        String held = root.getMessage();

        String message = outerFailure(outer(() -> {
            throw new RuntimeException((String) null, root);
        }));

        String note = after(OUTER_ACTION + "java.lang.RuntimeException (caused by "
                + "java.lang.IllegalArgumentException: ", message);
        assertTrue(note.endsWith(")"), message);
        assertTrue(held.length() > 1_000, held);
        assertCutBeforeEscape(held, note.substring(0, note.length() - 1));
    }

    // A rule's own message above a nested load() that failed, with a note: withNote()

    @ParameterizedTest(name = "{0} dashes")
    @ValueSource(ints = {0, 1, 2, 3, 4, 5})
    @DisplayName("a wrapper's own message copying a nested load()'s failure is cut before an escape, as its note is")
    void wrapperAboveNestedLoad(int dashes) {
        RulesEngine<Map<String, Object>> inner = builder(new StubExpressionLanguage().checkFactName(name -> {
            throw new IllegalArgumentException("not a name");
        })).fact(escapedName(dashes), String.class).build();
        AtomicReference<String> nested = new AtomicReference<>();
        AtomicReference<String> own = new AtomicReference<>();

        String message = outerFailure(outer(() -> {
            RuleCompilationException e = assertThrows(RuleCompilationException.class,
                    () -> inner.load(List.of(rule("inner-rule"))));
            nested.set(e.getMessage());
            own.set(WRAPPED + e.getMessage());
            throw new IllegalStateException(own.get(), e);
        }));

        String shown = after(OUTER_ACTION, message);
        int note = shown.indexOf(" (after " + NESTED_LOAD);
        assertTrue(note > 0 && shown.endsWith(")"), message);
        assertCutBeforeEscape(own.get(), shown.substring(0, note));
        assertCutBeforeEscape(nested.get(),
                shown.substring(note + " (after ".length() + NESTED_LOAD.length(), shown.length() - 1));
    }

    // A language's failed check of a declared fact's name at load(): RuleListCompiler, describe()

    @ParameterizedTest(name = "{0} dashes")
    @ValueSource(ints = {0, 1, 2, 3, 4, 5})
    @DisplayName("a declared fact's name check that fails, described escaped at load(), is cut before an escape")
    void declaredFactCheckFailing(int dashes) {
        String name = escapedName(dashes);
        RulesEngine<Map<String, Object>> engine = builder(new StubExpressionLanguage().checkFactName(n -> {
            throw new IllegalStateException("check failed");
        })).fact(name, String.class).build();

        RuleCompilationException e = assertThrows(RuleCompilationException.class,
                () -> engine.load(List.of(rule("r"))));

        assertInstanceOf(IllegalArgumentException.class, e.getCause(), e.getMessage());
        String held = e.getCause().getMessage();
        assertTrue(held.length() > 1_000, held);
        assertCutBeforeEscape(held,
                after("Declared fact '" + Failures.quote(name) + "' can't be used: ", e.getMessage()));
    }

    // An exception's toString() from the output factory: describeWithClass()

    @ParameterizedTest(name = "{0} dashes")
    @ValueSource(ints = {0, 1, 2, 3, 4, 5})
    @DisplayName("the output factory's failure with a long escaped message is cut before an escape")
    void outputFactoryFailing(int dashes) {
        IllegalArgumentException thrown = duplicateFactsRejection(escapedName(dashes));

        String message = outputFactoryFailure(() -> {
            throw thrown;
        });

        assertCutBeforeEscape(thrown.toString(), after(OUTPUT_FACTORY, message));
    }

    /** Runs an engine whose output factory is {@code factory}, and returns the message it failed with. */
    private static String outputFactoryFailure(Supplier<Map<String, Object>> factory) {
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.firstMatch(factory)
                .language(new StubExpressionLanguage()).build();
        engine.load(List.of(rule("r")));
        return assertThrows(RuntimeException.class, () -> engine.run(new FactMap<>())).getMessage();
    }

    // The root cause counts as shown only in the part kept

    /**
     * A root cause hidden behind an exception with no message is named after the first message, unless the part of
     * the message shown has the root cause's message. The part shown is the one cut before an escape, so a root cause's
     * message found only in the characters left out with the escape isn't shown, and is named.
     */
    @Test
    @DisplayName("a root cause whose message is only in the escape an action's failure was cut before is named")
    void rootCauseInTheEscapeLeftOut() {
        String text = "x".repeat(994) + "ROOT\\u0041" + "y".repeat(10);

        String message = outerFailure(outer(() -> {
            throw new IllegalArgumentException(text,
                    new RuntimeException((String) null, new IllegalStateException("ROOT\\u")));
        }));

        assertEquals(OUTER_ACTION + "x".repeat(994) + "ROOT... (16 more characters) (caused by "
                + "java.lang.IllegalStateException: ROOT\\u)", message);
    }

    @Test
    @DisplayName("a root cause found only in the escape the output factory's failure was cut before is named")
    void rootCauseInTheEscapeLeftOutWithClass() {
        String prefix = "java.lang.IllegalArgumentException: ";
        String text = "x".repeat(994 - prefix.length()) + "ROOT\\u0041" + "y".repeat(10);

        String message = outputFactoryFailure(() -> {
            throw new IllegalArgumentException(text,
                    new RuntimeException((String) null, new IllegalStateException("ROOT\\u")));
        });

        assertEquals(OUTPUT_FACTORY + prefix + "x".repeat(994 - prefix.length()) + "ROOT... (16 more characters)"
                + " (caused by java.lang.IllegalStateException: ROOT\\u)", message);
    }

    // A language's message the engine shortens raw by contract: RuleListCompiler.compile(), clip()

    @Test
    @DisplayName("a language's rejection whose text reads as an escape at the limit is cut before it")
    void invalidExpressionReadingAsAnEscape() {
        String text = "x".repeat(997) + "\\u00e9" + "y".repeat(10);
        RulesEngine<Map<String, Object>> engine = builder(new StubExpressionLanguage().compileAction(expression -> {
            throw new InvalidExpressionException(text);
        })).build();

        RuleCompilationException e = assertThrows(RuleCompilationException.class,
                () -> engine.load(List.of(rule("r"))));

        assertTrue(e.getMessage().endsWith(" " + "x".repeat(997) + "... (16 more characters)"), e.getMessage());
    }

    // A list of raw names: quoteAll(), clip()

    @Test
    @DisplayName("a list of names whose text reads as an escape at the limit is cut before it")
    void namesReadingAsAnEscape() {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            names.add("x".repeat(100));
        }
        names.add("x".repeat(80) + "\\n" + "y".repeat(10));
        String list = names.toString();

        assertEquals('\\', list.charAt(999), list);
        assertEquals(list.substring(0, 999) + "... (" + (list.length() - 999) + " more characters)",
                Failures.quoteAll(names));
    }

    // Guards: raw text with no escape at the limit, a message that fits, and MessageText.truncate, as before

    @Test
    @DisplayName("an action's raw message with no backslash is cut where the limit falls")
    void rawMessageCutAtTheLimit() {
        String message = outerFailure(outer(() -> {
            throw new IllegalStateException("x".repeat(1_500));
        }));

        assertEquals(OUTER_ACTION + "x".repeat(1_000) + "... (500 more characters)", message);
    }

    /**
     * Guards: an action's raw message with a backslash at the limit that doesn't start an escape the engine writes, or
     * an escape that ends at the limit, is cut where the limit falls, as before.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"\\q", "\\u1", "\\u12zz", "\\u-123", "\\u00E9", "an escape ending at the limit"})
    @DisplayName("an action's raw message with no escape the limit falls inside is cut where the limit falls")
    void rawMessageLookingLikeAnEscape(String how) {
        String text = switch (how) {
            case "\\u1" -> "x".repeat(998) + how;
            case "an escape ending at the limit" -> "x".repeat(994) + "\\u0001" + "y".repeat(10);
            default -> "x".repeat(998) + how + "y".repeat(10);
        };

        String message = outerFailure(outer(() -> {
            throw new IllegalStateException(text);
        }));

        assertEquals(OUTER_ACTION + text.substring(0, 1_000) + "... (" + (text.length() - 1_000)
                + " more characters)", message);
    }

    /**
     * An action's raw message with text at the limit that reads as each kind of escape the engine writes, the hex
     * digits at either end of their range and an escape that ends the message included, is cut before it.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"\\t", "\\r", "\\uabcf", "\\u00e9 ending the message"})
    @DisplayName("an action's raw message reading as an escape at the limit is cut before it")
    void rawMessageReadingAsAnEscape(String how) {
        String text = switch (how) {
            case "\\t", "\\r" -> "x".repeat(999) + how + "y".repeat(10);
            case "\\uabcf" -> "x".repeat(997) + how + "y".repeat(10);
            default -> "x".repeat(997) + "\\u00e9";
        };
        int kept = text.indexOf('\\');

        String message = outerFailure(outer(() -> {
            throw new IllegalStateException(text);
        }));

        assertEquals(OUTER_ACTION + "x".repeat(kept) + "... (" + (text.length() - kept) + " more characters)",
                message);
    }

    @Test
    @DisplayName("an action's raw message of 1,000 characters ending in a backslash is shown whole")
    void rawMessageThatFitsEndingInBackslash() {
        String text = "x".repeat(999) + "\\";

        String message = outerFailure(outer(() -> {
            throw new IllegalStateException(text);
        }));

        assertEquals(OUTER_ACTION + text, message);
    }

    @Test
    @DisplayName("an action's raw message with a character escaped at the limit is cut there, counting raw characters")
    void rawMessageEscapedAtTheLimit() {
        String message = outerFailure(outer(() -> {
            throw new IllegalStateException("x".repeat(999) + "\u0001" + "y".repeat(10));
        }));

        assertEquals(OUTER_ACTION + "x".repeat(999) + "\\u0001... (10 more characters)", message);
    }

    @Test
    @DisplayName("an escaped engine message that fits is shown whole")
    void escapedMessageThatFits() {
        AtomicReference<String> held = new AtomicReference<>();

        String message = outerFailure(outer(() -> rethrow(held, IllegalArgumentException.class,
                () -> duplicateFacts("a\u0001b"))));

        assertEquals("duplicate fact name 'a\\u0001b'", held.get());
        assertEquals(OUTER_ACTION + held.get(), message);
    }

    @Test
    @DisplayName("MessageText.truncate still cuts where the limit falls, inside text that reads as an escape too")
    void publicTruncateUnchanged() {
        assertEquals("x".repeat(997) + "\\u0... (13 more characters)",
                MessageText.truncate("x".repeat(997) + "\\u0001" + "y".repeat(10)));
    }
}
