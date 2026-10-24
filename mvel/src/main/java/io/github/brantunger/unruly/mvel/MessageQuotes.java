package io.github.brantunger.unruly.mvel;

import io.github.brantunger.unruly.api.language.MessageText;

import java.util.List;

/**
 * Writes the messages that quote a name within {@value MessageText#MAX_TEXT_LENGTH} characters: those that reject a
 * fact's name (see {@link MvelFactNames}), an option or a strong typing that can't apply (see {@link DeclaredTypes}),
 * language imports (see {@link MvelExpressionLanguage}) and an inline import (see {@link Imports.ImportRejected}). Each
 * is written only when something is rejected, so it links no lambda or method reference, which the JVM would link the
 * first time it runs, maybe deep in a stack (#1093).
 */
final class MessageQuotes {

    // The least of a class name a message that also quotes a fact's name keeps room for: the fact's name gets the
    // room first, but not all of it, so the class name is never the count alone.
    private static final int MIN_CLASS_NAME_SHOWN = 100;

    private MessageQuotes() {
    }

    /**
     * Writes a message that quotes a name within {@value MessageText#MAX_TEXT_LENGTH} characters, as
     * {@link #quotedWithin(String, String, String, int)} does: a message MVEL throws when it rejects a fact name or an
     * option, which the engine shortens and escapes when it reports it. A message that fits is reported whole, so a
     * long name of characters that escape isn't cut inside an escape, and the count of what was left out counts the
     * name's own characters. The message is longer than {@value MessageText#MAX_TEXT_LENGTH} characters, and the engine
     * shortens it when it reports it, when the text around the name leaves less room than the count takes for a name
     * that doesn't fit whole, or is itself longer than that for an empty name. A message that quotes a class's name
     * too quotes both with {@link #quotedWithClass}, so the class's name is fitted as well.
     *
     * @param before What the message says before the name, up to its opening quote
     * @param name   The name, not escaped
     * @param after  What the message says after the name, from its closing quote on
     * @return The message
     */
    static String quotedWithin(String before, String name, String after) {
        return quotedWithin(before, name, after, MessageText.MAX_TEXT_LENGTH);
    }

    /**
     * Writes a message that quotes a name: {@code before}, the name quoted within what the rest of the message leaves
     * of {@code room} (see {@link MessageText#quoteWithin}), and {@code after}. When the name doesn't fit whole, and
     * {@code before} and {@code after} leave less room than the count of what was left out takes (22 characters
     * and the count's digits), the name is shown as that count alone and the message is longer than
     * {@code room}: {@code quotedWithin("<", "a".repeat(50), ">", 12)} gives 26 characters. Nothing is thrown.
     *
     * @param before What the message says before the name, up to its opening quote
     * @param name   The name, not escaped
     * @param after  What the message says after the name, from its closing quote on
     * @param room   The most characters the message may take
     * @return The message
     */
    static String quotedWithin(String before, String name, String after, int room) {
        return before + MessageText.quoteWithin(name, room - before.length() - after.length()) + after;
    }

    /**
     * Writes a message that quotes a fact's name and a class's name within {@value MessageText#MAX_TEXT_LENGTH}
     * characters, as {@link #quotedWithin(String, String, String)} quotes one name: {@code before}, the fact's name,
     * {@code between}, the class's name and {@code after}, each name quoted within the room the rest leaves (see
     * {@link MessageText#quoteWithin}). A class name can hold any character a class file allows, and be long, so it
     * is escaped and shortened like any name. The fact's name, which is what the call that declared the fact can
     * change, gets the room first, but the class's name keeps room for at least its first
     * {@value #MIN_CLASS_NAME_SHOWN} characters, or up to 5 fewer, where raw text that reads as an escape, or a
     * surrogate pair, would be cut (see {@link MessageText#escapeWithin(String, int, int)}), and gets what the fact's
     * name leaves.
     *
     * @param before    What the message says before the fact's name
     * @param name      The fact's name, not escaped
     * @param between   What the message says between the two names
     * @param className The class's name, not escaped
     * @param after     What the message says after the class's name
     * @return The message
     */
    static String quotedWithClass(String before, String name, String between, String className, String after) {
        int room = MessageText.MAX_TEXT_LENGTH - before.length() - between.length() - after.length();
        String least = MessageText.escapeWithin(className, MIN_CLASS_NAME_SHOWN, Integer.MAX_VALUE);
        String quotedName = MessageText.quoteWithin(name, room - least.length());
        return before + quotedName + between + MessageText.quoteWithin(className, room - quotedName.length()) + after;
    }

    /**
     * Writes a message that lists names within {@value MessageText#MAX_TEXT_LENGTH} characters: {@code before}, then
     * the names, each quoted as {@link MessageText#quote} quotes it, between single quotes and separated by
     * {@code ", "}, as the engine lists names, such as {@code 'lodash', 'os.path'}. Names are listed whole, in order,
     * until the next one doesn't fit; then {@code , and N more} for the rest, where N counts the names left out. The
     * first name is always listed, quoted within the room the rest leaves (see {@link MessageText#quoteWithin}) if it
     * doesn't fit whole.
     *
     * @param before What the message says before the first name's opening quote
     * @param names  The names, not escaped; at least one
     * @return The message
     */
    static String quotedAllWithin(String before, List<String> names) {
        int room = MessageText.MAX_TEXT_LENGTH - before.length();
        StringBuilder list = new StringBuilder();
        int listed = 0;
        while (listed < names.size()) {
            String quoted = (listed == 0 ? "'" : ", '") + MessageText.quote(names.get(listed)) + "'";
            if (list.length() + quoted.length() + andMore(names.size() - listed - 1).length() > room) {
                break;
            }
            list.append(quoted);
            listed++;
        }
        if (listed == 0) {
            String rest = andMore(names.size() - 1);
            list.append('\'').append(MessageText.quoteWithin(names.get(0), room - "''".length() - rest.length()))
                    .append('\'');
            listed = 1;
        }
        return before + list + andMore(names.size() - listed);
    }

    /**
     * Says how many names a list left out.
     *
     * @param count How many names were left out
     * @return {@code , and N more}, or an empty string if none were
     */
    private static String andMore(int count) {
        return count == 0 ? "" : ", and " + count + " more";
    }
}
