package io.github.brantunger.unruly.mvel;

import io.github.brantunger.unruly.api.language.MessageText;
import org.mvel2.compiler.AbstractParser;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;
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
 *     <li>names an expression's own inline imports bring in, which the same expression names: a class it imports with
 *     {@code import acme.Bar;}, a class MVEL found in a package it imports with {@code import acme.*;}, and a static
 *     method it imports with {@code import_static}, as MVEL reads that expression's name as the import (see
 *     {@link #inlineImported})</li>
 *     <li>the first part of the name of a class the rule list's expressions use, such as {@code java} for
 *     {@code java.lang.Integer.MAX_VALUE}, as MVEL would read the fact in the class's place (see
 *     {@link ClassNameRoots})</li>
 *     <li>the name of a class with no package the rule list's expressions use, or of the class one they use is nested
 *     in, as MVEL would read the fact in the class's place (see {@link ClassNameRoots})</li>
 * </ul>
 */
final class FactNames {

    /**
     * How many names that aren't classes are remembered, because fact names can be unbounded (IDs, JSON keys) and
     * must not grow the cache forever. It only saves looking a name up in the imported packages, so with none imported
     * nothing is remembered. The characters of the names remembered are bounded too, by
     * {@value #MAX_CACHED_MISS_CHARS} in all, so the memory the cache holds is bounded as well as its count. When a new
     * name doesn't fit, names picked at random are evicted, one at a time, until it does. The cache isn't cleared: a
     * cycle of names just longer than the cache would clear it before any name came round again, and find none, where
     * random eviction still finds most of them. Eviction doesn't weigh a name's length, so a long name can evict
     * many short ones to make room: one of {@value #MAX_CACHED_MISS_LENGTH} characters, into a cache whose characters
     * are all taken by names of 255, evicts 256 of them. A longer name isn't remembered, so no one name takes more than
     * a sixteenth of what the cache may hold.
     */
    static final int MAX_CACHED_MISSES = 4096;

    /**
     * The most characters (UTF-16 units), all names together, remembered as not being a class: as many as
     * {@value #MAX_CACHED_MISSES} names of 255 characters take.
     */
    static final int MAX_CACHED_MISS_CHARS = MAX_CACHED_MISSES * 255;

    /**
     * The longest name, in characters (UTF-16 units), remembered as not being a class: a sixteenth of
     * {@link #MAX_CACHED_MISS_CHARS}, so at least sixteen names fill the cache.
     */
    static final int MAX_CACHED_MISS_LENGTH = MAX_CACHED_MISS_CHARS / 16;

    // The least of a class name a message that also quotes a fact's name keeps room for: the fact's name gets the
    // room first, but not all of it, so the class name is never the count alone.
    private static final int MIN_CLASS_NAME_SHOWN = 100;

    private static final Set<String> RESERVED = reservedWords();

    private final Set<String> importedClassNames;
    // The names an expression's own inline imports brought in and the same expression names, added as each compiles,
    // all before the rule list loads and any run checks a name (see inlineImported). Concurrent, as packageClassNames
    // is, though only the thread that loads the rule list adds to it.
    private final Set<String> inlineImportedNames = ConcurrentHashMap.newKeySet();
    private final ClassNameRoots classNameRoots;
    private final List<String> packages;
    private final ClassLoader classLoader;
    // Names found to be classes in an imported package. Bounded by the classes in those packages.
    private final Set<String> packageClassNames = ConcurrentHashMap.newKeySet();
    private final Set<String> misses = ConcurrentHashMap.newKeySet();
    // The names in misses again, in the first slotCount slots, so one can be picked at random to evict, and the
    // characters of those names, all together. All of them, and the evictions' random numbers, change together
    // under this lock, so the total is what the cache holds when names are cached on several threads at once. With
    // no package imported nothing is cached, so there are no slots.
    private final Object missesLock = new Object();
    private final String[] slots;
    private final SplittableRandom evictions;
    private int slotCount;
    private int missChars;

    /**
     * Creates a check for the imports a rule list was compiled with.
     *
     * @param ruleImports The packages and classes the engine imports, their class loader, and the first parts of the
     *                    names of the classes found while the rule list compiled, and the names of those with no
     *                    package
     */
    FactNames(Imports ruleImports) {
        this(ruleImports, new SplittableRandom());
    }

