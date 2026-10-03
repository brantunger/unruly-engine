package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.LinkageMissingRegistration;
import org.graalvm.nativeimage.MissingReflectionRegistrationError;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * How the engine tells the error a native image built with strict reachability metadata throws for a name it has no
 * metadata for: by its class's name alone. The tests' stand-in for that error has the name. What each lookup does with
 * it is {@code StrictMetadataImportTest}'s, {@code StrictMetadataSupertypeTest}'s, {@code StrictMetadataBridgeTest}'s,
 * {@code RunClassesTest}'s and mvel's {@code LanguageDiscoveryTest}'s to check.
 */
@DisplayName("only GraalVM's MissingReflectionRegistrationError, told by its class's name, is a name an image has no"
        + " metadata for (#951)")
class MissingRegistrationTest {

    @Test
    @DisplayName("the image's error is recognized, and no other error is")
    void missingRegistrationRecognized() {
        assertTrue(MissingRegistration.isMissingRegistration(new MissingReflectionRegistrationError("p.A")));
        assertTrue(MissingRegistration.isMissingRegistration(LinkageMissingRegistration.of("p.A")));
        assertFalse(MissingRegistration.isMissingRegistration(new Error("not the image's")));
        assertFalse(MissingRegistration.isMissingRegistration(new StackOverflowError()));
        assertFalse(MissingRegistration.isMissingRegistration(new NoClassDefFoundError("p/A")));
    }

    @Test
    @DisplayName("a public method is looked up as Class.getMethod finds it, and one there isn't is none")
    void publicMethodLookedUp() throws NoSuchMethodException {
        assertEquals(String.class.getMethod("length"), MissingRegistration.publicMethod(String.class, "length"));
        assertEquals(CharSequence.class.getMethod("charAt", int.class),
                MissingRegistration.publicMethod(CharSequence.class, "charAt", int.class));
        assertNull(MissingRegistration.publicMethod(String.class, "length", int.class));
        assertNull(MissingRegistration.publicMethod(String.class, "noSuchMethod"));
    }
}
