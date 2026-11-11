/**
 * The rules engine implementation. <b>Internal:</b> nothing in this package is part of the API, and it may change in
 * any release.
 *
 * <p>Create an engine with {@link io.github.brantunger.unruly.api.RulesEngineBuilder} and use it through
 * {@link io.github.brantunger.unruly.api.RulesEngine}. The engine class is package-private. The public types are
 * the ones the API packages and the test kit need: {@link io.github.brantunger.unruly.core.Engines}, the builder's
 * entry point; {@link io.github.brantunger.unruly.core.EngineConfiguration} and
 * {@link io.github.brantunger.unruly.core.CopyLimit}, the settings it collects;
 * {@link io.github.brantunger.unruly.core.EngineCompileContext},
 * {@link io.github.brantunger.unruly.core.EngineEvaluationContext},
 * {@link io.github.brantunger.unruly.core.EngineActionContext} and
 * {@link io.github.brantunger.unruly.core.EngineRunContext}, the implementations the sealed context interfaces in
 * {@code api} and {@code api.language} permit; {@link io.github.brantunger.unruly.core.Accessors}, which the API's
 * default output writer and {@link io.github.brantunger.unruly.api.language.FactProperties} use;
 * {@link io.github.brantunger.unruly.core.FatalErrors}, the search for a fatal error that
 * {@link io.github.brantunger.unruly.api.language.Throwables} publishes; and the helpers that {@code api},
 * {@code api.language} or both use: {@link io.github.brantunger.unruly.core.Text}, how text is shortened and escaped
 * for a message; {@link io.github.brantunger.unruly.core.IsoInstant}, how an instant is written;
 * {@link io.github.brantunger.unruly.core.Causes}, how what was thrown is read;
 * {@link io.github.brantunger.unruly.core.Lookups}, how a class or method lookup's "not found" errors are told;
 * {@link io.github.brantunger.unruly.core.ImportSize}, the limits on an import;
 * {@link io.github.brantunger.unruly.core.Whitespace}, how a name's blankness is decided;
 * {@link io.github.brantunger.unruly.core.Widening}, how a value widens;
 * {@link io.github.brantunger.unruly.core.LanguageNames}, how a language's name is checked; and
 * {@link io.github.brantunger.unruly.core.Sorting}, how a handful of strings are sorted, the only one of these
 * helpers that this package doesn't use itself. Those are the only uses
 * of this package in {@code api}, which uses it in nine files, and {@code api.language}, which uses it in seven, each
 * in the file that needs it, while this package uses both throughout; the dependency test,
 * {@code PackageDependencyTest}, pins the packages each package may use and the files those uses may be in. The
 * module exports this package only to the test kit, {@code io.github.brantunger.unruly.test}. Rules are compiled
 * when {@link io.github.brantunger.unruly.api.RulesEngine#load(java.util.List)} loads them, each by the language it
 * names or by the engine's default language, and evaluated against a
 * {@link io.github.brantunger.unruly.api.FactStore} at run time.</p>
 *
 * <p>The engine logs under the fixed logger name {@code io.github.brantunger.unruly.engine}.</p>
 */
package io.github.brantunger.unruly.core;
