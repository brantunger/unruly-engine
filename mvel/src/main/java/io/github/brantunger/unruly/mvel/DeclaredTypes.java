package io.github.brantunger.unruly.mvel;

import io.github.brantunger.unruly.api.language.ActionContext;
import io.github.brantunger.unruly.api.language.CompileContext;

import org.jspecify.annotations.Nullable;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads MVEL's {@code strongTyping} option, and works out the types to compile a rule list against when it's on.
 *
 * <p>
 * MVEL's strong typing rejects a name it has no type for, which is exactly what catches a misspelled property or an
 * unknown fact when the rules load. It's an option, because it also rejects rules that work without it: a
 * {@code foreach} variable without a type, a map entry read as a property ({@code m.a}), and every {@code def}
 * function. When it's on, it only works if the engine knows the whole picture, so all of this must hold, or loading
 * fails saying which doesn't:
 * </p>
 *
 * <ul>
 *     <li>the engine was built with {@code requireDeclaredFacts()}, so a name nobody declared really is a mistake
 *     rather than a fact the run supplies anyway;</li>
 *     <li>at least one fact is declared;</li>
 *     <li>no declared type is dynamic: {@link Object}, a {@link Map}, a {@link Collection}, or an array of one of
 *     them. MVEL's strict mode rejects {@code order.id} on a map and {@code items[0].qty} on a list, whose elements it
 *     has no type for. A collection or a map declared with its type arguments, such as {@code List<String>}, isn't
 *     dynamic where MVEL can use them, which the next paragraph says;</li>
 *     <li>the output type isn't dynamic either, because an action writes to {@code output}. The default output type is
 *     {@link Object}, so an engine that was never told one can't be type-checked.</li>
 * </ul>
 *
 * <p>
 * MVEL is told the type arguments of a fact declared as a {@link Collection} or a {@link Map} with them, such as
 * {@code List<String>} or {@code Map<String, Applicant>}, and then checks what a rule does with an element, as it
 * checks a bean's property: {@code tags[0].lenght()} fails when the rules load. Such a fact isn't dynamic when both of
 * these hold, and is as dynamic as its class when either doesn't:
 * </p>
 *
 * <ul>
 *     <li>the class's own type parameters are {@link Collection}'s or {@link Map}'s, one for one, in order and under
 *     the same names, as those of {@code List}, {@code Set}, {@code ArrayList}, {@code HashMap} and {@code SortedMap}
 *     are, and no class or interface above it has a type parameter of one of those names that stands for anything
 *     else. MVEL reads the first argument as a collection's element and the second as a map's value, whatever the
 *     class makes of them, so it gives the key's type to a value of a {@code Flipped<V, K> extends HashMap<K, V>} read
 *     with {@code []}. And it finds what a method returns by the name of a type parameter, so it has no type for what
 *     {@code get} returns on a {@code Things<T> extends ArrayList<T>}, and the element's type for a method that
 *     returns the {@code String} a {@code Top<E> extends Mid<String, E>} gives {@code Mid}'s own {@code E}. For the
 *     same reason no public method of the class returns a type variable of its own under one of those names, as
 *     {@code <E> E other()} does in a {@code Pile<E> extends ArrayList<E>}. Only a class that isn't the JDK's own is
 *     asked for its methods: the JDK's collection and map types have no such method, and a native image built with
 *     strict metadata throws when a class it has no metadata for is asked;</li>
 *     <li>every argument, a map's key included, is a class MVEL can check: one with no type parameters of its own that
 *     isn't dynamic itself or primitive, or an array of one, such as {@code String[]}. A wildcard counts as its upper
 *     bound, so
 *     {@code List<? extends String>}, which is how Kotlin writes a {@code List<String>}, is a list of strings, and
 *     {@code List<?>} and {@code List<? super String>} are lists of {@link Object}. MVEL takes one class for each
 *     argument and no more, and reads an element of a {@code Map<Integer, List<String>>}'s lists as an
 *     {@code Integer}, so an argument with arguments of its own leaves the fact dynamic.</li>
 * </ul>
 *
 * <p>
 * A bean declared with type arguments, such as {@code Box<String>}, is compiled against its class alone, as before:
 * MVEL checks {@code box.value} as an {@link Object} either way, and telling it the arguments makes it reject rules
 * that work.
 * </p>
 */
