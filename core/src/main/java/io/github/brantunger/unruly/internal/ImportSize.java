package io.github.brantunger.unruly.internal;

import org.jspecify.annotations.Nullable;

/**
 * How large an import string may be, and why one is rejected: the engine checks the imports it is built with and a
 * language's own imports before any class loader sees them, the former for a package name's shape too, and
 * {@code api.language.ImportLimits} publishes the limits and the reason, so a language bounds an import in an
 * expression's own text the same way, with the same message, rather than keeping a copy that could drift.
 * <b>Internal:</b> this class may change in any release, and isn't part of the API: its package isn't exported, and
 * the {@code api} and {@code core} packages share it. It has no static initializer of its own, so its first use deep
 * in a stack, as when a language's tests make a compile context with no engine built, can't break it.
 */
public final class ImportSize {

    /**
     * The most characters an import string may have. A longer one is rejected before it is looked up as a class, as
     * a parallel-capable class loader, such as the JDK's application class loader, keeps an object for each name it is
     * asked for, for as long as it lives.
     */
    public static final int MAX_IMPORT_LENGTH = 1_000;

    /**
     * The most dot-separated parts an import string may have. A name that isn't a class is looked up again with each
     * dot from the right replaced by {@code $}, and a language such as MVEL looks up each name a rule uses in each
     * imported package the same way, so the work grows with the number of parts.
     */
    public static final int MAX_IMPORT_PARTS = 64;

    private ImportSize() {
    }

    /**
     * Says why an import string is too large to look up, if it is: too long, or else with too many parts. The reason
     * follows the quoted name in the message of what rejects it, as {@link #checkSize} writes it.
     *
     * @param name An import string
     * @return {@code it has N characters, and an import may have at most 1000} if {@code name} has more than
     *         {@value #MAX_IMPORT_LENGTH} characters, else {@code it has N dot-separated parts, and an import may have
     *         at most 64} if it has more than {@value #MAX_IMPORT_PARTS} dot-separated parts, else {@code null}
     */
    public static @Nullable String problem(String name) {
        String tooLong = tooLong(name);
        if (tooLong != null) {
            return tooLong;
        }
        // Counted with a loop rather than a stream, whose first use could overflow deep in a stack (#1012), and in an
        // int, which the length bounds: appending a long would initialize java.lang.Long, which JDK 21 doesn't at
        // startup, so a first call through ImportLimits deep in a stack could break it for the JVM (#1260).
        int parts = 1;
        for (int dot = name.indexOf('.'); dot >= 0; dot = name.indexOf('.', dot + 1)) {
            parts++;
        }
        if (parts > MAX_IMPORT_PARTS) {
            return "it has " + parts + " dot-separated parts, and an import may have at most " + MAX_IMPORT_PARTS;
        }
        return null;
    }

    /**
     * Rejects an import string too long, or with too many parts, to look up, before any class loader sees it.
     * {@code EngineCompileContext} checks each package it is given with it, and then with {@link #checkPackageName},
     * so a context made outside an engine rejects what {@code build()} does.
     *
     * @param name An import string
     * @throws IllegalArgumentException if {@code name} has more than {@value #MAX_IMPORT_LENGTH} characters or more
     *                                  than {@value #MAX_IMPORT_PARTS} dot-separated parts, as {@link #problem} says
     */
    public static void checkSize(String name) {
        String problem = problem(name);
        if (problem != null) {
            throw rejected(name, problem);
        }
    }

    /**
     * Rejects an import string too long to pass on: the part of {@link #checkSize} that applies to a language's own
     * imports too, from {@code RulesEngineBuilder.languageImports}, which no class loader sees, so their parts aren't
     * limited.
     *
     * @param name An import string
     * @throws IllegalArgumentException if {@code name} has more than {@value #MAX_IMPORT_LENGTH} characters
     */
    public static void checkLength(String name) {
        String tooLong = tooLong(name);
        if (tooLong != null) {
            throw rejected(name, tooLong);
        }
    }

    /**
     * Rejects an import string that isn't a valid package name: one whose dot-separated parts aren't each a Java
     * identifier, read by code point, as Java reads a name. The engine checks each import it is built with by this,
     * after {@link #checkSize} and before any class loader sees it, so one it rejects leaves no lock object behind; a
     * class's name written as an import has the same shape. {@code EngineCompileContext} checks each package it is
     * given with it too, so a context made outside an engine rejects what {@code build()} does.
     *
     * @param name An import string
     * @throws IllegalArgumentException if {@code name} isn't a valid package name, with the message
     *                                  {@code '<name>' is neither a class nor a valid package name}, the name quoted
     *                                  as {@link Text#quote} quotes it, and no cause, as nothing was looked up
     */
    public static void checkPackageName(String name) {
        if (!isPackageName(name)) {
            throw new IllegalArgumentException("'" + Text.quote(name)
                    + "' is neither a class nor a valid package name");
        }
    }

    private static boolean isPackageName(String name) {
        for (String part : name.split("\\.", -1)) {
            if (!isIdentifier(part)) {
                return false;
            }
        }
        return true;
    }

    // Read by code point, as Java reads a name, so a letter outside the Basic Multilingual Plane, a surrogate pair, is
    // a letter and a lone surrogate isn't. mvel.MvelFactNames reads a fact's name by char on purpose: MVEL can't read
    // such a letter in a rule's text, so no rule could refer to a fact named with one.
    private static boolean isIdentifier(String name) {
        if (name.isEmpty()) {
            return false;
        }
        int c = name.codePointAt(0);
        if (!Character.isJavaIdentifierStart(c)) {
            return false;
        }
        for (int i = Character.charCount(c); i < name.length(); i += Character.charCount(c)) {
            c = name.codePointAt(i);
            if (!Character.isJavaIdentifierPart(c)) {
                return false;
            }
        }
        return true;
    }

    private static @Nullable String tooLong(String name) {
        if (name.length() > MAX_IMPORT_LENGTH) {
            return "it has " + name.length() + " characters, and an import may have at most " + MAX_IMPORT_LENGTH;
        }
        return null;
    }

    private static IllegalArgumentException rejected(String name, String problem) {
        return new IllegalArgumentException("Can't import '" + Text.quote(name) + "': " + problem);
    }
}
