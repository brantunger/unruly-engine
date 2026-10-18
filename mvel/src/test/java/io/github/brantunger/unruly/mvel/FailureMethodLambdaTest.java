package io.github.brantunger.unruly.mvel;

import io.github.brantunger.unruly.ClassFiles;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * #1097: the JVM links a lambda or a method reference the first time its call site runs, and linking one makes a class,
 * which takes more stack than the engine's checks make room for, so code that runs only when compiling fails must link
 * none, or the JVM's first compile error, maybe deep in a stack, could overflow there, and the error would be reported
 * as the overflow, without MVEL's description, line or column. {@link ExceptionReadsLambdaTest} checks
 * {@link MvelCompileErrors} whole. {@link MvelAnalysis} links a lambda when it's initialized, which preparing MVEL
 * does, so this reads its class file, as core's {@code ClassFiles} does, and checks only the methods a compile error
 * goes through: that none has an {@code invokedynamic} instruction. It checks {@link MvelCompileErrors#translate}, the
 * one method every compile error goes through, the same way, by name. The test fails too if a method named here isn't
 * there.
 */
@DisplayName("MVEL's analysis and its compile-error translator link no lambda when compiling fails (#1097)")
class FailureMethodLambdaTest {

    private static final Map<Class<?>, List<String>> FAILURE_METHODS = Map.of(
            MvelAnalysis.class, List.of("compile", "typeNamed", "throwIfLooped"),
            MvelCompileErrors.class, List.of("translate"));

    @Test
    @DisplayName("the methods that read a compile error have no invokedynamic")
    void noLambdaLinked() throws IOException, URISyntaxException {
        List<String> missing = new ArrayList<>();
        List<String> linking = new ArrayList<>();
        for (Map.Entry<Class<?>, List<String>> methods : FAILURE_METHODS.entrySet()) {
            ClassFiles.Methods read = ClassFiles.invokeDynamicMethods(ClassFiles.classFile(methods.getKey()));
            for (String method : methods.getValue()) {
                String named = methods.getKey().getSimpleName() + "." + method;
                if (!read.names().contains(method)) {
                    missing.add(named);
                }
                for (String descriptor : read.linking(method)) {
                    linking.add(methods.getKey().getSimpleName() + "." + descriptor);
                }
            }
        }

        assertEquals(List.of(), missing, "methods not in their class");
        assertEquals(List.of(), linking, "methods with an invokedynamic");
    }
}
