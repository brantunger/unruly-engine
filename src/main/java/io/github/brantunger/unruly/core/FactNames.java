package io.github.brantunger.unruly.core;

import org.mvel2.ParserConfiguration;
import org.mvel2.compiler.AbstractParser;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Checks that a fact's name is one rules can refer to. MVEL resolves some names before it looks at the facts, so a
 * fact with such a name is silently hidden, and a name that isn't an identifier is parsed as an expression
 * ({@code my-fact} reads as {@code my - fact}). These names are rejected:
 * <ul>
 *     <li>names that aren't Java identifiers</li>
 *     <li>MVEL's reserved words and literals, such as {@code empty}, {@code null}, {@code this}, {@code in} or
 *     {@code with}</li>
 *     <li>names MVEL resolves to a class: its built-in class names such as {@code Math} or {@code String}, and
 *     classes in a package registered with {@code addImport}</li>
 * </ul>
 */
final class FactNames {

    private static final Set<String> RESERVED = reservedWords();

    private final ParserConfiguration imports = new ParserConfiguration();
    // hasImport() caches the classes it finds in a plain HashMap, so calls are serialized and their results kept.
    private final Map<String, Boolean> classNames = new ConcurrentHashMap<>();

    /**
     * Creates a check for the imports a rule list was compiled with.
     *
     * @param ruleImports The packages and classes registered with {@code addImport}
     */
    FactNames(Imports ruleImports) {
        ruleImports.applyTo(imports);
    }

    /**
     * Rejects a fact name rules can't refer to.
     *
     * @param name The fact's name
     * @throws IllegalArgumentException if rules can't refer to a fact with this name
     */
    void check(String name) {
        if (!isIdentifier(name)) {
            throw new IllegalArgumentException("'" + name + "' is not a valid fact name: "
                    + "rules can only refer to a fact named with a Java identifier");
        }
        if (RESERVED.contains(name) || classNames.computeIfAbsent(name, this::isClassName)) {
            throw new IllegalArgumentException("'" + name + "' cannot be used as a fact name: "
                    + "MVEL reads it as a keyword or class name, so rules would never see the fact");
        }
    }

    private boolean isClassName(String name) {
        synchronized (imports) {
            return imports.hasImport(name);
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
