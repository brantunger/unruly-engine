package io.github.brantunger.unruly.api.language;

import io.github.brantunger.unruly.internal.Text;
import java.util.Objects;

/**
 * Shortens and escapes text a language didn't write, such as a fact name, an option value, an import or its
 * library's own error text, for a message, as the engine does in its own messages. Such text can hold line breaks,
 * bidi controls or lone surrogates, and a message the engine or a language logs mustn't let it start a log line of its
 * own or hide part of the line. It delegates to the engine's own escaping rather than keeping a copy of it.
 *
 * <p>
 * Use it for text the engine shows as it came: a message a language logs itself, the message of an exception its own
 * library sees first, and the issues it throws in an
 * {@link io.github.brantunger.unruly.api.exception.InvalidExpressionException}, which the engine copies untouched into
 * the {@link io.github.brantunger.unruly.api.exception.RuleCompilationException} it reports. Use {@link #quote} for a
 * name and {@code escape(truncate(text))} for other text. To end such text with a note of the language's own, such as
 * {@code " (caused by ...)"}, cut it with {@link #escapeWithin(String, int)},
 * {@link #escapeWithin(String, int, int)} or {@link #quoteWithin(String, int)}, giving it the room the rest of the
 * message leaves: the escaped result, its {@code ... (N more characters)} included, fits in that room, so the note
 * stays whole. To end text it shortened its own way with the engine's note, use {@link #leftOut}.
 * </p>
 *
 * <p>
 * The engine shortens to 1,000 characters and escapes the message of an
 * {@link io.github.brantunger.unruly.api.exception.InvalidExpressionException}, the message of a warning's issue when
 * it logs it, and the message of an exception from {@link ExpressionCompiler#checkFactName} when it logs it or, at
 * {@code load()}, reports it for a declared fact. <b>Leave a message the engine shortens raw: the engine escapes it
 * itself, and the count of what was left out of escaped text counts escaped characters.</b> Every cut the engine and
 * this class make, but {@link #truncate}'s, falls between code points and never inside an escape the engine writes or
 * inside raw text that reads as one (a backslash and {@code n}, {@code r} or {@code t}, or a backslash, {@code u} and
 * four lowercase hex digits): such text is left out whole, so up to 5 fewer characters show. A language may instead
 * escape such a message itself, when the escaped message fits in 1,000 characters, as
 * {@code escapeWithin(text, MAX_TEXT_LENGTH)} makes it; the engine then never shortens it.
 * </p>
 *
 * <p>
 * The escapes, the note {@code ... (N more characters)} and the limits, {@link #MAX_NAME_LENGTH} (200 characters,
 * UTF-16 units) for a name and {@link #MAX_TEXT_LENGTH} (1,000) for other text, are fixed for 2.x. javac inlines the
 * two constants into the classes that read them. Which characters above U+00FF are escaped follows Unicode 15.0 to
 * 17.0, as JDK 21 to 26 have it, from a table of the engine's own, whatever the running JDK's Unicode version, so a
 * later JDK escapes the same characters until a later 2.x release updates the table; below U+0100 the running JDK
 * decides, the same way on JDK 21 to 26. A later 2.x release may also escape more kinds of character.
 * </p>
 */
public final class MessageText {

    /**
     * The most characters (UTF-16 units) of a name {@link #quote} shows: 200, fixed for 2.x. javac inlines it into the
     * classes that read it.
     */
    public static final int MAX_NAME_LENGTH = Text.MAX_NAME_LENGTH;

    /**
     * The most characters (UTF-16 units) of other text {@link #truncate} keeps, and the length above which the engine
     * shortens the messages the class description lists: 1,000, fixed for 2.x. javac inlines it into the classes that
     * read it.
     */
    public static final int MAX_TEXT_LENGTH = Text.MAX_DESCRIPTION_LENGTH;

    private MessageText() {
    }

