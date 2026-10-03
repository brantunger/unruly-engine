package io.github.brantunger.unruly.api;

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
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.function.Supplier;

import static io.github.brantunger.unruly.JavaSources.assertCompiles;
import static io.github.brantunger.unruly.JavaSources.source;
import static org.junit.jupiter.api.Assertions.*;

/**
 * A native image built with strict reachability metadata throws GraalVM's {@code MissingReflectionRegistrationError}
 * when a class it has no metadata for is asked for its public methods, where the JVM lists them, so the default
 * {@link OutputWriter} reads it as a class without the method a bridge calls, and without setters for the bridge to
 * accept (#972). On the JVM, a class's {@code getMethod} and {@code getMethods} throw the error unchanged when the
 * class loader throws it for a class one of its superclass's methods names, which is how this bridge's class gets it:
 * the tests' stand-in for the error, as GraalVM for JDK 21 has it, an {@link Error}, or, as GraalVM for JDK 25 has it,
 * a {@link LinkageError}.
 */
@DisplayName("in a native image built with strict reachability metadata, a bridge's class it has no metadata for is"
        + " read as a class without the bridge's target, and without setters for the bridge to accept (#972)")
class StrictMetadataBridgeTest {

    /** An error that isn't the image's, which the lookup must pass on. */
    private static final class OtherError extends Error {
        private static final long serialVersionUID = 1L;

        OtherError() {
            super("not the image's");
        }
    }

    private static final OtherError OTHER_ERROR = new OtherError();

    /** A public generic superclass with a generic setter, and a method whose parameter's class has no metadata. */
    private static final String LEVELED = """
            package p;

            public abstract class Leveled<T> {
                public abstract void setLevel(T level);

                public void other(Missing missing) {
                }
            }
            """;

    /** A class that isn't public and overrides the generic setter, so the compiler gives it a bridge. */
    private static final String GRADED = """
            package p;

            class Graded extends Leveled<String> {
                @Override
                public void setLevel(String level) {
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
        assertCompiles(List.of("-proc:none", "-d", classes.toString()), List.of(source("p/Leveled", LEVELED),
                source("p/Graded", GRADED), source("p/Missing", MISSING)));
    }

    /**
     * The image's error for a name, as the GraalVM release for a JDK throws it.
     *
     * @param jdk 21, for an {@link Error}, or 25, for a {@link LinkageError}
     */
    private static Error imageError(int jdk, String name) {
        return jdk == 25 ? LinkageMissingRegistration.of(name) : new MissingReflectionRegistrationError(name);
    }

    /** A class loader for the compiled classes that throws {@code error}'s error each time it's asked for p.Missing. */
    private static ClassLoader loader(Supplier<Error> error) throws MalformedURLException {
        return new URLClassLoader(new URL[]{classes.toUri().toURL()}, StrictMetadataBridgeTest.class.getClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if ("p.Missing".equals(name)) {
                    throw error.get();
                }
                return super.loadClass(name, resolve);
            }
        };
    }

    /** The bridge the compiler gave p.Graded, from a loader that throws {@code error}'s error for p.Missing. */
    private static Method bridge(Supplier<Error> error) throws ClassNotFoundException, MalformedURLException {
        Class<?> graded = Class.forName("p.Graded", false, loader(error));
        // Only Graded's own methods, whose parameters the loader has: getMethods() would ask Leveled's too.
        for (Method method : graded.getDeclaredMethods()) {
            if (method.isBridge()) {
                return method;
            }
        }
        throw new AssertionError("p.Graded has no bridge");
    }

    @ParameterizedTest(name = "as GraalVM for JDK {0} throws it")
    @ValueSource(ints = {21, 25})
    @DisplayName("a class without metadata has no target for a bridge")
    void noTarget(int jdk) throws ReflectiveOperationException, MalformedURLException {
        Method bridge = bridge(() -> imageError(jdk, "p.Missing"));

        assertNull(BeansAndMapsWriter.target(bridge));
    }

    @ParameterizedTest(name = "as GraalVM for JDK {0} throws it")
    @ValueSource(ints = {21, 25})
    @DisplayName("a bridge whose class has no metadata accepts no narrower setter's type")
    void acceptsNoNarrowerSetter(int jdk) throws ReflectiveOperationException, MalformedURLException {
        Method bridge = bridge(() -> imageError(jdk, "p.Missing"));

        assertEquals(List.of(), new BeansAndMapsWriter.Lookup(bridge.getDeclaringClass()).bridged(bridge));
    }

    @Test
    @DisplayName("another error from looking up a bridge's target is passed on unchanged")
    void otherErrorFromTarget() throws ReflectiveOperationException, MalformedURLException {
        Method bridge = bridge(() -> OTHER_ERROR);

        assertSame(OTHER_ERROR, assertThrows(OtherError.class, () -> BeansAndMapsWriter.target(bridge)));
    }

    @Test
    @DisplayName("another error from listing a bridge's class's methods is passed on unchanged")
    void otherErrorFromMethods() throws ReflectiveOperationException, MalformedURLException {
        // The image's error for the target's lookup, so the setters for the bridge to accept are looked up next, and
        // another error after that: a failed lookup leaves nothing behind, so the loader is asked again.
        Deque<Error> errors = new ArrayDeque<>(List.of(new MissingReflectionRegistrationError("p.Missing")));
        Method bridge = bridge(() -> errors.isEmpty() ? OTHER_ERROR : errors.remove());
        BeansAndMapsWriter.Lookup lookup = new BeansAndMapsWriter.Lookup(bridge.getDeclaringClass());

        assertSame(OTHER_ERROR, assertThrows(OtherError.class, () -> lookup.bridged(bridge)));
        assertTrue(errors.isEmpty(), "the target's lookup didn't ask for p.Missing");
    }
}