final class DeclaredTypes {

    /** The option that turns strong typing on. */
    static final String STRONG_TYPING = "strongTyping";

    // How the message that strong typing can't apply starts, before the reason.
    private static final String CANT_APPLY = "MVEL's " + STRONG_TYPING + " option is on, but strong typing can't apply,"
            + " because ";

    private DeclaredTypes() {
    }

    /**
     * Returns the types to compile a rule list's expressions against: the class of every declared fact's type, as
     * {@link CompileContext#rawClass(Type)} gives it, with its type arguments where MVEL can use them, plus the output
     * object an action writes to. An empty map means compiling as MVEL does by default.
     *
     * @param context What the engine is compiling this rule list with
     * @return The type of each name an expression may refer to, or an empty map when {@code strongTyping} is off
     * @throws IllegalArgumentException if an option isn't one MVEL has, {@code strongTyping} isn't {@code true} or
     *                                  {@code false}, or it's {@code true} but strong typing can't apply
     */
    static Map<String, DeclaredInput> inputsFor(CompileContext context) {
        if (!strongTyping(context.options())) {
            return Map.of();
        }
        Map<String, Type> declared = context.declaredFacts();
        if (!context.allFactsDeclared()) {
            throw cantApply("the engine wasn't built with requireDeclaredFacts()");
        }
        if (declared.isEmpty()) {
            throw cantApply("no facts were declared");
        }
        Map<String, DeclaredInput> inputs = new LinkedHashMap<>();
        for (Map.Entry<String, Type> fact : declared.entrySet()) {
            Type written = fact.getValue();
            Class<?> type = CompileContext.rawClass(written);
            DeclaredInput input = isDynamic(type) ? typed(written, type) : new DeclaredInput(type);
            if (input == null) {
                // A type written with type arguments is named with them: they are what MVEL couldn't use.
                String name = written instanceof ParameterizedType ? written.getTypeName() : type.getName();
                throw new IllegalArgumentException(MessageQuotes.quotedWithClass(CANT_APPLY + "fact '", fact.getKey(),
                        "' is declared as ", name, ", whose members MVEL can't check"));
            }
            inputs.put(fact.getKey(), input);
        }
        if (isDynamic(context.outputType())) {
            throw new IllegalArgumentException(MessageQuotes.quotedWithin(CANT_APPLY + "the output type is ",
                    context.outputType().getName(), ", and an action writes to the output; build the engine with "
                            + "outputType(...)"));
        }
        // Only an action binds the output, but a condition that referred to it would be a mistake either way: it
        // reads facts, and the engine gives it no output object.
        inputs.put(ActionContext.OUTPUT_NAME, new DeclaredInput(context.outputType()));
        return inputs;
    }

    /**
     * Returns a dynamic type with the type arguments MVEL checks its elements with, if it was declared with them and
     * MVEL can use them (see the class comment).
     *
     * @param declared The declared type, as the application wrote it
     * @param type     Its class, which is dynamic
     * @return The class and the class of each type argument, or {@code null} if the type stays dynamic: it has no
     *         type arguments, or not one for each of its class's type parameters, its class's type parameters aren't
     *         {@link Collection}'s or {@link Map}'s, or MVEL can't check one of the arguments
     */
    private static @Nullable DeclaredInput typed(Type declared, Class<?> type) {
        // Of the dynamic types only a collection or a map is written with type arguments: Object has none, and an
        // array is declared by its class.
        if (!(declared instanceof ParameterizedType parameterized)
                || !linesUp(type, Map.class.isAssignableFrom(type) ? Map.class : Collection.class)) {
            return null;
        }
        Type[] written = parameterized.getActualTypeArguments();
        // The engine takes a parameterized type that isn't the JDK's own, which can hold any number of arguments, and
        // MVEL throws for a number that isn't the class's.
        if (written.length != type.getTypeParameters().length) {
            return null;
        }
        Class<?>[] arguments = new Class<?>[written.length];
        for (int i = 0; i < written.length; i++) {
            Class<?> argument = checkable(written[i]);
            if (argument == null) {
                return null;
            }
            arguments[i] = argument;
        }
        return new DeclaredInput(type, arguments);
    }

