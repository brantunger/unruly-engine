package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.mvel.ExactNameClassLoaderCopies;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.function.BiPredicate;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code mvel.ExactNameClassLoader} keeps its own copy of {@code core.ImportResolver.isWrongName}, which tells the
 * JVM's "wrong name" error for the name looked up from any other {@code NoClassDefFoundError}, because the
 * {@code mvel} package may not use {@code core.ImportResolver}, which isn't public either: this test is in
 * {@code core}'s package to reach it. The copies read the same errors the same way, so a change to one that misses
 * the other makes an engine import and a name in a rule's text read a class file found in another case differently.
 * Every case here runs on both. The cases have the names in both orders the JVMs write them in: the name asked for
 * first, as HotSpot writes it, and last, as OpenJ9 does.
 */
@DisplayName("the engine's \"wrong name\" check and the copy in ExactNameClassLoader read identically")
class WrongNameCopiesTest {

    /** A {@code NoClassDefFoundError} whose {@code getMessage()} throws, as an application's class loader might. */
    private static final class UnreadableNoClassDefFoundError extends NoClassDefFoundError {
        private static final long serialVersionUID = 1L;

        @Override
        public String getMessage() {
            throw new IllegalStateException("message accessor broke");
        }
    }

    /** One case: the error, the name looked up, and whether both copies take it for a "wrong name" error. */
    private record Case(NoClassDefFoundError error, String name, boolean wrongName) {
    }

    private static Stream<Arguments> copies() {
        return Stream.of(
                Arguments.of("core.ImportResolver", (BiPredicate<NoClassDefFoundError, String>)
                        ImportResolver::isWrongName),
                Arguments.of("mvel.ExactNameClassLoader", (BiPredicate<NoClassDefFoundError, String>)
                        ExactNameClassLoaderCopies::isWrongName));
    }

    private static List<Case> cases() {
        return List.of(
                new Case(new NoClassDefFoundError("applicant (wrong name: Applicant)"), "applicant", true),
                new Case(new NoClassDefFoundError("p/a (wrong name: p/A)"), "p.a", true),
                new Case(new NoClassDefFoundError("p/Rules$Limit (wrong name: p/rules$Limit)"), "p.Rules$Limit", true),
                new Case(new NoClassDefFoundError("p/A (wrong name: p/a)"), "p.a", true),
                new Case(new NoClassDefFoundError("p/rules$Limit (wrong name: p/Rules$Limit)"), "p.Rules$Limit", true),
                new Case(new NoClassDefFoundError("p/Base (wrong name: p/BASE)"), "p.Sub", false),
                new Case(new NoClassDefFoundError("p/BASE (wrong name: p/Base)"), "p.Sub", false),
                new Case(new NoClassDefFoundError("p/SUBX (wrong name: p/SubX)"), "p.Sub", false),
                new Case(new NoClassDefFoundError("p/A (wrong name: p/a) x"), "p.a", false),
                new Case(new NoClassDefFoundError("p/SubX (wrong name: p/SUBX)"), "p.Sub", false),
                new Case(new NoClassDefFoundError("p/Sub$In (wrong name: p/SUB$In)"), "p.Sub", false),
                new Case(new NoClassDefFoundError("x p/a (wrong name: p/A)"), "p.a", false),
                new Case(new NoClassDefFoundError("p/Base"), "p.Base", false),
                new Case(new NoClassDefFoundError(), "p.a", false),
                new Case(new UnreadableNoClassDefFoundError(), "p.a", false));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("copies")
    @DisplayName("every case is read the same way")
    void bothCopiesAgree(String copy, BiPredicate<NoClassDefFoundError, String> isWrongName) {
        List<Case> cases = cases();
        for (int i = 0; i < cases.size(); i++) {
            Case expected = cases.get(i);
            // Named by its place, as an error whose message can't be read can't be printed either.
            assertEquals(expected.wrongName(), isWrongName.test(expected.error(), expected.name()),
                    copy + " read case " + i + ", for " + expected.name() + ", differently");
        }
    }
}
