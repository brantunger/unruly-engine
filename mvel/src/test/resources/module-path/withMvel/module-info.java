module com.example.withmvel {
    // Nothing else: the engine's module requires SLF4J and MVEL itself.
    requires io.github.brantunger.unruly;

    // Rules read these classes through MVEL, so the package is exported to it. Only to it: the engine makes MVEL's
    // reflective optimizer the default, which reads them from the mvel2 module itself.
    exports com.example.withmvel.model to mvel2;
}