    /**
     * Writes the note that follows shortened text, as {@link #truncate}, {@link #quote} and the engine's own messages
     * write it: {@code ... (N more characters)}, N being how many characters of the text were left out. For a language
     * that shortens text its own way, such as to the room a message leaves after it, and ends it with the engine's
     * note. The count is written as given, whatever it is. It allocates the note.
     *
     * @param count How many characters were left out
     * @return {@code ... (} + {@code count} + {@code  more characters)}
     */
    public static String leftOut(int count) {
        return Text.leftOut(count);
    }

    /**
     * Escapes text for a message, without shortening it. {@code \n}, {@code \r} and {@code \t} become those
     * two-character escapes. Every other ISO control character, line or paragraph separator, space separator
     * (category Zs) other than U+0020, format character (category Cf), other {@code Default_Ignorable_Code_Point}
     * character and lone surrogate becomes a backslash, {@code u} and four lowercase hex digits, one for each UTF-16
     * unit, so an escaped character outside the Basic Multilingual Plane becomes two escapes, and each escaped unit
     * shows as 2 or 6 characters. Everything else is kept, a valid surrogate pair such as an emoji included. A
     * backslash isn't escaped, so escaping text twice gives the same text.
     *
     * <p>
     * Space separators and format characters above U+00FF are those of Unicode 15.0 to 17.0, as JDK 21 to 26 have
     * them, from a table of the engine's own rather than from the running JDK's {@link Character#getType(int)}, which
     * above U+00FF reads a table the JVM builds on its first use (#1286); below U+0100 the running JDK decides, the
     * same way on JDK 21 to 26. The space separators are U+00A0, U+1680, U+2000 to U+200A, U+202F, U+205F and U+3000
     * besides U+0020. The other default-ignorable characters are U+034F, U+115F to U+1160, U+17B4 to U+17B5, U+180B
     * to U+180F, U+2065, U+3164, U+FE00 to U+FE0F, U+FFA0, U+FFF0 to U+FFF8 and U+E0000 to U+E0FFF.
     * </p>
     *
     * <p>
     * To put long text in a message, shorten it first and escape it after, {@code escape(truncate(text))}, so a cut
     * never falls inside an escape this writes. The result can be up to six times longer than the 1,000
     * characters kept, since a character escapes to at most 6, so use it only for text the engine won't shorten again.
     * Leave a message the engine shortens raw: the engine escapes it itself (see the class description). To fit
     * escaped text into a room, use {@link #escapeWithin(String, int)}.
     * </p>
     *
     * @param text The text
     * @return The text, escaped
     * @throws NullPointerException if {@code text} is {@code null}
     */
    public static String escape(String text) {
        return Text.escape(Objects.requireNonNull(text, "text must not be null"));
    }

    /**
     * Shortens text for a message to at most 1,000 characters (UTF-16 units), without escaping it. Text of at most
     * 1,000 characters is returned as it is. Longer text keeps its first 1,000 characters, or 999 when the 1,000th is
     * a high surrogate, so the text never ends in half a character, followed by {@code ... (N more characters)}, where
     * N counts the text's own characters left out. 1,000 is also the length above which the engine shortens the
     * messages the class description lists, such as that of an
     * {@link io.github.brantunger.unruly.api.exception.InvalidExpressionException}.
     *
     * <p>
     * Shorten first and escape after, {@code escape(truncate(text))}, so a cut never falls inside an escape and N
     * counts the original text. Don't shorten text that is already escaped.
     * </p>
     *
     * @param text The text, not escaped
     * @return The text, shortened if it was longer
     * @throws NullPointerException if {@code text} is {@code null}
     */
    public static String truncate(String text) {
        return Text.truncate(Objects.requireNonNull(text, "text must not be null"));
    }

