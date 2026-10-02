package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
import io.github.brantunger.unruly.api.exception.RuleCompilationException;
import io.github.brantunger.unruly.api.exception.RuleExecutionException;
import io.github.brantunger.unruly.api.language.ActionResult;
import io.github.brantunger.unruly.api.language.ExpressionLanguage;
import io.github.brantunger.unruly.api.language.StubExpressionLanguage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * #912: the run around a nested {@code run()} or {@code load()} that failed quotes the nested failure's message, which
 * the engine has mostly escaped already, and shortens it to 1,000 characters. It cut that message where the limit fell,
 * inside an escape too, such as a backslash, {@code u} and four hex digits, leaving half of it. It now leaves out an
 * escape the limit falls inside whole, and counts the characters of the nested failure's message it left out. A message
 * that fits, and text with no escape at the limit, read as before.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
@DisplayName("a nested failure's message is shortened before an escape, never inside one")
class NestedTextCutTest {

    private static final String OUTER_ACTION = "Failed to execute action for rule 'outer-rule': ";
    private static final String NESTED_RUN = "a nested run() failed: ";
    private static final String NESTED_LOAD = "a nested load() failed: ";
    private static final String WRAPPED = "audit failed";

    /** The end of a text that holds the start of an escape {@link Failures#escape} writes and not the rest. */
    private static final Pattern HALF_AN_ESCAPE = Pattern.compile("\\\\(u[0-9a-f]{0,3})?$");

    private static RulesEngineBuilder<Map<String, Object>> builder(ExpressionLanguage language) {
        return RulesEngineBuilder.<Map<String, Object>>firstMatch(HashMap::new).language(language);
    }

    private static Rule rule(String name) {
        return Rule.builder().ruleName(name).condition("c").action("a").build();
    }

    private static RulesEngine<Map<String, Object>> loaded(RulesEngineBuilder<Map<String, Object>> builder) {
        return loaded(builder, "inner-rule");
    }

    private static RulesEngine<Map<String, Object>> loaded(RulesEngineBuilder<Map<String, Object>> builder,
                                                           String ruleName) {
        RulesEngine<Map<String, Object>> engine = builder.build();
        engine.load(List.of(rule(ruleName)));
        return engine;
    }

    /** An engine whose one rule runs {@code action} and fails with what it throws. */
    private static RulesEngine<Map<String, Object>> outer(Executable action) {
        return loaded(builder(new StubExpressionLanguage().action((context, session) -> {
            try {
                action.execute();
            } catch (RuntimeException e) {
                throw e;
            } catch (Throwable e) {
                throw new AssertionError(e);
            }
            return ActionResult.done();
        })), "outer-rule");
    }

    /** Runs the outer engine, and returns the message it failed with. */
    private static String outerFailure(RulesEngine<Map<String, Object>> engine) {
        return assertThrows(RuleExecutionException.class, () -> engine.run(new FactMap<>())).getMessage();
    }

    /** A name of dashes and then characters that are escaped, each as 6 characters, long enough to be cut. */
    private static String escapedName(int dashes) {
        return "-".repeat(dashes) + "\u0001".repeat(199);
    }

    /**
     * Asserts that the text the outer run shows of a nested failure's message is its first characters, cut where
     * the limit falls or before the escape the limit falls inside, never inside one, with the count of the message's
     * characters left out.
     */
    private static void assertCutBeforeEscape(String nested, String shown) {
        int kept = shown.lastIndexOf("... (");
        assertTrue(kept > 994 && kept <= 1_000, shown);
        assertEquals(nested.substring(0, kept) + "... (" + (nested.length() - kept) + " more characters)", shown);
        assertFalse(HALF_AN_ESCAPE.matcher(shown.substring(0, kept)).find(),
                () -> "cut inside an escape: ..." + shown.substring(kept - 20));
    }

    /** Returns what follows a prefix the outer run's message has to start with. */
    private static String after(String prefix, String message) {
        assertTrue(message.startsWith(prefix), message);
        return message.substring(prefix.length());
    }

    /** Returns the nested failure's text in the note of a message that ends with one. */
    private static String note(String nested, String message) {
        String shown = after(OUTER_ACTION + WRAPPED + " (after " + nested, message);
        assertTrue(shown.endsWith(")"), message);
        return shown.substring(0, shown.length() - 1);
    }

    // A nested load() whose failure quotes a name

