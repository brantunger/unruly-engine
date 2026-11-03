package io.github.brantunger.unruly.mvel;

import io.github.brantunger.unruly.ClassFiles;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins how many {@code invokedynamic} instructions each class of this module has, by the rule on lambdas in this
 * package's {@code package-info}: none on a failure, loop-detection or class-lookup path, nor in a static initializer,
 * while a path every compile or run takes may link one. It reads every class file
 * under the directory this module's classes were loaded from, this package's, {@code warmup}'s and
 * {@code module-info}'s, so a class added later is counted without being named here, and counts the instructions in
 * each as {@code javap -c} shows them, through {@link ClassFiles#invokeDynamicCount}. The counts must equal
 * {@link #EXPECTED} exactly: a class not in the table has 0. A lambda added to one of the classes, or a new class with
 * one, fails this test; so does one removed. A change to the table is a decision about which path may link a lambda,
 * to take by the rule, not a count to update. {@link LoopDetectionLambdaTest}, {@link ExceptionReadsLambdaTest} and
 * {@link FailureMethodLambdaTest} check the paths the rule names whole or method by method, and
 * {@link FirstUseClassInitializationTest} that MVEL's first failing, loop-detection and lookup steps link none.
 */
@DisplayName("each class of the mvel module has as many invokedynamic instructions as the lambda rule allows it")
class InvokeDynamicCountTest {

    private static final String PACKAGE = "io/github/brantunger/unruly/mvel/";
    // The classes with one, and how many, by where they link it: MvelExpression as it compiles (three), takes a copy,
    // warms up and makes a parser context; MvelExpressionLanguage in prepare(); MvelFactNames as it's created; Imports
    // as it makes a lookup configuration; MvelExpressionCompiler as it warms a session up; and MvelSession as it takes
    // a compiled copy. CallSites and MvelAnalysis, whose static initializers had three and one, have none (#1374).
    private static final Map<String, Integer> EXPECTED = Map.of(
            PACKAGE + "MvelExpression.class", 6,
            PACKAGE + "MvelExpressionLanguage.class", 3,
            PACKAGE + "MvelFactNames.class", 1,
            PACKAGE + "Imports.class", 1,
            PACKAGE + "MvelExpressionCompiler.class", 1,
            PACKAGE + "MvelSession.class", 1);

    @Test
    @DisplayName("every class file of the module, nested and warm-up ones too, has the count the table gives it, 0 if "
            + "it isn't there")
    void countsMatchTheTable() throws IOException, URISyntaxException {
        Map<String, byte[]> classFiles = classFiles();
        Map<String, Integer> counted = new TreeMap<>();
        for (Map.Entry<String, byte[]> classFile : classFiles.entrySet()) {
            int count = ClassFiles.invokeDynamicCount(classFile.getValue());
            if (count > 0) {
                counted.put(classFile.getKey(), count);
            }
        }

        assertTrue(classFiles.containsKey(PACKAGE + "MvelExpressionLanguage.class"), "the language not read: "
                + classFiles.keySet());
        assertTrue(classFiles.containsKey(PACKAGE + "MvelExpression$Copy.class"), "a nested class not read: "
                + classFiles.keySet());
        assertTrue(classFiles.containsKey(PACKAGE + "warmup/WarmUpTarget.class"), "the warm-up's package not read: "
                + classFiles.keySet());
        assertEquals(new TreeMap<>(EXPECTED), counted, "invokedynamic instructions by class");
    }

    // Every class file under the directory this module's classes were loaded from, by its entry name, as
    // ClassFiles.of names them; the tests run against the directory Gradle compiles the module to.
    private static Map<String, byte[]> classFiles() throws IOException, URISyntaxException {
        Path classes = Path.of(MvelExpressionLanguage.class.getProtectionDomain().getCodeSource().getLocation()
                .toURI());
        assertTrue(Files.isDirectory(classes), "not a directory of classes: " + classes);
        Map<String, byte[]> read = new TreeMap<>();
        try (Stream<Path> files = Files.walk(classes)) {
            for (Path file : files.toList()) {
                String entry = classes.relativize(file).toString().replace('\\', '/');
                if (entry.endsWith(".class")) {
                    read.put(entry, Files.readAllBytes(file));
                }
            }
        }
        return read;
    }
}
