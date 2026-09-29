package io.github.brantunger.unruly.core;

import org.jspecify.annotations.Nullable;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * What a bean property is, and how to call its getter or setter from the engine's module: which public methods are
 * getters and setters, the property a getter reads, the setter a property is written with, and a way to call a public
 * method of an application's class. Reading facts and writing the output use the same rules, so a class the engine
 * can read it can also write. <b>Internal:</b> this class may change in any release. It's public only so that
 * {@code api.language.FactProperties} and the default {@code api.OutputWriter}, in other packages, share it.
 */
public final class Accessors {

    private Accessors() {
    }

    /**
     * The property a public method reads, or {@code null} if the method isn't a getter. A getter isn't static, takes
     * no argument, returns something, and isn't declared by {@link Object} or {@link Enum}, so {@code getClass()} and
     * an enum's {@code getDeclaringClass()} are never properties. It's named {@code getX}, or {@code isX} where it
     * returns a {@code boolean} or a {@link Boolean}, and its property is {@code X} decapitalized as
     * {@code java.beans} does it: {@code getName()} reads {@code name}, but {@code getURL()} reads {@code URL}.
     *
     * @param method A public method of the object's class
     * @return The property's name, or {@code null}
     */
    public static @Nullable String property(Method method) {
        // Declared by Object or Enum, so getClass() and getDeclaringClass() are never properties.
        if (method.getDeclaringClass() == Object.class || method.getDeclaringClass() == Enum.class
                || Modifier.isStatic(method.getModifiers()) || method.getParameterCount() != 0
                || method.getReturnType() == void.class) {
            return null;
        }
        String name = method.getName();
        if (name.startsWith("get") && name.length() > 3) {
            return decapitalize(name.substring(3));
        }
        // Only an isX() returning boolean or Boolean is a getter: isNotAProperty() returning a String isn't one. A
        // list's isEmpty() does qualify, so a list read directly has a property named empty; what keeps a list from
        // contributing one to FactProperties.toData is that its convert() turns a collection into a list, and that
        // its isPlatformValue() leaves the platform's classes alone, not this.
        boolean returnsBoolean = method.getReturnType() == boolean.class || method.getReturnType() == Boolean.class;
        if (returnsBoolean && name.startsWith("is") && name.length() > 2) {
            return decapitalize(name.substring(2));
        }
        return null;
    }

    private static String decapitalize(String name) {
        if (name.length() > 1 && Character.isUpperCase(name.charAt(1))) {
            // As java.beans does: URL stays URL, so getURL() is the property URL.
            return name;
        }
        return name.substring(0, 1).toLowerCase(Locale.ROOT) + name.substring(1);
    }

    /**
     * Whether a public method is a setter: not static, with one parameter, and a name that starts with {@code set}.
     *
     * @param method A public method of the object's class
     * @return {@code true} if it's a setter
     */
    public static boolean isSetter(Method method) {
        return method.getParameterCount() == 1 && method.getName().startsWith("set")
                && !Modifier.isStatic(method.getModifiers());
    }

    /**
     * The name of the setter that writes a property: {@code set} and the property with its first letter upper-cased,
     * so both {@code xValue} and {@code XValue} are written with {@code setXValue}. It isn't the inverse of
     * {@link #property}, which reads {@code getXValue()} as {@code XValue}, as {@code java.beans} does.
     *
     * @param property The property's name, not empty
     * @return The setter's name
     */
    public static String setterName(String property) {
        return "set" + Character.toUpperCase(property.charAt(0)) + property.substring(1);
    }

    /**
     * Says that a getter or setter can't be called from here, and how to make it callable: the end of the message of
     * the exception that reports it, from the name of the class that declares it.
     *
     * @param kind   What the method is, {@code accessor} or {@code setter}
     * @param member The method that couldn't be called
     * @return The advice, which starts with the name of the class that declares {@code member}
     */
    public static String unreachable(String kind, Method member) {
        return member.getDeclaringClass().getName() + " can't be reached from here, and no public supertype declares"
                + " it. Declare the " + kind + " on a public type, or on a public interface the type implements; on"
                + " the module path, also export that type's package, or open it to io.github.brantunger.unruly.core"
                + " for a type that isn't public.";
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
