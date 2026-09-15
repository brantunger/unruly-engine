/**
 * Tools for testing an expression language: a contract test that any language's tests extend, and the contexts the
 * engine passes to a language, for unit tests of its compiler and compiled expressions.
 */
module io.github.brantunger.unruly.test {
    requires transitive io.github.brantunger.unruly.core;
    requires transitive org.junit.jupiter.api;

    exports io.github.brantunger.unruly.test;
    // JUnit runs the contract test's package-private test methods by reflection.
    opens io.github.brantunger.unruly.test to org.junit.platform.commons;
}
