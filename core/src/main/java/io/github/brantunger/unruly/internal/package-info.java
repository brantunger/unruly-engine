/**
 * What the engine's {@code api} and {@code core} packages share without either depending on the other: how text is
 * shortened and escaped for a message, how an instant is written, how what was thrown is read, how a class or method
 * lookup's "not found" errors are told, and the limits on an import. <b>Internal:</b> the module doesn't export this
 * package, so nothing in it is part of the API, and it may change in any release; {@code api.language} publishes what
 * expression languages and the test kit may use, in {@link io.github.brantunger.unruly.api.language.MessageText},
 * {@link io.github.brantunger.unruly.api.language.Throwables} and
 * {@link io.github.brantunger.unruly.api.language.ImportLimits}. Nothing here depends on {@code api} or
 * {@code core}, so the layering is this package, then {@code api}, then {@code core}.
 */
package io.github.brantunger.unruly.internal;
