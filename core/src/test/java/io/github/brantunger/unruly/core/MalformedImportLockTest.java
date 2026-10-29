package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.ChildJvm;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * #1272: a parallel-capable class loader, as the JDK's application class loader is, keeps a lock object for every name
 * it is asked to load, found or not, for as long as it lives, and hands the name to the platform class loader, which
 * does the same. {@code build()} looked an import up as a class, and each of its {@code $} forms, before it checked
 * that the import was a valid package name, so an import it rejected as malformed had left lock objects behind. It
 * checks the shape first now, so no form of a malformed import is looked up. {@link MalformedImportLockScenario}
 * reads the loaders' locks in a JVM of its own; a well-formed import that isn't a class, which {@code build()} accepts
 * as a package and looks up, shows the locks can be seen.
 */
@DisplayName("an import build() rejects as malformed leaves no lock object in the JDK's class loaders (#1272)")
class MalformedImportLockTest {

    @Test
    @DisplayName("no form of a malformed import is locked by build(), and each form of a well-formed one is")
    void malformedImportsLeaveNoLock(@TempDir Path dir) throws Exception {
        String output = ChildJvm.run(dir, MalformedImportLockScenario.class,
                "--add-opens", "java.base/java.lang=ALL-UNNAMED");

        List<String> rows = output.lines().filter(line -> line.startsWith(MalformedImportLockScenario.ROW)).toList();
        List<String> names = MalformedImportLockScenario.NAMES;
        assertEquals(names.size(), rows.size(), output);
        for (int i = 0; i < names.size(); i++) {
            String name = names.get(i);
            // Two loaders a form: the application's and the platform's.
            List<Boolean> none = Collections.nCopies(2 * MalformedImportLockScenario.forms(name).size(), false);
            List<Boolean> all = Collections.nCopies(none.size(), true);
            boolean control = i == names.size() - 1;
            String expected = MalformedImportLockScenario.ROW + i + (control ? " built" : " rejected") + " before "
                    + none + " after " + (control ? all : none);
            assertEquals(expected, rows.get(i),
                    "\"" + name + "\" " + MalformedImportLockScenario.forms(name) + ":\n" + output);
        }
    }
}
