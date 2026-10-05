package acme.both;

/**
 * A class named like the first part of its own package, so with this package imported, {@code acme} is both a class
 * MVEL resolves and, once a rule uses a class of {@code acme.orders}, a package's first part.
 */
public final class acme {

    private acme() {
    }
}
