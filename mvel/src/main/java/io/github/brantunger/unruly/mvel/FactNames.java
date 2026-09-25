package io.github.brantunger.unruly.mvel;

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
 *     classes the engine imports, on their own or in a package</li>
 * </ul>
 */
final class FactNames {

    /**
     * How many names that aren't classes are remembered. The cache is cleared when it is full, because fact names
     * can be unbounded (IDs, JSON keys) and must not grow it forever.
     */
    static final int MAX_CACHED_MISSES = 4096;

    // The longest part of a fact name a message shows, as in the engine's messages.
    private static final int MAX_NAME_LENGTH = 200;

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
     * @param ruleImports The packages and classes the engine imports, and their class loader
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
        if (!isIdentifier(name)) {
            throw new IllegalArgumentException("'" + quote(name) + "' is not a valid fact name: "
                    + "rules can only refer to a fact named with a Java identifier");
        }
        if (RESERVED.contains(name) || importedClassNames.contains(name) || isPackageClass(name)) {
            throw new IllegalArgumentException("'" + quote(name) + "' cannot be used as a fact name: "
                    + "MVEL reads it as a keyword or class name, so rules would never see the fact");
        }
    }

    /**
     * Escapes and shortens a fact name for a message, as the engine's {@code core.Failures.quote} does, which the
     * {@code mvel} package may not use. {@code FactNamesQuoteTest} and {@code QuoteCopiesTest} run the same cases on
     * both, so the two can't drift apart. Fact names can
     * come from request data, and the engine logs these messages, so a line break in a name mustn't start a log line.
     * Only a name that isn't an identifier can contain one. Format characters, such as bidi controls and zero-width
     * characters, which an identifier can contain too, are escaped as well, and so is a lone surrogate, which a
     * logger's encoder would write as {@code ?}. A name is never shortened inside a surrogate pair. MVEL's messages
     * about its options show the option's name and value this way too.
     */
    static String quote(String name) {
        int shown = Math.min(name.length(), MAX_NAME_LENGTH);
        if (shown < name.length() && Character.isHighSurrogate(name.charAt(shown - 1))) {
            shown--;
        }
        StringBuilder quoted = new StringBuilder(shown);
        int c;
        for (int i = 0; i < shown; i += Character.charCount(c)) {
            c = name.codePointAt(i);
            switch (c) {
                case '\n' -> quoted.append("\\n");
                case '\r' -> quoted.append("\\r");
                case '\t' -> quoted.append("\\t");
                default -> {
                    int type = Character.getType(c);
                    // The loop reads code points, so only a lone surrogate has the type SURROGATE.
                    if (Character.isISOControl(c) || type == Character.LINE_SEPARATOR
                            || type == Character.PARAGRAPH_SEPARATOR || type == Character.FORMAT
                            || type == Character.SURROGATE) {
                        for (char unit : Character.toChars(c)) {
                            appendEscape(quoted, unit);
                        }
                    } else {
                        quoted.appendCodePoint(c);
                    }
                }
            }
        }
        if (shown < name.length()) {
            quoted.append("... (").append(name.length() - shown).append(" more characters)");
        }
        return quoted.toString();
    }

    /**
     * Appends one UTF-16 unit as a backslash, {@code u} and four lowercase hex digits, as the engine's
     * {@code core.Failures.appendEscape} does, without parsing a format for every character.
     *
     * @param quoted What to append to
     * @param unit   The unit
     */
    static void appendEscape(StringBuilder quoted, char unit) {
        quoted.append("\\u");
        for (int shift = 12; shift >= 0; shift -= 4) {
            quoted.append(Character.forDigit((unit >> shift) & 0xF, 16));
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
     * {@code date.class} finds {@code Date.class}. A class that exists but can't be loaded isn't a class here either:
     * MVEL's own lookup of a name in an imported package ignores every error, so it reads the name as the fact. That
     * is why every error the load throws is read as "not a class", bar a {@link VirtualMachineError}, where the JVM
     * itself is failing and a fact name isn't what to report.
     *
     * <p>
     * In a native image the class file isn't looked up at all. An image serves no class file as a resource unless
     * its resource configuration names it, so the lookup found nothing, this answered "not a class" for every name,
     * and MVEL — which only loads, and never looks a resource up — read the imported class and hid the fact from
     * every rule. The lock the lookup exists to avoid isn't there either: an image's {@code ClassLoader.loadClass}
     * neither synchronizes nor keeps a lock object. So an image loads the class straight away, and the check agrees
     * with MVEL again.
     * </p>
     */
    private boolean isClass(String pkg, String name) {
        if (!inNativeImage() && classLoader.getResource(pkg.replace('.', '/') + '/' + name + ".class") == null) {
            return false;
        }
        try {
            Class.forName(pkg + '.' + name, false, classLoader);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        } catch (VirtualMachineError e) {
            // Not core's Failures.isFatal, which the mvel package may not use and which reads a StackOverflowError
            // as one rule's failure: every error the JVM itself raises propagates out of this lookup today, and a
            // fact-name check is no place to start absorbing one.
            throw e;
        } catch (Error e) {
            // One catch for the rest, so an image's MissingReflectionRegistrationError — an Error, but not a
            // LinkageError — reads as "not a class" rather than failing a run MVEL would have completed.
            return false;
        }
    }

    /**
     * Tells whether this call is running in a native image, as GraalVM's own {@code ImageInfo.inImageRuntimeCode}
     * does, without a dependency on its SDK. The property is {@code "runtime"} only in an image, and
     * {@code "buildtime"} while one is being built, where class loading is an ordinary JVM's and the lock the class
     * file lookup avoids is real, so the value is compared and not merely tested for. It is read on every call and
     * never into a field: a field an image's build filled in would answer {@code "buildtime"} for the image's whole
     * life. Only a name neither cache knows gets this far.
     */
    private static boolean inNativeImage() {
        return "runtime".equals(System.getProperty("org.graalvm.nativeimage.imagecode"));
    }

    // core.ImportResolver keeps a copy of this: the mvel package may not use that one. Fix both together.
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

    /**
     * MVEL's literals ({@code empty}, {@code null}, {@code Math} …), its word operators ({@code in}, {@code with} …)
     * and {@code this}.
     */
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
