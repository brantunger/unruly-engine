package io.github.brantunger.unruly.mvel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.net.URLClassLoader;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("only the JDK's own class loaders have a name looked up as a class file before it is loaded (#701)")
class JdkLoaderTest {

    @Test
    @DisplayName("the application's and the platform class loader are the JDK's own, and no other is")
    void jdkLoadersRecognised() throws Exception {
        try (URLClassLoader urls = new URLClassLoader(new URL[0])) {
            assertAll(
                    () -> assertTrue(Imports.isJdkLoader(ClassLoader.getSystemClassLoader())),
                    () -> assertTrue(Imports.isJdkLoader(ClassLoader.getPlatformClassLoader())),
                    () -> assertFalse(Imports.isJdkLoader(urls)),
                    () -> assertFalse(Imports.isJdkLoader(new RecordingClassLoader())),
                    () -> assertFalse(Imports.isJdkLoader(null)));
        }
    }
}
