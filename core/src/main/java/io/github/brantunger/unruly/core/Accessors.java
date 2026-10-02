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
 * getters and setters, the property a getter reads, the setter a property is written with, a way to call a public
 * method of an application's class, and the exception that says reading a fact's property failed. Reading facts and
 * writing the output use the same rules, so a class the engine can read it can also write. <b>Internal:</b> this class
 * may change in any release. It's public only so that {@code api.language.FactProperties} and the default
 * {@code api.OutputWriter}, in other packages, share it.
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
     * Makes the exception that says a fact's accessor threw: a getter, or a map's or a collection's own call. Its
     * message is {@code what} followed by what the accessor threw, after a colon, such as
     * {@code Reading 'price' on a com.example.Item failed: price service down}, so the reason reaches the log and the
     * rule's failure without a stack trace. What the accessor threw is left out when it has no message or reading the
     * message throws. The message isn't shortened or escaped here, so a caller that reads it directly gets it whole:
     * the engine shortens and escapes the whole text when it logs it or fails a rule with it, as it does any message
     * it didn't write.
     *
     * <p>
     * What the accessor threw is left out too when the engine has already logged it and names it in the rule's
     * failure, as {@code a nested run() failed: } and its text, which would otherwise repeat it: a nested run's
     * failure, whether or not a run is in progress on this thread, or a fatal {@link Error} a run nested in the one in
     * progress on this thread logged, in either case with nothing of its own around it, such as
     * {@code new RuntimeException(e)}. A caller that reads the message directly finds it as the cause. While a run, a
     * {@code load()} or a {@code validate()} is in progress on this thread, the exception around such a failure is
     * recorded as one the engine built, so the failure is logged once, by the nested run, and the rule around it
     * reads {@code a nested run() failed: } and that failure (see {@link LoggedFailures}). Nothing else is recorded,
     * so a run's other read failures never push it out of the record. A fatal error the run in progress, or one
     * around it, logged earlier and that is thrown again here, keeps its message, so the rule's failure names this
     * read.
     * </p>
     *
     * @param what  What failed, such as {@code Reading 'price' on a com.example.Item failed}
     * @param cause What the accessor threw
     * @return The exception to throw, caused by {@code cause}
     */
    public static IllegalStateException readFailed(String what, Throwable cause) {
        // Out of stack or memory, nothing more than the error's own message is read, so this can't fail again for that.
        if (cause instanceof VirtualMachineError error) {
            return loggedBelow(error) ? LoggedFailures.builtByEngine(new IllegalStateException(what, cause))
                    : new IllegalStateException(withMessage(what, messageOf(error)), cause);
        }
        Error fatal = Failures.fatalError(cause);
        boolean nested = fatal != null
                ? loggedBelow(fatal) && Failures.newsAbove(cause, fatal) == null
                : Failures.nestedRunFailure(cause) != null;
        if (nested) {
            return LoggedFailures.builtByEngineIfInProgress(new IllegalStateException(what, cause));
        }
        return new IllegalStateException(withMessage(what, Failures.readableMessage(cause)), cause);
    }

    /**
     * Tells whether a run nested in the one in progress on this thread logged a fatal error, rather than that run, one
     * around it, or a nested run that had ended before it started, however deep, which logged it earlier, and the same
     * instance may be thrown again, as the JVM throws the {@link OutOfMemoryError} it keeps ready.
     */
    private static boolean loggedBelow(Error fatal) {
        LoggedFailures.LoggedAt at = LoggedFailures.loggedAt(fatal);
        return at == LoggedFailures.LoggedAt.NESTED_RUN || at == LoggedFailures.LoggedAt.NESTED_LOAD;
    }

    private static String withMessage(String what, @Nullable String message) {
        return message == null ? what : what + ": " + message;
    }

    // Anything getMessage() throws only makes the message unavailable, as Failures.read makes it, without a lambda.
    private static @Nullable String messageOf(VirtualMachineError fatal) {
        try {
            return fatal.getMessage();
        } catch (Throwable thrown) {
            return null;
        }
    }

    /**
     * Finds an exception of a type among what was thrown and its causes, reading the causes as the engine reads them:
     * no further than {@code Failures.MAX_CAUSE_CHAIN_LENGTH} links, and stopping at a {@code getCause()} that throws,
     * whatever it throws, or that leads back to a link already read.
     *
     * @param thrown What was thrown
     * @param type   The type to find
     * @param <T>    That type
     * @return The first exception of that type, from the top, or {@code null} if there is none
     */
    public static <T extends Throwable> @Nullable T inCauses(Throwable thrown, Class<T> type) {
        for (Throwable link : Failures.causeChain(thrown)) {
            if (type.isInstance(link)) {
                return type.cast(link);
            }
        }
        return null;
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
