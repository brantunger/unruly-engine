package io.github.brantunger.unruly.test;

import io.github.brantunger.unruly.api.language.CompileContext;
import io.github.brantunger.unruly.api.language.ExpressionCompiler;
import io.github.brantunger.unruly.api.language.ExpressionLanguage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The kit's language wrappers, {@link SessionWatch}, {@link FactNameWatch} and {@link CompilerCloseCounter}, forward
 * every method of {@link ExpressionCompiler}, the {@code default} ones included, so a check that wraps the language
 * doesn't silently replace a hook the language overrides with the do-nothing default (#736, #1127).
 */
@DisplayName("the kit's language wrappers forward every method of ExpressionCompiler")
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

    @ParameterizedTest(name = "{0}")
    @MethodSource("wrappers")
    @DisplayName("the wrapper's compiler overrides every method of ExpressionCompiler, so none is left to its default")
    void everyMethodOverridden(String name, UnaryOperator<ExpressionLanguage> wrapper) {
        ExpressionCompiler delegate = (ExpressionCompiler) Proxy.newProxyInstance(
                ExpressionCompiler.class.getClassLoader(), new Class<?>[] {ExpressionCompiler.class},
                (proxy, method, arguments) -> null);
        Class<?> wrapping = wrapped(wrapper, delegate).getClass();

        List<String> missing = new ArrayList<>();
        for (Method method : ExpressionCompiler.class.getMethods()) {
            if (Modifier.isStatic(method.getModifiers())) {
                continue;
            }
            try {
                wrapping.getDeclaredMethod(method.getName(), method.getParameterTypes());
            } catch (NoSuchMethodException e) {
                missing.add(method.getName());
            }
        }

        assertEquals(List.of(), missing, name + " leaves these methods to ExpressionCompiler's defaults");
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
