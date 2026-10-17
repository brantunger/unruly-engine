package io.github.brantunger.unruly.test;

import io.github.brantunger.unruly.api.exception.ExpressionKind;
import io.github.brantunger.unruly.api.language.CompileContext;
import io.github.brantunger.unruly.api.language.Expression;
import io.github.brantunger.unruly.api.language.ExpressionCompiler;
import io.github.brantunger.unruly.api.language.ExpressionLanguage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The kit's language wrappers, {@link SessionWatch}, {@link FactNameWatch} and {@link CompilerCloseCounter}, forward
 * every method of {@link ExpressionLanguage} and {@link ExpressionCompiler}, the {@code default} ones included, so a
 * check that wraps the language doesn't silently replace a hook the language overrides with the do-nothing default
 * (#736, #1127). They extend {@link ForwardingLanguage} and {@link ForwardingCompiler}, which forward everything, so
 * those are checked to declare every method of their interface, enumerated by reflection rather than by hand, and to
 * forward each call with its arguments and return what the call returned: a method added to either interface fails
 * the kit's build here, not a language's.
 */
@DisplayName("the kit's language wrappers forward every method of ExpressionLanguage and ExpressionCompiler")
class WrapperForwardingTest {

    /** A call the delegate received: the method's name, and its arguments. */
    private record Call(String method, List<Object> arguments) {
    }

    /** Each wrapper, by name, as a function of the language it wraps. */
    static Stream<Object[]> wrappers() {
        return Stream.of(
                new Object[] {"SessionWatch.watching", (UnaryOperator<ExpressionLanguage>) language ->
                        new SessionWatch().watching(language)},
                new Object[] {"FactNameWatch.watchingRejection", (UnaryOperator<ExpressionLanguage>) language ->
                        FactNameWatch.watchingRejection(language, "x", new AtomicBoolean())},
                new Object[] {"FactNameWatch.recordingNamesRead", (UnaryOperator<ExpressionLanguage>) language ->
                        FactNameWatch.recordingNamesRead(language, new ArrayList<>())},
                new Object[] {"CompilerCloseCounter.countingCloses", (UnaryOperator<ExpressionLanguage>) language ->
                        CompilerCloseCounter.countingCloses(language, new ArrayList<>(), new ArrayList<>())});
    }

    /** A language whose compiler is {@code compiler}. */
    private static ExpressionLanguage languageOf(ExpressionCompiler compiler) {
        return new ExpressionLanguage() {
            @Override
            public String name() {
                return "wrapped";
            }

            @Override
            public ExpressionCompiler newCompiler(CompileContext context) {
                return compiler;
            }
        };
    }

    /** The compiler a wrapper gives for a language whose compiler is {@code compiler}. */
    private static ExpressionCompiler wrapped(UnaryOperator<ExpressionLanguage> wrapper,
                                              ExpressionCompiler compiler) {
        return wrapper.apply(languageOf(compiler)).newCompiler(LanguageTestContexts.compile());
    }

    /** The methods of {@code type} an implementation can leave to a default: every non-static one. */
    private static List<Method> methodsOf(Class<?> type) {
        List<Method> methods = Arrays.stream(type.getMethods())
                .filter(method -> !Modifier.isStatic(method.getModifiers())).toList();
        assertFalse(methods.isEmpty(), type.getSimpleName() + " has no methods");
        return methods;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("wrappers")
    @DisplayName("the wrapper's language and compiler override every method of their interface, in the wrapper or"
            + " in its forwarding base, so none is left to its default")
    void everyMethodOverridden(String name, UnaryOperator<ExpressionLanguage> wrapper) {
        ExpressionCompiler delegate = (ExpressionCompiler) Proxy.newProxyInstance(
                ExpressionCompiler.class.getClassLoader(), new Class<?>[] {ExpressionCompiler.class},
                (proxy, method, arguments) -> null);
        ExpressionLanguage language = wrapper.apply(languageOf(delegate));

        assertAll(
                () -> assertEquals(List.of(), leftToDefault(ExpressionLanguage.class, language.getClass()),
                        name + " leaves these methods to ExpressionLanguage's defaults"),
                () -> assertEquals(List.of(), leftToDefault(ExpressionCompiler.class,
                                language.newCompiler(LanguageTestContexts.compile()).getClass()),
                        name + " leaves these methods to ExpressionCompiler's defaults"));
    }

    /**
     * The methods of {@code type} that {@code implementing} leaves to the interface's own: neither it nor a superclass
     * overrides them.
     */
    private static List<String> leftToDefault(Class<?> type, Class<?> implementing) throws NoSuchMethodException {
        List<String> left = new ArrayList<>();
        for (Method method : methodsOf(type)) {
            Method resolved = implementing.getMethod(method.getName(), method.getParameterTypes());
            if (resolved.getDeclaringClass().isInterface()) {
                left.add(method.getName());
            }
        }
        return left;
    }

    @Test
    @DisplayName("ForwardingLanguage declares every method of ExpressionLanguage, so a method added to the interface"
            + " fails here, not a language's build")
    void languageBaseDeclaresEveryMethod() {
        assertEquals(List.of(), notDeclared(ExpressionLanguage.class, ForwardingLanguage.class),
                "ForwardingLanguage leaves these methods to ExpressionLanguage's defaults");
    }

    @Test
    @DisplayName("ForwardingCompiler declares every method of ExpressionCompiler, so a method added to the interface"
            + " fails here, not a language's build")
    void compilerBaseDeclaresEveryMethod() {
        assertEquals(List.of(), notDeclared(ExpressionCompiler.class, ForwardingCompiler.class),
                "ForwardingCompiler leaves these methods to ExpressionCompiler's defaults");
    }

    /** The methods of {@code type} that {@code base} doesn't declare itself, an inherited default included. */
    private static List<String> notDeclared(Class<?> type, Class<?> base) {
        List<String> missing = new ArrayList<>();
        for (Method method : methodsOf(type)) {
            try {
                base.getDeclaredMethod(method.getName(), method.getParameterTypes());
            } catch (NoSuchMethodException e) {
                missing.add(method.getName());
            }
        }
        return missing;
    }

    @Test
    @DisplayName("ForwardingLanguage forwards each method of ExpressionLanguage, and returns what it returns")
    void languageBaseForwardsEveryMethod() throws Throwable {
        assertForwardsEveryMethod(ExpressionLanguage.class, ForwardingLanguage::new);
    }

    @Test
    @DisplayName("ForwardingCompiler forwards each method of ExpressionCompiler, and returns what it returns")
    void compilerBaseForwardsEveryMethod() throws Throwable {
        assertForwardsEveryMethod(ExpressionCompiler.class, ForwardingCompiler::new);
    }

    /**
     * Calls each method of {@code type} on a forwarding instance, and fails unless the delegate received the same call,
     * once, and the forwarding instance returned what the delegate did.
     */
    private static <T> void assertForwardsEveryMethod(Class<T> type, Function<T, ? extends T> forwarding)
            throws Throwable {
        List<Call> calls = new ArrayList<>();
        List<Object> returned = new ArrayList<>();
        T delegate = type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
                (proxy, method, arguments) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return objectMethod(proxy, method, arguments, "the delegate");
                    }
                    calls.add(new Call(method.getName(), arguments == null ? List.of() : Arrays.asList(arguments)));
                    Object result = sample(method.getReturnType());
                    returned.add(result);
                    return result;
                }));
        T forwarder = forwarding.apply(delegate);

        for (Method method : methodsOf(type)) {
            calls.clear();
            returned.clear();
            Object[] arguments = Arrays.stream(method.getParameterTypes())
                    .map(WrapperForwardingTest::sample).toArray();
            Object result;
            try {
                result = method.invoke(forwarder, arguments);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }

            assertEquals(List.of(new Call(method.getName(), Arrays.asList(arguments))), calls,
                    () -> "the delegate's calls when " + method + " was called");
            if (method.getReturnType() != void.class) {
                assertSame(returned.get(0), result, () -> method + " returned something else than the delegate");
            }
        }
    }

    /**
     * A value of {@code type} to pass or return: a proxy that is only equal to itself for an interface that isn't
     * sealed, the kit's compile context for the sealed {@link CompileContext}, a fixed expression, text, class or
     * object for an {@link Expression}, a {@code String}, a {@code Class} or an {@code Object}, and {@code false} for a
     * {@code boolean}, which no method returns by default. Every value is distinct from every other, so a forward that
     * swaps or drops an argument, or returns something else than the delegate, is seen. {@code null} for any other
     * type, of which the interfaces have none.
     */
    private static Object sample(Class<?> type) {
        if (type == String.class) {
            return "sample";
        }
        if (type == boolean.class) {
            return Boolean.FALSE;
        }
        if (type == Object.class) {
            return new Object();
        }
        if (type == Class.class) {
            return int.class;
        }
        if (type == Expression.class) {
            return new Expression("r", ExpressionKind.CONDITION, "x == 1");
        }
        if (type == CompileContext.class) {
            return LanguageTestContexts.compile();
        }
        if (type.isInterface() && !type.isSealed()) {
            return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (proxy, method, arguments) ->
                    objectMethod(proxy, method, arguments, "a sample " + type.getSimpleName()));
        }
        return null;
    }

    /** Answers one of {@link Object}'s methods for a proxy that is only equal to itself, and fails any other. */
    private static Object objectMethod(Object proxy, Method method, Object[] arguments, String text) {
        return switch (method.getName()) {
            case "equals" -> proxy == arguments[0];
            case "hashCode" -> System.identityHashCode(proxy);
            case "toString" -> text;
            default -> throw new UnsupportedOperationException(method.getName());
        };
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("wrappers")
    @DisplayName("convertOutput reaches the language's compiler with the value and the target, and returns what it"
            + " returns")
    void convertOutputForwarded(String name, UnaryOperator<ExpressionLanguage> wrapper) {
        List<Call> calls = new ArrayList<>();
        Object converted = new Object();
        ExpressionCompiler delegate = (ExpressionCompiler) Proxy.newProxyInstance(
                ExpressionCompiler.class.getClassLoader(), new Class<?>[] {ExpressionCompiler.class},
                (proxy, method, arguments) -> {
                    calls.add(new Call(method.getName(), Arrays.asList(arguments)));
                    return converted;
                });

        Object result = wrapped(wrapper, delegate).convertOutput(3.0, int.class);

        assertSame(converted, result, name);
        assertEquals(List.of(new Call("convertOutput", List.of(3.0, int.class))), calls, name);
    }

    @Test
    @DisplayName("the check covers every wrapper the contract test uses")
    void everyWrapperListed() {
        assertEquals(4, wrappers().count());
    }
}
