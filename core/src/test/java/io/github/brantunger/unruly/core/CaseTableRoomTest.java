package io.github.brantunger.unruly.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Field;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * #1297: {@link Accessors} checks the stack's room for building the JDK's case table of the first plane, with
 * {@link StackHeadroom#checkInitializing()}, before it asks it about a character above U+00FF, until the table is known
 * to be built: by its own first such read, or by the JVM's first engine build, which builds it after the same check.
 * Then it checks no more, as the default output writer names a bean's setter on every write. Each test tells it what
 * it knows by its flag, or by running the first build's initialization again, then walks back up from the end of a
 * stack of its own, and at each depth makes a setter's name above U+00FF and checks the room itself: while the table
 * isn't known to be built, no depth may make the name without room for the check, and once it is, some depth must.
 * {@code CaseTableFirstUseTest} shows, in a new JVM, that the JVM's first read of the table comes after the check.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
@DisplayName("#1297: a setter's name above U+00FF checks the room until the case table is known to be built")
class CaseTableRoomTest {

    private static final String WIDE = "πx";
    private static final String CHECKED = "made only with room for the check";
    private static final String UNCHECKED = "made without room for the check";
    private static final String NOT_FOUND = "never made where the check overflowed";
    /** Small, so the stack's end is near. */
    private static final long STACK_BYTES = 512 * 1024;
    /** How many checks are made at the top of the stack first, so the JIT has compiled the check before the walk. */
    private static final int WARM_UPS = 2_000;

    @AfterEach
    void tableKnownBuilt() throws ReflectiveOperationException {
        // As every engine built in this JVM has left it.
        flag(Accessors.class, "caseTableBuilt").setBoolean(null, true);
    }

    @Test
    @DisplayName("while the table isn't known to be built, the name is made only where the check has room")
    void checksUntilKnownBuilt() throws ReflectiveOperationException, InterruptedException {
        warmUp();
        flag(Accessors.class, "caseTableBuilt").setBoolean(null, false);
        assertEquals(CHECKED, walk(CaseTableRoomTest::untilNamed));
    }

    @Test
    @DisplayName("once a name above U+00FF has been read, the name is made where the check overflows")
    void noCheckOnceRead() throws ReflectiveOperationException, InterruptedException {
        warmUp();
        flag(Accessors.class, "caseTableBuilt").setBoolean(null, false);
        assertEquals("setΠx", Accessors.setterName(WIDE));
        assertEquals(UNCHECKED, walk(CaseTableRoomTest::untilCheckPasses));
    }

    @Test
    @DisplayName("once the first build has initialized its classes, the name is made where the check overflows")
    void noCheckOnceBuilt() throws ReflectiveOperationException, InterruptedException {
        warmUp();
        flag(Accessors.class, "caseTableBuilt").setBoolean(null, false);
        // So the first build's initialization runs again, as the JVM's first build runs it. It sets the flag itself.
        flag(RunClasses.class, "initialized").setBoolean(null, false);
        RunClasses.initialize();
        assertEquals(UNCHECKED, walk(CaseTableRoomTest::untilCheckPasses));
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
        }, "case-table-walk", STACK_BYTES);
        thread.start();
        thread.join();
        return outcome.get();
    }

    // Recurses to the end of the stack, then on the way back makes the name at each depth until it is made, and
    // checks the room where it was: the name must not have been made without it. Concatenates no string, so it links
    // nothing near the end.
    private static String untilNamed(int depth) {
        try {
            String found = untilNamed(depth + 1);
            if (found != null) {
                return found;
            }
        } catch (StackOverflowError end) {
            return null;
        }
        if (!named()) {
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
    // and makes the name where it doesn't: it must be made at some such depth.
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
            return named() ? UNCHECKED : null;
        }
        return NOT_FOUND;
    }

    private static boolean named() {
        try {
            return Accessors.setterName(WIDE) != null;
        } catch (StackOverflowError tooDeep) {
            return false;
        }
    }
}
