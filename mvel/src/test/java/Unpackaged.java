/**
 * A class with no package that rules use in {@code NoPackageClassFactTest} and {@code ClassNameRootsTest}, which
 * reject a fact named {@code Unpackaged}. No other test uses the name, so no other rule finds this class.
 */
public final class Unpackaged {

    /** The value rules read through the class. */
    public static final int LIMIT = 5;

    /** The value rules read through an instance. */
    public final int v = 5;

    /**
     * Creates an instance, as a rule does with {@code new Unpackaged()}.
     */
    public Unpackaged() {
        // Nothing to set up.
    }

    /**
     * A class nested in {@link Unpackaged}, which rules use as {@code Unpackaged.Inner}.
     */
    public static final class Inner {

        /** The value rules read through the nested class. */
        public static final int LIMIT = 5;

        private Inner() {
        }
    }
}
