/**
 * MVEL 2 as an expression language for the engine: {@link io.github.brantunger.unruly.mvel.MvelExpressionLanguage},
 * named {@code mvel}. A {@link io.github.brantunger.unruly.api.RulesEngineBuilder} not given a language with
 * {@code language(...)} finds it with {@link java.util.ServiceLoader}, through this artifact's service file on the
 * class path or its module's {@code provides} clause. A rule whose language is {@code null} is written in MVEL when
 * MVEL is the engine's only language, or when
 * {@link io.github.brantunger.unruly.api.RulesEngineBuilder#defaultLanguage(String)} names it.
 *
 * <p>
 * <b>MVEL optimizer:</b> the engine leaves MVEL's global optimizer setting alone. Concurrent runs never share a
 * session, and an MVEL session holds the run's own compiled expressions, because MVEL replaces the accessors cached
 * in one without synchronization when a fact's runtime class changes. So they are safe with any optimizer, including
 * MVEL's default JIT optimizer.
 * </p>
 *
 * <p>Types in this package are non-null unless annotated {@link org.jspecify.annotations.Nullable}.</p>
 *
 * @see <a href="https://github.com/brantunger/unruly-engine/blob/main/docs/languages/mvel.md">MVEL</a>
 * @see <a href="https://github.com/brantunger/unruly-engine/blob/main/docs/languages/mvel.md#-compiled-copies">Compiled
 *      copies in MVEL</a>
 */
@NullMarked
package io.github.brantunger.unruly.mvel;

import org.jspecify.annotations.NullMarked;
