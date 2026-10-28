package io.github.brantunger.unruly.internal;

import org.jspecify.annotations.Nullable;

import java.util.Collection;

/**
 * How the engine shortens and escapes text for a message: a fact, rule or language name, a message a language or an
 * application's class wrote, and a list of names. The limits and the escapes are what {@code api.language.MessageText}
 * publishes, and {@code api.Names} and {@code api.LoggingRuleListener} show names with, rather than keeping a copy that
 * could drift. <b>Internal:</b> this class may change in any release, and isn't part of the API: its package isn't
 * exported, and the {@code api} and {@code core} packages share it. It has no static initializer of its own, so its own
 * first use deep in a stack can't break it.
 */
public final class Text {

    /**
     * How much of a fact, rule or language name a message includes; see {@link #quote}. {@code MessageText} publishes
     * it.
     */
    public static final int MAX_NAME_LENGTH = 200;

    /**
     * How much of an exception's message an error message includes; see {@link #truncate} and {@code core.Failures}.
     * {@code MessageText} publishes it.
     */
    public static final int MAX_DESCRIPTION_LENGTH = 1_000;

    // The last code point of Latin-1, up to which the JDK's table of the characters is built at startup (#1286).
    private static final int LAST_LATIN_1 = 0xFF;

    private Text() {
    }

    /**
     * Shortens a message to at most {@value #MAX_DESCRIPTION_LENGTH} characters, as {@link #shorten(String, int)}
     * does. {@code api.language.MessageText} publishes this.
     *
     * @param text The message
     * @return The message, shortened if it was longer
     */
    public static String truncate(String text) {
        return shorten(text, MAX_DESCRIPTION_LENGTH);
    }

    /**
     * Makes text safe to put in a message: shortened as {@link #shortenBeforeEscape} shortens it, then
     * {@link #escape escaped}. Raw text, such as a name or a language's message, is never cut inside an escape, as it's
     * escaped after it's cut. Text can hold escapes already, though: the message of an exception the engine built,
     * such as a rule that fails to compile or a fact it rejects, with a language's message or a name in it, a nested
     * failure's message, and a message of a rule's own that copies one. Those are cut before an escape, never inside
     * one, so up to 5 fewer characters show; where raw text only reads as an escape, those characters are lost.
     *
     * @param text The text
     * @return The text, shortened if it was longer, then escaped
     */
    public static String clip(String text) {
        return escape(shortenBeforeEscape(text));
    }

    /**
     * Shortens text to at most {@value #MAX_DESCRIPTION_LENGTH} characters as {@link #truncate} does, except where the
     * limit falls inside an escape {@link #escape} writes ({@code \n}, {@code \r}, {@code \t}, or a backslash,
     * {@code u} and four lowercase hex digits): the escape is left out whole, and the text keeps at most 5 characters
     * fewer. {@link #escape} doesn't escape a backslash, so text that only reads as an escape is left out the same way.
     * The count of what was left out counts the characters of the text as given: escaped where it was escaped already,
     * such as a nested failure's message as it's held, and raw otherwise. So it matches the count in the line a nested
     * failure was logged with only when that line shows the same text.
     *
     * @param text The text, escaped or not
     * @return The text, shortened if it was longer, not escaped
     */
    public static String shortenBeforeEscape(String text) {
        if (text.length() <= MAX_DESCRIPTION_LENGTH) {
            return text;
        }
        return cut(text, keptBeforeEscape(text, MAX_DESCRIPTION_LENGTH));
    }

    /**
     * How many characters of text longer than {@code limit} to keep, as {@link #shortenBeforeEscape} keeps
     * {@value #MAX_DESCRIPTION_LENGTH} and {@link #shorten(String)} keeps {@value #MAX_NAME_LENGTH}: as many as
     * {@link #shorten(String, int)} keeps, or fewer, up to the backslash of an escape they would end inside, or of text
     * that reads as one.
     *
     * @param text  The text, longer than {@code limit}
     * @param limit The most characters to keep, at least 1
     * @return How many of its first characters to keep
     */
    public static int keptBeforeEscape(String text, int limit) {
        int kept = keptLength(text, limit);
        int backslash = text.lastIndexOf('\\', kept - 1);
        return backslash >= 0 && backslash + escapeLength(text, backslash) > kept ? backslash : kept;
    }

