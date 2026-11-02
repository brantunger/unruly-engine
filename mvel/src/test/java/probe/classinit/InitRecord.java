package probe.classinit;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The simple names of the classes in this package whose static initializers have run, kept apart from those classes,
 * so that reading it initializes none of them, in {@code FactNameClassInitializationTest} (#1310).
 */
public final class InitRecord {

    private static final Set<String> RAN = ConcurrentHashMap.newKeySet();

    private InitRecord() {
    }

    /**
     * Records that a class's static initializer ran.
     *
     * @param simpleName The class's simple name
     */
    static void ran(String simpleName) {
        RAN.add(simpleName);
    }

    /**
     * Tells whether a class's static initializer has run.
     *
     * @param simpleName The class's simple name
     * @return {@code true} if it has
     */
    public static boolean hasRun(String simpleName) {
        return RAN.contains(simpleName);
    }
}
