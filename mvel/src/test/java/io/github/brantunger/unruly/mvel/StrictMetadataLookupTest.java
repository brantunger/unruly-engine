package io.github.brantunger.unruly.mvel;

import io.github.brantunger.unruly.LinkageMissingRegistration;
import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.FactStore;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
import org.graalvm.nativeimage.MissingReflectionRegistrationError;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static io.github.brantunger.unruly.TestSupport.withContextClassLoader;
import static org.junit.jupiter.api.Assertions.*;

/**
 * A native image built with strict reachability metadata throws GraalVM's {@code MissingReflectionRegistrationError}
 * for a name it has no metadata for, where the JVM throws {@link ClassNotFoundException}, and MVEL looks up many names
 * that aren't classes. The class loaders here throw the tests' stand-in for that error, which has its name, as
 * GraalVM for JDK 21 has it, an {@link Error}, or, as GraalVM for JDK 25 has it, a {@link LinkageError}.
 */
@DisplayName("in a native image built with strict reachability metadata, MVEL reads a name it has no metadata for as"
        + " no class (#951)")
class StrictMetadataLookupTest {

    private static final String IMAGE_CODE = "org.graalvm.nativeimage.imagecode";

    /** An error that isn't the image's, which the lookup must pass on. */
    private static final class OtherError extends Error {
        private static final long serialVersionUID = 1L;

        OtherError() {
            super("not the image's");
        }
    }

    /**
     * The application's class loader in such an image: a name the tests' class loader has no class by has no metadata
     * either, so it throws the image's error for it, as the GraalVM release for a JDK throws it: 21, for an
     * {@link Error}, or 25, for a {@link LinkageError}.
     */
    private static final class StrictImageLoader extends ClassLoader {

        private final int jdk;

        StrictImageLoader(int jdk) {
            super(StrictMetadataLookupTest.class.getClassLoader());
            this.jdk = jdk;
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) {
            try {
                return super.loadClass(name, resolve);
            } catch (ClassNotFoundException e) {
                throw jdk == 25 ? LinkageMissingRegistration.of(name) : new MissingReflectionRegistrationError(name);
            }
        }
    }

    private static Stream<Arguments> imageAndJdk() {
        return Stream.of(Arguments.of(false, 21), Arguments.of(true, 21), Arguments.of(false, 25),
                Arguments.of(true, 25));
    }

    @ParameterizedTest(name = "as in an image: {0}, as GraalVM for JDK {1} throws it")
    @MethodSource("imageAndJdk")
    @DisplayName("a rule that reads a fact's property and names a nested class through a package import compiles and"
            + " runs")
    void rulesRun(boolean image, int jdk) {
        if (image) {
            System.setProperty(IMAGE_CODE, "runtime");
        }
        try {
            Map<String, Object> output = withContextClassLoader(new StrictImageLoader(jdk), () -> {
                try (RulesEngine<Map<String, Object>> engine = RulesEngineBuilder
                        .<Map<String, Object>>firstMatch(HashMap::new).imports("java.util").build()) {
                    engine.load(List.of(Rule.builder().ruleName("r").condition("applicant.creditScore >= 750")
                            .action("output.put('v', new AbstractMap.SimpleEntry('a', 1).getValue())").build()));
                    FactStore<Object> facts = new FactMap<>();
                    facts.setValue("applicant", Map.of("creditScore", 780));
                    return engine.run(facts);
                }
            });

            assertEquals(Map.of("v", 1), output);
        } finally {
            System.clearProperty(IMAGE_CODE);
        }
    }

    @ParameterizedTest(name = "as GraalVM for JDK {0} throws it")
    @ValueSource(ints = {21, 25})
    @DisplayName("a name the application's class loader has no metadata for is a class that isn't there, with the"
            + " image's error as the cause")
    void notFound(int jdk) {
        ExactNameClassLoader loader = new ExactNameClassLoader(new StrictImageLoader(jdk), 10, false);

        ClassNotFoundException ex = assertThrows(ClassNotFoundException.class, () -> loader.loadClass("p.Missing"));

        assertEquals("p.Missing", ex.getMessage());
        Throwable cause = ex.getCause();
        assertEquals(MissingReflectionRegistrationError.class.getName(), cause.getClass().getName());
        assertEquals(jdk == 25, cause instanceof LinkageError);
    }

    @Test
    @DisplayName("another error from the application's class loader that isn't a LinkageError is passed on unchanged")
    void otherErrorPassedOn() {
        OtherError other = new OtherError();
        ClassLoader parent = new ClassLoader(StrictMetadataLookupTest.class.getClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) {
                throw other;
            }
        };
        ExactNameClassLoader loader = new ExactNameClassLoader(parent, 10, false);

        assertSame(other, assertThrows(OtherError.class, () -> loader.loadClass("p.Other")));
    }
}
