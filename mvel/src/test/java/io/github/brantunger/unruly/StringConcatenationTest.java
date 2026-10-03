package io.github.brantunger.unruly;

import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.mvel.MvelExpressionLanguage;
import io.github.brantunger.unruly.test.ExpressionLanguageContractTest;
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
 * #965: on JDK 25 and later, a string concatenation of several values whose first link overflows the stack fails for
 * good, so the library's classes are compiled with javac's hidden {@code -XDstringConcat=inline} option, which builds
 * strings with {@code StringBuilder} rather than linking a call site to {@code StringConcatFactory}. javac ignores an
 * {@code -XD} option it doesn't know without a word, so this is the only check that the option still does that. It
 * reads every class file of each project's main classes, from the directory or jar the class given was loaded from,
 * and fails if one names {@code StringConcatFactory}, or if it finds none to read.
 */
@DisplayName("string concatenation")
class StringConcatenationTest {

    private static final byte[] FACTORY = "java/lang/invoke/StringConcatFactory".getBytes(StandardCharsets.UTF_8);

    @ParameterizedTest(name = "{0}")
    @ValueSource(classes = {RulesEngine.class, MvelExpressionLanguage.class, ExpressionLanguageContractTest.class})
    @DisplayName("the library's classes link no string concatenation to StringConcatFactory")
    void noConcatenationLinked(Class<?> type) throws IOException, URISyntaxException {
        Path classes = Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI());
        List<String> read = new ArrayList<>();
        List<String> linking = new ArrayList<>();
        if (Files.isDirectory(classes)) {
            try (Stream<Path> files = Files.walk(classes)) {
                for (Path file : files.filter(f -> f.toString().endsWith(".class")).toList()) {
                    check(classes.relativize(file).toString(), Files.readAllBytes(file), read, linking);
                }
            }
        } else {
            try (ZipFile jar = new ZipFile(classes.toFile())) {
                for (ZipEntry entry : jar.stream().filter(e -> e.getName().endsWith(".class")).toList()) {
                    try (InputStream in = jar.getInputStream(entry)) {
                        check(entry.getName(), in.readAllBytes(), read, linking);
                    }
                }
            }
        }

        assertFalse(read.isEmpty(), "no class files read from " + classes);
        assertEquals(List.of(), linking, "classes in " + classes + " that name StringConcatFactory");
    }

    private static void check(String name, byte[] classFile, List<String> read, List<String> linking) {
        read.add(name);
        if (contains(classFile, FACTORY)) {
            linking.add(name);
        }
    }

    // Whether the class file holds the name, as the constant pool does for a call site StringConcatFactory links.
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
