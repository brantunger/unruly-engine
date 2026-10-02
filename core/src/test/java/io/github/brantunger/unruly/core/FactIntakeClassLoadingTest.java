package io.github.brantunger.unruly.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A run's first rejected fact name, or first fact declared with a primitive type, may come deep in another run's stack,
 * after the stack-headroom check (see StackHeadroom), so {@code FactIntake} leaves nothing there for the JVM to load or
 * link on first use. Both tests read what javac made of {@code FactIntake}: the class it makes for an enum
 * {@code switch} is named {@code FactIntake$1}, and the method it makes for a lambda in {@code factValues} is named
 * {@code lambda$factValues$}, then a number. They can't see a {@code switch} with a {@code case null}, which javac
 * compiles to an invokedynamic call of {@code SwitchBootstraps} with no {@code $1} class, a method reference, which
 * leaves no {@code lambda$} method, or a lambda compiled by ecj, which names its methods differently.
 */
@DisplayName("FactIntake loads no class and bootstraps no lambda for a first rejected name or primitive fact (#880)")
class FactIntakeClassLoadingTest {

    @Test
    @DisplayName("FactIntake leaves no switch-map class for a run's first rejected fact name to load")
    void noSwitchMapClass() {
        String name = FactIntake.class.getName() + "$1";

        assertThrows(ClassNotFoundException.class,
                () -> Class.forName(name, false, FactIntake.class.getClassLoader()));
    }

    @Test
    @DisplayName("FactIntake leaves no lambda for a run's first primitive fact to bootstrap")
    void noFactValuesLambda() {
        List<String> lambdas = Arrays.stream(FactIntake.class.getDeclaredMethods())
                .map(Method::getName)
                .filter(name -> name.startsWith("lambda$factValues$"))
                .toList();

        assertEquals(List.of(), lambdas);
    }
}
