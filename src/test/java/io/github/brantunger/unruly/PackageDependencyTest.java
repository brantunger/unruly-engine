package io.github.brantunger.unruly;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreeScanner;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("packages depend on each other only as designed")
class PackageDependencyTest {

    private static final Path SOURCES = Path.of("src", "main", "java", "io", "github", "brantunger", "unruly");

    private static final String ROOT_PACKAGE = "io.github.brantunger.unruly.";

    /** A name in code that starts with one of this library's packages (group 1, relative) and then a class. */
    private static final Pattern LIBRARY_NAME =
            Pattern.compile("^io\\.github\\.brantunger\\.unruly\\.([a-z][a-z0-9]*(?:\\.[a-z][a-z0-9]*)*)\\.[A-Z]");

    /**
     * The library packages each package's code may use. The SPI packages stand alone, {@code mvel} uses only them,
     * and {@code core} finds languages with ServiceLoader rather than using {@code mvel}, so the MVEL language could
     * become its own artifact.
     */
    private static final Map<String, Set<String>> ALLOWED = Map.of(
            "api", Set.of("api.exception", "api.language", "core"),
            "api.exception", Set.of(),
            "api.language", Set.of(),
            "core", Set.of("api", "api.exception", "api.language"),
            "mvel", Set.of("api.exception", "api.language"));

    /** Dependencies that only one file may have. */
    private static final Map<Dependency, String> ONLY_FILE = Map.of(
            new Dependency("api", "core"), "api/RulesEngineBuilder.java");

    private static List<SourceFile> sourceFiles;

    private record Dependency(String from, String to) {
    }

    /** A main source file: its path relative to the library's root package, its package, and the packages it uses. */
    private record SourceFile(String path, String packageName, Set<String> uses) {
    }

    @BeforeAll
    static void parseSources() throws IOException {
        List<Path> paths;
        try (Stream<Path> files = Files.walk(SOURCES)) {
            paths = files.filter(file -> file.toString().endsWith(".java")).sorted().toList();
        }
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        List<SourceFile> parsed = new ArrayList<>();
        try (StandardJavaFileManager fileManager = javac.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
            for (Path path : paths) {
                JavacTask task = (JavacTask) javac.getTask(null, fileManager, null, null, null,
                        fileManager.getJavaFileObjects(path));
                for (CompilationUnitTree unit : task.parse()) {
                    String packageName = unit.getPackageName().toString().substring(ROOT_PACKAGE.length());
                    Set<String> uses = libraryPackagesUsed(unit);
                    uses.remove(packageName);
                    parsed.add(new SourceFile(SOURCES.relativize(path).toString().replace('\\', '/'), packageName,
                            uses));
                }
            }
        }
        sourceFiles = parsed;
    }

    /** Collects the library packages named in imports and qualified names. Comments, and so Javadoc, are skipped. */
    private static Set<String> libraryPackagesUsed(CompilationUnitTree unit) {
        Set<String> uses = new TreeSet<>();
        new TreeScanner<Void, Void>() {
            @Override
            public Void visitImport(ImportTree tree, Void unused) {
                use(tree.getQualifiedIdentifier());
                return null;
            }

            @Override
            public Void visitMemberSelect(MemberSelectTree tree, Void unused) {
                return use(tree) ? null : super.visitMemberSelect(tree, unused);
            }

            private boolean use(Tree name) {
                Matcher matcher = LIBRARY_NAME.matcher(name.toString());
                if (matcher.find()) {
                    uses.add(matcher.group(1));
                    return true;
                }
                return false;
            }
        }.scan(unit, null);
        return uses;
    }

    @Test
    @DisplayName("every package has a list of the packages it may use")
    void everyPackageListed() {
        Set<String> packages = sourceFiles.stream().map(SourceFile::packageName)
                .collect(Collectors.toCollection(TreeSet::new));

        assertEquals(new TreeSet<>(ALLOWED.keySet()), packages);
    }

    @Test
    @DisplayName("each package uses only the packages listed for it")
    void onlyAllowedPackagesUsed() {
        List<String> violations = new ArrayList<>();
        for (SourceFile file : sourceFiles) {
            for (String used : file.uses()) {
                if (!ALLOWED.getOrDefault(file.packageName(), Set.of()).contains(used)) {
                    violations.add(file.path() + " uses " + used);
                }
            }
        }

        assertEquals(List.of(), violations);
    }

    @Test
    @DisplayName("api uses core only to build the engines")
    void singleFileDependencies() {
        ONLY_FILE.forEach((dependency, expected) -> {
            List<String> users = sourceFiles.stream()
                    .filter(file -> file.packageName().equals(dependency.from()))
                    .filter(file -> file.uses().contains(dependency.to()))
                    .map(SourceFile::path)
                    .toList();

            assertEquals(List.of(expected), users, dependency.from() + " -> " + dependency.to());
        });
    }

    @Test
    @DisplayName("only the mvel package uses the MVEL library")
    void mvelLibraryOnlyInMvelPackage() throws IOException {
        assertTrue(sourceFiles.stream().anyMatch(file -> file.packageName().equals("mvel")), "no mvel package found");

        List<String> found = new ArrayList<>();
        for (SourceFile file : sourceFiles) {
            if (!file.packageName().equals("mvel") && Files.readString(SOURCES.resolve(file.path())).contains("org.mvel2")) {
                found.add(file.path());
            }
        }
        assertEquals(List.of(), found);
    }
}