    /**
     * Creates a check for the imports a rule list was compiled with, which evicts the names it picks with
     * {@code evictions}, so a test can seed it.
     *
     * @param ruleImports The packages and classes the engine imports, their class loader, and the first parts of the
     *                    names of the classes found while the rule list compiled, and the names of those with no
     *                    package
     * @param evictions   Picks the name to evict when the cache is full; used only under a lock
     */
    FactNames(Imports ruleImports, SplittableRandom evictions) {
        importedClassNames = ruleImports.classes().stream()
                .map(Class::getSimpleName)
                .collect(Collectors.toUnmodifiableSet());
        classNameRoots = ruleImports.classNameRoots();
        packages = List.copyOf(ruleImports.packages());
        classLoader = ruleImports.classLoader();
        slots = new String[packages.isEmpty() ? 0 : MAX_CACHED_MISSES];
        this.evictions = evictions;
    }

    /**
     * Adds names one expression's own inline imports brought in, which the same expression names, or every name it
     * imports once its scan gives up (see {@link MvelExpressionCompiler#namesAdded}), so a fact by any of them is
     * rejected: MVEL reads the expression's name as the import, never as the fact. Only the expression that
     * imports a name looks it up, but each expression's names are checked for the rule list's runs, as all its other
     * names are.
     *
     * @param names The names
     */
    void inlineImported(Set<String> names) {
        inlineImportedNames.addAll(names);
    }

    /**
     * Rejects a fact name rules can't refer to.
     *
     * @param name The fact's name
     * @throws IllegalArgumentException if rules can't refer to a fact with this name
     */
    void check(String name) {
        if (!isIdentifier(name)) {
            throw rejected(name, "' is not a valid fact name: rules can only refer to a fact named with a Java "
                    + "identifier");
        }
        if (classNameRoots.contains(name)) {
            // The other way round: MVEL would read the fact where the rules mean the class. Checked first, so a name
            // that is also a class MVEL resolves gets this reason. The name is quoted twice, each within the room the
            // other leaves.
            throw new IllegalArgumentException(quotedWithClass("'", name, "' cannot be used as a fact name: the rules "
                    + "use a class whose package starts with '", name, "', and MVEL would read the fact in the "
                    + "class's place"));
        }
        if (classNameRoots.containsWithoutPackage(name)) {
            // Likewise for a class with no package, whose whole name the fact has. Checked after the first parts, so
            // a name that is both gets the first part's reason.
            throw new IllegalArgumentException(quotedWithClass("'", name, "' cannot be used as a fact name: the rules "
                    + "use the class '", name, "', and MVEL would read the fact in the class's place"));
        }
        if (RESERVED.contains(name) || importedClassNames.contains(name) || inlineImportedNames.contains(name)
                || isPackageClass(name)) {
            throw rejected(name, "' cannot be used as a fact name: MVEL reads it as a keyword or class name, so rules "
                    + "would never see the fact");
        }
    }

    /**
     * Makes the exception that rejects a fact name: the name quoted within the message (see
     * {@link #quotedWithin(String, String, String)}), then {@code after}.
     *
     * @param name  The rejected name
     * @param after What the message says after the name, from its closing quote on
     * @return The exception
     */
    private static IllegalArgumentException rejected(String name, String after) {
        return new IllegalArgumentException(quotedWithin("'", name, after));
    }

    /**
     * Writes a message that quotes a name within {@value MessageText#MAX_TEXT_LENGTH} characters, as
     * {@link #quotedWithin(String, String, String, int)} does: a message MVEL throws when it rejects a fact name or an
     * option, which the engine shortens and escapes when it reports it. A message that fits is reported whole, so a
     * long name of characters that escape isn't cut inside an escape, and the count of what was left out counts the
     * name's own characters. The message is longer than {@value MessageText#MAX_TEXT_LENGTH} characters, and the engine
     * shortens it when it reports it, when the text around the name leaves less room than the count takes for a name
     * that doesn't fit whole, or is itself longer than that for an empty name. A message that quotes a class's name
     * too quotes both with {@link #quotedWithClass}, so the class's name is fitted as well.
     *
     * @param before What the message says before the name, up to its opening quote
     * @param name   The name, not escaped
     * @param after  What the message says after the name, from its closing quote on
     * @return The message
     */
    static String quotedWithin(String before, String name, String after) {
        return quotedWithin(before, name, after, MessageText.MAX_TEXT_LENGTH);
    }

