package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.language.MessageText;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * #1121: a name over 200 characters is cut before raw text that reads as an escape, as the 1,000-character cut of a
 * message is, so a message never shows a name ending in half of one, such as a backslash and {@code u} alone.
 */
@DisplayName("A name is cut at 200 characters before raw text that reads as an escape, never inside it")
class NameCutBeforeEscapeTest {

    // A backslash, u and four lowercase hex digits, written out so the source holds no escape javac reads.
    private static final String READS_AS_ESCAPE = "\\" + "u0041";

    // 198 characters, then the six that read as an escape across the 200-character limit, then 10 more.
    private static final String NAME = "a".repeat(198) + READS_AS_ESCAPE + "b".repeat(10);

    @Test
    @DisplayName("quote leaves raw text that reads as \\u0041 out whole where the limit falls inside it")
    void unicodeEscapeLeftOutWhole() {
        assertEquals("a".repeat(198) + "... (16 more characters)", MessageText.quote(NAME));
        assertEquals("a".repeat(198) + "... (16 more characters)", Failures.quote(NAME));
    }

    @Test
    @DisplayName("quote leaves raw text that reads as \\n, \\r or \\t out whole where the limit falls inside it")
    void shortEscapesLeftOutWhole() {
        for (char kind : new char[]{'n', 'r', 't'}) {
            String name = "a".repeat(199) + "\\" + kind + "b";

            assertEquals("a".repeat(199) + "... (3 more characters)", MessageText.quote(name), "\\" + kind);
        }
    }

    @Test
    @DisplayName("a list of names cuts each name as quote does")
    void listsCutEachNameBeforeEscape() {
        String shown = "a".repeat(198) + "... (16 more characters)";

        assertEquals("[" + shown + ", b]", Failures.quoteAll(List.of(NAME, "b")));
        assertEquals("'" + shown + "', 'b'", Failures.quoteJoined(List.of(NAME, "b")));
    }

    // A guard: text that only reads as an escape when it is lowercase, or that the limit falls after, is cut as before.
    @Test
    @DisplayName("text that doesn't read as an escape, or one that ends at the limit, is cut as any text")
    void otherTextCutAsBefore() {
        assertEquals("a".repeat(198) + "\\q... (1 more characters)", MessageText.quote("a".repeat(198) + "\\qb"));
        assertEquals("a".repeat(196) + "\\u00... (4 more characters)",
                MessageText.quote("a".repeat(196) + "\\u00ABcd"));
        assertEquals("a".repeat(194) + READS_AS_ESCAPE + "... (1 more characters)",
                MessageText.quote("a".repeat(194) + READS_AS_ESCAPE + "b"));
        assertEquals("a".repeat(199) + "\\", MessageText.quote("a".repeat(199) + "\\"));
    }

    @Test
    @DisplayName("the note that names a root cause with no message cuts its class name as quote cuts a name")
    void causeNoteCutsClassNameBeforeEscape() throws Exception {
        Throwable root = (Throwable) renamed(Root.class, NAME).getConstructor().newInstance();
        assertEquals(NAME, root.getClass().getName());

        assertEquals("java.lang.RuntimeException (caused by " + "a".repeat(198) + "... (16 more characters))",
                Failures.describe(new RuntimeException((String) null, root)));
    }

    /** A root cause with no message, defined again under a name a Java source can't give it. */
    public static final class Root extends RuntimeException {
        private static final long serialVersionUID = 1L;

        /** Creates one, with no message. */
        public Root() {
        }
    }

    /**
     * Defines a class again under another name, in no package, by rewriting the constant that names it in its class
     * file. A class name can hold a backslash, though Java source can't write one.
     *
     * @param type The class
     * @param name Its new name
     * @return The class under its new name
     */
    private static Class<?> renamed(Class<?> type, String name) throws IOException {
        byte[] bytes;
        try (InputStream in = type.getResourceAsStream(type.getName().substring(type.getPackageName().length() + 1)
                + ".class")) {
            bytes = in.readAllBytes();
        }
        byte[] from = utf8Constant(type.getName().replace('.', '/'));
        byte[] to = utf8Constant(name);
        int at = indexOf(bytes, from);
        assertTrue(at >= 0, "no constant names " + type.getName());
        ByteArrayOutputStream file = new ByteArrayOutputStream();
        file.write(bytes, 0, at);
        file.write(to);
        file.write(bytes, at + from.length, bytes.length - at - from.length);
        byte[] patched = file.toByteArray();
        return new ClassLoader(type.getClassLoader()) {
            Class<?> define() {
                return defineClass(null, patched, 0, patched.length);
            }
        }.define();
    }

    /** A class file's {@code CONSTANT_Utf8} entry for an ASCII string: its tag, its length and its bytes. */
    private static byte[] utf8Constant(String text) {
        byte[] chars = text.getBytes(StandardCharsets.US_ASCII);
        byte[] entry = new byte[chars.length + 3];
        entry[0] = 1;
        entry[1] = (byte) (chars.length >> 8);
        entry[2] = (byte) chars.length;
        System.arraycopy(chars, 0, entry, 3, chars.length);
        return entry;
    }

    private static int indexOf(byte[] bytes, byte[] part) {
        for (int i = 0; i + part.length <= bytes.length; i++) {
            boolean found = true;
            for (int j = 0; j < part.length && found; j++) {
                found = bytes[i + j] == part[j];
            }
            if (found) {
                return i;
            }
        }
        return -1;
    }
}