    /**
     * Tells whether a cut at {@code at} falls inside an escape {@link #escape} writes, or inside text that reads as
     * one. Only the last backslash before the cut can start one, as no escape holds a backslash after its first
     * character, and only one at most 5 characters before it, as no escape is longer than 6.
     *
     * @param text The text
     * @param at   Where the text would be cut
     * @return {@code true} if an escape starts before the cut and ends after it
     */
    private static boolean insideEscape(String text, int at) {
        int from = Math.max(0, at - 5);
        int backslash = from + text.substring(from, at).lastIndexOf('\\');
        return backslash >= from && backslash + escapeLength(text, backslash) > at;
    }

    /**
     * How long the escape {@link #escape} writes that starts at a backslash is: 2 for {@code \n}, {@code \r} and
     * {@code \t}, 6 for a backslash, {@code u} and four lowercase hex digits, or 0 if what follows the backslash is
     * none of them.
     *
     * @param text The text
     * @param at   Where the backslash is
     * @return The escape's length, or 0
     */
    private static int escapeLength(String text, int at) {
        // Only escapeWithin's cut can fall after a backslash that ends the text; keptBeforeEscape's leaves some out.
        if (at + 1 == text.length()) {
            return 0;
        }
        char kind = text.charAt(at + 1);
        if ("nrt".indexOf(kind) >= 0) {
            return 2;
        }
        if (kind != 'u' || at + 6 > text.length()) {
            return 0;
        }
        for (int i = at + 2; i < at + 6; i++) {
            char digit = text.charAt(i);
            if ((digit < '0' || digit > '9') && (digit < 'a' || digit > 'f')) {
                return 0;
            }
        }
        return 6;
    }

    /**
     * Shortens text to at most {@code limit} characters, saying how many were left out: the plain cut of
     * {@link #truncate}, which {@link #keptBeforeEscape} backs off from before an escape. A surrogate pair the limit
     * falls inside is left out whole, so the text never ends in half a character.
     *
     * @param text  The text
     * @param limit How many characters to keep
     * @return The text, shortened if it was longer
     */
    public static String shorten(String text, int limit) {
        if (text.length() <= limit) {
            return text;
        }
        return cut(text, keptLength(text, limit));
    }

    /**
     * Keeps the first characters of a text, saying how many were left out, as {@link #shorten(String, int)} writes it.
     *
     * @param text The text
     * @param kept How many characters to keep
     * @return The kept characters, then {@code ... (N more characters)}
     */
    private static String cut(String text, int kept) {
        return text.substring(0, kept) + leftOut(text.length() - kept);
    }

    /**
     * Says how many characters were left out of a text, after the part of it a message shows.
     *
     * @param count How many characters were left out
     * @return {@code ... (N more characters)}
     */
    public static String leftOut(int count) {
        return "... (" + count + " more characters)";
    }

    /**
     * Escapes text as {@link #escape} does, shortened first, if it is longer than {@code most} characters or its
     * escaped form is longer than {@code room} characters, to as many of its first characters as fit in {@code room}
     * with {@code ... (N more characters)} after them, as {@link #cut} writes it. N counts the text's own characters
     * left out, as given, not escaped ones. The text is cut between code points, and never inside an escape
     * {@link #escape} writes or inside text that reads as one ({@code \n}, {@code \r}, {@code \t}, or a backslash,
     * {@code u} and four lowercase hex digits), which is left out whole: text can hold escapes already, such as a
     * message the engine built, and escaping it doesn't mend one cut in half. It shows at most the first {@code most}
     * characters, as {@link #keptBeforeEscape} keeps them, and none for a {@code most} of 0 or less. A room too small
     * for the count alone, a negative one included, gets the count alone; empty text is escaped as empty.
     * {@code api.language.MessageText} publishes this.
     *
     * @param text The text, raw, or with escapes the engine already wrote
     * @param most The most of its characters to show
     * @param room The most characters the escaped text may take, the count included
     * @return The text, shortened if it didn't fit or is longer than {@code most}, and escaped
     */
    public static String escapeWithin(String text, int most, int room) {
        int last = most >= text.length() ? text.length() : most <= 0 ? 0 : keptBeforeEscape(text, most);
        // Each code point escapes to at least its own characters, and each one kept can shorten the count by at most
        // one digit, so the two together only grow as more is kept, and once they don't fit, nothing more will.
        int kept = 0;
        long shown = 0;
        int end = 0;
        while (end < last && shown <= room) {
            int next = end + Character.charCount(text.codePointAt(end));
            shown += escape(text.substring(end, next)).length();
            if (shown + leftOut(text.length() - next).length() <= room && !insideEscape(text, next)) {
                kept = next;
            }
            end = next;
        }
        return end == text.length() && shown <= Math.max(room, 0) ? escape(text)
                : escape(text.substring(0, kept)) + leftOut(text.length() - kept);
    }

