package io.github.brantunger.unruly.mvel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.net.URLClassLoader;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("only the JDK's own class loaders, and a chain of plain URLClassLoaders, have a name looked up as a class"
        + " file before it is loaded (#701, #1130)")
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

    @Test
    @DisplayName("a chain of plain URLClassLoaders up to the JDK's own or the boot loader serves every class file, as"
            + " the JDK's own do, and no other class loader does (#1130)")
    void plainUrlLoadersServeEveryClassFile() throws Exception {
        ClassLoader app = ClassLoader.getSystemClassLoader();
        try (URLClassLoader plain = new URLClassLoader(new URL[0], app);
             URLClassLoader chain = new URLClassLoader(new URL[0], plain);
             URLClassLoader boot = new URLClassLoader(new URL[0], null);
             URLClassLoader platform = new URLClassLoader(new URL[0], ClassLoader.getPlatformClassLoader());
             // On JDK 21 to 23, a java.net.FactoryURLClassLoader; on JDK 24 and later, a URLClassLoader.
             URLClassLoader made = URLClassLoader.newInstance(new URL[0], app);
             URLClassLoader subclass = new URLClassLoader(new URL[0], app) {
             };
             URLClassLoader overSubclass = new URLClassLoader(new URL[0], subclass);
             URLClassLoader overOther = new URLClassLoader(new URL[0], new RecordingClassLoader())) {
            assertAll(
                    () -> assertTrue(Imports.servesEveryClassFile(app)),
                    () -> assertTrue(Imports.servesEveryClassFile(ClassLoader.getPlatformClassLoader())),
                    () -> assertTrue(Imports.servesEveryClassFile(plain)),
                    () -> assertTrue(Imports.servesEveryClassFile(chain)),
                    () -> assertTrue(Imports.servesEveryClassFile(boot)),
                    () -> assertTrue(Imports.servesEveryClassFile(platform)),
                    () -> assertTrue(Imports.servesEveryClassFile(made), made.getClass().getName()),
                    () -> assertFalse(Imports.servesEveryClassFile(subclass)),
                    () -> assertFalse(Imports.servesEveryClassFile(overSubclass)),
                    () -> assertFalse(Imports.servesEveryClassFile(overOther)),
                    () -> assertFalse(Imports.servesEveryClassFile(new RecordingClassLoader())),
                    () -> assertFalse(Imports.servesEveryClassFile(null)));
        }
    }
}
