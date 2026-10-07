package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.ClassFiles;
import io.github.brantunger.unruly.api.OutputWriter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * #1097: the JVM links a lambda or a method reference the first time its call site runs, and linking one makes a class,
 * which takes more stack than the engine's checks make room for, so code that runs only when something fails or waits
 * must link none, or the JVM's first such failure or wait, maybe deep in a stack, could overflow there and report the
 * overflow in place of what happened. {@link FailureCodeLambdaTest} checks the classes whose code is all such code. The
 * classes here link lambdas on paths every run or load takes too, so this reads each one's class file, as
 * {@link ClassFiles} does, and checks the methods on those paths alone: that none has an {@code invokedynamic}
 * instruction, a lambda's, a method reference's or any other. They are a failed borrow and the closing of a retired
 * rule set it may do, a run's wait for a copy or a build slot, a write that no setter accepts, with the methods of
 * {@link Widening} it calls, and a load with two or more failures. The test fails too if a method named here isn't
 * there, so it can't pass because one was renamed.
 */
@DisplayName("the engine's failure and wait paths link no lambda in classes whose other code does (#1097)")
class FailureMethodLambdaTest {

    static Stream<Arguments> failurePaths() {
        return Stream.of(
                // A failed borrow, which may be the last user of a retired rule set and close it, and a run's wait
                // for a copy or a build slot.
                Arguments.of(RuleSet.class, List.of("failedBorrow", "closeTaken", "closeUnused", "partDone",
                        "closeCompilers", "lend", "slottedCopy")),
                // A write that no setter accepts, and what its message says of the setters there are.
                Arguments.of(OutputWriter.beansAndMaps().getClass(), List.of("set", "written", "primitiveSetters",
                        "unreachable")),
                Arguments.of(Widening.class, List.of("isPrimitiveLike", "isWrapper", "order")),
                // A load that fails, and one with two or more failures.
                Arguments.of(RuleListCompiler.class, List.of("combined", "compilationFailure", "overflowReason")),
                Arguments.of(RuleListCompiler.Compilation.class, List.of("failed", "throwIfAnyFailed")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("failurePaths")
    @DisplayName("the class's methods on failure and wait paths have no invokedynamic")
    void noLambdaLinked(Class<?> type, List<String> methods) throws IOException, URISyntaxException {
        ClassFiles.Methods read = ClassFiles.invokeDynamicMethods(ClassFiles.classFile(type));
        List<String> missing = new ArrayList<>();
        List<String> linking = new ArrayList<>();
        for (String method : methods) {
            if (!read.names().contains(method)) {
                missing.add(method);
            }
            linking.addAll(read.linking(method));
        }

        assertEquals(List.of(), missing, "methods not in " + type.getName());
        assertEquals(List.of(), linking, "methods of " + type.getName() + " with an invokedynamic");
    }

    @Test
    @DisplayName("the reader finds the invokedynamic of a lambda on a path every run takes")
    void readerFindsALambda() throws IOException, URISyntaxException {
        // RuleSet.enter() counts a run with a lambda, which every run links shallow in its stack, so the reader finds
        // one where there is one, and the test above can't pass because it finds none anywhere.
        assertEquals(List.of("enter()Z"), ClassFiles.invokeDynamicMethods(ClassFiles.classFile(RuleSet.class))
                .linking("enter"));
    }
}
