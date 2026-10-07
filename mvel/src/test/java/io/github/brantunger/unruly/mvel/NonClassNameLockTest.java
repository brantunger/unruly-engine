package io.github.brantunger.unruly.mvel;

import io.github.brantunger.unruly.ChildJvm;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MVEL looks up much that isn't a class: whole statements after an inline package import, and each prefix of a
 * property chain. A parallel-capable class loader, as the JDK's application class loader is, keeps a lock object for
 * every name it is asked for, for as long as it lives, so rules whose names or literals differ left more on every
 * load. The rule list's class loader refuses a name no class can have, and, with the JDK's own class loaders, a name
 * without a class file, before the application's class loader is asked (#752). MVEL doesn't ask the thread's context
 * class loader for it after, as that is the rule list's while MVEL compiles (#807).
 */
@DisplayName("a name MVEL tries that can't be a class leaves no lock object in the JDK's class loaders (#752)")
class NonClassNameLockTest {

    @Test
    @DisplayName("with the JDK's class loaders, neither an inline package import nor a property chain leaves one")
    void jdkLoadersKeepNoLock(@TempDir Path dir) throws Exception {
        String output = ChildJvm.run(dir, NonClassNameLockScenario.class,
                "--add-opens", "java.base/java.lang=ALL-UNNAMED");

        List<String> messages = output.lines().filter(line -> line.startsWith(NonClassNameLockScenario.MESSAGE))
                .map(line -> line.substring(NonClassNameLockScenario.MESSAGE.length()))
                .toList();
        // Each property MVEL reads through a value it types as Object is looked up as a class nested in Object,
        // java.lang.Object$p0 and so on. After the rule list's class loader refuses it, MVEL asks the thread's context
        // class loader for it, which is the rule list's own while MVEL compiles, so none is asked of the JDK's (#807).
        assertEquals(List.of("inline import +0 [], nested in Object +0", "literals +0 [], nested in Object +0",
                "f.p%d == 1 +0 [], nested in Object +0", "f.p%d.q == 1 +0 [], nested in Object +0",
                "f.p%d.q.r.s == 1 +0 [], nested in Object +0"), messages,
                "each case and the names it locked that have no class file; scenario output:\n" + output);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"java.util.output.put('k', x)", "java.util.List.of(amountDue)", "a b", "a-b", "a;b"})
    @DisplayName("a name no class can have isn't asked of any class loader")
    void nameNoClassCanHaveNotAsked(String name) {
        RecordingClassLoader parent = new RecordingClassLoader();
        ExactNameClassLoader loader = new ExactNameClassLoader(parent);

        assertThrows(ClassNotFoundException.class, () -> loader.loadClass(name));
        assertEquals(List.of(), parent.loadedClasses);
    }

    @Test
    @DisplayName("a name's characters are read one code point at a time, as a stream of its code points reads them, "
            + "lone surrogates included (#1103)")
    void binaryNameReadByCodePoint() {
        // U+1D465, a mathematical italic x, is a letter outside the Basic Multilingual Plane; U+1F600, an emoji, is
        // outside it too, and no identifier may have it.
        List<String> names = List.of("", ".", "java.util.Map$Entry", "a1.b2", "$", "\uD835\uDC65", "a\uD835\uDC65.b",
                "\uD835", "\uDC65", "a\uD835", "\uD835a", "\uDC65a", "\uD83D\uDE00", "a b", "a-b", "a'b");
        List<String> expected = new ArrayList<>();
        List<String> read = new ArrayList<>();
        for (String name : names) {
            expected.add(name + " " + name.codePoints().allMatch(c -> c == '.' || Character.isJavaIdentifierPart(c)));
            read.add(name + " " + ExactNameClassLoader.isBinaryName(name));
        }

        assertEquals(expected, read);
        assertEquals(List.of(true, true, true, true, true, true, true, false, false, false, false, false, false, false,
                false, false), read.stream().map(each -> each.endsWith("true")).toList());
    }

    @Test
    @DisplayName("a class loader of the application's own is asked for a well-formed name it serves no class file for")
    void otherLoaderAskedForWellFormedName() {
        RecordingClassLoader parent = new RecordingClassLoader();
        ExactNameClassLoader loader = new ExactNameClassLoader(parent);

        assertThrows(ClassNotFoundException.class, () -> loader.loadClass("java.util.java.util.ArrayList"));
        assertEquals(List.of("java.util.java.util.ArrayList"), parent.loadedClasses);
    }

    @Test
    @DisplayName("with the JDK's class loaders, a class with a class file, nested or not, is still found")
    void jdkLoaderFindsClasses() {
        ExactNameClassLoader loader = new ExactNameClassLoader(ClassLoader.getSystemClassLoader());

        assertAll(
                () -> assertEquals(Map.Entry.class, loader.loadClass("java.util.Map$Entry")),
                () -> assertEquals(NonClassNameLockTest.class, loader.loadClass(NonClassNameLockTest.class.getName())),
                () -> assertThrows(ClassNotFoundException.class, () -> loader.loadClass("java.util.Map.Entry")));
    }

    @Test
    @DisplayName("with the JDK's class loaders, a class loaded is remembered, apart from names with no class file")
    void jdkLoaderRemembersOnlyClasses() {
        ExactNameClassLoader loader = new ExactNameClassLoader(ClassLoader.getSystemClassLoader());

        assertAll(
                () -> assertEquals(String.class, loader.loadClass("java.lang.String")),
                () -> assertEquals(String.class, loader.loadClass("java.lang.String")),
                () -> assertThrows(ClassNotFoundException.class, () -> loader.loadClass("java.util.java.util.List")),
                () -> assertThrows(ClassNotFoundException.class, () -> loader.loadClass("java.util.Map.Entry")),
                () -> assertEquals(1, loader.cachedClasses()),
                () -> assertEquals(2, loader.cachedMisses()));
    }

    @Test
    @DisplayName("with the JDK's class loaders, a name with no class file is remembered, up to a bound and a length")
    void missesBounded() {
        ExactNameClassLoader loader = new ExactNameClassLoader(ClassLoader.getSystemClassLoader(), 2);
        String longName = "a".repeat(ExactNameClassLoader.MAX_CACHED_MISS_LENGTH + 1);

        assertAll(
                () -> assertThrows(ClassNotFoundException.class, () -> loader.loadClass(longName)),
                () -> assertEquals(0, loader.cachedMisses()),
                () -> assertThrows(ClassNotFoundException.class, () -> loader.loadClass("a.B")),
                () -> assertThrows(ClassNotFoundException.class, () -> loader.loadClass("a.B")),
                () -> assertThrows(ClassNotFoundException.class, () -> loader.loadClass("a.C")),
                () -> assertThrows(ClassNotFoundException.class, () -> loader.loadClass("a.D")),
                () -> assertEquals(2, loader.cachedMisses()),
                () -> assertEquals(255, ExactNameClassLoader.MAX_CACHED_MISS_LENGTH));
    }

    @Test
    @DisplayName("once as many classes as it may remember are remembered, a class loaded after is found, not kept")
    void cacheBounded() throws ClassNotFoundException {
        ExactNameClassLoader loader = new ExactNameClassLoader(ClassLoader.getSystemClassLoader(), 2);

        loader.loadClass("java.lang.String");
        loader.loadClass("java.lang.Integer");

        assertEquals(Long.class, loader.loadClass("java.lang.Long"));
        assertEquals(2, loader.cachedClasses());
    }

    @Test
    @DisplayName("a class loader of the application's own isn't remembered for, as its class files aren't looked up")
    void otherLoaderRemembersNothing() throws ClassNotFoundException {
        ExactNameClassLoader loader = new ExactNameClassLoader(new RecordingClassLoader());

        assertEquals(String.class, loader.loadClass("java.lang.String"));
        assertEquals(0, loader.cachedClasses());
    }

    @Test
    @DisplayName("a class file found, or found missing, is looked up once, when looked up first")
    void classFileLookedUpOnce() throws ClassNotFoundException {
        RecordingClassLoader parent = new RecordingClassLoader();
        ExactNameClassLoader loader = new ExactNameClassLoader(parent, ExactNameClassLoader.MAX_CACHED_CLASSES, true);

        assertEquals(String.class, loader.loadClass("java.lang.String"));
        assertEquals(String.class, loader.loadClass("java.lang.String"));
        assertThrows(ClassNotFoundException.class, () -> loader.loadClass("a.B"));
        assertThrows(ClassNotFoundException.class, () -> loader.loadClass("a.B"));

        assertEquals(List.of("java/lang/String.class", "a/B.class"), parent.resources);
        assertEquals(List.of("java.lang.String", "java.lang.String"), parent.loadedClasses);
    }

    @Test
    @DisplayName("it remembers as many classes as the fact-name cache remembers misses")
    void cacheBoundIsFactNamesBound() {
        assertEquals(FactNames.MAX_CACHED_MISSES, ExactNameClassLoader.MAX_CACHED_CLASSES);
    }
}
