package io.github.brantunger.unruly.mvel;

import io.github.brantunger.unruly.ClassFiles;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * #1093: the JVM links a lambda or a method reference the first time its call site runs, and linking one makes a class,
 * which takes more stack than the engine's checks make room for. {@link ExceptionReads} reads what a rule's code threw
 * when an MVEL condition or action fails, so a lambda of its own would be linked by the JVM's first such failure, maybe
 * deep in a stack, where linking it could overflow. So it uses none. #1097: nor does {@link MvelCompileErrors}, which
 * describes an MVEL rule that fails to compile, and so runs only when a load or {@code validate()} fails. This reads
 * their class files, and those of their nested classes, from the directory or jar each was loaded from, as core's
 * {@code FailureCodeLambdaTest} reads {@code Failures}', and fails if one names {@code LambdaMetafactory}, as the
 * constant pool does for a call site linked to it, if a method of one has any other {@code invokedynamic}, which the
 * JVM links the same way, or if the class itself isn't read. {@link FailureMethodLambdaTest} checks
 * {@link MvelAnalysis}' methods that read a compile error. {@link FirstUseClassInitializationTest} checks that
 * MVEL's first failing condition and action, and its first failing load and {@code validate()}, link none.
 */
@DisplayName("ExceptionReads and MvelCompileErrors link no lambda (#1093, #1097)")
class ExceptionReadsLambdaTest {

    @ParameterizedTest(name = "{0}")
    @ValueSource(classes = {ExceptionReads.class, MvelCompileErrors.class})
    @DisplayName("the class and its nested classes link no lambda or method reference to LambdaMetafactory, nor "
            + "any other invokedynamic call site")
    void noLambdaLinked(Class<?> type) throws IOException, URISyntaxException {
        String name = type.getName().replace('.', '/');
        Map<String, byte[]> classFiles = ClassFiles.of(type);
        List<String> linking = new ArrayList<>();
        List<String> linkingMethods = new ArrayList<>();
        for (Map.Entry<String, byte[]> classFile : classFiles.entrySet()) {
            if (ClassFiles.namesLambdaFactory(classFile.getValue())) {
                linking.add(classFile.getKey());
            }
            for (String method : ClassFiles.invokeDynamicMethods(classFile.getValue()).linking()) {
                linkingMethods.add(classFile.getKey() + " " + method);
            }
        }

        assertTrue(classFiles.containsKey(name + ".class"), "no " + name + ".class read: " + classFiles.keySet());
        assertEquals(List.of(), linking, "classes that name LambdaMetafactory");
        // Nor any other call site the JVM links on its first run: a pattern switch, a record's own equals, hashCode or
        // toString, or a string concatenation compiled with javac's default (#1097).
        assertEquals(List.of(), linkingMethods, "methods with an invokedynamic");
    }
}
