package io.github.brantunger.unruly.mvel;

import io.github.brantunger.unruly.api.language.ImportLimits;
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

    // A span of a word addWords adds, a word being a run of characters between MVEL's whitespace, starts at the word's
    // start or just after any character that isn't part of an identifier, as ParseTools.isIdentifierPart tells: MVEL
    // lexes a name whole from different ones in different places, such as ,a in (,a) == 5, \a in max(1+\a,2), a!b in
    // isdef a!b/**/, a.b in a.b-- and my] in x^my]+=1, so no operator, such as ^ or #, falls through a list of them
    // (#1119, #1143). A span keeps a minus sign or a dot inside it, so an author's word glued to one is kept whole
    // too, and still checked, such as my-fact in my-fact-1 and in my-fact*2, which MVEL reads as my minus fact
    // (#1089). A span stops before any character that isn't part of an identifier too, such as # or a no-break space,
    // as MVEL stops ,a there in ,a#b. A span doesn't start just after part of an identifier, so ,,c in b,,c, which MVEL
    // lexes as a name after b, isn't added, though it is in b ,,c; no rule reads it (where MVEL reads such a token, as
    // after an operator, it reads , and then its property ,c).
    // The characters below are those no name MVEL lexes holds after its first character, but for one isdef reads, or
    // one that starts with a run of characters MVEL skips, as isMvelSkipped tells: a span that holds one there is added
    // only before ++, -- or an assignment, as below, or where it starts just after isdef, or starts the word that
    // follows isdef and MVEL's whitespace, as isdef reads a name up to MVEL's whitespace, ( { or a comment, or where
    // each one it holds is in the run of characters MVEL skips that starts the span, and part of an identifier follows
    // that run, as MVEL lexes ,,a whole in x^,,a (#1172). So (,a) == 5 adds ,a but not (,a or ,a), and x^,,a adds ,,a
    // but not ,,a,b nor ,,-a, nor ,, in x^,, as a span of nothing but characters MVEL skips. A dense word of them adds
    // about as many spans as it has of them, not the square of them (#1129): a span that starts inside such a run gets
    // past it only up to the next of them. A word with many other characters that aren't part of an identifier, such as
    // minus signs, dots, backslashes or no-break spaces, which MVEL may read inside a name, or with many ++, -- or
    // assignments, or several isdefs, can still add the square of them, within the limits below.
    private static final String INSIDE_STOPS = "()[]{},;'\"=<>!&|?:*/+%#^\u00ab\u00bb\u00ac~";
    // The characters that aren't part of an identifier but have a case of their own where MVEL's lexer starts a token
    // that isn't an identifier, in mvel2 2.5.4.Final's AbstractParser.nextToken (AbstractParser.java:893-1266). Every
    // other character above MVEL's whitespace that isn't part of an identifier falls to its default: cursor++
    // (:1267-1268), which skips it and keeps the token's start, so the token MVEL lexes next starts with it, such as
    // a comma, U+00AB, U+00BB, U+00AC, a backslash, a backquote, U+00A7 or a no-break space; of INSIDE_STOPS, only the
    // comma, U+00AB, U+00BB and U+00AC.
    private static final String TOKEN_CASES = ".@=-+*;?#/:^%()[]{}<>'\"&|~!";
    // The assignment operators: MVEL reads the name before one from where its token starts, whatever it holds, as my]
    // in my]=1, and so before ++ or --, as my] in my]--; U+00AB=, U+00BB= and U+00AC= are MVEL's own short forms of
    // <<=, >>= and >>>=. Each counts only when no = follows it, so == is none.
    private static final String[] ASSIGNMENTS = {"=", "+=", "-=", "*=", "/=", "%=", "&=", "|=", "^=", "<<=", ">>=",
        ">>>=", "\u00ab=", "\u00bb=", "\u00ac="};
    // A span also starts just after isdef that starts a word or follows a character that isn't part of an identifier,
    // as MVEL reads #a in isdef#a and in x#isdef#a.
    private static final String ISDEF = "isdef";

    // The characters besides MVEL's whitespace that end a piece addPieces adds, as 2.29.0 to 2.29.4 split words
    // (#1090): with and without the comma, then without it but with * / + %.
    private static final String PIECE_ENDS = "()[]{},;'\"=<>!&|?:";
    private static final String PIECE_ENDS_BUT_COMMA = "()[]{};'\"=<>!&|?:";
    private static final String PIECE_ENDS_AND_OPERATORS = PIECE_ENDS_BUT_COMMA + "*/+%";

    // The spans addWords reads in a word grow as the square of the characters in it that aren't part of an identifier,
    // each after isdef counted twice, as a span may start both at it and just after it, and so do the spans it adds for
    // a word of dots and minus signs. So addNames gives up on a word with more of them than an import has parts, or
    // more characters than an import may have: the longest qualified name a rule can name a class by inline fits in
    // one word.
    private static final int MAX_WORD_STOPS = ImportLimits.MAX_IMPORT_PARTS;
    private static final int MAX_WORD_LENGTH = ImportLimits.MAX_IMPORT_LENGTH;
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
     * Tells whether MVEL's lexer skips a character where it starts a token, keeping the token's start before it: a
     * character that isn't part of an identifier, as {@link ParseTools#isIdentifierPart(int)} tells, nor MVEL's
     * whitespace, and that MVEL's lexer has no case of its own for, such as a comma, U+00AB, U+00BB, U+00AC, a
     * backslash, a backquote or a no-break space. So the token MVEL lexes next starts with it, such as {@code ,,a} in
     * {@code x^,,a} (#1172). MVEL skips a {@code ?} so too where an expression or a substatement starts, though it has
     * a case of its own (AbstractParser.java:989-993), and this doesn't take it: for {@code ?} then U+00AB and
     * {@code b} in parentheses, MVEL reads the name U+00AB and {@code b}, which is added anyway, as a span starts just
     * after the {@code ?}.
     *
     * @param c The character
     * @return {@code true} if MVEL skips it into the token that follows it
     */
    static boolean isMvelSkipped(char c) {
        return !ParseTools.isIdentifierPart(c) && !isMvelWhitespace(c) && TOKEN_CASES.indexOf(c) < 0;
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
     * too; and each part of the run between MVEL's whitespace. Then adds what {@link #addPieces} finds, the words
     * 2.29.4 found, so no name found before #1089 is lost. Then adds, for each word between MVEL's whitespace, what
     * {@link #addWords} finds: names a rule's author may have meant, and names MVEL lexes with other characters in
     * them, such as {@code ,a}. MVEL reads a fact only by a name it lexed from the text, as it has no names it
     * computes or interpolates, and reads {@code this} as no fact. A name added that MVEL reads as something else, or
     * not at all, such as a word of a comment, only adds a name. A name MVEL reads with MVEL's whitespace in it can
     * still be missed, such as one {@code isdef} reads up to a comment on a later line. Gives up, with only some names
     * added, on a word of more than 1,000 characters ({@link ImportLimits#MAX_IMPORT_LENGTH}), or with more than 64
     * characters that aren't part of an identifier ({@link ImportLimits#MAX_IMPORT_PARTS}), each one just after
     * {@code isdef} counted twice, as the spans it reads grow as the square of those. Scanned with loops rather than a
     * stream, as a load calls it (#1012).
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
        addPieces(text, "", names);
        addPieces(text, PIECE_ENDS, names);
        addPieces(text, PIECE_ENDS_BUT_COMMA, names);
        addPieces(text, PIECE_ENDS_AND_OPERATORS, names);
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
     * just after a character that isn't part of an identifier, as {@link ParseTools#isIdentifierPart(int)} tells, or
     * just after {@code isdef} that starts the word or follows such a character and isn't followed by part of an
     * identifier, and stops at its end or just before a character that isn't part of an identifier. A span that holds
     * one of {@code ( ) [ ] { } , ; ' " = < > ! & | ? : * / + % # ^ ~}, U+00AB, U+00BB or U+00AC after its first
     * character, which no name MVEL lexes holds there but for one {@code isdef} reads, one before an assignment, or one
     * that starts with a run of characters MVEL skips, is added only where it stops just before {@code ++}, {@code --}
     * or an assignment operator not followed by {@code =}, past MVEL's whitespace, as MVEL reads the name before one
     * from where its token starts, such as {@code my]} in {@code my]--} and in {@code x^my]+=1}; or where each of them
     * it holds is in the run of characters {@link #isMvelSkipped(char)} takes that starts it, and part of an identifier
     * follows that run, as MVEL lexes {@code ,,a} whole in {@code x^,,a}, but not {@code ,,a,b}, {@code ,,-a} or
     * {@code ,,} in {@code x^,,} (#1172); but every span is added that starts just after {@code isdef}, or starts the
     * word that follows it and MVEL's whitespace, as {@code isdef} reads a name up to MVEL's whitespace, {@code (},
     * <code>{</code> or a comment. So each part between dots, and each name MVEL lexes whole, such as {@code ,a} in
     * {@code (,a) == 5} and in {@code ,a#b}, {@code ,,a} in {@code x^,,a}, {@code \a} in {@code max(1+\a,2)},
     * {@code a!b} in {@code isdef a!b} with a comment glued to it, {@code #a} in {@code isdef#a} and in
     * {@code x#isdef#a}, {@code a.b} in {@code a.b--} and {@code my]} in {@code my]--}, but not {@code (,a} or
     * {@code ,a)} in {@code (,a) == 5}; and for {@code my-fact-1}, {@code my}, {@code fact}, {@code 1},
     * {@code my-fact}, {@code fact-1} and {@code my-fact-1}. So a word dense with those characters adds about as many
     * spans as it has of them, as a span that starts in a run of them MVEL skips gets past it only up to the next of
     * them; but in a word with many other characters that aren't part of an identifier, such as minus signs, dots,
     * backslashes or no-break spaces, or with many {@code ++}, {@code --} or assignments, or with several
     * {@code isdef}s, the spans can grow as the square of the characters that aren't part of an identifier (#1129). A
     * span doesn't start just after part of an identifier, so {@code ,,c} in {@code b,,c}, which MVEL lexes as a name
     * after {@code b}, isn't added, though it is in {@code b ,,c}; no rule reads it (where MVEL reads such a token, as
     * after an operator, it reads {@code ,} and then its property {@code ,c}).
     * Adds a backslash alone for each in the word, as MVEL reads {@code \\a} as the name {@code \} and then
     * {@code \a} of it, and a high surrogate alone for each, as MVEL reads a letter beyond U+FFFF as its high surrogate
     * and then its low one of it, even for a word with too many characters that aren't part of an identifier, which
     * the compiler then forgets with every other name, but not for a word of more than 1,000 characters. Adds no span
     * of a word of more than 1,000 characters, or with more than 64 that aren't part of an identifier, each one just
     * after {@code isdef} counted twice.
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
            boolean afterIsdef = followsIsdef(text, start, from);
            if (from == start || !ParseTools.isIdentifierPart(text.charAt(from - 1)) || afterIsdef) {
                // isdef reads a name up to MVEL's whitespace, ( { or a comment, so a span it may read keeps every stop.
                boolean whole = afterIsdef || from == start && wordFollowsIsdef(text, start);
                boolean stopInside = false;
                // Whether every character of the span so far is one MVEL skips into the name that follows: a stop in
                // that run counts no more once part of an identifier follows it, as MVEL lexes ,,a whole in x^,,a. So
                // only a stop after the run leaves the span out, every span added before #1172 still is, and a span
                // of nothing but such characters, such as ,, in x^,, still isn't.
                boolean skippedRun = true;
                for (int to = from + 1; to <= end; to++) {
                    char last = text.charAt(to - 1);
                    if (skippedRun && !isMvelSkipped(last)) {
                        skippedRun = false;
                        if (ParseTools.isIdentifierPart(last)) {
                            stopInside = false;
                        }
                    }
                    stopInside |= !whole && to - 1 > from && INSIDE_STOPS.indexOf(last) >= 0;
                    if ((to == end || !ParseTools.isIdentifierPart(text.charAt(to)))
                            && (!stopInside || beforeAssignment(text, to))) {
                        names.add(text.substring(from, to));
                    }
                }
            }
        }
        return null;
    }

    /**
     * Adds each piece of an expression's text as 2.29.0 to 2.29.4 found its words (#1090): each run of characters
     * between MVEL's whitespace and the given characters, and each of its parts between dots, such as {@code my-fact}
     * in {@code my-fact.size}. It finds some names {@link #addWords} doesn't add, such as {@code a.b('s')} in
     * {@code a.b('s') == 1}, so a fact named so is still checked as it was before #1089; called once with each set of
     * ends, it scans the text in linear time.
     *
     * @param text  The expression's text
     * @param ends  The characters besides MVEL's whitespace that end a piece
     * @param names The names to add to
     */
    private static void addPieces(String text, String ends, Set<String> names) {
        int start = 0;
        for (int at = 0; at <= text.length(); at++) {
            if (at == text.length() || isMvelWhitespace(text.charAt(at)) || ends.indexOf(text.charAt(at)) >= 0) {
                String piece = text.substring(start, at);
                addName(piece, names);
                int part = 0;
                for (int dot = piece.indexOf('.'); dot >= 0; dot = piece.indexOf('.', part)) {
                    addName(piece.substring(part, dot), names);
                    part = dot + 1;
                }
                if (part > 0) {
                    addName(piece.substring(part), names);
                }
                start = at + 1;
            }
        }
    }

    /**
     * Tells whether {@code ++}, {@code --} or an assignment operator not followed by {@code =} starts at a place in an
     * expression's text, or after MVEL's whitespace there, as MVEL trims a name before one: so a span that stops there
     * is a name MVEL may read, such as {@code my]} in {@code my] -- }.
     *
     * @param text The expression's text
     * @param at   Where a span stops
     * @return {@code true} if MVEL may read the span before {@code at} as the name an operator there changes
     */
    private static boolean beforeAssignment(String text, int at) {
        int operator = at;
        while (operator < text.length() && isMvelWhitespace(text.charAt(operator))) {
            operator++;
        }
        if (text.startsWith("++", operator) || text.startsWith("--", operator)) {
            return true;
        }
        for (String assignment : ASSIGNMENTS) {
            if (text.startsWith(assignment, operator) && !text.startsWith("=", operator + assignment.length())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Tells whether a word follows the keyword {@code isdef} and MVEL's whitespace, the keyword starting the text or
     * following MVEL's whitespace or a character that isn't part of an identifier: MVEL reads the name after it whole,
     * up to MVEL's whitespace, {@code (}, <code>{</code> or a comment, such as {@code a!b} in {@code isdef a!b} with a
     * comment glued to it and in {@code x^isdef a!b}.
     *
     * @param text  The expression's text
     * @param start Where the word starts
     * @return {@code true} if {@code isdef} may read a name from the word's start
     */
    private static boolean wordFollowsIsdef(String text, int start) {
        int keyword = start;
        while (keyword > 0 && isMvelWhitespace(text.charAt(keyword - 1))) {
            keyword--;
        }
        if (keyword == start) {
            return false;
        }
        keyword -= ISDEF.length();
        return keyword >= 0 && text.startsWith(ISDEF, keyword) && (keyword == 0
                || isMvelWhitespace(text.charAt(keyword - 1))
                || !ParseTools.isIdentifierPart(text.charAt(keyword - 1)));
    }

    /**
     * Tells whether a character of a word follows the keyword {@code isdef} that starts the word or follows a character
     * that isn't part of an identifier, and isn't part of an identifier itself, so {@code isdef} is a keyword there:
     * MVEL reads the name glued to it whole, such as {@code #a} in {@code isdef#a} and in {@code x#isdef#a}, and
     * {@code \a} in {@code (isdef\a)}.
     *
     * @param text  The expression's text
     * @param start Where the word starts
     * @param at    Where the character is, after the word's start
     * @return {@code true} if a name MVEL reads after {@code isdef} may start there
     */
    private static boolean followsIsdef(String text, int start, int at) {
        int keyword = at - ISDEF.length();
        return keyword >= start && text.startsWith(ISDEF, keyword)
                && (keyword == start || !ParseTools.isIdentifierPart(text.charAt(keyword - 1)))
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