    @ParameterizedTest(name = "{0} dashes")
    @ValueSource(ints = {1, 2, 3, 4, 5, 6})
    @DisplayName("a nested load() rejecting a declared fact's long escaped name is cut before an escape")
    void nestedLoadDeclaredFact(int dashes) {
        RulesEngine<Map<String, Object>> inner = builder(new StubExpressionLanguage().checkFactName(name -> {
            throw new IllegalArgumentException("not a name");
        })).fact(escapedName(dashes), String.class).build();
        AtomicReference<String> nested = new AtomicReference<>();
        RulesEngine<Map<String, Object>> engine = outer(() -> {
            try {
                inner.load(List.of(rule("inner-rule")));
            } catch (RuleCompilationException e) {
                nested.set(e.getMessage());
                throw e;
            }
        });

        String message = outerFailure(engine);

        assertTrue(nested.get().startsWith("Declared fact '" + "-".repeat(dashes) + "\\u0001"), nested.get());
        assertCutBeforeEscape(nested.get(), after(OUTER_ACTION + NESTED_LOAD, message));
    }

    // A nested run() whose facts are rejected

    @ParameterizedTest(name = "{0} dashes")
    @ValueSource(ints = {1, 2, 3, 4, 5, 6})
    @DisplayName("a nested run() rejecting a fact's long escaped name is cut before an escape")
    void nestedRunRejectedFact(int dashes) {
        RulesEngine<Map<String, Object>> inner = loaded(builder(new StubExpressionLanguage()).requireDeclaredFacts());
        FactMap<Object> facts = new FactMap<>();
        facts.setValue(escapedName(dashes), 1);
        AtomicReference<String> nested = new AtomicReference<>();
        RulesEngine<Map<String, Object>> engine = outer(() -> {
            try {
                inner.run(facts);
            } catch (IllegalArgumentException e) {
                nested.set(e.getMessage());
                throw e;
            }
        });

        String message = outerFailure(engine);

        assertTrue(nested.get().startsWith("Fact '" + "-".repeat(dashes) + "\\u0001"), nested.get());
        assertCutBeforeEscape(nested.get(), after(OUTER_ACTION + NESTED_RUN, message));
    }

    @ParameterizedTest(name = "{0} dashes")
    @ValueSource(ints = {1, 2, 3, 4, 5, 6})
    @DisplayName("a nested run() rejecting a fact's long escaped name, wrapped with news, is cut before an escape")
    void nestedRunRejectedFactInANote(int dashes) {
        RulesEngine<Map<String, Object>> inner = loaded(builder(new StubExpressionLanguage()).requireDeclaredFacts());
        FactMap<Object> facts = new FactMap<>();
        facts.setValue(escapedName(dashes), 1);
        AtomicReference<String> nested = new AtomicReference<>();
        RulesEngine<Map<String, Object>> engine = outer(() -> {
            try {
                inner.run(facts);
            } catch (IllegalArgumentException e) {
                nested.set(e.getMessage());
                throw new IllegalStateException(WRAPPED, e);
            }
        });

        String message = outerFailure(engine);

        assertCutBeforeEscape(nested.get(), note(NESTED_RUN, message));
    }

    @ParameterizedTest(name = "{0} letters")
    @ValueSource(ints = {0, 1, 2, 3, 4, 5})
    @DisplayName("a nested run()'s failed rule, wrapped with news, is a note cut before an escape")
    void nestedRunFailureInANote(int letters) {
        RulesEngine<Map<String, Object>> inner = loaded(builder(new StubExpressionLanguage().action((context, s) -> {
            throw new IllegalStateException("x".repeat(letters) + "\u0001".repeat(300));
        })));
        AtomicReference<String> nested = new AtomicReference<>();
        RulesEngine<Map<String, Object>> engine = outer(() -> {
            try {
                inner.run(new FactMap<>());
            } catch (RuleExecutionException e) {
                nested.set(e.getMessage());
                throw new IllegalStateException(WRAPPED, e);
            }
        });

        String message = outerFailure(engine);

        assertTrue(nested.get().length() > 1_000, nested.get());
        assertCutBeforeEscape(nested.get(), note(NESTED_RUN, message));
    }

