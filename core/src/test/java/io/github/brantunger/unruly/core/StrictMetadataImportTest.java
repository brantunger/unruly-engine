package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.LinkageMissingRegistration;
import org.graalvm.nativeimage.MissingReflectionRegistrationError;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

import static io.github.brantunger.unruly.TestSupport.withContextClassLoader;
import static org.junit.jupiter.api.Assertions.*;

/**
 * A native image built with strict reachability metadata throws GraalVM's {@code MissingReflectionRegistrationError}
 * for a name it has no metadata for, where the JVM throws {@link ClassNotFoundException}, so an import string's lookup
 * reads it as no class. The class loader here throws the tests' stand-in for that error, which has its name, as
 * GraalVM for JDK 21 has it, an {@link Error}, or, as GraalVM for JDK 25 has it, a {@link LinkageError}.
 */
@DisplayName("in a native image built with strict reachability metadata, an import it has no metadata for isn't a"
        + " class (#951)")
class StrictMetadataImportTest {

    /** An error that isn't the image's, which the lookup must pass on. */
    private static final class OtherError extends Error {
        private static final long serialVersionUID = 1L;

        OtherError() {
            super("not the image's");
        }
    }

    private static final OtherError OTHER_ERROR = new OtherError();

    /**
     * The image's error for a name, as the GraalVM release for a JDK throws it.
     *
     * @param jdk 21, for an {@link Error}, or 25, for a {@link LinkageError}
     */
    private static Error imageError(int jdk, String name) {
        return jdk == 25 ? LinkageMissingRegistration.of(name) : new MissingReflectionRegistrationError(name);
    }

    /**
     * A class loader where every name in package {@code p} has no metadata, as in such an image built with GraalVM for
     * {@code jdk}, except these: {@code p.Outer$Inner}, which is {@link Map.Entry}; {@code p.J.K}, which isn't a class,
     * as on the JVM; and {@code p.Other}, whose lookup throws an error that isn't the image's.
     */
    private static ClassLoader loader(int jdk) {
        return new ClassLoader(StrictMetadataImportTest.class.getClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                switch (name) {
                    case "p.Outer$Inner" -> {
                        return Map.Entry.class;
                    }
                    case "p.J.K" -> throw new ClassNotFoundException(name);
                    case "p.Other" -> throw OTHER_ERROR;
                    default -> {
                        if (name.startsWith("p.") || name.startsWith("p$")) {
                            throw imageError(jdk, name);
                        }
                        return super.loadClass(name, resolve);
                    }
                }
            }
        };
    }

    private static Class<?> resolve(String name, int jdk) {
        return withContextClassLoader(loader(jdk), () -> ImportResolver.resolve(name));
    }

    @ParameterizedTest(name = "as GraalVM for JDK {0} throws it")
    @ValueSource(ints = {21, 25})
    @DisplayName("a package name is a package import")
    void packageImport(int jdk) {
        assertNull(resolve("p.pkg", jdk));
    }

    @ParameterizedTest(name = "as GraalVM for JDK {0} throws it")
    @ValueSource(ints = {21, 25})
    @DisplayName("a nested class written as Java imports it is found by its binary name")
    void nestedClass(int jdk) {
        assertSame(Map.Entry.class, resolve("p.Outer.Inner", jdk));
    }

    @ParameterizedTest(name = "as GraalVM for JDK {0} throws it")
    @ValueSource(ints = {21, 25})
    @DisplayName("a form of the name with a '$' that has no metadata is skipped, after a first form that isn't a class")
    void binaryNameWithoutMetadata(int jdk) {
        assertNull(resolve("p.J.K", jdk));
    }

    @ParameterizedTest(name = "as GraalVM for JDK {0} throws it")
    @ValueSource(ints = {21, 25})
    @DisplayName("a name that is neither a class nor a valid package name is rejected, with the image's error in the"
            + " cause")
    void notAPackageName(int jdk) {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> resolve("p.1x", jdk));

        assertEquals("'p.1x' is neither a class nor a valid package name", ex.getMessage());
        ClassNotFoundException notFound = assertInstanceOf(ClassNotFoundException.class, ex.getCause());
        assertEquals("p.1x", notFound.getMessage());
        Throwable cause = notFound.getCause();
        assertEquals(MissingReflectionRegistrationError.class.getName(), cause.getClass().getName());
        assertEquals(jdk == 25, cause instanceof LinkageError);
    }

    @Test
    @DisplayName("another error from the lookup that isn't a LinkageError is passed on unchanged")
    void otherErrorPassedOn() {
        assertSame(OTHER_ERROR, assertThrows(OtherError.class, () -> resolve("p.Other", 21)));
    }
}
