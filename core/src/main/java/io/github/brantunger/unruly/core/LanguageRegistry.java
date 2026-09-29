package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.language.ExpressionLanguage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.JarURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.TreeSet;

/**
 * The expression languages an engine compiles rules with, and its default language, fixed when the engine is built:
 * the languages given to the builder, or else those found with {@link ServiceLoader}, such as MVEL. The engine refers
 * to no language directly, and a language creates nothing until rules are loaded.
 *
 * @param languages       The languages by name
 * @param defaultLanguage The name of the language of a rule whose language is {@code null}
 */
record LanguageRegistry(Map<String, ExpressionLanguage> languages, String defaultLanguage) {

    private static final Logger log = LoggerFactory.getLogger(AbstractRulesEngine.LOGGER_NAME);

    /** The resource name of {@link ExpressionLanguage}'s class file, in this library and in any copy of it. */
    private static final String API_CLASS_FILE = ExpressionLanguage.class.getName().replace('.', '/') + ".class";

    /**
     * Resolves an engine's languages and its default language.
     *
     * @param given       The languages given to the builder, by the name the builder checked, or none
     * @param defaultName The default language named on the builder, or {@code null}
     * @param loader      A class loader that may see languages this library's loader can't: the building thread's
     *                    context class loader
     * @return The languages and the default
     * @throws IllegalStateException if there is no language; if there are several and {@code defaultName} is
     *                               {@code null}; if {@code defaultName} isn't one of them; or if a found language's
     *                               name is {@code null} or blank, or two different found languages have the same name.
     *                               Anything {@link ServiceLoader} or a language throws while it is found, such as a
     *                               {@link ServiceConfigurationError}, is thrown unchanged, except that when
     *                               {@code loader} has its own copy of {@link ExpressionLanguage}, a second copy of
     *                               this library, or isn't this library's loader and lists the class file of one
     *                               from another location than this library's, nothing more is found with it after
     *                               the first error ServiceLoader reports. A language whose class has the name of one
     *                               found already, a second copy of it, is skipped, and the one found first is kept
     */
    static LanguageRegistry resolve(Map<String, ExpressionLanguage> given, String defaultName, ClassLoader loader) {
        return resolve(given, defaultName, loader == ImportResolver.LIBRARY_CLASS_LOADER
                ? List.of(loader)
                : List.of(ImportResolver.LIBRARY_CLASS_LOADER, loader));
    }

    /**
     * Resolves an engine's languages and its default language, finding languages with the given class loaders.
     *
     * @param given       The languages given to the builder, by the name the builder checked, or none
     * @param defaultName The default language named on the builder, or {@code null}
     * @param loaders     The class loaders to find languages with, in order, if none are given
     * @return The languages and the default
     * @throws IllegalStateException as {@link #resolve(Map, String, ClassLoader)} describes
     */
    static LanguageRegistry resolve(Map<String, ExpressionLanguage> given, String defaultName,
                                    List<ClassLoader> loaders) {
        Map<String, ExpressionLanguage> languages = new HashMap<>();
        if (given.isEmpty()) {
            // The class of each language found, by its name.
            Map<String, Class<?>> found = new HashMap<>();
            for (ClassLoader loader : loaders) {
                discover(loader, languages, found);
            }
        } else {
            languages.putAll(given);
        }
        Set<String> names = new TreeSet<>(languages.keySet());
        if (defaultName != null) {
            if (!languages.containsKey(defaultName)) {
                throw new IllegalStateException("The default language '" + Failures.quote(defaultName)
                        + "' isn't one of the engine's expression languages: " + Failures.quoteAll(names));
            }
            return new LanguageRegistry(Map.copyOf(languages), defaultName);
        }
        if (names.isEmpty()) {
            throw new IllegalStateException("The engine has no expression language: add one with language(), or put "
                    + "a language on the class path or, with a provides clause, on the module path");
        }
        Iterator<String> name = names.iterator();
        String only = name.next();
        if (name.hasNext()) {
            throw new IllegalStateException("The engine has several expression languages, " + Failures.quoteAll(names)
                    + ", so name the language of rules without one with defaultLanguage()");
        }
        return new LanguageRegistry(Map.copyOf(languages), only);
    }

