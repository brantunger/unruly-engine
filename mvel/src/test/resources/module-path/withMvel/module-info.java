module com.example.withmvel {
    // Nothing else: the engine's module requires SLF4J and MVEL itself.
    requires io.github.brantunger.unruly;

    // Rules read these classes through MVEL, so the package is exported without a "to" clause.
    exports com.example.withmvel.model;
}
