package io.github.brantunger.unruly.core;

import java.lang.reflect.Type;
import java.util.Map;

/**
 * An engine's declared facts as its languages are told them, by name: each type checked, a primitive class as its
 * wrapper and a {@code FactType} as the type it stands for, once, when the engine is built. An
 * {@link EngineCompileContext} keeps one as it is, without checking its types again, so a {@code load()} or a
 * {@code validate()} never asks a type an application wrote for its raw type: what such a type answered when the
 * engine was built is what holds, for the languages as for the engine's check of a run's values. Only the engine
 * makes one, so a map from anywhere else is still checked. It's unmodifiable, and has no static initializer.
 */
final class DeclaredFacts extends NullRejectingMap<Type> {

    /**
     * Keeps the declarations an engine has checked.
     *
     * @param types The type to tell languages of each fact, by name, already checked; copied
     */
    DeclaredFacts(Map<String, Type> types) {
        super(types);
    }
}
