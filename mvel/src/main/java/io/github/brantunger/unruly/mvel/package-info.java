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
 * <p>
 * <b>Lambdas:</b> the JVM links a lambda, a method reference or any other {@code invokedynamic} call site the first
 * time it runs, which makes a class and takes more stack than the engine's checks make room for. So this module links
 * none on a path taken only when something fails (reading what a rule's code threw, describing a compile error,
 * rejecting a name, an option or an import), in its loop detection (counting MVEL's calls for the class loader and
 * walking the stack for them) or in a class lookup (every name MVEL looks up goes through the rule list's class
 * loader), nor in a static initializer: the JVM's first such step, maybe deep in a stack, could overflow linking one,
 * and the failure would be reported as the overflow rather than as what happened, or, in a static initializer, leave
 * the class unusable for the life of the JVM. A string concatenation compiled with javac's default links the same way,
 * and on JDK 25 and later one whose first link overflows fails for good, so the module is compiled to build its strings
 * without it (#965). A path every compile or run takes may link one: an overflow while a lambda is set up isn't
 * remembered, and its next use tries again (see core's {@code StackHeadroom}). {@code InvokeDynamicCountTest} pins how
 * many each class links, and the lambda tests it names check the paths above whole or method by method.
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
