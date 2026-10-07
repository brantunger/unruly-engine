package io.github.brantunger.unruly.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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
 * which takes more stack than the engine's checks make room for. The code that handles a failure runs only when
 * something fails, so the JVM's first failure deep in a stack paid for the link there, and could overflow:
 * {@code run()} then threw a {@link StackOverflowError} in place of the failure, and listeners missed {@code onError}
 * or {@code onRunError}. So {@link Failures}, which reads and describes what was thrown, and {@link ListenerNotifier},
 * which tells listeners of it, use neither, nor does {@link UniqueMatchRulesEngine}, whose own code fails a run that
 * matches more than one rule, nor {@link Closing}, which closes what a failed borrow made. This reads their class
 * files, and those of their nested classes, from the directory or jar each was loaded from, as
 * {@code StringConcatenationTest} reads the library's, and fails if one names {@code LambdaMetafactory}, as the
 * constant pool does for a call site linked to it, or if the class itself isn't read.
 * {@link FirstRunClassInitializationTest} checks that the engine's other failure paths link none either.
 */
@DisplayName("failure handling links no lambda (#1093)")
class FailureCodeLambdaTest {

    private static final byte[] FACTORY = "java/lang/invoke/LambdaMetafactory".getBytes(StandardCharsets.UTF_8);

    @ParameterizedTest(name = "{0}")
    @ValueSource(classes = {Failures.class, ListenerNotifier.class, UniqueMatchRulesEngine.class, Closing.class})
    @DisplayName("the class and its nested classes link no lambda or method reference to LambdaMetafactory")
    void noLambdaLinked(Class<?> type) throws IOException, URISyntaxException {
        Path classes = Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI());
        String name = type.getName().replace('.', '/');
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
