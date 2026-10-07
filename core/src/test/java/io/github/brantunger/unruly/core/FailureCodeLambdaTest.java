package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.ClassFiles;
import io.github.brantunger.unruly.api.exception.RuleCompilationException;
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
 * which takes more stack than the engine's checks make room for. The code that handles a failure runs only when
 * something fails, so the JVM's first failure deep in a stack paid for the link there, and could overflow:
 * {@code run()} then threw a {@link StackOverflowError} in place of the failure, and listeners missed {@code onError}
 * or {@code onRunError}. So {@link Failures}, which reads and describes what was thrown, and {@link ListenerNotifier},
 * which tells listeners of it, use neither, nor does {@link UniqueMatchRulesEngine}, whose own code fails a run that
 * matches more than one rule, nor {@link Closing}, which closes what a failed borrow made. #1097: nor
 * {@link CopyPermits}, which only a run that has to wait for a permit, or looks for a build slot, uses, nor
 * {@link RuleCompilationException}, which every failed load creates. This reads their class files, and those of their
 * nested classes, from the directory or jar each was loaded from, as {@code StringConcatenationTest} reads the
 * library's, and fails if one names {@code LambdaMetafactory}, as the constant pool does for a call site linked to
 * it, if a method of one has any other {@code invokedynamic}, which the JVM links the same way, or if the class itself
 * isn't read. {@link FailureMethodLambdaTest} checks the methods of classes whose other code links lambdas.
 * {@link FirstRunClassInitializationTest} checks that the engine's other failure paths link none either.
 */
@DisplayName("failure handling links no lambda (#1093, #1097)")
class FailureCodeLambdaTest {

    @ParameterizedTest(name = "{0}")
    @ValueSource(classes = {Failures.class, ListenerNotifier.class, UniqueMatchRulesEngine.class, Closing.class,
            CopyPermits.class, RuleCompilationException.class})
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
