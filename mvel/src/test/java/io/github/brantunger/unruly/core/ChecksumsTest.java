package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("the rule-set checksum is a SHA-256 another system can reproduce")
class ChecksumsTest {

    /** The SHA-256 of no bytes, from the algorithm's specification. */
    private static final String EMPTY_SHA_256 =
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    /**
     * The checksum an engine reports for one rule, which is what another system reproduces from the documented
     * layout: the length of each value in bytes as four bytes most significant first, then its UTF-8 bytes. A lone
     * surrogate, which UTF-8 can't encode, is written as its three-byte form, as WTF-8 does.
     */
    private static String checksumOf(Rule rule) {
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>firstMatch(HashMap::new)
                .build();
        engine.load(List.of(rule));
        return engine.rules().checksum();
    }

    @Test
    @DisplayName("rules hash to the values an independent implementation of the documented layout computes")
    void knownChecksums() {
        assertEquals("500a059b1cb5650d87d53c91d0658bba42964c122a1d491377154b455d4d004b",
                checksumOf(Rule.builder().ruleName("ab").condition("true").action("x").build()),
                "rule 'ab', no priority, language mvel, condition 'true', action 'x', enabled, no window, no tags");
        // The priority contributes its decimal digits, and a name or an action outside ASCII its UTF-8 bytes, which
        // are longer than the text: a length counted in characters, or ISO-8859-1 bytes, gives another value.
        assertEquals("5bd13e8057d24a6b5e7f7d659d3931f0dbedc305747f7429570a272f1bf5844d",
                checksumOf(Rule.builder().ruleName("é").priority(1).condition("true")
                        .action("output.put('ü', 1)").build()),
                "rule 'é', priority 1, language mvel, condition 'true', action \"output.put('ü', 1)\"");
        // A disabled rule with a window and tags. The end has milliseconds, which Instant.toString() writes as
        // three digits. The tags are in the order of their UTF-8 bytes: U+FF21 before U+1F600, although Java's
        // String order, which compares UTF-16 units, puts U+1F600 (a surrogate pair from U+D83D) first.
        assertEquals("331878412894ed9e73033ba2aa09d60fa1d5ca71a6843856e30d37284c46f4ea",
                checksumOf(Rule.builder().ruleName("r").condition("true").action("x").enabled(false)
                        .validFrom(Instant.parse("2027-06-01T00:00:00Z"))
                        .validTo(Instant.parse("2027-09-01T00:00:00.500Z"))
                        .tags(List.of("\uFF21", "\uD83D\uDE00", "eu")).build()),
                "rule 'r', disabled, from 2027-06-01T00:00:00Z to 2027-09-01T00:00:00.500Z, tags eu, U+FF21, U+1F600");
    }

