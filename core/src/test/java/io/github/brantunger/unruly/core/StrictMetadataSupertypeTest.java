package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.LinkageMissingRegistration;
import org.graalvm.nativeimage.MissingReflectionRegistrationError;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;

import static io.github.brantunger.unruly.JavaSources.assertCompiles;
import static io.github.brantunger.unruly.JavaSources.source;
import static org.junit.jupiter.api.Assertions.*;

/**
 * A native image built with strict reachability metadata throws GraalVM's {@code MissingReflectionRegistrationError}
 * when a public supertype it has no metadata for is asked for a method, where the JVM throws
 * {@link NoSuchMethodException} or finds it, so {@link Accessors#callable} reads it as a supertype that doesn't
 * declare the method (#972). On the JVM, a public interface's {@code getMethod} throws the error unchanged when the
 * class loader throws it for a class one of the interface's methods names, which is how these classes get it: the
 * tests' stand-in for the error, as GraalVM for JDK 21 has it, an {@link Error}, or, as GraalVM for JDK 25 has it, a
 * {@link LinkageError}.
 */
@DisplayName("in a native image built with strict reachability metadata, a public supertype it has no metadata for"
        + " doesn't stop a method being made callable (#972)")
class StrictMetadataSupertypeTest {

    /** An error that isn't the image's, which the lookup must pass on. */
    private static final class OtherError extends Error {
        private static final long serialVersionUID = 1L;

        OtherError() {
            super("not the image's");
        }
    }

    private static final OtherError OTHER_ERROR = new OtherError();

    /** A public interface with the setter, and a method whose parameter's class has no metadata. */
    private static final String UNREGISTERED = """
            package p;

            public interface Unregistered {
                void setRate(String rate);

                void other(Missing missing);
            }
            """;

    /** A public interface with the setter, which the image has metadata for. */
    private static final String REGISTERED = """
            package p;

            public interface Registered {
                void setRate(String rate);
            }
            """;

    /** A class that isn't public, whose setter both interfaces declare, the one without metadata first. */
    private static final String BOTH = """
            package p;

            abstract class Both implements Unregistered, Registered {
                public void setRate(String rate) {
                }
            }
            """;

    /** A class that isn't public, whose setter only the interface without metadata declares. */
    private static final String ALONE = """
            package p;

            abstract class Alone implements Unregistered {
                public void setRate(String rate) {
                }
            }
            """;

    private static final String MISSING = """
            package p;

            public final class Missing {
            }
            """;

    @TempDir
    private static Path classes;

    @BeforeAll
    static void compile() {
        assertCompiles(List.of("-proc:none", "-d", classes.toString()), List.of(source("p/Unregistered", UNREGISTERED),
                source("p/Registered", REGISTERED), source("p/Both", BOTH), source("p/Alone", ALONE),
                source("p/Missing", MISSING)));
    }

    /**
     * The image's error for a name, as the GraalVM release for a JDK throws it.
     *
     * @param jdk 21, for an {@link Error}, or 25, for a {@link LinkageError}
     */
    private static Error imageError(int jdk, String name) {
        return jdk == 25 ? LinkageMissingRegistration.of(name) : new MissingReflectionRegistrationError(name);
    }

    /** A class loader for the compiled classes that throws {@code error}'s error for {@code p.Missing}. */
    private static ClassLoader loader(Supplier<Error> error) throws MalformedURLException {
        return new URLClassLoader(new URL[]{classes.toUri().toURL()},
                StrictMetadataSupertypeTest.class.getClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if ("p.Missing".equals(name)) {
                    throw error.get();
                }
                return super.loadClass(name, resolve);
            }
        };
    }

    private static Method setter(Class<?> type) throws NoSuchMethodException {
        return type.getDeclaredMethod("setRate", String.class);
    }

    @ParameterizedTest(name = "as GraalVM for JDK {0} throws it")
    @ValueSource(ints = {21, 25})
    @DisplayName("the next public supertype is asked after one without metadata, and its method is the one called")
    void nextSupertypeAsked(int jdk) throws ReflectiveOperationException, MalformedURLException {
        Class<?> both = Class.forName("p.Both", false, loader(() -> imageError(jdk, "p.Missing")));

        Method callable = Accessors.callable(both, setter(both));

        assertEquals("p.Registered", callable.getDeclaringClass().getName());
        assertEquals("setRate", callable.getName());
    }

    @ParameterizedTest(name = "as GraalVM for JDK {0} throws it")
    @ValueSource(ints = {21, 25})
    @DisplayName("where no other public supertype declares it, the class's own method is returned")
    void ownMethodReturned(int jdk) throws ReflectiveOperationException, MalformedURLException {
        Class<?> alone = Class.forName("p.Alone", false, loader(() -> imageError(jdk, "p.Missing")));
        Method setter = setter(alone);

        assertSame(setter, Accessors.callable(alone, setter));
    }

    @Test
    @DisplayName("another error from a supertype's lookup is passed on unchanged")
    void otherErrorPassedOn() throws ReflectiveOperationException, MalformedURLException {
        Class<?> both = Class.forName("p.Both", false, loader(() -> OTHER_ERROR));
        Method setter = setter(both);

        assertSame(OTHER_ERROR, assertThrows(OtherError.class, () -> Accessors.callable(both, setter)));
    }
}