    /**
     * Writes a message that quotes a name: {@code before}, the name quoted within what the rest of the message leaves
     * of {@code room} (see {@link MessageText#quoteWithin}), and {@code after}. When the name doesn't fit whole, and
     * {@code before} and {@code after} leave less room than the count of what was left out takes (22 characters
     * and the count's digits), the name is shown as that count alone and the message is longer than
     * {@code room}: {@code quotedWithin("<", "a".repeat(50), ">", 12)} gives 26 characters. Nothing is thrown.
     *
     * @param before What the message says before the name, up to its opening quote
     * @param name   The name, not escaped
     * @param after  What the message says after the name, from its closing quote on
     * @param room   The most characters the message may take
     * @return The message
     */
    static String quotedWithin(String before, String name, String after, int room) {
        return before + MessageText.quoteWithin(name, room - before.length() - after.length()) + after;
    }

    /**
     * Writes a message that quotes a fact's name and a class's name within {@value MessageText#MAX_TEXT_LENGTH}
     * characters, as {@link #quotedWithin(String, String, String)} quotes one name: {@code before}, the fact's name,
     * {@code between}, the class's name and {@code after}, each name quoted within the room the rest leaves (see
     * {@link MessageText#quoteWithin}). A class name can hold any character a class file allows, and be long, so it
     * is escaped and shortened like any name. The fact's name, which is what the call that declared the fact can
     * change, gets the room first, but the class's name keeps room for at least its first
     * {@value #MIN_CLASS_NAME_SHOWN} characters, or up to 5 fewer, where raw text that reads as an escape, or a
     * surrogate pair, would be cut (see {@link MessageText#escapeWithin(String, int, int)}), and gets what the fact's
     * name leaves.
     *
     * @param before    What the message says before the fact's name
     * @param name      The fact's name, not escaped
     * @param between   What the message says between the two names
     * @param className The class's name, not escaped
     * @param after     What the message says after the class's name
     * @return The message
     */
    static String quotedWithClass(String before, String name, String between, String className, String after) {
        int room = MessageText.MAX_TEXT_LENGTH - before.length() - between.length() - after.length();
        String least = MessageText.escapeWithin(className, MIN_CLASS_NAME_SHOWN, Integer.MAX_VALUE);
        String quotedName = MessageText.quoteWithin(name, room - least.length());
        return before + quotedName + between + MessageText.quoteWithin(className, room - quotedName.length()) + after;
    }

    /**
     * Writes a message that lists names within {@value MessageText#MAX_TEXT_LENGTH} characters: {@code before}, then
     * the names, each quoted as {@link MessageText#quote} quotes it, between single quotes and separated by
     * {@code ", "}, as the engine lists names, such as {@code 'lodash', 'os.path'}. Names are listed whole, in order,
     * until the next one doesn't fit; then {@code , and N more} for the rest, where N counts the names left out. The
     * first name is always listed, quoted within the room the rest leaves (see {@link MessageText#quoteWithin}) if it
     * doesn't fit whole.
     *
     * @param before What the message says before the first name's opening quote
     * @param names  The names, not escaped; at least one
     * @return The message
     */
    static String quotedAllWithin(String before, List<String> names) {
        int room = MessageText.MAX_TEXT_LENGTH - before.length();
        StringBuilder list = new StringBuilder();
        int listed = 0;
        while (listed < names.size()) {
            String quoted = (listed == 0 ? "'" : ", '") + MessageText.quote(names.get(listed)) + "'";
            if (list.length() + quoted.length() + andMore(names.size() - listed - 1).length() > room) {
                break;
            }
            list.append(quoted);
            listed++;
        }
        if (listed == 0) {
            String rest = andMore(names.size() - 1);
            list.append('\'').append(MessageText.quoteWithin(names.get(0), room - "''".length() - rest.length()))
                    .append('\'');
            listed = 1;
        }
        return before + list + andMore(names.size() - listed);
    }

    /**
     * Says how many names a list left out.
     *
     * @param count How many names were left out
     * @return {@code , and N more}, or an empty string if none were
     */
    private static String andMore(int count) {
        return count == 0 ? "" : ", and " + count + " more";
    }

