package io.github.brantunger.unruly.api.language;

import io.github.brantunger.unruly.core.Failures;

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
 * name and {@code escape(truncate(text))} for other text.
 * </p>
 *
 * <p>
 * The engine shortens to 1,000 characters and escapes the message of an
 * {@link io.github.brantunger.unruly.api.exception.InvalidExpressionException}, the message of a warning's issue when
 * it logs it, and the message of an exception from {@link ExpressionCompiler#checkFactName} when it logs it or, at
 * {@code load()}, reports it for a declared fact. <b>Leave a message the engine shortens raw: the engine escapes it
 * itself, and the count of what was left out of escaped text counts escaped characters.</b> Where the 1,000-character
 * limit falls inside an escape the engine writes, or inside text that reads as one (a backslash and {@code n},
 * {@code r} or {@code t}, or a backslash, {@code u} and four lowercase hex digits), the engine leaves it out whole, so
 * up to 5 fewer characters show. The MVEL language escapes the messages
 * of its compile errors, and of its rejections of a fact name, an option's name or value, a declared or output type,
 * or any language imports, itself, but fits each escaped message in 1,000 characters, so the engine never shortens
 * one it reports directly.
 * </p>
 *
 * <p>
 * The escapes, the note {@code ... (N more characters)} and the limits of 200 characters (UTF-16 units) for a name
 * and 1,000 for other text are fixed for 2.x. Which characters are escaped follows Unicode: format characters are read
 * from the running JDK's Unicode version, so a later JDK, or a later 2.x release, may escape more characters as
 * Unicode marks them.
 * </p>
 */
public final class MessageText {

    private MessageText() {
    }

    /**
     * Escapes text for a message, without shortening it. {@code \n}, {@code \r} and {@code \t} become those
     * two-character escapes. Every other ISO control character, line or paragraph separator, format character
     * (category Cf), other {@code Default_Ignorable_Code_Point} character and lone surrogate becomes a backslash,
     * {@code u} and four lowercase hex digits, one for each UTF-16 unit, so an escaped character outside the Basic
     * Multilingual Plane becomes two escapes, and each escaped unit shows as 2 or 6 characters. Everything else is
     * kept, a valid surrogate pair such as an emoji included. A backslash isn't escaped, so escaping text twice gives
     * the same text.
     *
     * <p>
     * Format characters are those of the running JDK's Unicode version ({@link Character#getType(int)}). The other
     * default-ignorable characters are U+034F, U+115F to U+1160, U+17B4 to U+17B5, U+180B to U+180F, U+2065, U+3164,
     * U+FE00 to U+FE0F, U+FFA0, U+FFF0 to U+FFF8 and U+E0000 to U+E0FFF.
     * </p>
     *
     * <p>
     * To put long text in a message, shorten it first and escape it after, {@code escape(truncate(text))}, so a cut
     * never falls inside an escape this writes. The result can be up to six times longer than the 1,000
     * characters kept, since a character escapes to at most 6, so use it only for text the engine won't shorten again.
     * Leave a message the engine shortens raw: the engine escapes it itself (see the class description).
     * </p>
     *
     * @param text The text
     * @return The text, escaped
     * @throws NullPointerException if {@code text} is {@code null}
     */
    public static String escape(String text) {
        return Failures.escape(Objects.requireNonNull(text, "text must not be null"));
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
        return Failures.truncate(Objects.requireNonNull(text, "text must not be null"));
    }

    /**
     * Shortens a name for a message to at most 200 characters (UTF-16 units), as {@link #truncate} shortens text, with
     * the same note after it, then {@link #escape escapes} it. It adds no quotes: write them around it, as in
     * {@code "fact '" + MessageText.quote(name) + "'"}.
     *
     * @param name The name, such as a fact's, an option's or a class's
     * @return The name, shortened if it was longer, then escaped
     * @throws NullPointerException if {@code name} is {@code null}
     */
    public static String quote(String name) {
        return Failures.quote(Objects.requireNonNull(name, "name must not be null"));
    }
}