    /**
     * Quotes a name as {@link #quote} does, or, if that is longer than {@code room} characters, escapes it within the
     * room as {@link #escapeWithin} does, showing no more of it than {@link #quote} does. An empty name is quoted as
     * empty, whatever the room. {@code api.language.MessageText} publishes this.
     *
     * @param name The name
     * @param room The most characters the quoted name may take, the count of what was left out included
     * @return The name, quoted
     */
    public static String quoteWithin(String name, int room) {
        String quoted = quote(name);
        return quoted.length() <= room ? quoted : escapeWithin(name, MAX_NAME_LENGTH, room);
    }

    /**
     * How many characters of text longer than {@code limit} to keep: {@code limit}, or one fewer when the last of them
     * is a high surrogate.
     */
    private static int keptLength(String text, int limit) {
        return Character.isHighSurrogate(text.charAt(limit - 1)) ? limit - 1 : limit;
    }

    /**
     * Makes a fact, rule or language name safe to put in a message the engine logs: shortened to
     * {@value #MAX_NAME_LENGTH} characters (UTF-16 units), then {@link #escape escaped}; each escaped unit shows as 2
     * or 6 characters. The name keeps one fewer where the limit falls inside a surrogate pair, which is left out whole,
     * and up to 5 fewer where it falls inside raw text that reads as an escape, which is left out whole too, as
     * {@link #keptBeforeEscape} keeps them, so it never shows as an escape cut in half.
     * {@code api.language.MessageText} publishes this.
     *
     * @param name The name
     * @return The name, shortened if it was longer, then escaped
     */
    public static String quote(String name) {
        return escape(shorten(name));
    }

    /**
     * Shortens a name as {@link #quote} does, without escaping it, for a list of names that is escaped as a whole.
     *
     * @param name The name
     * @return The name, shortened if it was longer
     */
    public static String shorten(String name) {
        if (name.length() <= MAX_NAME_LENGTH) {
            return name;
        }
        return cut(name, keptBeforeEscape(name, MAX_NAME_LENGTH));
    }

    /**
     * Makes a list of names safe to put in a message, shown as a {@link java.util.List}'s {@code toString()} shows it,
     * such as {@code [eu, retail]}: each name shortened as {@link #quote} does, the list shortened and the whole
     * escaped last, as {@link #clip} does, so a cut never falls inside an escape. The count of what was
     * left out of the list counts the characters of the list as it was before it was escaped: the shortened names, the
     * notes of what was left out of each, and the separators.
     *
     * @param names The names; a {@code null} one is shown as {@code null}
     * @return The list, its names shortened to {@value #MAX_NAME_LENGTH} characters and it to
     *         {@value #MAX_DESCRIPTION_LENGTH}, then escaped
     */
    public static String quoteAll(Collection<? extends @Nullable String> names) {
        StringBuilder list = new StringBuilder("[");
        String separator = "";
        for (String name : names) {
            list.append(separator).append(name == null ? "null" : shorten(name));
            separator = ", ";
        }
        return clip(list.append(']').toString());
    }

    /**
     * Makes a list of names safe to put in a message, each name shortened as {@link #quote} does and put between
     * single quotes, the names separated by {@code ", "}, such as {@code 'eu', 'retail'}; the list is then shortened
     * and escaped as {@link #quoteAll} shortens and escapes it.
     *
     * @param names The names
     * @return The list, its names shortened to {@value #MAX_NAME_LENGTH} characters and it to
     *         {@value #MAX_DESCRIPTION_LENGTH}, then escaped
     */
    public static String quoteJoined(Collection<String> names) {
        StringBuilder list = new StringBuilder();
        String separator = "";
        for (String name : names) {
            list.append(separator).append('\'').append(shorten(name)).append('\'');
            separator = ", ";
        }
        return clip(list.toString());
    }