    /**
     * Shortens a name for a message to at most 200 characters ({@link #MAX_NAME_LENGTH}, UTF-16 units), then
     * {@link #escape escapes} it. The cut falls between code points and before raw text that reads as an escape, which
     * is left out whole, so up to 5 fewer characters show; {@code ... (N more characters)} follows, as
     * {@link #truncate} writes it, N counting the name's own characters left out. It adds no quotes: write them around
     * it, as in {@code "fact '" + MessageText.quote(name) + "'"}.
     *
     * @param name The name, such as a fact's, an option's or a class's
     * @return The name, shortened if it was longer, then escaped
     * @throws NullPointerException if {@code name} is {@code null}
     */
    public static String quote(String name) {
        return Text.quote(Objects.requireNonNull(name, "name must not be null"));
    }

    /**
     * Escapes text for a message as {@link #escape} does, shortened first if its escaped form is longer than
     * {@code room} characters: to as many of its first characters as fit in {@code room} with
     * {@code ... (N more characters)} after them, as {@link #truncate} writes it. Use it to keep what a message says
     * after the text, such as a closing note, whole: give it the room the rest of the message leaves.
     *
     * <p>
     * N counts the text's own characters left out, as given, not escaped ones. The cut falls between code points, so
     * never inside a surrogate pair or an escape this writes, and never inside an escape the text already holds, or
     * inside raw text that reads as one ({@code \n}, {@code \r}, {@code \t}, or a backslash, {@code u} and four
     * lowercase hex digits): such text is left out whole. {@link #escape} leaves an escape the text holds as it is,
     * so a cut inside one would show it cut in half.
     * </p>
     *
     * <p>
     * The result is at most {@code room} characters, unless {@code room} is too small for the note alone: then the
     * result is the note alone, and nothing is thrown. A negative room is taken as 0. Empty text is returned empty,
     * whatever the room.
     * </p>
     *
     * @param text The text, raw, or with escapes the engine already wrote
     * @param room The most characters the escaped text may take, the note included
     * @return The text, shortened if it didn't fit, then escaped
     * @throws NullPointerException if {@code text} is {@code null}
     */
    public static String escapeWithin(String text, int room) {
        return escapeWithin(text, Integer.MAX_VALUE, room);
    }

    /**
     * Escapes text within a room as {@link #escapeWithin(String, int)} does, showing at most its first {@code most}
     * characters (UTF-16 units). Where {@code most} falls inside a surrogate pair or just after a lone high
     * surrogate, or inside an escape the text holds or raw text that reads as one, that is left out whole, as
     * {@link #quote} leaves it out at {@value #MAX_NAME_LENGTH}, so up to 5 fewer characters show. A {@code most} of
     * 0 or less shows none of a non-empty text, only the note. With a {@code room} of {@link Integer#MAX_VALUE}, the
     * text is shortened to {@code most} characters alone.
     *
     * @param text The text, raw, or with escapes the engine already wrote
     * @param most The most of its characters to show
     * @param room The most characters the escaped text may take, the note included
     * @return The text, shortened if it didn't fit or is longer than {@code most}, then escaped
     * @throws NullPointerException if {@code text} is {@code null}
     */
    public static String escapeWithin(String text, int most, int room) {
        return Text.escapeWithin(Objects.requireNonNull(text, "text must not be null"), most, room);
    }

    /**
     * Quotes a name for a message as {@link #quote} does, or, if that is longer than {@code room} characters, escapes
     * it within the room as {@link #escapeWithin(String, int)} does. Either way it shows no more of the name than
     * {@link #quote} does. An empty name is quoted as empty, whatever the room. It adds no quotes.
     *
     * @param name The name, such as a fact's, an option's or a class's
     * @param room The most characters the quoted name may take, the note included
     * @return The name, shortened if it was longer or didn't fit, then escaped
     * @throws NullPointerException if {@code name} is {@code null}
     */
    public static String quoteWithin(String name, int room) {
        return Text.quoteWithin(Objects.requireNonNull(name, "name must not be null"), room);
    }
}
