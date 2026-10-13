package io.github.brantunger.unruly.mvel;

import io.github.brantunger.unruly.ChildJvm;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * With a plugin's or a tenant's {@code URLClassLoader} as the thread's context class loader, every name MVEL tried
 * reached the JDK's application class loader, which asks first, and kept a lock object there for the life of the JVM
 * (#1130). A plain {@code URLClassLoader} serves the class file of every class it loads, so a name without one is now
 * refused before it is asked, as with the JDK's own loaders. With any other class loader, such as a subclass, a name in
 * a {@code java.} package that the JDK serves no class file for is refused too.
 */
@DisplayName("a name MVEL tries that isn't a class leaves no lock object with a URLClassLoader as context (#1130)")
class UrlContextLockTest {

    private static final Pattern CASE = Pattern.compile(Pattern.quote(UrlContextLockScenario.MESSAGE)
            + "(\\w+) locked \\+\\d+, no class file \\+(\\d+), java\\. \\+(\\d+)");

    @Test
    @DisplayName("a plain URLClassLoader leaves none, and a subclass of one none in a java. package")
    void urlLoadersKeepNoLock(@TempDir Path dir) throws Exception {
        String output = ChildJvm.run(dir, UrlContextLockScenario.class,
                "--add-opens", "java.base/java.lang=ALL-UNNAMED");

        // Each case's count of names locked that have no class file, and of those in a java. package.
        Map<String, String> counts = output.lines().map(CASE::matcher).filter(Matcher::lookingAt)
                .collect(Collectors.toMap(each -> each.group(1), each -> each.group(2) + " " + each.group(3)));
        String subclass = counts.getOrDefault("subclass", "");
        assertAll("scenario output:\n" + output,
                () -> assertEquals("0 0", counts.get("app")),
                () -> assertEquals("0 0", counts.get("url")),
                () -> assertTrue(subclass.endsWith(" 0"), subclass));
    }
}
