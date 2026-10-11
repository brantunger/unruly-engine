/**
 * MVEL 2 as an expression language for the engine: {@link io.github.brantunger.unruly.mvel.MvelExpressionLanguage},
 * named {@code mvel}. A {@link io.github.brantunger.unruly.api.RulesEngineBuilder} not given a language with
 * {@code language(...)} finds it with {@link java.util.ServiceLoader}, through this artifact's service file on the
 * class path or its module's {@code provides} clause. A rule whose language is {@code null} is written in MVEL when
 * MVEL is the engine's only language, or when
 * {@link io.github.brantunger.unruly.api.RulesEngineBuilder#defaultLanguage(String)} names it.
 *
 * <p>
 * <b>MVEL optimizer:</b> before it compiles anything, the engine makes MVEL's reflective optimizer MVEL's default, for
 * the whole JVM, in place of its JIT optimizer, which generates code that fails some calls once an expression has run
 * about 50 times in 100 ms, such as one of {@code List.of(1, 2)}. MVEL clears a thread's own optimizer after each use,
 * so only a thread that an application gave one with {@code OptimizerFactory.setThreadAccessorOptimizer}, or that ran
 * an MVEL compiled set expression, before the engine loaded keeps it for what MVEL optimizes that way, such as an
 * inline list or {@code new}. Concurrent runs never share a session, and an MVEL session holds the run's own compiled
 * expressions, because MVEL replaces the accessors cached in one without synchronization when a fact's runtime class
 * changes. So they are safe with any optimizer, including MVEL's JIT optimizer, should an application make it the
 * default again.
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
