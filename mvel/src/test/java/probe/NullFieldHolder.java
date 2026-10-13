package probe;

/**
 * A class a rule imports with {@code import probe.NullFieldHolder.*;} in {@code InlineNullStaticFieldTest}, whose
 * second public static field is {@code null}, which MVEL can't import (#1198). A top-level class, so MVEL finds it by
 * its dotted name.
 */
public final class NullFieldHolder {

    /**
     * A field MVEL imports before the {@code null} one.
     */
    public static int A = 1;

    /**
     * The field whose value is {@code null}.
     */
    public static Object X = null;

    /**
     * A field after the {@code null} one.
     */
    public static int B = 2;

    private NullFieldHolder() {
    }
}
