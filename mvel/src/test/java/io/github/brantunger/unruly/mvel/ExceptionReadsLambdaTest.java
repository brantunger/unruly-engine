package io.github.brantunger.unruly.mvel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

/**
 * #1093: the JVM links a lambda or a method reference the first time its call site runs, and linking one makes a class,
 * which takes more stack than the engine's checks make room for. {@link ExceptionReads} reads what a rule's code threw
 * when an MVEL condition or action fails, so a lambda of its own would be linked by the JVM's first such failure, maybe
 * deep in a stack, where linking it could overflow. So it uses none. This reads its class file, and those of its nested
 * classes, from the directory or jar it was loaded from, as core's {@code FailureCodeLambdaTest} reads
 * {@code Failures}', and fails if one names {@code LambdaMetafactory}, as the constant pool does for a call site linked
 * to it, or if the class itself isn't read. {@link FirstUseClassInitializationTest} checks that MVEL's first failing
 * condition and action link none.
 */
@DisplayName("ExceptionReads links no lambda (#1093)")
class ExceptionReadsLambdaTest {

    private static final byte[] FACTORY = "java/lang/invoke/LambdaMetafactory".getBytes(StandardCharsets.UTF_8);

    @Test
    @DisplayName("ExceptionReads and its nested classes link no lambda or method reference to LambdaMetafactory")
    void noLambdaLinked() throws IOException, URISyntaxException {
        Path classes = Path.of(ExceptionReads.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        String name = ExceptionReads.class.getName().replace('.', '/');
        List<String> read = new ArrayList<>();
        List<String> linking = new ArrayList<>();
        if (Files.isDirectory(classes)) {
            try (Stream<Path> files = Files.walk(classes)) {
                for (Path file : files.toList()) {
                    String entry = classes.relativize(file).toString().replace('\\', '/');
                    if (isOf(entry, name)) {
                        check(entry, Files.readAllBytes(file), read, linking);
                    }
                }
            }
        } else {
            try (ZipFile jar = new ZipFile(classes.toFile())) {
                for (ZipEntry entry : jar.stream().filter(e -> isOf(e.getName(), name)).toList()) {
                    try (InputStream in = jar.getInputStream(entry)) {
                        check(entry.getName(), in.readAllBytes(), read, linking);
                    }
                }
            }
        }

        assertTrue(read.contains(name + ".class"), "no " + name + ".class read from " + classes + ": " + read);
        assertEquals(List.of(), linking, "classes in " + classes + " that name LambdaMetafactory");
    }

    // The class itself, or one nested in it.
    private static boolean isOf(String entry, String name) {
        return entry.equals(name + ".class") || entry.startsWith(name + "$") && entry.endsWith(".class");
    }

    private static void check(String name, byte[] classFile, List<String> read, List<String> linking) {
        read.add(name);
        if (contains(classFile, FACTORY)) {
            linking.add(name);
        }
    }

    // Whether the class file holds the name, as the constant pool does for a call site LambdaMetafactory links.
    private static boolean contains(byte[] bytes, byte[] name) {
        for (int i = 0; i <= bytes.length - name.length; i++) {
            int at = 0;
            while (at < name.length && bytes[i + at] == name[at]) {
                at++;
            }
            if (at == name.length) {
                return true;
            }
        }
        return false;
    }
}
