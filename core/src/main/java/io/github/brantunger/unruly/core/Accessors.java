package io.github.brantunger.unruly.core;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Finds a way to call a public method of an application's class, such as a fact's getter or an output's setter, from
 * the engine's module. Reading facts and writing the output use the same rule, so a class the engine can read it can
 * also write. <b>Internal:</b> this class may change in any release. It's public only so that
 * {@code api.language.FactProperties} and the default {@code api.OutputWriter}, in other packages, share it.
 */
public final class Accessors {

    private Accessors() {
    }

    /**
     * Returns a method that can be called from here. A public method declared on a class that isn't itself public
     * can't be invoked from another package, so the same method is looked up on a public type above <b>the object's
     * class</b>: the method may be declared on a base class that is no more public than the object's own, while the
     * interface that makes it public is implemented by the object's class alone.
     *
     * <p>
     * When no public supertype declares it either, the method itself is made accessible where the module system
     * allows it: {@link Method#trySetAccessible()} succeeds only when the declaring class's package is open to this
     * module, which every package on the class path is. It widens only the class's access, never the member's,
     * because every method passed here is public. Where access is refused, the method is returned anyway, so that
     * calling it reports why. Access is a flag on the {@link Method} object, which callers cache and never hand out.
     * </p>
     *
     * @param type   The object's class, which the method was found on
     * @param method The public method found there
     * @return The same method, or the one a public supertype declares
     */
    public static Method callable(Class<?> type, Method method) {
        if (reachable(method.getDeclaringClass())) {
            return method;
        }
        for (Class<?> supertype : supertypesOf(type)) {
            if (!reachable(supertype)) {
                continue;
            }
            try {
                Method declared = supertype.getMethod(method.getName(), method.getParameterTypes());
                // A reachable type can inherit the method from one that isn't, and invoking it would fail the same
                // way, so what matters is where the method we found is declared. An interface may also declare a
                // static method of the same name, which isn't the object's method at all.
                if (reachable(declared.getDeclaringClass()) && !Modifier.isStatic(declared.getModifiers())) {
                    return declared;
                }
            } catch (NoSuchMethodException e) {
                continue;
            }
        }
        method.trySetAccessible();
        return method;
    }

    /**
     * Whether a method declared on this type can be invoked from here. Being public isn't enough: a public class in
     * a package its module doesn't export isn't reflectively reachable either, which is how {@code TimeZone}'s own
     * {@code sun.util.calendar.ZoneInfo} behaves, and how an application's internal package behaves on the module
     * path. Both are reached through a type that is reachable, such as {@code java.util.TimeZone} itself.
     *
     * <p>
     * A package a module {@code opens} to this one counts as exported to it, so a public class there is reachable
     * too. A class that isn't public never is, whatever its package; {@link #callable} reaches its methods directly
     * only where the package is open to this module.
     * </p>
     *
     * @param type The type declaring the method
     * @return {@code true} if this module can invoke its methods
     */
    private static boolean reachable(Class<?> type) {
        return Modifier.isPublic(type.getModifiers())
                && type.getModule().isExported(type.getPackageName(), Accessors.class.getModule());
    }

    /**
     * Every type above {@code type}, nearest first: its interfaces, the interfaces those extend, its superclasses,
     * and their interfaces. Walking the whole graph is what finds the public interface behind one that isn't.
     *
     * @param type The class to walk up from
     * @return Its supertypes, each once
     */
    private static List<Class<?>> supertypesOf(Class<?> type) {
        List<Class<?>> supertypes = new ArrayList<>();
        Set<Class<?>> seen = new HashSet<>();
        Deque<Class<?>> queue = new ArrayDeque<>();
        queue.add(type);
        while (!queue.isEmpty()) {
            Class<?> next = queue.remove();
            if (!seen.add(next)) {
                continue;
            }
            if (next != type) {
                supertypes.add(next);
            }
            queue.addAll(List.of(next.getInterfaces()));
            if (next.getSuperclass() != null) {
                queue.add(next.getSuperclass());
            }
        }
        return supertypes;
    }
}
