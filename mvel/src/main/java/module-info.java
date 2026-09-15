/**
 * The Unruly rules engine with the MVEL expression language. Requiring this module also gives access to the engine,
 * the module {@code io.github.brantunger.unruly.core}.
 *
 * @provides io.github.brantunger.unruly.api.language.ExpressionLanguage The MVEL language, named {@code mvel}.
 */
module io.github.brantunger.unruly {
    requires transitive io.github.brantunger.unruly.core;
    // mvel2 has no module name, so its name comes from its jar's file name.
    requires mvel2;

    exports io.github.brantunger.unruly.mvel;

    provides io.github.brantunger.unruly.api.language.ExpressionLanguage
            with io.github.brantunger.unruly.mvel.MvelExpressionLanguage;
}
