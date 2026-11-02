package probe.classinit;

/**
 * A class whose static initializer records that it ran and then throws, which the check of a fact's name must not
 * run, in {@code FactNameClassInitializationTest} (#1310). Only that test names it, and only by its name.
 */
public final class ThrowingInit {

    static {
        InitRecord.ran("ThrowingInit");
        fail();
    }

    private ThrowingInit() {
    }

    private static void fail() {
        throw new IllegalStateException("ThrowingInit's static initializer ran");
    }
}
