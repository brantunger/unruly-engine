package io.github.brantunger.unruly.mvel;

import org.jspecify.annotations.Nullable;
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

    // The characters a span of a word addWords adds may start after, a word being a run of characters between MVEL's
    // whitespace: MVEL lexes a name whole from different ones in different places, such as ,a in (,a) == 5, \a in
    // max(1+\a,2), a!b in isdef a!b/**/ and a.b in a.b--, so every span between any two of them is added. A span keeps
    // the ends inside it, so an author's word glued to one is kept whole too, and still checked, such as my-fact in
    // my-fact-1 and in my-fact*2, which MVEL reads as my minus fact (#1089). A span stops before any character that
    // isn't part of an identifier, these and others such as # or a no-break space, as MVEL stops ,a there in ,a#b.
    private static final String WORD_ENDS = "()[]{},;'\"=<>!&|?:*/+%-.";
    // A span also starts just after isdef that starts a word or follows an end, as MVEL reads #a in isdef#a.
    private static final String ISDEF = "isdef";

    // A word's spans grow as the square of the characters in it that aren't part of an identifier, each after isdef
    // counted twice, as a span may start both at it and just after it. So addNames gives up on a word with more of them
    // than an import has parts, or more characters than an import may have: the longest qualified name a rule can name
    // a class by inline fits in one word.
    private static final int MAX_WORD_STOPS = Imports.MAX_IMPORT_PARTS;
    private static final int MAX_WORD_LENGTH = Imports.MAX_IMPORT_LENGTH;
    private static final char BACKSLASH = '\\';

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
     * too; and each part of the run between MVEL's whitespace. Then adds, for each word between MVEL's whitespace,
     * what {@link #addWords} finds: names a rule's author may have meant, and names MVEL lexes with other characters
     * in them, such as {@code ,a}. MVEL reads a fact only by a name it lexed from the text, as it has no names it
     * computes or interpolates, and reads {@code this} as no fact. A name added that MVEL reads as something else, or
     * not at all, such as a word of a comment, only adds a name. A name MVEL reads with MVEL's whitespace in it can
     * still be missed, such as one {@code isdef} reads up to a comment on a later line. Gives up, with only some names
     * added, on a word of more than 1,000 characters ({@link Imports#MAX_IMPORT_LENGTH}), or with more than 64
     * characters that aren't part of an identifier ({@link Imports#MAX_IMPORT_PARTS}), each one just after
     * {@code isdef} counted twice, as its spans grow as the square of those. Scanned with loops rather than a stream,
     * as a load calls it (#1012).
     *
     * @param text  The expression's text
     * @param names The names to add to
     * @return {@code null} if every name was added, or which limit a word went past, such as
     *         {@code "a word of more than 1000 characters"}, if it gave up
     */
    static @Nullable String addNames(String text, Set<String> names) {
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
        int start = 0;
        for (int at = 0; at <= text.length(); at++) {
            if (at == text.length() || isMvelWhitespace(text.charAt(at))) {
                String limit = addWords(text, start, at, names);
                if (limit != null) {
                    return limit;
                }
                start = at + 1;
            }
        }
        return null;
    }

    /**
     * Adds the names in one word of an expression's text, a run of characters between MVEL's whitespace, which MVEL
     * may read as several names but a rule's author may have written as a fact's, such as {@code my-fact} in
     * {@code my-fact == 1}, which MVEL reads as {@code my} minus {@code fact}: so a fact named so is still checked,
     * and rejected, rather than the rule reading other facts. Adds every span of the word that starts at its start,
     * just after one of its ends, or just after {@code isdef} that starts the word or follows an end and isn't followed
     * by part of an identifier, and stops at its end or just before a character that isn't part of an identifier, as
     * {@link ParseTools#isIdentifierPart(int)} tells, an end being one of
     * {@code ( ) [ ] { } , ; ' " = < > ! & | ? : * / + % - .}: so the word itself, each part between dots, and each
     * name MVEL lexes whole, such as {@code ,a} in {@code (,a) == 5} and in {@code ,a#b}, {@code \a} in
     * {@code max(1+\a,2)}, {@code a!b} in {@code isdef a!b} with a comment glued to it, {@code #a} in
     * {@code isdef#a}, and {@code a.b} in {@code a.b--}; and for {@code my-fact-1}, {@code my}, {@code fact},
     * {@code 1}, {@code my-fact}, {@code fact-1} and {@code my-fact-1}. Adds a backslash alone for each in the word,
     * as MVEL reads {@code \\a} as the name {@code \} and then {@code \a} of it, and a high surrogate alone for each,
     * as MVEL reads a letter beyond U+FFFF as its high surrogate and then its low one of it, even for a word with too
     * many characters that aren't part of an identifier, which the compiler then forgets with every other name, but
     * not for a word of more than 1,000 characters. Adds no span of a word of more than 1,000 characters, or with more
     * than 64 that aren't part of an identifier, each one just after {@code isdef} counted twice.
     *
     * @param text  The expression's text
     * @param start Where the word starts
     * @param end   Where the word ends, just after its last character
     * @param names The names to add to
     * @return {@code null} if every name was added, or which limit the word went past
     */
    private static @Nullable String addWords(String text, int start, int end, Set<String> names) {
        if (end - start > MAX_WORD_LENGTH) {
            return "a word of more than " + MAX_WORD_LENGTH + " characters";
        }
        int stops = 0;
        for (int at = start; at < end; at++) {
            char c = text.charAt(at);
            if (!ParseTools.isIdentifierPart(c)) {
                // Twice after isdef, where a span may start both at it and just after it.
                stops += followsIsdef(text, start, at) ? 2 : 1;
                if (c == BACKSLASH || Character.isHighSurrogate(c)) {
                    names.add(String.valueOf(c));
                }
            }
        }
        if (stops > MAX_WORD_STOPS) {
            return "a word with more than " + MAX_WORD_STOPS + " characters that aren't part of an identifier, each"
                    + " one just after isdef counted twice";
        }
        for (int from = start; from < end; from++) {
            if (from == start || WORD_ENDS.indexOf(text.charAt(from - 1)) >= 0 || followsIsdef(text, start, from)) {
                for (int to = from + 1; to <= end; to++) {
                    if (to == end || !ParseTools.isIdentifierPart(text.charAt(to))) {
                        names.add(text.substring(from, to));
                    }
                }
            }
        }
        return null;
    }

    /**
     * Tells whether a character of a word follows the keyword {@code isdef} that starts the word or follows an end, and
     * isn't part of an identifier itself, so {@code isdef} is a keyword there: MVEL reads the name glued to it whole,
     * such as {@code #a} in {@code isdef#a} and {@code \a} in {@code (isdef\a)}.
     *
     * @param text  The expression's text
     * @param start Where the word starts
     * @param at    Where the character is, after the word's start
     * @return {@code true} if a name MVEL reads after {@code isdef} may start there
     */
    private static boolean followsIsdef(String text, int start, int at) {
        int keyword = at - ISDEF.length();
        return keyword >= start && text.startsWith(ISDEF, keyword)
                && (keyword == start || WORD_ENDS.indexOf(text.charAt(keyword - 1)) >= 0)
                && !ParseTools.isIdentifierPart(text.charAt(at));
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