    /**
     * Says how many characters were left out of a text, as the engine does after the part of it a message shows.
     *
     * @param count How many characters were left out
     * @return {@code ... (N more characters)}
     */
    static String leftOut(int count) {
        return "... (" + count + " more characters)";
    }

    private boolean isPackageClass(String name) {
        if (packages.isEmpty()) {
            return false;
        }
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
        if (name.length() > MAX_CACHED_MISS_LENGTH) {
            return false;
        }
        synchronized (missesLock) {
            if (misses.contains(name)) {
                return false; // another thread cached it since the check above
            }
            // Ends: a name is at most a sixteenth of the characters the cache may hold, so an empty cache fits it.
            while (slotCount >= MAX_CACHED_MISSES || missChars + name.length() > MAX_CACHED_MISS_CHARS) {
                evictOne();
            }
            slots[slotCount] = name;
            slotCount++;
            misses.add(name);
            missChars += name.length();
        }
        return false;
    }

    // Evicts a name picked at random, under missesLock. The last name moves into its slot, and the last slot is
    // emptied, so the slots don't keep an evicted name, and its characters, past the cache's bound.
    @SuppressWarnings("PMD.NullAssignment")
    private void evictOne() {
        int victim = evictions.nextInt(slotCount);
        String evicted = slots[victim];
        slotCount--;
        slots[victim] = slots[slotCount];
        slots[slotCount] = null;
        misses.remove(evicted);
        missChars -= evicted.length();
    }

    /**
     * Tells how many names that aren't classes are remembered, for tests.
     *
     * @return The number of names in the cache
     */
    int cachedMisses() {
        synchronized (missesLock) {
            return slotCount;
        }
    }

    /**
     * Tells how many characters the names that aren't classes remembered take, all together, for tests.
     *
     * @return The characters (UTF-16 units) of the names in the cache
     */
    int cachedMissChars() {
        synchronized (missesLock) {
            return missChars;
        }
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
     * itself is failing and a fact name isn't what to report. A name the rule list's class loader refuses to look up,
     * as too long or with too many parts (see {@link ExactNameClassLoader}), isn't a class either, as MVEL reads it,
     * so its class file isn't looked up.
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
        String className = pkg + '.' + name;
        if (ExactNameClassLoader.refuses(className) || !mayBeClass(classLoader, className)) {
            return false;
        }
        try {
            Class.forName(className, false, classLoader);
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
     * Tells whether a class loader may have a class by a name, without asking it to load one: whether it serves the
     * class file, which keeps no lock object in a parallel-capable loader, as asking it to load the class would (see
     * {@link #isClass}). In a native image, which serves no class file it wasn't configured to, and whose loaders keep
     * no such lock, any name may be a class. A name this answers {@code true} for may still not be a class, such as
     * {@code date} for {@code Date.class} in a class directory on a case-insensitive file system, so only loading it
     * tells.
     *
     * @param loader     The class loader
     * @param binaryName The class's binary name, such as {@code java.util.Map$Entry}
     * @return {@code false} if the class loader has no class by that name
     */
    static boolean mayBeClass(ClassLoader loader, String binaryName) {
        return inNativeImage() || loader.getResource(binaryName.replace('.', '/') + ".class") != null;
    }

    /**
     * Tells whether this call is running in a native image, as GraalVM's own {@code ImageInfo.inImageRuntimeCode}
     * does, without a dependency on its SDK. The property is {@code "runtime"} only in an image, and
     * {@code "buildtime"} while one is being built, where class loading is an ordinary JVM's and the lock the class
     * file lookup avoids is real, so the value is compared and not merely tested for. It is read on every call and
     * never into a field: a field an image's build filled in would answer {@code "buildtime"} for the image's whole
     * life. Only a name no cache knows gets this far: neither of this check's, nor, for a name in a rule, the rule
     * list's own (see {@link Imports}).
     */
    private static boolean inNativeImage() {
        return "runtime".equals(System.getProperty("org.graalvm.nativeimage.imagecode"));
    }

    // Read by char on purpose, unlike core.ImportResolver's check of a package name, which reads code points: MVEL
    // can't read a letter outside the Basic Multilingual Plane, a surrogate pair, in a rule's text, so no rule
    // could refer to a fact named with one, and such a name is rejected like any other a rule can't refer to.
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
        words.addAll(RuleText.wordOperators());
        words.add("this");
        return Set.copyOf(words);
    }
}