    /**
     * Returns whether a class's own type parameters are those of the collection type it is one of, one for one, in
     * order and under the same names, and no class or interface above it gives one of those names to anything else.
     * MVEL reads a typed input's arguments by position for an element read with {@code []} or as a property, and by
     * the name of a type parameter for what a method returns, wherever the method is declared, so both must hold for
     * it to give every element, and what every inherited method returns, the type the class does.
     *
     * @param type   The class, which is a {@code target}
     * @param target {@link Collection} or {@link Map}
     * @return {@code true} if each of {@code target}'s type parameters is the class's own at the same place, by the
     *         same name, and so is every type parameter of that name of a class or an interface above the class, and
     *         no public method returns a type variable of its own under one of those names
     */
    private static boolean linesUp(Class<?> type, Class<?> target) {
        TypeVariable<?>[] own = type.getTypeParameters();
        TypeVariable<?>[] targets = target.getTypeParameters();
        if (own.length != targets.length) {
            return false;
        }
        for (int i = 0; i < own.length; i++) {
            if (!own[i].getName().equals(targets[i].getName())) {
                return false;
            }
        }
        // With the names, this is what holds the place too: the walk reaches the target, whose type parameters have
        // the class's names, in order.
        List<TypeVariable<?>> names = List.of(own);
        return keepsNames(type, own, names) && !returnsItsOwnTypeVariable(type, names);
    }

