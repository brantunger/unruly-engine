/**
 * The Unruly rules engine with the MVEL expression language. Requiring this module also gives access to the engine,
 * the module {@code io.github.brantunger.unruly.core}.
 *
 * @provides io.github.brantunger.unruly.api.language.ExpressionLanguage The MVEL language, named {@code mvel}.
 */
// "requires-automatic": mvel2 has no module name, so it can only be required as an automatic module.
@SuppressWarnings("requires-automatic")
module io.github.brantunger.unruly {
    requires transitive io.github.brantunger.unruly.core;
    // mvel2 has no module name, so its name comes from its jar's file name.
    requires mvel2;
    // MVEL logs through java.util.logging; the engine filters one of its records (see MvelWarningFilter).
    requires java.logging;
    // The engine's logger, which MvelExpressionCompiler logs to as core does.
    requires org.slf4j;

    exports io.github.brantunger.unruly.mvel;
    // To MVEL alone, which reads the value MvelExpressionLanguage.prepare() evaluates on by reflection.
    exports io.github.brantunger.unruly.mvel.warmup to mvel2;

    provides io.github.brantunger.unruly.api.language.ExpressionLanguage
            with io.github.brantunger.unruly.mvel.MvelExpressionLanguage;
}