    private static void discover(ClassLoader loader, Map<String, ExpressionLanguage> languages,
                                 Map<String, Class<?>> found) {
        Iterator<ServiceLoader.Provider<ExpressionLanguage>> providers =
                ServiceLoader.load(ExpressionLanguage.class, loader).stream().iterator();
        while (true) {
            ServiceLoader.Provider<ExpressionLanguage> provider;
            try {
                if (!providers.hasNext()) {
                    return;
                }
                provider = providers.next();
            } catch (ServiceConfigurationError e) {
                // A loader with its own copy of this library's API, or that sees one, lists that copy's languages, and
                // each is a second copy. Asked only here, so a build() without an error doesn't look.
                if (!hasOwnCopy(loader)) {
                    throw e;
                }
                // The copy's languages implement the copy's ExpressionLanguage, not this one, so ServiceLoader
                // rejects them. Nothing more is looked for with this loader: what it lists belongs to the copy, and
                // an error ServiceLoader meets before it reads a listing comes back on every call.
                log.debug("Stopped finding expression languages with {}: that class loader sees another copy of this"
                        + " library, so the languages it lists are a second copy: {}", loader, e.getMessage());
                return;
            }
            // By the class's name, before the language is created: a loader that delegates to this library's loader
            // finds the same class again, and one that holds a second copy of a language, with or without its own
            // copy of the library, finds a class of the same name. Either way the language found first is kept.
            Class<? extends ExpressionLanguage> type = provider.type();
            Class<?> other = found.putIfAbsent(type.getName(), type);
            if (other != null) {
                if (other != type) {
                    log.debug("Skipped the expression language {} that {} found with ServiceLoader: it's a second"
                            + " copy of one already found with another class loader", type.getName(), loader);
                }
                continue;
            }
            ExpressionLanguage language = provider.get();
            String name = language.name();
            LanguageNames.Problem problem = LanguageNames.check(name, languages);
            if (problem == LanguageNames.Problem.NULL_OR_BLANK) {
                throw new IllegalStateException("The expression language " + language.getClass().getName()
                        + " found with ServiceLoader has a null or blank name");
            }
            if (problem == LanguageNames.Problem.TAKEN) {
                throw new IllegalStateException("The expression languages " + languages.get(name).getClass().getName()
                        + " and " + language.getClass().getName() + " found with ServiceLoader are both named '"
                        + Failures.quote(name) + "'");
            }
            languages.put(name, language);
        }
    }

    /**
     * Whether a class loader has its own copy of {@link ExpressionLanguage}, rather than this library's, or sees
     * another copy of it: it lists {@code ExpressionLanguage}'s class file from a location other than this library's.
     * A location is the jar a class file is in, or the class file itself, and two locations are the same file however
     * their URLs spell it, so a second loader that opens this library's own jar sees no other copy. A location that is
     * neither a jar nor a file is compared by its URL alone. This library's own loader sees none either, even with two
     * copies of the library on the class path, so a broken listing there still fails {@code build()}. A loader that
     * can't say, because looking the class or its class files up fails in any way, has none, and a location whose file
     * can't be found is taken for this library's.
     */
    private static boolean hasOwnCopy(ClassLoader loader) {
        try {
            if (loader.loadClass(ExpressionLanguage.class.getName()) != ExpressionLanguage.class) {
                return true;
            }
        } catch (ClassNotFoundException | RuntimeException | LinkageError e) {
            return false;
        }
        if (loader == ImportResolver.LIBRARY_CLASS_LOADER) {
            // What this library's own loader lists is the application's, and a problem there is the application's.
            return false;
        }
        // A loader that resolves this library's API but also lists another copy of it, as a plug-in host's delegating
        // loader does, lists languages built against that copy.
        try {
            URL own = ImportResolver.LIBRARY_CLASS_LOADER.getResource(API_CLASS_FILE);
            for (URL listed : Collections.list(loader.getResources(API_CLASS_FILE))) {
                if (!listed.toExternalForm().equals(own.toExternalForm()) && !isSameFile(listed, own)) {
                    return true;
                }
            }
            return false;
        } catch (IOException | RuntimeException e) {
            // Also where this library's class files can't be read as resources at all.
            return false;
        }
    }

    /**
     * Whether two URLs of a class file name the same location: the same jar, or the same class file outside a jar,
     * however each URL spells its path, such as with a {@code ..} segment, a short name, another case or a space left
     * unencoded. URLs that name neither a jar nor a file, such as a plug-in framework's own, aren't. Two whose files
     * can't be found or compared are taken for the same file, so that such a location doesn't hide an error.
     */
    private static boolean isSameFile(URL classFile, URL other) {
        try {
            return Files.isSameFile(location(classFile), location(other));
        } catch (FileSystemNotFoundException e) {
            // No file system for the URL's scheme, so the URLs are all there is to compare, and they differ.
            return false;
        } catch (IOException | URISyntaxException | RuntimeException e) {
            // Taken for this library's, so that a listing problem there still fails build().
            return true;
        }
    }

    /** The jar a class file's URL names, or the class file itself. */
    private static Path location(URL classFile) throws IOException, URISyntaxException {
        URL file = "jar".equals(classFile.getProtocol())
                ? ((JarURLConnection) classFile.openConnection()).getJarFileURL()
                : classFile;
        try {
            return Path.of(file.toURI());
        } catch (URISyntaxException e) {
            // A URL made from a path without encoding it, as File.toURL() makes one: its path is the file's own.
            return Path.of(new URI(file.getProtocol(), file.getAuthority(), file.getPath(), null, null));
        }
    }
}
