package io.github.brantunger.unruly.mvel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ClassNameRoots records only what an expression's compilation looked up")
class ClassNameRootsTest {

    private static Imports imports() {
        return new Imports(Set.of(), Set.of(), ClassNameRootsTest.class.getClassLoader());
    }

    private static Class<?> load(ClassLoader loader, String name) {
        try {
            return loader.loadClass(name);
        } catch (ClassNotFoundException e) {
            throw new AssertionError(name, e);
        }
    }

    @Test
    @DisplayName("a class named with its package in the expression adds its first part, once the compile is done")
    void compileRecords() {
        Imports imports = imports();
        FactNames names = new FactNames(imports);
        names.check("java");

        MvelExpression.compile("amount < java.lang.Integer.MAX_VALUE", imports);

        assertTrue(imports.classNameRoots().contains("java"));
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> names.check("java"));
        assertEquals("'java' cannot be used as a fact name: the rules use a class whose package starts with "
                + "'java', and MVEL would read the fact in the class's place", ex.getMessage());
    }

    @Test
    @DisplayName("a compiled copy, a run and any other lookup after the compile add nothing")
    void laterLookupsAddNothing() {
        Imports imports = imports();
        MvelExpression expression = MvelExpression.compile("amount > 1", imports);
        MvelSession session = new MvelSession();

        expression.newCompiled();
        expression.newCompiled();
        session.compiled(expression);
        load(imports.classLoader(), "java.lang.Integer");
        // Publishes what was recorded again.
        MvelExpression.compile("amount > 2", imports);

        assertFalse(imports.classNameRoots().contains("java"));
        new FactNames(imports).check("java");
    }

    @Test
    @DisplayName("a class with no package adds nothing")
    void noPackageAddsNothing() {
        ClassNameRoots roots = new ClassNameRoots();
        ClassLoader noPackage = new ClassLoader(null) {
            @Override
            public Class<?> loadClass(String name) {
                return String.class;
            }
        };
        ExactNameClassLoader loader = new ExactNameClassLoader(noPackage, roots);

        roots.recordWhile(() -> load(loader, "Order"));

        assertFalse(roots.contains("Order"));
    }

    @Test
    @DisplayName("what a compilation that throws looked up is published too")
    void publishedWhenTheCompileThrows() {
        ClassNameRoots roots = new ClassNameRoots();
        ExactNameClassLoader loader = new ExactNameClassLoader(ClassNameRootsTest.class.getClassLoader(), roots);
        IllegalStateException failure = new IllegalStateException("compile failed");

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> roots.recordWhile(() -> {
            load(loader, "java.lang.Integer");
            throw failure;
        }));

        assertSame(failure, thrown);
        assertTrue(roots.contains("java"));
    }

    /**
     * A class loader that records every name it is asked for, and finds a class as its parent does, or throws
     * {@code failure} for every name if it is given one.
     */
    private static final class RecordingLoader extends ClassLoader {

        final List<String> names = new ArrayList<>();
        private final Error failure;

        RecordingLoader(Error failure) {
            super(ClassNameRootsTest.class.getClassLoader());
            this.failure = failure;
        }

        @Override
        public Class<?> loadClass(String name) throws ClassNotFoundException {
            names.add(name);
            if (failure != null) {
                throw failure;
            }
            return super.loadClass(name);
        }
    }

    // Scans the text inside a compilation, with a class loader that asks the recording one for every name, and no
    // name imported.
    private static ClassNameRoots scanned(String text, RecordingLoader recording) {
        return scanned(text, recording, Set.of());
    }

    private static ClassNameRoots scanned(String text, RecordingLoader recording, Set<String> imported) {
        ClassNameRoots roots = new ClassNameRoots();
        ExactNameClassLoader loader = new ExactNameClassLoader(recording, roots);
        roots.recordWhile(() -> {
            roots.lookUpDottedNames(text, loader, imported::contains);
            return null;
        });
        return roots;
    }

    @Test
    @DisplayName("a dot or .? that ends a comment on the line before doesn't make a name a property")
    void scanReadsPastAComment() {
        RecordingLoader recording = new RecordingLoader(null);

        ClassNameRoots roots = scanned("// a.\njava.lang.Integer.MAX_VALUE + /* b.?*/java.lang.Long.MAX_VALUE",
                recording);

        assertTrue(roots.contains("java"));
        assertEquals(List.of("java.lang.Integer.MAX_VALUE", "java.lang.Integer"), recording.names);
    }

    @Test
    @DisplayName("a name after a dot and comments, as MVEL reads them, is a property, and isn't looked up")
    void scanReadsCommentsAfterADotAsWhitespace() {
        RecordingLoader recording = new RecordingLoader(null);

        scanned("a.\n// x\nb.c + d. /* x */ e.f + g./* x */?h.i + j. /* x */ // y\n /* z */ ?k.l + m.\r\n// x.\r\nn.o",
                recording);

        assertEquals(List.of(), recording.names);
    }

    @Test
    @DisplayName("a name after a comment, or a ? after one, that no dot comes before, is looked up")
    void scanLooksUpANameAfterAComment() {
        RecordingLoader recording = new RecordingLoader(null);

        scanned("/**/?a.b + c /* x. */ ?d.e", recording);

        assertEquals(List.of("a.b", "d.e"), recording.names);
    }

    @Test
    @DisplayName("a name whose first part is an imported class isn't looked up")
    void scanSkipsImportedClasses() {
        RecordingLoader recording = new RecordingLoader(null);

        scanned("Outer.FIELD + Math.PI + java.lang.Integer.MAX_VALUE", recording, Set.of("Outer", "Math"));

        assertEquals(List.of("java.lang.Integer.MAX_VALUE", "java.lang.Integer"), recording.names);
    }

    @Test
    @DisplayName("a name with dots is looked up whole, then without its last part, until a class is found")
    void scanFindsTheClass() {
        RecordingLoader recording = new RecordingLoader(null);

        ClassNameRoots roots = scanned("def f(x) { x }; f(java.lang.Integer.MAX_VALUE) + f(java.lang.Long.MAX_VALUE)",
                recording);

        assertTrue(roots.contains("java"));
        // The second name's first part is recorded already.
        assertEquals(List.of("java.lang.Integer.MAX_VALUE", "java.lang.Integer"), recording.names);
    }

    @Test
    @DisplayName("names in string literals and comments, and properties after a dot, aren't looked up")
    void scanSkipsLiteralsCommentsAndProperties() {
        RecordingLoader recording = new RecordingLoader(null);

        ClassNameRoots roots = scanned("'java.lang.Integer' + \"java.lang.Long\" // java.lang.Short\n"
                + "/*java.lang.Byte */ f().java.lang.Integer + g() . java.lang.Long + h.?java.lang.Short + 1.5 + x.",
                recording);

        assertFalse(roots.contains("java"));
        assertEquals(List.of(), recording.names);
    }

    @Test
    @DisplayName("a fact's property records nothing, and each name is looked up once")
    void scanOfAPropertyRecordsNothing() {
        RecordingLoader recording = new RecordingLoader(null);

        ClassNameRoots roots = scanned("?applicant.score.value > 1 ? applicant.score.value : x.1", recording);

        assertFalse(roots.contains("applicant"));
        assertEquals(List.of("applicant.score.value", "applicant.score"), recording.names);
    }

    @Test
    @DisplayName("of a long name, only the parts the class loader would look up are")
    void scanIsBounded() {
        RecordingLoader recording = new RecordingLoader(null);
        String manyParts = "a" + ".a".repeat(ExactNameClassLoader.MAX_NAME_PARTS + 10);
        String longParts = "b".repeat(600) + "." + "b".repeat(600) + "." + "b".repeat(600) + "." + "b".repeat(600);

        scanned(manyParts + " + " + longParts, recording);

        List<String> many = recording.names.stream().filter(name -> name.startsWith("a")).toList();
        assertEquals(ExactNameClassLoader.MAX_NAME_PARTS - 1, many.size());
        assertEquals(ExactNameClassLoader.MAX_NAME_PARTS, RuleText.dottedParts(many.get(0)));
        assertEquals(List.of(1802, 1201), recording.names.stream().filter(name -> name.startsWith("b"))
                .map(String::length).toList());
    }

    @Test
    @DisplayName("a class that can't be loaded records nothing, and shorter names are still looked up")
    void scanOfAnUnloadableClassRecordsNothing() {
        RecordingLoader recording = new RecordingLoader(new NoClassDefFoundError("a class it needs is missing"));

        ClassNameRoots roots = scanned("java.lang.Integer.MAX_VALUE", recording);

        assertFalse(roots.contains("java"));
        assertEquals(List.of("java.lang.Integer.MAX_VALUE", "java.lang.Integer", "java.lang"), recording.names);
    }
}
