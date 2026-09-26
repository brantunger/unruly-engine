package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.Rule;

import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

/**
 * Computes the checksum that identifies a loaded rule list, as
 * {@link io.github.brantunger.unruly.api.RuleSetInfo#checksum()} describes it.
 */
final class Checksums {

    /** The algorithm the checksum is documented to use. */
    static final String SHA_256 = "SHA-256";

    // The length written for a value that isn't there, which no real length can be.
    private static final int ABSENT = -1;
    private static final int BYTE_MASK = 0xff;
    // The first code points UTF-8 writes in two, three and four bytes.
    private static final int TWO_BYTES = 0x80;
    private static final int THREE_BYTES = 0x800;
    private static final int FOUR_BYTES = 0x10000;
    // A byte after the first holds six bits of the code point, after the marker 10.
    private static final int SIX_BITS = 6;
    private static final int LOW_SIX_BITS = 0x3f;
    private static final int CONTINUATION = 0x80;
    // Shifted right by the number of bytes, the ones the first byte starts with: 110, 1110 or 11110.
    private static final int LEAD_ONES = 0xff00;

    private Checksums() {
    }

    /**
     * Returns the checksum of a compiled rule list.
     *
     * @param rules The rules, in evaluation order, each with the language the engine resolved for it
     * @return The lowercase hex SHA-256 of the rules
     */
    static String ofRules(List<CompiledRule> rules) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (CompiledRule compiled : rules) {
            Rule rule = compiled.rule();
            write(bytes, rule.getRuleName());
            write(bytes, rule.getPriority() == null ? null : String.valueOf(rule.getPriority()));
            // The language as the engine resolved it, so a rule without one hashes as the engine's default language.
            write(bytes, compiled.language());
            write(bytes, rule.getCondition());
            write(bytes, rule.getAction());
            write(bytes, String.valueOf(rule.isEnabled()));
            write(bytes, text(rule.getValidFrom()));
            write(bytes, text(rule.getValidTo()));
            writeTags(bytes, rule);
        }
        return hex(SHA_256, bytes.toByteArray());
    }

    /** An instant as ISO-8601 text in UTC, as {@link Instant#toString()} writes it, or {@code null}. */
    private static String text(Instant instant) {
        return instant == null ? null : instant.toString();
    }

    /**
     * Writes a rule's tags: how many there are, as four bytes, then each one as a value, in the order of their bytes
     * compared as unsigned numbers, which another system can reproduce without Java's order of strings. A lone
     * surrogate's three bytes sort after U+D7FF and before U+E000, as its code point does.
     */
    private static void writeTags(ByteArrayOutputStream bytes, Rule rule) {
        writeLength(bytes, rule.getTags().size());
        rule.getTags().stream()
                .map(Checksums::utf8)
                .sorted(Arrays::compareUnsigned)
                .forEach(utf8 -> {
                    writeLength(bytes, utf8.length);
                    bytes.writeBytes(utf8);
                });
    }

    /**
     * Returns the lowercase hex digest of some bytes.
     *
     * @param algorithm The digest algorithm, such as {@link #SHA_256}
     * @param data      The bytes to digest
     * @return The digest as lowercase hex
     * @throws IllegalStateException if the JVM doesn't have the algorithm
     */
    static String hex(String algorithm, byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("This JVM has no " + algorithm + " digest", e);
        }
    }

    /**
     * Writes a value as its length in bytes, most significant byte first, then its UTF-8 bytes, as {@link #utf8}
     * writes them.
     */
    private static void write(ByteArrayOutputStream bytes, String value) {
        if (value == null) {
            writeLength(bytes, ABSENT);
            return;
        }
        byte[] utf8 = utf8(value);
        writeLength(bytes, utf8.length);
        bytes.writeBytes(utf8);
    }

    /**
     * Returns a string's UTF-8 bytes. A lone surrogate, which UTF-8 can't encode, is written as its three-byte form,
     * as WTF-8 does. {@link String#getBytes(java.nio.charset.Charset)} writes {@code ?} for it instead, which would
     * give rules that differ only there the same checksum. A string without a lone surrogate gets the same bytes from
     * both.
     */
    private static byte[] utf8(String value) {
        int length = 0;
        int index = 0;
        while (index < value.length()) {
            int codePoint = value.codePointAt(index);
            length += byteCount(codePoint);
            index += Character.charCount(codePoint);
        }
        byte[] utf8 = new byte[length];
        int at = 0;
        index = 0;
        while (index < value.length()) {
            // A high surrogate followed by a low one is one code point; any other surrogate is a code point of its own.
            int codePoint = value.codePointAt(index);
            int count = byteCount(codePoint);
            if (codePoint < TWO_BYTES) {
                utf8[at] = (byte) codePoint;
            } else {
                int last = count - 1;
                utf8[at] = (byte) (((LEAD_ONES >>> count) & BYTE_MASK) | (codePoint >>> (SIX_BITS * last)));
                for (int next = 1; next < count; next++) {
                    utf8[at + next] = (byte) (CONTINUATION | codePoint >>> SIX_BITS * (last - next) & LOW_SIX_BITS);
                }
            }
            at += count;
            index += Character.charCount(codePoint);
        }
        return utf8;
    }

    /** How many bytes UTF-8 writes a code point in, a lone surrogate included. */
    private static int byteCount(int codePoint) {
        if (codePoint < TWO_BYTES) {
            return 1;
        }
        if (codePoint < THREE_BYTES) {
            return 2;
        }
        return codePoint < FOUR_BYTES ? 3 : 4;
    }

    private static void writeLength(ByteArrayOutputStream bytes, int length) {
        bytes.write(length >>> Byte.SIZE * 3 & BYTE_MASK);
        bytes.write(length >>> Byte.SIZE * 2 & BYTE_MASK);
        bytes.write(length >>> Byte.SIZE & BYTE_MASK);
        bytes.write(length & BYTE_MASK);
    }
}