    // A language's own text, which the engine didn't escape

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"a line break", "a character escaped"})
    @DisplayName("a language's rejection holding text that reads as an escape at the limit is cut before it")
    void languageTextReadingAsAnEscape(String how) {
        String text = how.equals("a line break") ? "x".repeat(999) + "\\n" + "y".repeat(10)
                : "x".repeat(997) + "\\u00e9" + "y".repeat(10);
        int kept = how.equals("a line break") ? 999 : 997;

        String message = outerFailure(outerRejectedBy(text));

        assertEquals(OUTER_ACTION + NESTED_RUN + text.substring(0, kept) + "... (" + (text.length() - kept)
                + " more characters)", message);
    }

    /**
     * Guards: a language's text with a backslash at the limit that doesn't start an escape the engine writes, or an
     * escape that ends at the limit, and text with no backslash at all, are cut where the limit falls, as before.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"\\q", "\\u1", "\\u12zz", "\\u-123", "\\u00E9", "an escape ending at the limit",
        "no backslash"})
    @DisplayName("a language's rejection with no escape the limit falls inside is cut where the limit falls")
    void languageTextCutAtTheLimit(String how) {
        String text = switch (how) {
            case "\\u1" -> "x".repeat(998) + how;
            case "an escape ending at the limit" -> "x".repeat(994) + "\\u0001" + "y".repeat(10);
            case "no backslash" -> "x".repeat(1_500);
            default -> "x".repeat(998) + how + "y".repeat(10);
        };

        String message = outerFailure(outerRejectedBy(text));

        assertEquals(OUTER_ACTION + NESTED_RUN + text.substring(0, 1_000) + "... (" + (text.length() - 1_000)
                + " more characters)", message);
    }

    /**
     * A root cause hidden behind an exception with no message is named after a nested failure's text, unless the part
     * of the text shown has the root cause's message. The part shown is the one cut before an escape, so a root cause's
     * message found only in the characters left out with the escape isn't shown, and is named.
     */
    @Test
    @DisplayName("a root cause whose message is only in the escape a nested failure's text was cut before is named")
    void rootCauseInTheEscapeLeftOut() {
        String text = "x".repeat(994) + "ROOT\\u0041" + "y".repeat(10);
        RuntimeException rejection = new IllegalArgumentException(text,
                new RuntimeException((String) null, new IllegalStateException("ROOT\\u")));

        String message = outerFailure(outerRejectedBy(rejection));

        assertEquals(OUTER_ACTION + NESTED_RUN + "x".repeat(994) + "ROOT... (16 more characters) (caused by "
                + "java.lang.IllegalStateException: ROOT\\u)", message);
    }

    /** An outer engine whose rule runs an engine whose language rejects every fact name with {@code text}. */
    private static RulesEngine<Map<String, Object>> outerRejectedBy(String text) {
        return outerRejectedBy(new IllegalArgumentException(text));
    }

    /** An outer engine whose rule runs an engine whose language rejects every fact name with {@code rejection}. */
    private static RulesEngine<Map<String, Object>> outerRejectedBy(RuntimeException rejection) {
        RulesEngine<Map<String, Object>> inner = loaded(builder(new StubExpressionLanguage().checkFactName(name -> {
            throw rejection;
        })));
        FactMap<Object> facts = new FactMap<>();
        facts.setValue("f", 1);
        return outer(() -> inner.run(facts));
    }

    // Guards: a nested failure's message that fits is shown whole, as before

    @Test
    @DisplayName("a nested load()'s message that fits is shown whole")
    void nestedLoadThatFits() {
        RulesEngine<Map<String, Object>> inner = builder(new StubExpressionLanguage().checkFactName(name -> {
            throw new IllegalArgumentException("not\ta name");
        })).fact("a\u0001b", String.class).build();
        RulesEngine<Map<String, Object>> engine = outer(() -> inner.load(List.of(rule("inner-rule"))));

        String message = outerFailure(engine);

        assertEquals(OUTER_ACTION + NESTED_LOAD + "Declared fact 'a\\u0001b' can't be used: not\\ta name", message);
    }

    @Test
    @DisplayName("a nested run()'s failed rule that fits, wrapped with news, is a note shown whole")
    void nestedRunFailureThatFits() {
        RulesEngine<Map<String, Object>> inner = loaded(builder(new StubExpressionLanguage().action((context, s) -> {
            throw new IllegalStateException("bad\u0001value");
        })));
        RulesEngine<Map<String, Object>> engine = outer(() -> {
            try {
                inner.run(new FactMap<>());
            } catch (RuleExecutionException e) {
                throw new IllegalStateException(WRAPPED, e);
            }
        });

        String message = outerFailure(engine);

        assertEquals(OUTER_ACTION + WRAPPED + " (after " + NESTED_RUN
                + "Failed to execute action for rule 'inner-rule': bad\\u0001value)", message);
    }
}
