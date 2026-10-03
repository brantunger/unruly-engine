package io.github.brantunger.unruly;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Constructor;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static io.github.brantunger.unruly.JavaSources.assertCompiles;
import static io.github.brantunger.unruly.JavaSources.source;

/**
 * Makes the error a native image built with strict reachability metadata throws, in GraalVM for JDK 25, for a name it
 * has no metadata for: a {@code org.graalvm.nativeimage.MissingReflectionRegistrationError} that is a
 * {@link LinkageError}. GraalVM for JDK 21's, which the stand-in by that name in each module's tests copies, is an
 * {@link Error} but not a {@link LinkageError}. The library tells the error by its class's name alone, so this one has
 * the stand-in's name, and is compiled into a class loader of its own, which can hold a second class by that name.
 */
public final class LinkageMissingRegistration {

    private static final String NAME = "org.graalvm.nativeimage.MissingReflectionRegistrationError";

    private static final Constructor<? extends LinkageError> CONSTRUCTOR = compile();

    private LinkageMissingRegistration() {
    }

    /**
     * Creates the error for a name, as the image words it.
     *
     * @param name The name looked up
     * @return The error, a {@link LinkageError} whose class is named as GraalVM's is
     */
    public static LinkageError of(String name) {
        try {
            return CONSTRUCTOR.newInstance(name);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Constructor<? extends LinkageError> compile() {
        try {
            Path directory = Files.createTempDirectory("linkage-missing-registration");
            try {
                assertCompiles(List.of("-proc:none", "-d", directory.toString()), List.of(source(
                        NAME.replace('.', '/'), """
                        package org.graalvm.nativeimage;

                        public final class MissingReflectionRegistrationError extends LinkageError {
                            private static final long serialVersionUID = 1L;

                            public MissingReflectionRegistrationError(String name) {
                                super("The program tried to reflectively access class " + name
                                        + " without it being registered for runtime reflection.");
                            }
                        }
                        """)));
                // Not the tests' class loader, which has the stand-in by the same name and would find that first.
                try (URLClassLoader loader = new URLClassLoader(new URL[]{directory.toUri().toURL()},
                        ClassLoader.getPlatformClassLoader())) {
                    // Initialized now, while the class loader is open.
                    return Class.forName(NAME, true, loader).asSubclass(LinkageError.class)
                            .getConstructor(String.class);
                }
            } finally {
                try (Stream<Path> files = Files.walk(directory)) {
                    for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
                        Files.delete(file);
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
