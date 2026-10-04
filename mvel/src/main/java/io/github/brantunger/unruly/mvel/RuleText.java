package io.github.brantunger.unruly.mvel;

import org.mvel2.compiler.AbstractParser;

import java.util.HashSet;
import java.util.Set;

/**
 * The lexical rules the module reads a rule's text by where it reads the text itself, before or after MVEL does, so
 * each scanner reads it by a named rule instead of one of its own.
 *
 * <p>
 * Two rules of whitespace are named, as the scanners need both: {@link #isMvelWhitespace(char)} is MVEL's own, for
 * text the engine must find where MVEL found it, and {@link #isWhitespace(char)} adds Java's, for text where skipping
 * more than MVEL does is safe.
 * </p>
 */
final class RuleText {

    /**
     * {@link #isMvelWhitespace(char)} as a regular expression's character class, U+0000 to U+0020, for a pattern that
     * must skip what MVEL skips.
     */
    static final String MVEL_WHITESPACE = "[\\x00-\\x20]";

    private RuleText() {
    }

    /**
     * Tells whether MVEL's lexer skips a character as whitespace: every character up to and including a space,
     * U+0000 to U+0020, a control character such as U+0001 too, and nothing above it, not even a no-break space or
     * U+2028. This is MVEL's own {@code ParseTools.isWhitespace}, named here so a scan that must find what MVEL read,
     * such as the name after an {@code import}, reads the text as MVEL does. {@link #MVEL_WHITESPACE} is the same rule
     * for a regular expression.
     *
     * @param c The character
     * @return {@code true} if MVEL skips it as whitespace
     */
    static boolean isMvelWhitespace(char c) {
        return c <= ' ';
    }

    /**
     * Tells whether a character is whitespace to either MVEL or Java: every character {@link #isMvelWhitespace(char)}
     * takes, and every one {@link Character#isWhitespace(char)} takes, such as U+2003 and U+2028, but not a no-break
     * space. A scan that skips whitespace to decide what a word is, such as whether a keyword follows a dot, uses it
     * so no character either reads as a gap can hide the dot (#721).
     *
     * @param c The character
     * @return {@code true} if MVEL or Java counts it as whitespace
     */
    static boolean isWhitespace(char c) {
        return isMvelWhitespace(c) || Character.isWhitespace(c);
    }

    /**
     * MVEL's operators that are words, such as {@code in}, {@code with} and {@code instanceof}: every one that starts
     * like an identifier, so a name can't be one.
     *
     * @return The operators, in a new set the caller may add to
     */
    static Set<String> wordOperators() {
        Set<String> words = new HashSet<>();
        for (String operator : AbstractParser.OPERATORS.keySet()) {
            if (Character.isJavaIdentifierStart(operator.charAt(0))) {
                words.add(operator);
            }
        }
        return words;
    }

    /**
     * Counts a name's dot-separated parts: one more than its dots, so a name without one has one part, and the empty
     * parts of {@code a..b} count too. Counted with a loop rather than a stream, as a load that reads a property
     * calls it, and a stream's first use would initialize the JDK's classes for it there, maybe deep in a stack
     * (#1012).
     *
     * @param name The name
     * @return How many dot-separated parts it has
     */
    static long dottedParts(String name) {
        long parts = 1;
        for (int dot = name.indexOf('.'); dot >= 0; dot = name.indexOf('.', dot + 1)) {
            parts++;
        }
        return parts;
    }
}
