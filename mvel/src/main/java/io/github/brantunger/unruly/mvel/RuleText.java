package io.github.brantunger.unruly.mvel;

import org.mvel2.compiler.AbstractParser;
import org.mvel2.util.ParseTools;

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

    // The characters besides MVEL's whitespace that end a word addWords adds, with and without the comma: MVEL reads
    // a comma that starts a name as part of it, such as ,a in (,a) == 5. Then without the comma but with the
    // operators * / + %, and the comment marks they make: MVEL lexes some names whole up to one, such as ,a in
    // 1+,a == 6, and an author's word glued to one is kept whole, so it's still checked, such as my-fact in
    // my-fact*2, which MVEL reads as my minus fact times 2. Not -, which would split my-fact itself.
    private static final String WORD_ENDS = "()[]{},;'\"=<>!&|?:";
    private static final String WORD_ENDS_BUT_COMMA = "()[]{};'\"=<>!&|?:";
    private static final String WORD_ENDS_AND_OPERATORS = WORD_ENDS_BUT_COMMA + "*/+%";

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
     * Adds the names MVEL could read a variable by from an expression's text, as best a scan of the text can tell:
     * each run of characters MVEL reads as part of an identifier, as {@link ParseTools#isIdentifierPart(int)} tells,
     * wherever it is, in a string literal or a comment too; the run with MVEL's whitespace trimmed from its ends, as
     * MVEL trims a name it reads, such as {@code a} for U+0001 then {@code a}, U+0001 being part of an identifier
     * too; and each part of the run between MVEL's whitespace. Then adds the words {@link #addWords} finds: names a
     * rule's author may have meant, and names MVEL lexes with other characters in them, such as {@code ,a}. MVEL
     * reads a fact only by a name it lexed from the text, as it has no names it computes or interpolates, and reads
     * {@code this} as no fact. A name added that MVEL reads as something else, or not at all, such as a word of a
     * comment, only adds a name. A name MVEL reads whole can still be missed, such as one glued to a minus sign, as
     * {@code \a} in {@code 1-\a} or {@code a.b} in {@code a.b--}, since {@link #addWords} doesn't split a word there,
     * or one with punctuation in it, such as {@code a!b}, that {@code isdef} reads whole when a comment follows it.
     * Scanned with loops rather than a stream, as a load calls it (#1012).
     *
     * @param text  The expression's text
     * @param names The names to add to
     */
    static void addNames(String text, Set<String> names) {
        int index = 0;
        while (index < text.length()) {
            if (!ParseTools.isIdentifierPart(text.charAt(index))) {
                index++;
                continue;
            }
            int end = index + 1;
            while (end < text.length() && ParseTools.isIdentifierPart(text.charAt(end))) {
                end++;
            }
            String run = text.substring(index, end);
            addName(run, names);
            addName(run.trim(), names);
            int part = index;
            for (int at = index; at <= end; at++) {
                if (at == end || isMvelWhitespace(text.charAt(at))) {
                    addName(text.substring(part, at), names);
                    part = at + 1;
                }
            }
            index = end;
        }
        addWords(text, "", names);
        addWords(text, WORD_ENDS, names);
        addWords(text, WORD_ENDS_BUT_COMMA, names);
        addWords(text, WORD_ENDS_AND_OPERATORS, names);
    }

    /**
     * Adds each word of an expression's text, which MVEL may read as several names but a rule's author may have
     * written as a fact's, such as {@code my-fact} in {@code my-fact == 1}, which MVEL reads as {@code my} minus
     * {@code fact}: so a fact named so is still checked, and rejected, rather than the rule reading other facts. MVEL
     * also reads some such words as one name itself, such as {@code ,a} in {@code (,a) == 5}. A word is a run of
     * characters between MVEL's whitespace and the given characters, and each of its parts between dots, such as
     * {@code my-fact} in {@code my-fact.size}. Called with no characters, for words between whitespace alone, with
     * {@link #WORD_ENDS}, with them but the comma, and with those and {@code * / + %}, which also end a word MVEL
     * reads whole, such as {@code ,a} in {@code 1+,a == 6}. Only a guess at what was meant: {@code my-fact*2} adds
     * the word, {@code my-fact-1} doesn't, as a minus sign doesn't end a word, and a name with a space in it is never
     * one word.
     *
     * @param text  The expression's text
     * @param ends  The characters besides MVEL's whitespace that end a word
     * @param names The names to add to
     */
    private static void addWords(String text, String ends, Set<String> names) {
        int start = 0;
        for (int at = 0; at <= text.length(); at++) {
            if (at == text.length() || isMvelWhitespace(text.charAt(at)) || ends.indexOf(text.charAt(at)) >= 0) {
                String word = text.substring(start, at);
                addName(word, names);
                int part = 0;
                for (int dot = word.indexOf('.'); dot >= 0; dot = word.indexOf('.', part)) {
                    addName(word.substring(part, dot), names);
                    part = dot + 1;
                }
                if (part > 0) {
                    addName(word.substring(part), names);
                }
                start = at + 1;
            }
        }
    }

    private static void addName(String name, Set<String> names) {
        if (!name.isEmpty()) {
            names.add(name);
        }
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
