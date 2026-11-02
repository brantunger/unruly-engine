package probe.classinit;

/**
 * A class whose static initializer records that it ran and returns, which the check of a fact's name must not run, in
 * {@code FactNameClassInitializationTest} (#1310). Only that test names it, and only by its name.
 */
public final class QuietInit {

    static {
        InitRecord.ran("QuietInit");
    }

    private QuietInit() {
    }
}
