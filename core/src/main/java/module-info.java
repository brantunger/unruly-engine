/**
 * The Unruly rules engine without an expression language. Add the module {@code io.github.brantunger.unruly} for
 * MVEL, or a module that provides another language.
 *
 * @uses io.github.brantunger.unruly.api.language.ExpressionLanguage The engine finds the languages it wasn't given
 *     with {@link java.util.ServiceLoader}.
 */
// "module": the test kit's module, which core exports a package to, isn't on the module path when core compiles.
@SuppressWarnings("module")
module io.github.brantunger.unruly.core {
    requires transitive org.jspecify;
    requires org.slf4j;

    exports io.github.brantunger.unruly.api;
    exports io.github.brantunger.unruly.api.exception;
    exports io.github.brantunger.unruly.api.language;
    // Only the test kit creates the context records, for unit tests of a language.
    exports io.github.brantunger.unruly.core to io.github.brantunger.unruly.test;

    uses io.github.brantunger.unruly.api.language.ExpressionLanguage;
}
