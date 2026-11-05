package io.github.brantunger.unruly.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Field;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntFunction;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * #1366: a compile context made with a package import above U+00FF checks the stack's room for building the JDK's
 * tables of the characters above U+00FF, with {@link StackHeadroom#checkInitializing()} through
 * {@link CharacterTables}, before {@code ImportSize.checkPackageName} asks one about such a code point, until they are
 * known to be built: by its own first such check, which builds every
 * plane's, or by the JVM's first engine build, which builds them after the same check. Then it checks no more, as a
 * language's tests may make many compile contexts. Each test tells it what it knows by its flag, or by running the
 * first build's initialization again, then walks back up from the end of a stack of its own, and at each depth checks
 * a package import's name above U+00FF and checks the room itself: while the tables aren't known to be built, no depth
 * may check the name without room for the check, and once they are, some depth must. {@code CaseTableFirstUseTest}
 * shows, in a new JVM, that the JVM's first read of each table comes after the check.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
@DisplayName("#1366: a package import's name above U+00FF checks the room until the character tables are known to be"
        + " built")
class ImportTablesRoomTest {

    private static final String WIDE = "com.\u03c0x";
    private static final String CHECKED = "checked only with room for the check";
    private static final String UNCHECKED = "checked without room for the check";
    private static final String NOT_FOUND = "never checked where the check overflowed";
    private static final ClassLoader LOADER = ImportTablesRoomTest.class.getClassLoader();
    /** Small, so the stack's end is near. */
    private static final long STACK_BYTES = 512 * 1024;
    /** How many checks are made at the top of the stack first, so the JIT has compiled the check before the walk. */
    private static final int WARM_UPS = 2_000;

    @AfterEach
    void tablesKnownBuilt() throws ReflectiveOperationException {
        // As every engine built in this JVM has left it.
        flag(CharacterTables.class, "built").setBoolean(null, true);
    }

    @Test
    @DisplayName("while the tables aren't known to be built, the name is checked only where the room check has room")
    void checksUntilKnownBuilt() throws ReflectiveOperationException, InterruptedException {
        warmUp();
        flag(CharacterTables.class, "built").setBoolean(null, false);
        assertEquals(CHECKED, walk(ImportTablesRoomTest::untilChecked));
    }

    @Test
    @DisplayName("once a name above U+00FF has been checked, the name is checked where the room check overflows")
    void noCheckOnceRead() throws ReflectiveOperationException, InterruptedException {
        warmUp();
        flag(CharacterTables.class, "built").setBoolean(null, false);
        assertDoesNotThrow(() -> new EngineCompileContext(Set.of(WIDE), Set.of(), LOADER));
        assertEquals(UNCHECKED, walk(ImportTablesRoomTest::untilCheckPasses));
    }

    @Test
    @DisplayName("once the first build has initialized its classes, the name is checked where the room check overflows")
    void noCheckOnceBuilt() throws ReflectiveOperationException, InterruptedException {
        warmUp();
        flag(CharacterTables.class, "built").setBoolean(null, false);
        // So the first build's initialization runs again, as the JVM's first build runs it. It sets the flag itself.
        flag(FirstUseClasses.class, "initialized").setBoolean(null, false);
        FirstUseClasses.initialize();
        assertEquals(UNCHECKED, walk(ImportTablesRoomTest::untilCheckPasses));
    }

    private static Field flag(Class<?> type, String name) throws NoSuchFieldException {
        Field flag = type.getDeclaredField(name);
        flag.setAccessible(true);
        return flag;
    }

    private static void warmUp() {
        for (int i = 0; i < WARM_UPS; i++) {
            StackHeadroom.checkInitializing();
        }
    }

    // Runs the walk on a thread with a small stack of its own, and returns what it found.
    private static Object walk(IntFunction<String> walk) throws InterruptedException {
        AtomicReference<Object> outcome = new AtomicReference<>();
        Thread thread = new Thread(null, () -> {
            try {
                outcome.set(walk.apply(0));
            } catch (Throwable t) {
                outcome.set(t);
            }
        }, "import-tables-walk", STACK_BYTES);
        thread.start();
        thread.join();
        return outcome.get();
    }

    // Recurses to the end of the stack, then on the way back checks the name at each depth until it is checked, and
    // checks the room where it was: the name must not have been checked without it. Concatenates no string, so it
    // links nothing near the end.
    private static String untilChecked(int depth) {
        try {
            String found = untilChecked(depth + 1);
            if (found != null) {
                return found;
            }
        } catch (StackOverflowError end) {
            return null;
        }
        if (!checked()) {
            return null;
        }
        try {
            StackHeadroom.checkInitializing();
        } catch (StackOverflowError tooShallow) {
            return UNCHECKED;
        }
        return CHECKED;
    }

    // Recurses to the end of the stack, then on the way back checks the room at each depth until the check passes,
    // and checks the name where it doesn't: it must be checked at some such depth.
    private static String untilCheckPasses(int depth) {
        try {
            String found = untilCheckPasses(depth + 1);
            if (found != null) {
                return found;
            }
        } catch (StackOverflowError end) {
            return null;
        }
        try {
            StackHeadroom.checkInitializing();
        } catch (StackOverflowError tooShallow) {
            return checked() ? UNCHECKED : null;
        }
        return NOT_FOUND;
    }

    private static boolean checked() {
        try {
            new EngineCompileContext(Set.of(WIDE), Set.of(), LOADER);
            return true;
        } catch (StackOverflowError tooDeep) {
            return false;
        }
    }
}