    /**
     * The SHA-256 of the documented layout, built here byte by byte, for one rule with the given name bytes, no
     * priority, language mvel, condition {@code true}, action {@code x}, enabled, no window and the given tag bytes,
     * which must already be in the documented order.
     */
    private static String layoutOf(byte[] name, byte[]... tags) throws NoSuchAlgorithmException {
        ByteArrayOutputStream layout = new ByteArrayOutputStream();
        value(layout, name);
        length(layout, -1);
        value(layout, ascii("mvel"));
        value(layout, ascii("true"));
        value(layout, ascii("x"));
        value(layout, ascii("true"));
        length(layout, -1);
        length(layout, -1);
        length(layout, tags.length);
        for (byte[] tag : tags) {
            value(layout, tag);
        }
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(layout.toByteArray()));
    }

    private static void value(ByteArrayOutputStream layout, byte[] bytes) {
        length(layout, bytes.length);
        layout.writeBytes(bytes);
    }

    private static void length(ByteArrayOutputStream layout, int length) {
        layout.writeBytes(ByteBuffer.allocate(Integer.BYTES).putInt(length).array());
    }

    private static byte[] ascii(String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }

    /** Bytes written as unsigned numbers, such as {@code 0xED}. */
    private static byte[] bytes(int... values) {
        byte[] bytes = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            bytes[i] = (byte) values[i];
        }
        return bytes;
    }

    private static Rule named(String name, String... tags) {
        return Rule.builder().ruleName(name).condition("true").action("x").tags(List.of(tags)).build();
    }

    @Test
    @DisplayName("a lone surrogate hashes as its three WTF-8 bytes, which another system can reproduce")
    void loneSurrogateKnownChecksum() throws NoSuchAlgorithmException {
        // U+D800 is ED A0 80 and U+DFFF is ED BF BF, where String.getBytes(UTF_8) writes 3F, a '?', for either.
        String expected = layoutOf(bytes('r', 0xED, 0xA0, 0x80), bytes(0xED, 0xBF, 0xBF));

        assertEquals("f537b7b4ea2edd44534d1c8d8837a39780b34acbb0828ad698e626798b8b40c2", expected,
                "the layout built here gives the value computed apart from it");
        assertEquals(expected, checksumOf(named("r\uD800", "\uDFFF")), "rule 'r' and U+D800, tag U+DFFF");
    }

    @Test
    @DisplayName("rules that differ only by a lone surrogate, or by one against a '?', hash differently")
    void loneSurrogatesChangeTheChecksum() {
        Rule rule = named("r");

        assertAll(
                () -> assertNotEquals(checksumOf(named("r\uD800")), checksumOf(named("r\uDC00")),
                        "name r and U+D800 versus U+DC00"),
                () -> assertNotEquals(checksumOf(named("r\uD800")), checksumOf(named("r?")),
                        "name r and U+D800 versus r?"),
                () -> assertNotEquals(checksumOf(named("r\uDC00")), checksumOf(named("r?")),
                        "name r and U+DC00 versus r?"),
                () -> assertNotEquals(checksumOf(rule.toBuilder().condition("x == '\uD800'").build()),
                        checksumOf(rule.toBuilder().condition("x == '?'").build()), "condition"),
                () -> assertNotEquals(checksumOf(rule.toBuilder().action("output.put('\uD800', 1)").build()),
                        checksumOf(rule.toBuilder().action("output.put('?', 1)").build()), "action"),
                () -> assertNotEquals(checksumOf(named("r", "eu\uD800")), checksumOf(named("r", "eu?")),
                        "tag eu and U+D800 versus eu?"));
    }

    @Test
    @DisplayName("a surrogate pair is one code point in four bytes")
    void surrogatePair() throws NoSuchAlgorithmException {
        // U+1F600 is D83D DE00 in UTF-16, and F0 9F 98 80 in UTF-8.
        assertEquals(layoutOf(bytes(0xF0, 0x9F, 0x98, 0x80)), checksumOf(named("\uD83D\uDE00")), "U+1F600");
    }

    @Test
    @DisplayName("a low surrogate before a high one isn't a pair, so each is written as a lone surrogate")
    void reversedSurrogatePair() throws NoSuchAlgorithmException {
        assertEquals(layoutOf(bytes(0xED, 0xB8, 0x80, 0xED, 0xA0, 0xBD)), checksumOf(named("\uDE00\uD83D")),
                "U+DE00 then U+D83D");
    }

    @Test
    @DisplayName("each code point is written in as many bytes as UTF-8 gives it, from one to four")
    void wellFormedTextHashesAsUtf8() throws NoSuchAlgorithmException {
        // 'a' is one byte, U+00E9 two, U+FF21 three and U+1F600 four, as String.getBytes(UTF_8) writes them.
        assertEquals(layoutOf(bytes('a', 0xC3, 0xA9, 0xEF, 0xBC, 0xA1, 0xF0, 0x9F, 0x98, 0x80)),
                checksumOf(named("a\u00E9\uFF21\uD83D\uDE00")), "a, U+00E9, U+FF21, U+1F600");
    }

    @Test
    @DisplayName("the code points either side of each UTF-8 length boundary get the bytes UTF-8 gives them")
    void lengthBoundaries() {
        assertAll(
                () -> assertEquals(layoutOf(bytes(0x7F)), checksumOf(named("\u007F")), "U+007F"),
                () -> assertEquals(layoutOf(bytes(0xC2, 0x80)), checksumOf(named("\u0080")), "U+0080"),
                () -> assertEquals(layoutOf(bytes(0xDF, 0xBF)), checksumOf(named("\u07FF")), "U+07FF"),
                () -> assertEquals(layoutOf(bytes(0xE0, 0xA0, 0x80)), checksumOf(named("\u0800")), "U+0800"),
                () -> assertEquals(layoutOf(bytes(0xEF, 0xBF, 0xBF)), checksumOf(named("\uFFFF")), "U+FFFF"),
                () -> assertEquals(layoutOf(bytes(0xF0, 0x90, 0x80, 0x80)), checksumOf(named("\uD800\uDC00")),
                        "U+10000"),
                // A high surrogate followed by another high one doesn't pair, so each is written on its own.
                () -> assertEquals(layoutOf(bytes(0xED, 0xA0, 0x80, 0xED, 0xA0, 0x80)),
                        checksumOf(named("\uD800\uD800")), "U+D800 twice"));
    }

    @Test
    @DisplayName("a lone surrogate's tag sorts by its bytes, between U+D7FF and U+E000, as its code point does")
    void loneSurrogateTagsSortByTheirBytes() throws NoSuchAlgorithmException {
        assertEquals(layoutOf(bytes('r'), bytes('?'), bytes(0xED, 0x9F, 0xBF), bytes(0xED, 0xB0, 0x80),
                        bytes(0xEE, 0x80, 0x80)),
                checksumOf(named("r", "\uE000", "\uDC00", "\uD7FF", "?")), "tags ?, U+D7FF, U+DC00, U+E000");
    }

    @Test
    @DisplayName("enabling a rule, or changing its window or tags, changes the checksum")
    void newFieldsChangeTheChecksum() {
        Rule rule = Rule.builder().ruleName("r").condition("true").action("x").build();
        String plain = checksumOf(rule);

        assertNotEquals(plain, checksumOf(rule.toBuilder().enabled(false).build()), "enabled");
        assertNotEquals(plain, checksumOf(rule.toBuilder().validFrom(Instant.EPOCH).build()), "validFrom");
        assertNotEquals(plain, checksumOf(rule.toBuilder().validTo(Instant.EPOCH).build()), "validTo");
        assertNotEquals(plain, checksumOf(rule.toBuilder().tags(Set.of("eu")).build()), "tags");
        // The start and the end are separate values, so the same instant as one or the other hashes differently.
        assertNotEquals(checksumOf(rule.toBuilder().validFrom(Instant.EPOCH).build()),
                checksumOf(rule.toBuilder().validTo(Instant.EPOCH).build()), "validFrom versus validTo");
        // The count separates the tags from the next rule, so a tag can't pass for the start of another rule.
        assertNotEquals(checksumOf(rule.toBuilder().tags(Set.of("a", "b")).build()),
                checksumOf(rule.toBuilder().tags(Set.of("ab")).build()), "tags a, b versus ab");
    }

    @Test
    @DisplayName("the same characters split differently between two values hash differently")
    void lengthsSeparateTheValues() {
        String split = checksumOf(Rule.builder().ruleName("r").condition("true").action("x").build());
        String other = checksumOf(Rule.builder().ruleName("r").condition("tru").action("ex").build());

        assertNotEquals(split, other,
                "each value is hashed with its length, so the bytes of two values never run together");
    }

    @Test
    @DisplayName("an empty rule list hashes to the SHA-256 of no bytes")
    void emptyRules() {
        assertEquals(EMPTY_SHA_256, Checksums.ofRules(List.of()));
        assertEquals(EMPTY_SHA_256, Checksums.hex(Checksums.SHA_256, new byte[0]));
    }

    @Test
    @DisplayName("a digest the JVM doesn't have is reported, rather than surfacing as a checked exception")
    void unknownAlgorithm() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> Checksums.hex("no-such-digest", new byte[0]));

        assertEquals("This JVM has no no-such-digest digest", ex.getMessage());
    }
}
