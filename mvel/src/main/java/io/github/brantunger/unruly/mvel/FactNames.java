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

    // The longest part of a name a message shows, as MessageText.quote shows it: quoteWithin shows no more of a name.
    private static final int MAX_NAME_LENGTH = 200;

    /**
     * The longest text {@code MessageText.truncate} keeps, which is also the longest message about an expression the
     * engine reports without shortening it again, and so the longest message about an expression MVEL rejected, and
     * about a fact name or an option it rejected.
     */
    static final int MAX_DESCRIPTION_LENGTH = 1_000;

    private static final Set<String> RESERVED = reservedWords();

    private final Set<String> importedClassNames;
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
     * @param ruleImports The packages and classes the engine imports, and their class loader
     */
    FactNames(Imports ruleImports) {
        this(ruleImports, new SplittableRandom());
    }

    /**
     * Creates a check for the imports a rule list was compiled with, which evicts the names it picks with
     * {@code evictions}, so a test can seed it.
     *
     * @param ruleImports The packages and classes the engine imports, and their class loader
     * @param evictions   Picks the name to evict when the cache is full; used only under a lock
     */
    FactNames(Imports ruleImports, SplittableRandom evictions) {
        importedClassNames = ruleImports.classes().stream()
                .map(Class::getSimpleName)
                .collect(Collectors.toUnmodifiableSet());
        packages = List.copyOf(ruleImports.packages());
        classLoader = ruleImports.classLoader();
        slots = new String[packages.isEmpty() ? 0 : MAX_CACHED_MISSES];
        this.evictions = evictions;
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
        if (RESERVED.contains(name) || importedClassNames.contains(name) || isPackageClass(name)) {
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
     * Tells how many of a name's characters {@link MessageText#quote} shows: at most {@value #MAX_NAME_LENGTH}, and
     * never half a surrogate pair.
     *
     * @param name The name
     * @return How many of its first characters are shown
     */
    private static int shownOf(String name) {
        int shown = Math.min(name.length(), MAX_NAME_LENGTH);
        if (shown < name.length() && Character.isHighSurrogate(name.charAt(shown - 1))) {
            shown--;
        }
        return shown;
    }

    /**
     * Escapes text as {@link MessageText#escape} does, shortened first, if its escaped form is longer than
     * {@code room} characters, to as many of its first characters as fit in {@code room} with
     * {@code ... (N more characters)} after them, as {@link MessageText#truncate} writes it. N counts the text's own
     * characters left out, not escaped ones, and the text is cut between code points, so never inside a surrogate pair
     * or an escape. A message about an expression MVEL rejected is its description and a fixed part, such as
     * {@code failed to compile at line 1, column 8: }, so given the room the fixed part leaves in
     * {@value #MAX_DESCRIPTION_LENGTH} characters, the message is shortened once, here, and the engine, which shortens
     * a message longer than that, reports it whole. A room too small for the count alone gets the count alone.
     *
     * @param text The text, not escaped
     * @param room The most characters the escaped text may take, the count included
     * @return The text, shortened if it didn't fit, and escaped
     */
    static String escapeWithin(String text, int room) {
        return escapeWithin(text, text.length(), room);
    }

    /**
     * Escapes text within a room as {@link #escapeWithin(String, int)} does, showing at most its first {@code most}
     * characters.
     *
     * @param text The text, not escaped
     * @param most The most of its characters to show, which ends between code points
     * @param room The most characters the escaped text may take, the count included
     * @return The text, shortened if it didn't fit or is longer than {@code most}, and escaped
     */
    private static String escapeWithin(String text, int most, int room) {
        // Each code point escapes to at least its own characters, and each one kept can shorten the count by at most
        // one digit, so the two together only grow as more is kept, and once they don't fit, nothing more will.
        int kept = 0;
        int shown = 0;
        int end = 0;
        while (end < most && shown <= room) {
            int next = end + Character.charCount(text.codePointAt(end));
            shown += MessageText.escape(text.substring(end, next)).length();
            if (shown + leftOut(text.length() - next).length() <= room) {
                kept = next;
            }
            end = next;
        }
        return end == text.length() && shown <= room ? MessageText.escape(text)
                : MessageText.escape(text.substring(0, kept)) + leftOut(text.length() - kept);
    }

    /**
     * Quotes a name as {@link MessageText#quote} does, or, if that is longer than {@code room} characters, escapes it
     * within the room as {@link #escapeWithin} does, for a name that is part of a message about an expression MVEL
     * rejected, such as a class or an import, or of a message rejecting a fact name or an option, so the name can't
     * make the message too long. Either way it shows no more of the name than {@link MessageText#quote} does. An empty
     * name is quoted as empty, whatever the room, as nothing of it is left out.
     *
     * @param name The name
     * @param room The most characters the quoted name may take, the count of what was left out included
     * @return The name, quoted
     */
    static String quoteWithin(String name, int room) {
        String quoted = MessageText.quote(name);
        return quoted.length() <= room || name.isEmpty() ? quoted : escapeWithin(name, shownOf(name), room);
    }

    /**
     * Writes a message that quotes a name within {@value #MAX_DESCRIPTION_LENGTH} characters, as
     * {@link #quotedWithin(String, String, String, int)} does: a message MVEL throws when it rejects a fact name or an
     * option, which the engine shortens and escapes when it reports it. A message that fits is reported whole, so a
     * long name of characters that escape isn't cut inside an escape, and the count of what was left out counts the
     * name's own characters. The message is longer than {@value #MAX_DESCRIPTION_LENGTH} characters, and the engine
     * shortens it when it reports it, when the text around the name, such as a declared type's long class name,
     * leaves less room than the count takes for a name that doesn't fit whole, or is itself longer than that for an
     * empty name.
     *
     * @param before What the message says before the name, up to its opening quote
     * @param name   The name, not escaped
     * @param after  What the message says after the name, from its closing quote on
     * @return The message
     */
    static String quotedWithin(String before, String name, String after) {
        return quotedWithin(before, name, after, MAX_DESCRIPTION_LENGTH);
    }

    /**
     * Writes a message that quotes a name: {@code before}, the name quoted within what the rest of the message leaves
     * of {@code room} (see {@link #quoteWithin}), and {@code after}. When the name doesn't fit whole, and
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
        return before + quoteWithin(name, room - before.length() - after.length()) + after;
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
