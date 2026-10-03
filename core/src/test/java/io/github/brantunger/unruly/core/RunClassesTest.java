package io.github.brantunger.unruly.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.invoke.MethodHandles;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * #911: how {@code RunClasses} initializes what a lookup can't reach, what isn't there, and what it can only name in a
 * native image. Which classes it initializes is {@code FirstRunClassInitializationTest}'s to check.
 */
@DisplayName("RunClasses initializes a class a lookup can't reach by name, skips one that isn't there, and names none"
        + " in a native image (#911, #951)")
class RunClassesTest {

    private static final String IMAGE_CODE = "org.graalvm.nativeimage.imagecode";

    // Set by Unreachable's static initializer.
    private static final AtomicBoolean INITIALIZED = new AtomicBoolean();

    // Set by UnreachableInImage's static initializer.
    private static final AtomicBoolean UNREACHABLE_IN_IMAGE_INITIALIZED = new AtomicBoolean();

    // Set by Reachable's, Named's and NamedWhileBuilding's static initializers.
    private static final AtomicBoolean REACHABLE_INITIALIZED = new AtomicBoolean();
    private static final AtomicBoolean NAMED_INITIALIZED = new AtomicBoolean();
    private static final AtomicBoolean NAMED_WHILE_BUILDING_INITIALIZED = new AtomicBoolean();

    // Not public, so a public lookup can't reach it.
    private static final class Unreachable {
        static {
            INITIALIZED.set(true);
        }
    }

    // Not public, as Unreachable isn't, and only ever given to RunClasses in a native image.
    private static final class UnreachableInImage {
        static {
            UNREACHABLE_IN_IMAGE_INITIALIZED.set(true);
        }
    }

    // In this package, so this package's lookup reaches it.
    private static final class Reachable {
        static {
            REACHABLE_INITIALIZED.set(true);
        }
    }

    // Only ever named, so nothing but RunClasses initializes it.
    private static final class Named {
        static {
            NAMED_INITIALIZED.set(true);
        }
    }

    // Only ever named, as Named is, by the check of an image being built.
    private static final class NamedWhileBuilding {
        static {
            NAMED_WHILE_BUILDING_INITIALIZED.set(true);
        }
    }

    @Test
    @DisplayName("a class the lookup can't reach is initialized by name, and a name no class has is skipped")
    void unreachableAndMissing() {
        RunClasses.initialize(MethodHandles.publicLookup(), List.of(Unreachable.class),
                List.of(RunClassesTest.class.getName() + "$Missing"));

        assertTrue(INITIALIZED.get());
    }

    @Test
    @DisplayName("in a native image, the classes are initialized through the lookup and none is looked up by name")
    void nativeImage() {
        System.setProperty(IMAGE_CODE, "runtime");
        try {
            RunClasses.initialize(MethodHandles.lookup(), List.of(Reachable.class), List.of(Named.class.getName()));
        } finally {
            System.clearProperty(IMAGE_CODE);
        }

        assertTrue(REACHABLE_INITIALIZED.get());
        assertFalse(NAMED_INITIALIZED.get(), "a class named in a native image was initialized");
    }

    // #951: naming the class is a lookup by name too, which an image built with strict reachability metadata fails.
    @Test
    @DisplayName("in a native image, a class the lookup can't reach isn't initialized by name either (#951)")
    void nativeImageUnreachable() {
        System.setProperty(IMAGE_CODE, "runtime");
        try {
            RunClasses.initialize(MethodHandles.publicLookup(), List.of(UnreachableInImage.class), List.of());
        } finally {
            System.clearProperty(IMAGE_CODE);
        }

        assertFalse(UNREACHABLE_IN_IMAGE_INITIALIZED.get(),
                "a class the lookup can't reach was named in a native image");
    }

    @Test
    @DisplayName("while a native image is built, the classes are still initialized by name")
    void nativeImageBuild() {
        System.setProperty(IMAGE_CODE, "buildtime");
        try {
            RunClasses.initialize(MethodHandles.lookup(), List.of(), List.of(NamedWhileBuilding.class.getName()));
        } finally {
            System.clearProperty(IMAGE_CODE);
        }

        assertTrue(NAMED_WHILE_BUILDING_INITIALIZED.get());
    }
}
