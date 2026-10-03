package io.github.brantunger.unruly.mvel;

import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
import io.github.brantunger.unruly.api.exception.RuleCompilationException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static io.github.brantunger.unruly.JavaSources.assertCompiles;
import static io.github.brantunger.unruly.JavaSources.source;
import static io.github.brantunger.unruly.TestSupport.withContextClassLoader;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The fact name {@code applicant} is a real class here, whose superclass {@code Base} has only a class file in another
 * case, {@code BASE.class}. The class loader finds a class file as a case-insensitive file system does, ignoring case,
 * and defines it under the name it was asked for, so the JVM itself throws
 * {@code NoClassDefFoundError: Base (wrong name: BASE)} while it loads {@code applicant}, on every operating system.
 * That error is about {@code Base}, not the name looked up, so {@code applicant} is a class that exists but can't be
 * loaded, as where {@code Base.class} is simply missing, and not a name with no class.
 */
@DisplayName("a fact name whose class's superclass finds a class file in another case fails the rule list (#993)")
class WrongNameDependencyTest {

    /** In the default package, so the bare fact name {@code applicant} finds it at the directory's root. */
    private static final String APPLICANT = """
            public class applicant extends Base {
            }
            """;

    private static final String BASE = """
            public class Base {
            }
            """;

    private static final String OTHER_CASE_BASE = """
            public class BASE {
            }
            """;

    private static final Rule PRIME_RATE = Rule.builder().ruleName("prime-rate")
            .condition("applicant.creditScore >= 750").action("output.put('rate', 4.5)").build();

    @TempDir
    static Path classes;

    @TempDir
    static Path otherCaseClasses;

    /**
     * Compiles the classes once, then replaces {@code Base.class} by {@code BASE.class}, compiled apart, as the two
     * files would be one on a case-insensitive file system.
     */
    @BeforeAll
    static void compileClasses() throws IOException {
        assertCompiles(List.of("-proc:none", "-d", classes.toString()),
                List.of(source("applicant", APPLICANT), source("Base", BASE)));
        assertCompiles(List.of("-proc:none", "-d", otherCaseClasses.toString()),
                List.of(source("BASE", OTHER_CASE_BASE)));
        Files.delete(classes.resolve("Base.class"));
        Files.copy(otherCaseClasses.resolve("BASE.class"), classes.resolve("BASE.class"));
    }

    /**
     * A class directory on a case-insensitive file system: a class file is found by its path with case ignored, and
     * defined under the name the lookup asked for, whatever the class in it is named.
     */
    private static final class CaseInsensitiveClassDirectory extends ClassLoader {

        CaseInsensitiveClassDirectory() {
            super(WrongNameDependencyTest.class.getClassLoader());
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            String path = name.replace('.', '/') + ".class";
            try (Stream<Path> files = Files.walk(classes)) {
                Path file = files.filter(found -> classes.relativize(found).toString().replace('\\', '/')
                        .equalsIgnoreCase(path)).findFirst().orElseThrow(() -> new ClassNotFoundException(name));
                byte[] bytes = Files.readAllBytes(file);
                return defineClass(name, bytes, 0, bytes.length);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    @Test
    @DisplayName("the rule naming it fails to compile with the JVM's error for the superclass, as where its class"
            + " file is missing")
    void wrongNameOfADependencyFailsTheLoad() {
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>firstMatch(HashMap::new)
                .build();

        RuleCompilationException thrown = assertThrows(RuleCompilationException.class,
                () -> withContextClassLoader(new CaseInsensitiveClassDirectory(), () -> {
                    engine.load(List.of(PRIME_RATE));
                    FactMap<Object> facts = new FactMap<>();
                    facts.setValue("applicant", Map.of("creditScore", 780));
                    return engine.run(facts);
                }));

        assertEquals("prime-rate", thrown.getRuleName());
        assertEquals("Condition for rule 'prime-rate' failed to compile at line 1, column 1: Base (wrong name: BASE)",
                thrown.getMessage());
        assertTrue(Stream.iterate((Throwable) thrown, t -> t != null, Throwable::getCause)
                .anyMatch(t -> t instanceof NoClassDefFoundError
                        && "Base (wrong name: BASE)".equals(t.getMessage())), thrown.getMessage());
    }
}
