package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.Rule;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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
        }
        return hex(SHA_256, bytes.toByteArray());
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

    /** Writes a value as its length in bytes, most significant byte first, then its UTF-8 bytes. */
    private static void write(ByteArrayOutputStream bytes, String value) {
        if (value == null) {
            writeLength(bytes, ABSENT);
            return;
        }
        byte[] utf8 = value.getBytes(StandardCharsets.UTF_8);
        writeLength(bytes, utf8.length);
        bytes.writeBytes(utf8);
    }

    private static void writeLength(ByteArrayOutputStream bytes, int length) {
        bytes.write(length >>> Byte.SIZE * 3 & BYTE_MASK);
        bytes.write(length >>> Byte.SIZE * 2 & BYTE_MASK);
        bytes.write(length >>> Byte.SIZE & BYTE_MASK);
        bytes.write(length & BYTE_MASK);
    }
}
