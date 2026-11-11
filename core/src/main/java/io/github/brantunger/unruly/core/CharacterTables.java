package io.github.brantunger.unruly.core;

/**
 * The JDK's tables of the Unicode characters above U+00FF, one class for each plane, such as
 * {@code java.lang.CharacterData00}, which a {@link Character} method reads for such a character and the JVM builds on
 * its first use, in its static initializer: only the Latin-1 one is built at startup. A first use that overflows deep
 * in a stack leaves that table unusable for the life of the JVM, the application's own code included. The JVM's first
 * engine build reads every one of them after checking the room for that ({@link FirstUseClasses}), with
 * {@link #warm()}; {@link EngineCompileContext}, which a language's tests make with no engine built, checks the room
 * with {@link #checkRoom(String)} before {@code core.ImportSize} reads a package import's name above U+00FF
 * (#1366).
 */
final class CharacterTables {

    // The last code point of Latin-1, up to which the JDK's table of the characters is built at startup.
    private static final char LAST_LATIN_1 = '\u00ff';

    // Set once every plane's table is known to be built: by the JVM's first engine build, or by this class's own first
    // check that found room for building them. It has no initializer, so this class has no static initializer, which a
    // first use deep in a stack could break. Not volatile: a thread that doesn't see it set yet only checks the room
    // once more, and the JVM's own lock on a table's initialization makes any thread that uses it wait for it to be
    // built. Not Accessors' flag, which says only the first plane's table is built.
    private static boolean built;

    private CharacterTables() {
    }

    /**
     * Makes sure every plane's table is built before the caller asks {@link Character} methods about the characters of
     * a name: if the name has a character above U+00FF and the tables aren't known to be built, checks that the stack
     * has room for building them, as the JVM's first engine build does, and then builds them all, so no read that
     * follows is the first of any. A name whose characters are all U+00FF or below reads only the table the JVM builds
     * at startup, and takes no check. The check for the engine's own steps, {@link StackHeadroom#check()}, isn't
     * enough, as it makes no room for initializing a class.
     *
     * @param name The name the caller is about to read, such as a package import
     * @throws StackOverflowError if the name has a character above U+00FF, the tables aren't known to be built, and
     *                            the stack has too little room left to build them, before any is touched; the next
     *                            call checks again
     */
    static void checkRoom(String name) {
        if (built) {
            return;
        }
        // A loop, not a stream, whose first use could overflow deep in a stack (#1012). A code point above U+00FF has
        // a char above it: a surrogate, for one outside the first plane.
        for (int i = 0; i < name.length(); i++) {
            if (name.charAt(i) > LAST_LATIN_1) {
                StackHeadroom.checkInitializing();
                warm();
                return;
            }
        }
    }

    /**
     * Builds every plane's table, by the type of one character of each, and records that they are built, so
     * {@link #checkRoom(String)} checks no more: called by {@link FirstUseClasses} once it has checked the room for
     * that, and by {@link #checkRoom(String)}.
     */
    static void warm() {
        // The Basic Multilingual Plane (CharacterData00), planes 1 to 3 (01, 02, 03), the unassigned planes 4 to 13
        // (Undefined), plane 14 (0E), and the private-use planes 15 and 16 (PrivateUse) (#1286). The first reads the
        // character from a string, as the JDK's code for a string with such a character, StringUTF16, is initialized by
        // the first read of a character of one, and nothing at startup does that on Linux or macOS; a JVM on Windows,
        // and the read of the JDK's security settings before this in FirstUseClasses, initialize it before this, so
        // this reads one in case. From checkRoom, a compile context's ImportSize.checkSize, with indexOf, and
        // checkRoom's own charAt scan have already read the name, a string with such a character, before the check, so
        // on Linux or macOS StringUTF16 may be initialized there first (#1295).
        Character.getType("\u03c0".codePointAt(0));
        Character.getType(0x10000);
        Character.getType(0x20000);
        Character.getType(0x30000);
        Character.getType(0x40000);
        Character.getType(0xE0001);
        Character.getType(0xF0000);
        built = true;
    }
}
