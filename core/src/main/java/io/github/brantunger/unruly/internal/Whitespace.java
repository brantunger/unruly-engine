package io.github.brantunger.unruly.internal;

/**
 * Whether a name is blank, as {@link String#isBlank()} says, decided without the JDK's tables of the characters above
 * U+00FF. {@code String.isBlank()} asks {@link Character#isWhitespace(int)} about each character from the start until
 * the first that isn't whitespace, and for a character above U+00FF that reads the table of its plane,
 * {@code java.lang.CharacterData00} or a sibling, which the JVM builds on its first use: a name that starts with such a
 * character, checked for the first time deep in a stack, could overflow inside that table's static initializer, and
 * leave the table unusable for the life of the JVM, the application's own code included (#1292). The checks of a rule's
 * name and tags, a run's tags, a language's name and a fact's name run with no engine built, so before the engine's
 * first build has made room on the stack and initialized every such table; they ask here instead. <b>Internal:</b>
 * this class may change in any release, and isn't part of the API: its package isn't exported, and the {@code api} and
 * {@code core} packages share it. It has no static initializer of its own, so its own first use deep in a stack can't
 * break it.
 */
public final class Whitespace {

    // The last code point of Latin-1, up to which the JDK's table of the characters is built at startup (#1286).
    private static final char LAST_LATIN_1 = '\u00ff';

    private Whitespace() {
    }

    /**
     * Tells whether a string is blank: empty, or only characters {@link Character#isWhitespace(int)} accepts. The
     * answer is the one {@link String#isBlank()} gives, for every string, on JDK 21 to 26: below U+0100 it is
     * {@code Character.isWhitespace}'s, whose table the JVM builds as it starts; above U+00FF it comes from
     * {@link #isWideWhitespace}, so no JDK table is read for it. The string is read a code point at a time, as
     * {@code String.isBlank()} reads it; the loop ends at the first that isn't whitespace, which a lone surrogate or
     * a code point above U+FFFF always is, so it never has to step over a surrogate pair.
     *
     * <p>
     * On Linux and macOS, a string with a character above U+00FF read for the first time in the JVM initializes
     * {@code java.lang.StringUTF16}, and a read deep in a stack can overflow inside that, leaving the class unusable
     * (#1295). How much room that first read needs depends on what calls into it: {@code String.codePointAt} here,
     * as {@code String.isBlank()}'s own read, is a method the JVM still interprets when the first read comes early,
     * so a cold deep first read of a name keeps the room {@code String.isBlank()} had, where {@code String.charAt},
     * which the JVM compiles at startup, needs more and broke the class at depths {@code isBlank()} survived. That is
     * measured on JDK 21, 25 and 26, not guaranteed: a JVM that has compiled {@code codePointAt} may differ, as one
     * that has compiled {@code isBlank()} may.
     * </p>
     *
     * @param text The string
     * @return {@code true} if it is empty or only whitespace
     */
    public static boolean isBlank(String text) {
        for (int i = 0; i < text.length(); i++) {
            // codePointAt, not charAt: see above. A surrogate pair ends the loop, so no step over it is needed.
            int c = text.codePointAt(i);
            boolean whitespace = c <= LAST_LATIN_1 ? Character.isWhitespace(c) : isWideWhitespace(c);
            if (!whitespace) {
                return false;
            }
        }
        return true;
    }

    /**
     * Tells whether a character above U+00FF is whitespace, as {@link Character#isWhitespace(int)} says on JDK 21 to
     * 26 (the same in Unicode 15.0 to 17.0): the space separators other than the no-break spaces U+2007 and U+202F,
     * which are U+1680, U+2000 to U+2006, U+2008 to U+200A, U+205F and U+3000, and the line and paragraph separators
     * U+2028 and U+2029. Comparisons rather than a table, so this class has no static initializer; as the set is
     * fixed, a JDK with a newer Unicode may disagree, and {@code WhitespaceTest} compares every code point with the
     * running JDK and fails when the comparisons have to be updated.
     *
     * @param c A code point above U+00FF
     * @return {@code true} if it is whitespace
     */
    private static boolean isWideWhitespace(int c) {
        return c == '\u1680' || (c >= '\u2000' && c <= '\u2006') || (c >= '\u2008' && c <= '\u200a')
                || c == '\u2028' || c == '\u2029' || c == '\u205f' || c == '\u3000';
    }
}
