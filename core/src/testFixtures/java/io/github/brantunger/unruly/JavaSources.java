package io.github.brantunger.unruly;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Compiles Java source held in a string with the JDK's compiler, for tests that need a class, or a module, that isn't
 * on the class path when the tests start.
 */
public final class JavaSources {

    private JavaSources() {
    }

    /** What compiling some sources gave: whether they compiled, and the compiler's errors, one per line. */
    public record Compilation(boolean compiled, String errors) {
    }

    /**
     * A source file whose text is {@code text}.
     *
     * @param path The file's path without {@code .java}, such as {@code com/example/Facts} or {@code module-info}
     * @param text The file's source
     * @return The file, for {@link #compile} or {@link #assertCompiles}
     */
    public static JavaFileObject source(String path, String text) {
        return new SimpleJavaFileObject(URI.create("string:///" + path + ".java"), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return text;
            }
        };
    }

    /**
     * Compiles {@code sources} with the system Java compiler and {@code options}, such as {@code -d} and the
     * directory to write the classes to.
     *
     * @param options The compiler's options
     * @param sources The files to compile
     * @return Whether they compiled, and the messages of the compiler's errors, in {@link Locale#ROOT}
     */
    public static Compilation compile(List<String> options, List<? extends JavaFileObject> sources) {
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        boolean compiled = javac.getTask(null, null, diagnostics, options, null, sources).call();
        String errors = diagnostics.getDiagnostics().stream()
                .filter(diagnostic -> diagnostic.getKind() == Diagnostic.Kind.ERROR)
                .map(diagnostic -> diagnostic.getMessage(Locale.ROOT))
                .collect(Collectors.joining("\n"));
        return new Compilation(compiled, errors);
    }

    /**
     * Compiles {@code sources} as {@link #compile} does, and fails the test with the compiler's errors if they
     * don't compile.
     *
     * @param options The compiler's options
     * @param sources The files to compile
     */
    public static void assertCompiles(List<String> options, List<? extends JavaFileObject> sources) {
        Compilation compilation = compile(options, sources);
        assertTrue(compilation.compiled(), compilation.errors());
    }
}
