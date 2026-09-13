package io.github.brantunger.unruly;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CI runs the build once per JDK in its matrix with {@code -PtestJdk}. This confirms the tests actually run on that
 * JDK rather than on the Java 17 compile toolchain.
 */
@DisplayName("test JDK")
class TestJdkTest {

    @Test
    @DisplayName("tests run on the JDK the build was asked to test with")
    void runsOnRequestedJdk() {
        String requested = System.getProperty("unruly.test.jdk");

        assertNotNull(requested, "build.gradle sets unruly.test.jdk for the test task");
        assertEquals(Integer.parseInt(requested), Runtime.version().feature());
    }
}
