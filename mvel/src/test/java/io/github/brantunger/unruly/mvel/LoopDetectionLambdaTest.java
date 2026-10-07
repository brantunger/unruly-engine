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
 * #1099: the JVM links a lambda or a method reference the first time its call site runs, and linking one makes a class,
 * which takes more stack than the engine's checks make room for. MVEL's loop detection walks the stack only once an
 * analysis pass or a run has asked for the class loader more than {@value CallSites#UNCOUNTED_CALLS} times, which a
 * valid expression may do as well as a loop, and the rule list's class loader walks it only for a name with a {@code $}
 * and too many parts. So the JVM's first such walk, maybe deep in a stack, could overflow linking one, and the load or
 * run would fail as an overflow rather than as what happened. {@link CallSites} links lambdas when it is initialized,
 * which preparing MVEL does, and {@link ExactNameClassLoader} when a rule first compiles, so this reads their class
 * files, as {@link FailureMethodLambdaTest} reads {@link MvelAnalysis}', and checks the methods a walk goes through,
 * and the classes nested in them, whole. It checks whole the configuration that counts the calls and throws when MVEL
 * went round in a loop, {@code Imports.SharedLookupConfiguration}, read by its name, and the two errors it throws. The
 * test fails too if a method or a class named here isn't there. {@link FirstUseClassInitializationTest} checks that
 * MVEL's first such steps link none.
 */
@DisplayName("MVEL's loop detection links no lambda when it walks the stack, nor when it stops a loop (#1099)")
class LoopDetectionLambdaTest {

    // The methods each call MVEL makes for the class loader goes through once it is counted, those that draw the gap
    // to the next walk, and those that tell its site.
    private static final List<String> CALL_SITES_METHODS = List.of("counted", "countedAt", "nextGap",
            "hashOfStack", "hashOfTopAndDepth", "frame");
    // The method that refuses a name with a '$' and too many parts, and the one that walks the stack for it.
    private static final List<String> CLASS_LOADER_METHODS = List.of("checkSize", "inNestedLookup");
    // The classes nested in Imports that count the calls and throw once MVEL went round in a loop.
    private static final List<String> IMPORTS_CLASSES = List.of("SharedLookupConfiguration", "AnalysisLoop",
            "RunLoop");

    @Test
    @DisplayName("CallSites' and ExactNameClassLoader's methods that walk the stack have no invokedynamic")
    void walksLinkNoLambda() throws IOException, URISyntaxException {
        List<String> missing = new ArrayList<>();
        List<String> linking = new ArrayList<>();
        linking(CallSites.class, CALL_SITES_METHODS, missing, linking);
        linking(ExactNameClassLoader.class, CLASS_LOADER_METHODS, missing, linking);

        assertEquals(List.of(), missing, "methods not there");
        assertEquals(List.of(), linking, "methods with an invokedynamic");
    }

    @Test
    @DisplayName("the classes nested in CallSites and ExactNameClassLoader link no lambda or method reference, nor any "
            + "other invokedynamic call site")
    void nestedClassesLinkNoLambda() throws IOException, URISyntaxException {
        List<String> linking = new ArrayList<>();
        for (Class<?> type : List.of(CallSites.class, ExactNameClassLoader.class)) {
            String outer = type.getName().replace('.', '/') + ".class";
            for (Map.Entry<String, byte[]> classFile : ClassFiles.of(type).entrySet()) {
                if (!classFile.getKey().equals(outer)) {
                    linking.addAll(linking(classFile.getKey(), classFile.getValue()));
                }
            }
        }

        assertEquals(List.of(), linking, "nested classes that link a lambda or have an invokedynamic");
    }

    @Test
    @DisplayName("the configuration that counts MVEL's calls for the class loader, and the errors it throws for a "
            + "loop, link no lambda or method reference, nor any other invokedynamic call site")
    void loopErrorsLinkNoLambda() throws IOException, URISyntaxException {
        Map<String, byte[]> classFiles = ClassFiles.of(Imports.class);
        List<String> missing = new ArrayList<>();
        List<String> linking = new ArrayList<>();
        for (String nested : IMPORTS_CLASSES) {
            String name = Imports.class.getName().replace('.', '/') + "$" + nested + ".class";
            byte[] classFile = classFiles.get(name);
            if (classFile == null) {
                missing.add(name);
            } else {
                linking.addAll(linking(name, classFile));
            }
        }

        assertEquals(List.of(), missing, "classes not read: " + classFiles.keySet());
        assertEquals(List.of(), linking, "classes that link a lambda or have an invokedynamic");
    }

    // Adds each method named that the class hasn't got, and each of them with an invokedynamic.
    private static void linking(Class<?> type, List<String> methods, List<String> missing, List<String> linking)
            throws IOException, URISyntaxException {
        ClassFiles.Methods read = ClassFiles.invokeDynamicMethods(ClassFiles.classFile(type));
        for (String method : methods) {
            if (!read.names().contains(method)) {
                missing.add(type.getSimpleName() + " " + method);
            }
            for (String linked : read.linking(method)) {
                linking.add(type.getSimpleName() + " " + linked);
            }
        }
    }

    // The class file's name if it names LambdaMetafactory, and each of its methods with an invokedynamic.
    private static List<String> linking(String name, byte[] classFile) throws IOException {
        List<String> linking = new ArrayList<>();
        if (ClassFiles.namesLambdaFactory(classFile)) {
            linking.add(name);
        }
        for (String method : ClassFiles.invokeDynamicMethods(classFile).linking()) {
            linking.add(name + " " + method);
        }
        return linking;
    }
}
