package io.github.brantunger.unruly.core;

import org.mvel2.compiler.AbstractParser;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Checks that a fact's name is one rules can refer to. MVEL resolves some names before it looks at the facts, so a
 * fact with such a name is silently hidden, and a name that isn't an identifier is parsed as an expression
 * ({@code my-fact} reads as {@code my - fact}). These names are rejected:
 * <ul>
 *     <li>names that aren't Java identifiers</li>
 *     <li>MVEL's reserved words and literals, such as {@code empty}, {@code null}, {@code this}, {@code in} or
 *     {@code with}</li>
 *     <li>names MVEL resolves to a class: its built-in class names such as {@code Math} or {@code String}, and
 *     classes registered with {@code addImport}, on their own or in a package</li>
 * </ul>
 */
final class FactNames {

    /**
     * How many names that aren't classes are remembered. The cache is cleared when it is full, because fact names
     * can be unbounded (IDs, JSON keys) and must not grow it forever.
     */
    static final int MAX_CACHED_MISSES = 4096;

    private static final Set<String> RESERVED = reservedWords();

    private final Set<String> importedClassNames;
    private final List<String> packages;
    private final ClassLoader classLoader;
    // Names found to be classes in an imported package. Bounded by the classes in those packages.
    private final Set<String> packageClassNames = ConcurrentHashMap.newKeySet();
    private final Set<String> misses = ConcurrentHashMap.newKeySet();

    /**
     * Creates a check for the imports a rule list was compiled with.
     *
     * @param ruleImports The packages and classes registered with {@code addImport}, and their class loader
     */
    FactNames(Imports ruleImports) {
        importedClassNames = ruleImports.classes().stream()
                .map(Class::getSimpleName)
                .collect(Collectors.toUnmodifiableSet());
        packages = List.copyOf(ruleImports.packages());
        classLoader = ruleImports.classLoader();
    }

    /**
     * Rejects a fact name rules can't refer to.
     *
     * @param name The fact's name
     * @throws IllegalArgumentException if rules can't refer to a fact with this name
     */
    void check(String name) {
        if (name == null) {
            throw new IllegalArgumentException("fact name must not be null");
        }
        if (!isIdentifier(name)) {
            throw new IllegalArgumentException("'" + name + "' is not a valid fact name: "
                    + "rules can only refer to a fact named with a Java identifier");
        }
        if (RESERVED.contains(name) || importedClassNames.contains(name) || isPackageClass(name)) {
            throw new IllegalArgumentException("'" + name + "' cannot be used as a fact name: "
                    + "MVEL reads it as a keyword or class name, so rules would never see the fact");
        }
    }

    private boolean isPackageClass(String name) {
        if (packageClassNames.contains(name)) {
            return true;
        }
        if (misses.contains(name)) {
            return false;
        }
        for (String pkg : packages) {
            if (isClass(pkg, name)) {
                packageClassNames.add(name);
                return true;
            }
        }
        if (misses.size() >= MAX_CACHED_MISSES) {
            misses.clear();
        }
        misses.add(name);
        return false;
    }

    /**
     * Tells whether {@code pkg.name} is a class, as MVEL's own lookup would. MVEL tries to load the class, but a
     * parallel-capable class loader, as the JDK's own loaders are, keeps a lock object for every name it is asked to
     * load, found or not, for as long as the loader lives. So the class file is looked up first, which keeps nothing
     * beyond what the JDK may cache softly, and only a class file that exists is loaded.
     * Loading it also rules out a false match from a class directory on a case-insensitive file system, where
     * {@code date.class} finds {@code Date.class}.
     */
    private boolean isClass(String pkg, String name) {
        if (classLoader.getResource(pkg.replace('.', '/') + '/' + name + ".class") == null) {
            return false;
        }
        try {
            Class.forName(pkg + '.' + name, false, classLoader);
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    static boolean isIdentifier(String name) {
        if (name.isEmpty() || !Character.isJavaIdentifierStart(name.charAt(0))) {
            return false;
        }
        for (int i = 1; i < name.length(); i++) {
            if (!Character.isJavaIdentifierPart(name.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /** MVEL's literals ({@code empty}, {@code null}, {@code Math} …), its word operators ({@code in}, {@code with} …) and {@code this}. */
    private static Set<String> reservedWords() {
        Set<String> words = new HashSet<>(AbstractParser.LITERALS.keySet());
        for (String operator : AbstractParser.OPERATORS.keySet()) {
            if (Character.isJavaIdentifierStart(operator.charAt(0))) {
                words.add(operator);
            }
        }
        words.add("this");
        return Set.copyOf(words);
    }
}
