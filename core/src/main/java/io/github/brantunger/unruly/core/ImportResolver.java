package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.internal.ImportSize;
import io.github.brantunger.unruly.internal.Lookups;
import io.github.brantunger.unruly.internal.Text;

/**
 * Works out what the import strings an engine is built with name, and which class loader imported classes are looked up
 * with.
 */
final class ImportResolver {

    // For a thread that has no context class loader. PMD asks for the context class loader instead, which is exactly
    // what is missing in that case.
    @SuppressWarnings("PMD.UseProperClassLoader")
    static final ClassLoader LIBRARY_CLASS_LOADER = ImportResolver.class.getClassLoader();

    private ImportResolver() {
    }

    /**
     * Works out what an import string names. Its size is checked first, then its shape: it must be a syntactically
     * valid package name, every dot-separated part a Java identifier, before any class loader sees it, so a string
     * rejected for either reason leaves no lock object in a parallel-capable class loader, such as the JDK's
     * application class loader. So a class whose binary name isn't made of Java identifiers, as the JVM allows for a
     * class defined at run time, can't be imported by name. A name of that shape that the context class loader can load
     * as a class is then imported on its own, so {@code imports("java.time.LocalDate")} works; one no form of which is
     * a class is a package import, and one whose class exists but can't be loaded is rejected. A nested class can be
     * written as Java imports it ({@code java.util.Map.Entry}) or by its binary name ({@code java.util.Map$Entry}), as
     * in an inline MVEL {@code import}. A class file found for the name, or for one of its {@code $} forms, in a
     * different case, as a class directory on a case-insensitive file system finds one, isn't a class of that name, so
     * the forms still to try are tried; one found in a different case for a class the class named depends on, such as
     * its superclass, makes it a class that can't be loaded. In a native image built with strict reachability metadata,
     * a name the image has no metadata for isn't a class, as on the JVM a name no class has isn't one, so a package
     * import such as {@code java.util} still works there.
     *
     * @param name An import string given to the builder
     * @return The class, or {@code null} if {@code name} is a package name
     * @throws IllegalArgumentException if {@code name} has more than {@value ImportSize#MAX_IMPORT_LENGTH} characters
     *                                  or more than {@value ImportSize#MAX_IMPORT_PARTS} dot-separated parts (see
     *                                  {@link ImportSize#checkSize}); if it isn't a valid package name (see
     *                                  {@link ImportSize#checkPackageName}), with no cause, as it wasn't looked up;
     *                                  or if it names a class that exists but can't be loaded, for example because a
     *                                  class it depends on is missing. The linkage error's text is cut to at most
     *                                  1,000 characters, with a note of how many were left out, then escaped, as the
     *                                  application's own class loader may have written it, and a root cause it hides
     *                                  is named
     */
    static Class<?> resolve(String name) {
        ImportSize.checkSize(name);
        ImportSize.checkPackageName(name);
        try {
            return loadImport(name, contextClassLoader());
        } catch (ClassNotFoundException e) {
            // A valid package name that isn't a class.
            return null;
        } catch (LinkageError e) {
            // A class file found for the name looked up, in another case, is already no class (see load). Any other
            // linkage error, a class file found in another case for a class it depends on too, means the class
            // exists, and rules couldn't use it.
            throw new IllegalArgumentException("Can't import '" + Text.quote(name)
                    + "': the class exists but can't be loaded: "
                    + Failures.describeWithClass(e), e);
        }
    }

    /**
     * Loads an imported class as MVEL looks one up: by its name, then with each dot from the right replaced by
     * {@code $} in turn, so {@code java.util.Map.Entry} finds {@code java.util.Map$Entry}. The class isn't
     * initialized.
     *
     * @throws ClassNotFoundException the first lookup's exception, if no form of the name is a class
     */
    private static Class<?> loadImport(String name, ClassLoader loader) throws ClassNotFoundException {
        try {
            return load(name, loader);
        } catch (ClassNotFoundException notFound) {
            String binaryName = name;
            for (int dot = name.lastIndexOf('.'); dot > 0; dot = binaryName.lastIndexOf('.')) {
                binaryName = binaryName.substring(0, dot) + '$' + binaryName.substring(dot + 1);
                Class<?> nested = loadOrNull(binaryName, loader);
                if (nested != null) {
                    return nested;
                }
            }
            throw notFound;
        }
    }

    private static Class<?> loadOrNull(String name, ClassLoader loader) {
        try {
            return load(name, loader);
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    /**
     * Loads a class, without initializing it, reporting as a class that isn't there a class file found for its name in
     * a different case, as a class directory on a case-insensitive file system finds one, and a name a native image has
     * no metadata for (see {@link Lookups#isMissingRegistration}), before any caller can take their error
     * for a class that exists but can't be loaded. So a form of an import's name that only finds such a file isn't a
     * class, and the next form is tried. Such a file found for a class this one depends on, such as its superclass,
     * fails the load as any other missing class it depends on does.
     *
     * @throws ClassNotFoundException if the class loader has no class by that name; if it only finds a class file
     *                                for that name in a different case, with the JVM's "wrong name" error as the
     *                                cause; or, in a native image built with strict reachability metadata, if the
     *                                image has no metadata for it, with the image's error as the cause
     */
    private static Class<?> load(String name, ClassLoader loader) throws ClassNotFoundException {
        try {
            return loader.loadClass(name);
        } catch (NoClassDefFoundError e) {
            if (Lookups.isWrongName(e, name)) {
                throw new ClassNotFoundException(name, e);
            }
            throw e;
        } catch (Error e) {
            if (Lookups.isMissingRegistration(e)) {
                throw new ClassNotFoundException(name, e);
            }
            throw e;
        }
    }

    /**
     * Returns the class loader to look up imported classes with: the calling thread's context class loader, or this
     * library's own loader when the thread has none. Left to itself, MVEL takes the context class loader of whichever
     * thread first needs one, so a lookup made from another thread could see different classes.
     *
     * @return The class loader for imports set up on this thread
     */
    static ClassLoader contextClassLoader() {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        return loader != null ? loader : LIBRARY_CLASS_LOADER;
    }
}
