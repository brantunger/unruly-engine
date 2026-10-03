package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.LinkageMissingRegistration;
import org.graalvm.nativeimage.MissingReflectionRegistrationError;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * How the engine tells the error a native image built with strict reachability metadata throws for a name it has no
 * metadata for: by its class's name alone. The tests' stand-in for that error has the name. What each lookup does with
 * it is {@code StrictMetadataImportTest}'s, {@code RunClassesTest}'s and mvel's {@code LanguageDiscoveryTest}'s to
 * check.
 */
@DisplayName("only GraalVM's MissingReflectionRegistrationError, told by its class's name, is a name an image has no"
        + " metadata for (#951)")
class MissingRegistrationTest {

    @Test
    @DisplayName("the image's error is recognized, and no other error is")
    void missingRegistrationRecognized() {
        assertTrue(ImportResolver.isMissingRegistration(new MissingReflectionRegistrationError("p.A")));
        assertTrue(ImportResolver.isMissingRegistration(LinkageMissingRegistration.of("p.A")));
        assertFalse(ImportResolver.isMissingRegistration(new Error("not the image's")));
        assertFalse(ImportResolver.isMissingRegistration(new StackOverflowError()));
        assertFalse(ImportResolver.isMissingRegistration(new NoClassDefFoundError("p/A")));
    }
}
