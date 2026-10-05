package acme.orders;

/**
 * A class in a package of its own, whose first part no other package of the tests has, for rules that import the
 * package.
 */
public final class Order {

    /** A constant a rule reads through the class. */
    public static final int LIMIT = 100;

    private Order() {
    }
}
