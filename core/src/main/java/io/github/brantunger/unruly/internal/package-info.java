/**
 * What the engine's {@code api} and {@code core} packages both use: how text is shortened and escaped for a message,
 * how an instant is written, how what was thrown is read, how a class or method lookup's "not found" errors are told,
 * the limits on an import, how a name's blankness is decided, how a value widens, and how a handful of strings are
 * sorted, which only {@code api} uses. <b>Internal:</b> the module doesn't export this package, so nothing in it is
 * part of the API, and it may change in any release; {@code api.language} publishes what expression languages and the
 * test kit may use, in {@link io.github.brantunger.unruly.api.language.MessageText},
 * {@link io.github.brantunger.unruly.api.language.Throwables} and
 * {@link io.github.brantunger.unruly.api.language.ImportLimits}. Nothing here uses {@code api} or {@code core}.
 * {@code core} uses {@code api}, {@code api.language} and this package throughout; {@code api} uses {@code core} in
 * three files and {@code api.language} uses it in five, where the builder creates the engine, the default output
 * writer and {@code api.language.FactProperties} reach the engine's accessors, each sealed context interface permits
 * the engine's implementation, and {@code api.language.Throwables} publishes the engine's search for a fatal error.
 * The dependency test, {@code PackageDependencyTest}, pins the packages each package may use and the files those uses
 * may be in.
 */
package io.github.brantunger.unruly.internal;