    /**
     * Returns whether a class has a public method, its own or inherited, static ones too, that returns a type
     * variable of the method's own under the name of one of the class's type parameters, as {@code <E> E other()}
     * does in a {@code Pile<E>}. MVEL finds what a method returns by that name alone, so it would check what such a
     * method returns as the fact's type argument, which it isn't. A method that returns the type variable inside
     * another type, such as {@code <E> List<E>}, isn't one: MVEL doesn't check an element of what that returns as the
     * fact's type argument.
     *
     * <p>
     * A class of the JDK's own, one the boot class loader defines, isn't asked for its methods, and counts as having
     * none: the JDK's collection and map types have no such method, and a native image built with strict metadata
     * throws when a class it has no metadata for is asked for its methods, which an application has no reason to
     * write for {@code java.util.List}. A class of the application's is asked, so such an image needs the metadata
     * for it, {@code queryAllPublicMethods}, as it does for MVEL to call a method of the class.
     * </p>
     *
     * @param type The class
     * @param own  Its type parameters
     * @return {@code true} if a public method's return type is a type variable the method declares, named as one of
     *         {@code own}; {@code false} for a class the boot class loader defines
     */
    // The class's own loader is what says whether the class is the JDK's.
    @SuppressWarnings("PMD.UseProperClassLoader")
    private static boolean returnsItsOwnTypeVariable(Class<?> type, List<TypeVariable<?>> own) {
        if (type.getClassLoader() == null) {
            return false;
        }
        for (Method method : type.getMethods()) {
            if (method.getGenericReturnType() instanceof TypeVariable<?> returned
                    && returned.getGenericDeclaration() instanceof Method) {
                for (TypeVariable<?> variable : own) {
                    if (variable.getName().equals(returned.getName())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Returns whether a class or an interface, and every one above it, uses the names of a class's own type parameters
     * only for those type parameters: for {@code Top<E> extends Mid<String, E>} it's {@code false} at {@code Mid},
     * whose own {@code E} is the {@code String}.
     *
     * @param supertype The class itself, or a class or an interface above it
     * @param arguments What the class makes of each of {@code supertype}'s type parameters, in order: one of its own
     *                  type variables, or whatever it or a class between the two wrote instead
     * @param own       The class's own type parameters
     * @return {@code false} if a type parameter of {@code supertype}, or of a class or an interface above it, has the
     *         name of one of {@code own} and isn't it
     */
    private static boolean keepsNames(Class<?> supertype, Type[] arguments, List<TypeVariable<?>> own) {
        TypeVariable<?>[] parameters = supertype.getTypeParameters();
        for (int i = 0; i < parameters.length; i++) {
            for (TypeVariable<?> variable : own) {
                if (variable.getName().equals(parameters[i].getName()) && !variable.equals(arguments[i])) {
                    return false;
                }
            }
        }
        Type superclass = supertype.getGenericSuperclass();
        if (superclass != null && !keepsNames(superclass, parameters, arguments, own)) {
            return false;
        }
        for (Type implemented : supertype.getGenericInterfaces()) {
            if (!keepsNames(implemented, parameters, arguments, own)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns whether a class's superclass or one of its interfaces, as the class wrote it, and every class and
     * interface above that, uses the names of {@code own} only for them (see
     * {@link #keepsNames(Class, Type[], List)}).
     *
     * @param extended   A superclass or an interface, as the class that extends or implements it wrote it
     * @param parameters The type parameters of the class that wrote it
     * @param arguments  What the class {@code own} are of makes of each of {@code parameters}, in order
     * @param own        The type parameters of the class the walk started at
     * @return {@code false} if a type parameter there has the name of one of {@code own} and isn't it
     */
    private static boolean keepsNames(Type extended, TypeVariable<?>[] parameters, Type[] arguments,
            List<TypeVariable<?>> own) {
        Class<?> supertype = CompileContext.rawClass(extended);
        if (!(extended instanceof ParameterizedType parameterized)) {
            // Written without type arguments, so its type parameters stand for nothing of the class's.
            return keepsNames(supertype, supertype.getTypeParameters(), own);
        }
        Type[] written = parameterized.getActualTypeArguments();
        Type[] seen = new Type[written.length];
        for (int i = 0; i < written.length; i++) {
            seen[i] = written[i];
            for (int j = 0; j < parameters.length; j++) {
                if (parameters[j].equals(written[i])) {
                    seen[i] = arguments[j];
                }
            }
        }
        return keepsNames(supertype, seen, own);
    }

    /**
     * Returns the class MVEL checks an element by for one type argument, if it can check one.
     *
     * @param argument A type argument of a collection or a map, as the application wrote it
     * @return The argument, or a wildcard's upper bound, if it's a class with no type parameters that isn't dynamic
     *         or primitive, or an array of one; otherwise {@code null}
     */
    private static @Nullable Class<?> checkable(Type argument) {
        Type bound = argument;
        if (argument instanceof WildcardType wildcard) {
            // The JDK's own wildcard always has an upper bound, Object if none was written; another library's may
            // have none.
            Type[] bounds = wildcard.getUpperBounds();
            if (bounds.length == 0) {
                return null;
            }
            bound = bounds[0];
        }
        // No collection holds a primitive, which only a parameterized type that isn't the JDK's own can say one does.
        return bound instanceof Class<?> type && !type.isPrimitive() && !isDynamic(type) && !isGeneric(type)
                ? type : null;
    }

    /**
     * Returns whether a class has type parameters, which a type argument that is only a class says nothing for.
     *
     * @param type A type argument's class
     * @return {@code true} for a class with type parameters, and an array of one
     */
    private static boolean isGeneric(Class<?> type) {
        return type.isArray() ? isGeneric(type.getComponentType()) : type.getTypeParameters().length != 0;
    }

    /**
     * Reads the options MVEL was given.
     *
     * @param options The options by name
     * @return Whether {@code strongTyping} is on
     * @throws IllegalArgumentException if an option isn't one MVEL has, or {@code strongTyping} is neither
     *                                  {@code true} nor {@code false}
     */
    private static boolean strongTyping(Map<String, String> options) {
        for (String key : options.keySet()) {
            if (!STRONG_TYPING.equals(key)) {
                throw new IllegalArgumentException(MessageQuotes.quotedWithin("MVEL has no option '", key,
                        "'; its only option is " + STRONG_TYPING));
            }
        }
        String value = options.getOrDefault(STRONG_TYPING, "false");
        return switch (value) {
            case "true" -> true;
            case "false" -> false;
            default -> throw new IllegalArgumentException(MessageQuotes.quotedWithin("MVEL's " + STRONG_TYPING
                    + " option must be true or false, but was '", value, "'"));
        };
    }

    private static IllegalArgumentException cantApply(String reason) {
        return new IllegalArgumentException(CANT_APPLY + reason);
    }

    /**
     * Returns whether a type says nothing about what its members are, so MVEL can't check them.
     *
     * @param type The declared type
     * @return {@code true} for {@link Object}, any {@link Map} or {@link Collection}, and an array of any of them
     */
    private static boolean isDynamic(Class<?> type) {
        if (type.isArray()) {
            return isDynamic(type.getComponentType());
        }
        return type == Object.class || Map.class.isAssignableFrom(type) || Collection.class.isAssignableFrom(type);
    }
}
