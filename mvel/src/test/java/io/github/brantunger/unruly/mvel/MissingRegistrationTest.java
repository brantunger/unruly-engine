package io.github.brantunger.unruly.mvel;

import io.github.brantunger.unruly.LinkageMissingRegistration;
import org.graalvm.nativeimage.MissingReflectionRegistrationError;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * How the MVEL language tells the error a native image built with strict reachability metadata throws for a name it
 * has no metadata for: by its class's name alone, in its own copy of core's check. The tests' stand-in for that error
 * has the name. What each lookup does with it is {@code StrictMetadataLookupTest}'s and
 * {@code NestedClassImportTest}'s to check.
 */
@DisplayName("only GraalVM's MissingReflectionRegistrationError, told by its class's name, is a name an image has no"
        + " metadata for (#951)")
class MissingRegistrationTest {

    @Test
    @DisplayName("the image's error is recognized, and no other error is")
    void missingRegistrationRecognized() {
        assertTrue(ExactNameClassLoader.isMissingRegistration(new MissingReflectionRegistrationError("p.A")));
        assertTrue(ExactNameClassLoader.isMissingRegistration(LinkageMissingRegistration.of("p.A")));
        assertFalse(ExactNameClassLoader.isMissingRegistration(new Error("not the image's")));
        assertFalse(ExactNameClassLoader.isMissingRegistration(new StackOverflowError()));
        assertFalse(ExactNameClassLoader.isMissingRegistration(new NoClassDefFoundError("p/A")));
    }
}
