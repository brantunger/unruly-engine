/**
 * The Unruly rules engine without an expression language. Add the module {@code io.github.brantunger.unruly} for
 * MVEL, or a module that provides another language.
 *
 * @uses io.github.brantunger.unruly.api.language.ExpressionLanguage The engine finds the languages it wasn't given
 *     with {@link java.util.ServiceLoader}.
 */
module io.github.brantunger.unruly.core {
    requires transitive org.jspecify;
    requires org.slf4j;

    exports io.github.brantunger.unruly.api;
    exports io.github.brantunger.unruly.api.exception;
    exports io.github.brantunger.unruly.api.language;

    uses io.github.brantunger.unruly.api.language.ExpressionLanguage;
}
