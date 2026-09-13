package io.github.brantunger.unruly.core;

import org.mvel2.ParserConfiguration;

import java.util.Set;

/**
 * The imports a rule list is compiled with: whole packages ({@code java.util}) and single classes
 * ({@code java.time.LocalDate}).
 *
 * @param packages Package names, imported with all their classes
 * @param classes  Classes imported one by one
 */
record Imports(Set<String> packages, Set<Class<?>> classes) {

    /** No imports. */
    static final Imports NONE = new Imports(Set.of(), Set.of());

    /**
     * Registers these imports on a configuration.
     *
     * @param configuration The configuration to add the imports to
     */
    void applyTo(ParserConfiguration configuration) {
        packages.forEach(configuration::addPackageImport);
        classes.forEach(configuration::addImport);
    }
}
