package org.graalvm.nativeimage;

/**
 * Stands in for the error a GraalVM native image built with strict reachability metadata throws for a name it has no
 * metadata for, where the JVM throws {@link ClassNotFoundException}. The library tells that error by its class's name
 * alone, so a test can throw this one from a class loader, as the image's would, without GraalVM. It extends
 * {@link Error}, as GraalVM for JDK 21's, the release CI builds its image with, does. It is in the tests only.
 */
public final class MissingReflectionRegistrationError extends Error {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the error for a name, as the image words it.
     *
     * @param name The name looked up
     */
    public MissingReflectionRegistrationError(String name) {
        super("The program tried to reflectively access class " + name
                + " without it being registered for runtime reflection.");
    }
}
