module com.example.withoutmvel {
    requires io.github.brantunger.unruly.core;

    // The engine finds the language with ServiceLoader, although its package isn't exported.
    provides io.github.brantunger.unruly.api.language.ExpressionLanguage
            with com.example.withoutmvel.KeyLanguage;

    // The engine reads the record fact's components and calls the output bean's setters from its own module, which
    // a package this one exports to it, and to nothing else, is enough for: both classes are public.
    exports com.example.withoutmvel.model to io.github.brantunger.unruly.core;
}
