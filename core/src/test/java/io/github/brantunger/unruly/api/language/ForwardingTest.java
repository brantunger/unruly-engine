package io.github.brantunger.unruly.api.language;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The forwarding test fixtures forward every method of their interface, the {@code default} ones included, so a test
 * that overrides one method of a real language keeps the rest, and a method added later isn't silently dropped (#736).
 */
@DisplayName("the forwarding test language and compiler forward every method of their interface")
class ForwardingTest {

    /** A call the delegate received: the method's name, and its arguments. */
    private record Call(String method, List<Object> arguments) {
    }

    @Test
    @DisplayName("ForwardingExpressionCompiler forwards each method of ExpressionCompiler, and returns what it returns")
    void compilerForwardsEveryMethod() throws Throwable {
        assertForwardsEveryMethod(ExpressionCompiler.class, ForwardingExpressionCompiler::new);
    }

    @Test
    @DisplayName("ForwardingExpressionLanguage forwards each method of ExpressionLanguage, and returns what it returns")
    void languageForwardsEveryMethod() throws Throwable {
        assertForwardsEveryMethod(ExpressionLanguage.class, ForwardingExpressionLanguage::new);
    }

    /**
     * Calls each method of {@code type} on a forwarding instance, and fails unless the delegate received the same call,
     * once, and the forwarding instance returned what the delegate did.
     */
    private static <T> void assertForwardsEveryMethod(Class<T> type, Function<T, ? extends T> forwarding)
            throws Throwable {
        List<Call> calls = new ArrayList<>();
        List<Object> returned = new ArrayList<>();
        T delegate = type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
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

        List<Method> methods = Arrays.stream(type.getMethods())
                .filter(method -> !Modifier.isStatic(method.getModifiers())).toList();
        assertFalse(methods.isEmpty(), type.getSimpleName() + " has no methods");
        for (Method method : methods) {
            calls.clear();
            returned.clear();
            Object[] arguments = Arrays.stream(method.getParameterTypes()).map(ForwardingTest::sample).toArray();
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
     * sealed, and a fixed text for a {@code String}. {@code null} for any other type, such as the sealed
     * {@link CompileContext}.
     */
    private static Object sample(Class<?> type) {
        if (type == String.class) {
            return "sample";
        }
        if (type.isInterface() && !type.isSealed()) {
            return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, arguments) ->
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
}