    /**
     * Makes text the engine didn't write safe to put in a message it logs, without shortening it. Line breaks, tabs
     * and other control characters, and the Unicode line and paragraph separators, are escaped ({@code \n},
     * {@code \r}, {@code \t}, or a backslash, {@code u} and four hex digits), so neither a name nor a fact value that
     * reached the message from request data can start a log line of its own. So are the Unicode format characters
     * (category Cf), such as bidi controls and zero-width and tag characters, which could reorder the rest of a line in
     * a viewer or make two different names look the same, and the other characters Unicode marks
     * {@code Default_Ignorable_Code_Point}, which a viewer shows as nothing, such as the Hangul fillers, the combining
     * grapheme joiner, variation selectors and the code points kept unassigned for more of them. So is every space
     * separator (category Zs) other than the ordinary space U+0020: U+00A0, U+1680, U+2000 to U+200A, U+202F, U+205F
     * and U+3000, which a viewer shows as a space or a mark, so a name ending in a no-break space would look like a
     * valid name followed by an ordinary space. An escaped character outside the Basic Multilingual Plane, such as a
     * tag character or a variation selector from U+E0100, is escaped as its two UTF-16 units, each a backslash,
     * {@code u} and four hex digits.
     *
     * <p>
     * Which characters are escaped is fixed to Unicode 15.0 to 17.0, as JDK 21 to 26 have it, whatever the running
     * JDK's version: above U+00FF, the format and separator characters come from {@link #isFormatOrSeparator} and
     * the other default-ignorable ones from {@link #isOtherDefaultIgnorable}, not from {@code Character.getType},
     * whose tables for those characters the JVM builds on their first use, which deep in a stack can leave one
     * unusable for the life of the JVM (#1286). Below U+0100, the JDK's table is built at startup, so the type comes
     * from {@code Character.getType} as before.
     * </p>
     *
     * <p>
     * Escaping text that has already been escaped changes nothing, because a backslash isn't a control, format,
     * space-separator or default-ignorable character, so a caller that can't tell whether a message has been through
     * here may escape it again.
     * </p>
     *
     * <p>
     * A lone surrogate, half of a pair without the other half, is escaped too: a logger's encoder would otherwise
     * write it as {@code ?}, so the log would differ from the message and two different names would log the same. A
     * valid surrogate pair, such as an emoji, is kept whole.
     * </p>
     *
     * @param text The text
     * @return The text, with every character that could start a line, every format or other default-ignorable
     *         character, every space separator other than U+0020 and every lone surrogate escaped
     */
    public static String escape(String text) {
        StringBuilder escaped = new StringBuilder(text.length());
        int c;
        for (int i = 0; i < text.length(); i += Character.charCount(c)) {
            c = text.codePointAt(i);
            switch (c) {
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (isEscaped(c)) {
                        for (char unit : Character.toChars(c)) {
                            appendEscape(escaped, unit);
                        }
                    } else {
                        escaped.appendCodePoint(c);
                    }
                }
            }
        }
        return escaped.toString();
    }

    /**
     * Tells whether {@link #escape} escapes a code point other than {@code \n}, {@code \r} and {@code \t}: a control,
     * line or paragraph separator, format, other default-ignorable or space-separator character other than U+0020,
     * or a lone surrogate. Above U+00FF, it asks {@link #isFormatOrSeparator} and {@link #isOtherDefaultIgnorable}
     * rather than {@code Character.getType}, whose table for those characters the JVM builds on its first use (#1286).
     *
     * @param c The code point
     * @return {@code true} if it is escaped
     */
    private static boolean isEscaped(int c) {
        if (c > LAST_LATIN_1) {
            // The loop reads code points, so only a lone surrogate is one; the test is arithmetic, on the code
            // point's own value, as a supplementary one cast to a char could read as a surrogate.
            return (c <= Character.MAX_VALUE && Character.isSurrogate((char) c)) || isFormatOrSeparator(c)
                    || isOtherDefaultIgnorable(c);
        }
        // Latin-1 has no line or paragraph separator, surrogate or other default-ignorable character, so of the kinds
        // above only the controls, the format character U+00AD and the space separator U+00A0 are to be found here.
        int type = Character.getType(c);
        return Character.isISOControl(c) || type == Character.FORMAT || (type == Character.SPACE_SEPARATOR && c != ' ');
    }

    /**
     * Tells whether a code point above U+00FF is of Unicode's categories Cf (format), Zs (space separator), Zl (line
     * separator) or Zp (paragraph separator), which {@link #escape} escapes, as {@code Character.getType} gives them on
     * JDK 21, 25 and 26 (the same in Unicode 15.0 to 17.0; 186 code points). Ranges of different categories are joined
     * where they touch: U+2000..U+200A are space separators and U+200B..U+200F format characters; U+2028 is the line
     * separator, U+2029 the paragraph separator, U+202A..U+202E format characters and U+202F a space separator; and
     * U+205F is a space separator and U+2060..U+2064 format characters. Comparisons,
     * rather than {@code Character.getType}, which above U+00FF reads a table of the JDK's that the JVM builds on its
     * first use, which a use deep in a stack can leave unusable for the life of the JVM (#1286); and comparisons rather
     * than a table of this class's own, so this class has no static initializer. So which characters are escaped is
     * fixed to Unicode 15.0 to 17.0, whatever the running JDK's version; {@code MessageTextTest.everyCodePointAlone}
     * compares the comparisons with the running JDK's and fails when they have to be updated. Below U+0100, where the
     * JDK's table is built at startup, the type still comes from {@code Character.getType}.
     *
     * @param c A code point above U+00FF
     * @return {@code true} if it is a format, space-separator, line-separator or paragraph-separator character
     */
    private static boolean isFormatOrSeparator(int c) {
        return (c >= 0x0600 && c <= 0x0605) || c == 0x061C || c == 0x06DD || c == 0x070F
                || (c >= 0x0890 && c <= 0x0891) || c == 0x08E2 || c == 0x1680 || c == 0x180E
                || (c >= 0x2000 && c <= 0x200F) || (c >= 0x2028 && c <= 0x202F) || (c >= 0x205F && c <= 0x2064)
                || (c >= 0x2066 && c <= 0x206F) || c == 0x3000 || c == 0xFEFF || (c >= 0xFFF9 && c <= 0xFFFB)
                || c == 0x110BD || c == 0x110CD || (c >= 0x13430 && c <= 0x1343F) || (c >= 0x1BCA0 && c <= 0x1BCA3)
                || (c >= 0x1D173 && c <= 0x1D17A) || c == 0xE0001 || (c >= 0xE0020 && c <= 0xE007F);
    }

    /**
     * Tells whether a code point above U+00FF is one of the {@code Default_Ignorable_Code_Point} characters of
     * Unicode's {@code DerivedCoreProperties.txt} (the same in Unicode 15.0 to 17.0) that aren't format characters:
     * fillers, marks such as the variation selectors, and the unassigned code points kept for more of them. Two of the
     * ranges hold format characters too, which {@link #isFormatOrSeparator} answers for anyway: U+180E in
     * U+180B..U+180F, and the tag characters U+E0001 and U+E0020..U+E007F in U+E0000..U+E0FFF, which holds the
     * variation selectors U+E0100..U+E01EF and, for the rest, unassigned code points. Comparisons, as
     * {@link #isFormatOrSeparator} makes, so this class has no static initializer.
     *
     * @param c A code point above U+00FF
     * @return {@code true} if it is a default-ignorable character that isn't a format character
     */
    private static boolean isOtherDefaultIgnorable(int c) {
        return c == 0x034F || (c >= 0x115F && c <= 0x1160) || (c >= 0x17B4 && c <= 0x17B5)
                || (c >= 0x180B && c <= 0x180F) || c == 0x2065 || c == 0x3164 || (c >= 0xFE00 && c <= 0xFE0F)
                || c == 0xFFA0 || (c >= 0xFFF0 && c <= 0xFFF8) || (c >= 0xE0000 && c <= 0xE0FFF);
    }

    /**
     * Appends one UTF-16 unit as a backslash, {@code u} and four lowercase hex digits, as
     * {@code String.format("\\u%04x", unit)} would, without parsing a format for every character.
     *
     * @param escaped What to append to
     * @param unit    The unit
     */
    public static void appendEscape(StringBuilder escaped, char unit) {
        escaped.append("\\u");
        for (int shift = 12; shift >= 0; shift -= 4) {
            escaped.append(Character.forDigit((unit >> shift) & 0xF, 16));
        }
    }
}
