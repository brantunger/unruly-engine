/**
 * Tools for testing an expression language.
 *
 * <ul>
 *   <li>{@link io.github.brantunger.unruly.test.ExpressionLanguageContractTest} — A JUnit Jupiter test that checks
 *   what the engine promises for rules in any language. A language's test extends it.</li>
 *   <li>{@link io.github.brantunger.unruly.test.LanguageTestContexts} — Creates the contexts the engine passes to a
 *   language, which are sealed, for unit tests of a compiler or a compiled expression.</li>
 * </ul>
 *
 * <p>Types in this package are non-null unless annotated {@link org.jspecify.annotations.Nullable}.</p>
 *
 * @see <a href=
 * "https://github.com/brantunger/unruly-engine/blob/main/docs/languages/custom.md#-testing-with-the-contract-kit">
 * Testing with the contract kit</a>
 */
@NullMarked
package io.github.brantunger.unruly.test;

import org.jspecify.annotations.NullMarked;
