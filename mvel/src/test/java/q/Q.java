package q;

/**
 * A class a rule calls with its package, {@code q.Q.f(1)}, in {@code ValidManyCallsTest}. The names are as short as
 * they can be: MVEL asks for the class loader about half the square of how deep such calls are nested, whatever their
 * length, and the limit grows with the expression's length. Not {@code a.B}: other tests look that name up as one
 * that isn't a class.
 */
public final class Q {

    private Q() {
    }

    /**
     * Returns its argument.
     *
     * @param value Any number
     * @return The number
     */
    public static int f(int value) {
        return value;
    }
}
