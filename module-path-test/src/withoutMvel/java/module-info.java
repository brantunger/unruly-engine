module com.example.withoutmvel {
    requires io.github.brantunger.unruly.core;

    // The engine finds the language with ServiceLoader, although its package isn't exported.
    provides io.github.brantunger.unruly.api.language.ExpressionLanguage
            with com.example.withoutmvel.KeyLanguage;
}
