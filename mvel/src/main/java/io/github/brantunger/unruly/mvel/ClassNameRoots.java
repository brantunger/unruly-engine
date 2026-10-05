package io.github.brantunger.unruly.mvel;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * The first part of the name of each class a rule list's expressions use, such as {@code java} for
 * {@code java.lang.Integer}, which {@link FactNames} rejects as a fact's name: MVEL would read a fact of that name in
 * the class's place.
 *
 * <p>
 * Only a class the rule list's {@link ExactNameClassLoader} loads while {@link #recordWhile} compiles an expression
 * counts: one MVEL looks up as it compiles, such as one named with its package, or one it finds through an import, such
 * as {@code com.acme.Order} for {@code Order} with the package {@code com.acme} imported, or
 * {@code java.util.Map$Entry} for {@code Map.Entry} with {@code java.util.Map} imported; and one
 * {@link #lookUpDottedNames} finds for a name written with dots in the expression's text, which MVEL may look up only
 * as the expression runs, such as one in the arguments of a call to a function the expression defines. A class MVEL
 * knows by its simple name without looking it up, such as {@code Integer}, or that the engine imports by itself, adds
 * nothing. Nor does any lookup made after the rule list's expressions compiled: the engine's own, such as
 * {@link FactNames}' check of a fact's name in each imported package, and those made while a later compiled copy is
 * made or runs. So a name accepted once is accepted for the rule list's whole life. Each rule list has its own, so a
 * name one list's rules use as a class's package is still a fact's name in another.
 * </p>
 *
 * <p>
 * A lookup made through the rule list's class loader for any other reason while an expression compiles counts too:
 * MVEL initializes a class it finds, and the thread's context class loader is the rule list's meanwhile (see
 * {@link MvelExpression}), so a static initializer that looks classes up through it, such as
 * {@code java.sql.DriverManager}'s, which loads the JDBC drivers, adds their first parts, such as {@code org} or
 * {@code com}. Only the first load that initializes the class does, so a rule list may reject such a name and a later
 * load of the same rules accept it.
 * </p>
 *
 * <p>
 * The engine compiles a rule list's expressions on the thread that loads it, before any run or check of a fact's name
 * uses the rule list. So what is recorded is told by how many compilations are under way, not by the thread, and once
 * each expression has compiled, what was recorded is published as a set that doesn't change, which {@link #contains}
 * reads.
 * </p>
 */
final class ClassNameRoots {

    private final Set<String> found = ConcurrentHashMap.newKeySet();
    // How many compilations are under way: one at a time in the engine, but compiling two at once records both.
    private final AtomicInteger compiling = new AtomicInteger();
    // Copies and publishes together, so the copy published last, whichever compilation ends last, holds every name.
    private final Object publishLock = new Object();
    private volatile Set<String> published = Set.of();

    /**
     * Compiles an expression, recording the first part of the name of each class the rule list's class loader loads
     * meanwhile, then publishes what was recorded, whether the compilation returned or threw.
     *
     * @param compile The compilation
     * @param <T>     What the compilation returns
     * @return What the compilation returned
     */
    <T> T recordWhile(Supplier<T> compile) {
        compiling.incrementAndGet();
        try {
            return compile.get();
        } finally {
            compiling.decrementAndGet();
            synchronized (publishLock) {
                published = Set.copyOf(found);
            }
        }
    }

    /**
     * Looks up, with the rule list's class loader, each name written with dots in an expression's text, as MVEL looks
     * one up when it reads it as a class: the whole name, then the name without its last part, and so on down to two
     * parts, stopping at the first that is a class. MVEL looks some up only as the expression first runs, such as one
     * in the arguments of a call to a function the expression defines, so called while {@link #recordWhile} compiles
     * the expression, this records their first parts too. A name that starts with a fact or a variable, such as
     * {@code applicant.score}, records nothing unless one of those names is a class. Nor is a name looked up whose
     * first part MVEL reads as an imported class, such as {@code Outer.FIELD} with {@code Outer} imported, or
     * {@code Math.PI}: MVEL never reads that part as a package. A first part that names a class in more than one
     * imported package, such as {@code Date} with {@code java.util} and {@code java.sql} imported, fails the
     * expression's compile with MVEL's "ambiguous class name", wherever the name is.
     *
     * <p>
     * A name is identifiers joined by dots, with nothing between them; one in a string literal or a comment, or that
     * follows a dot, with only whitespace or comments between, such as {@code b.c} in {@code f().b.c}, a property of
     * what comes before it, isn't looked up. Each name is looked up at most once, and none whose first part is
     * recorded already, and of each name only the parts the class loader would look up: at most
     * {@value ExactNameClassLoader#MAX_NAME_PARTS} parts and {@value ExactNameClassLoader#MAX_NAME_LENGTH} characters.
     * So the scan reads the text once, and looks up at most that many names for each name in it. A name that isn't a
     * class, or one whose class can't be loaded, records nothing.
     * </p>
     *
     * @param text     The expression's text
     * @param loader   The rule list's class loader
     * @param imported Tells whether MVEL reads a name as an imported class
     */
    void lookUpDottedNames(String text, ClassLoader loader, Predicate<String> imported) {
        Set<String> seen = new HashSet<>();
        int index = 0;
        // Where each comment starts, by where it ends: MVEL reads a comment as whitespace, and a dot in one is none
        // a name can follow.
        Map<Integer, Integer> comments = new HashMap<>();
        while (index < text.length()) {
            char ch = text.charAt(index);
            switch (ch) {
                case '\'', '"' -> index = ConditionAssignments.endOfLiteral(text, index);
                case '/' -> {
                    int end = ConditionAssignments.endOfSlash(text, index);
                    if (end > index + 1) {
                        comments.put(end, index);
                    }
                    index = end;
                }
                default -> {
                    if (Character.isJavaIdentifierStart(ch)) {
                        int end = endOfDottedName(text, index);
                        if (!followsDot(text, index, comments) && seen.add(text.substring(index, end))) {
                            lookUp(text.substring(index, end), loader, imported);
                        }
                        index = end;
                    } else {
                        index++;
                    }
                }
            }
        }
    }

    /**
     * Looks a dotted name up from its longest part the class loader would look up down to its first two parts,
     * stopping at the first that is a class.
     *
     * @param name     The name, such as {@code java.lang.Integer.MAX_VALUE}
     * @param loader   The rule list's class loader
     * @param imported Tells whether MVEL reads a name as an imported class
     */
    private void lookUp(String name, ClassLoader loader, Predicate<String> imported) {
        int firstDot = name.indexOf('.');
        if (firstDot < 0) {
            return;
        }
        String first = name.substring(0, firstDot);
        if (found.contains(first) || imported.test(first)) {
            return;
        }
        // Where each name to look up ends, the first two parts first, then one more part each, while the class
        // loader would look the name up.
        int[] ends = new int[ExactNameClassLoader.MAX_NAME_PARTS - 1];
        int count = 0;
        int from = firstDot;
        while (count < ends.length) {
            int next = name.indexOf('.', from + 1);
            int end = next < 0 ? name.length() : next;
            if (end > ExactNameClassLoader.MAX_NAME_LENGTH) {
                break;
            }
            ends[count] = end;
            count++;
            if (next < 0) {
                break;
            }
            from = next;
        }
        for (int i = count - 1; i >= 0; i--) {
            if (isClass(loader, name.substring(0, ends[i]))) {
                return;
            }
        }
    }

    private static boolean isClass(ClassLoader loader, String name) {
        try {
            loader.loadClass(name);
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            // Not a class, or one that can't be loaded, which MVEL can't read either.
            return false;
        }
    }

    // Where a dotted name ends: identifiers joined by dots, with nothing between them.
    private static int endOfDottedName(String text, int start) {
        int index = start + 1;
        while (index < text.length()) {
            char ch = text.charAt(index);
            if (Character.isJavaIdentifierPart(ch)) {
                index++;
            } else if (ch == '.' && index + 1 < text.length()
                    && Character.isJavaIdentifierStart(text.charAt(index + 1))) {
                index += 2;
            } else {
                break;
            }
        }
        return index;
    }

    /**
     * Tells whether a name follows a dot, with any whitespace (see {@link RuleText#isWhitespace(char)}) or comments
     * between, as MVEL reads a comment after a dot as whitespace: it is then a property of what comes before it, not a
     * name of its own. A dot in a comment, such as {@code // the cap.} on the line before, is no dot of the expression.
     * A name after a {@code ?} that follows a dot counts as following the dot too: a {@code ?} after a dot ends MVEL's
     * token, and nothing after it is evaluated, so a name there is never a class MVEL would read.
     *
     * @param text     The text
     * @param start    Where the name starts
     * @param comments Where each comment before the name starts, by where it ends
     * @return {@code true} if the name is a property
     */
    private static boolean followsDot(String text, int start, Map<Integer, Integer> comments) {
        int before = skipBack(text, start - 1, comments);
        if (before >= 0 && text.charAt(before) == '?') {
            before = skipBack(text, before - 1, comments);
        }
        return before >= 0 && text.charAt(before) == '.';
    }

    // Where the last character before that isn't whitespace or in a comment is, from at on back, or -1 if none is.
    private static int skipBack(String text, int at, Map<Integer, Integer> comments) {
        int before = at;
        while (before >= 0) {
            Integer comment = comments.get(before + 1);
            if (comment != null) {
                before = comment - 1;
            } else if (RuleText.isWhitespace(text.charAt(before))) {
                before--;
            } else {
                break;
            }
        }
        return before;
    }

    /**
     * Records the first part of a class's name, if an expression is compiling and the name has a package.
     *
     * @param className The binary name of a class the rule list's class loader loaded
     */
    void loaded(String className) {
        int dot = className.indexOf('.');
        if (compiling.get() > 0 && dot > 0) {
            found.add(className.substring(0, dot));
        }
    }

    /**
     * Tells whether a name is the first part of the name of a class found while the rule list's expressions compiled.
     *
     * @param name The name
     * @return {@code true} if it is
     */
    boolean contains(String name) {
        return published.contains(name);
    }
}
