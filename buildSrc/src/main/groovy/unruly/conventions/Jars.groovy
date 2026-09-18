package unruly.conventions

import java.util.zip.ZipFile

/** Reads what the API check needs from jars. A class rather than a script closure, so a task can keep a reference. */
final class Jars {

    private Jars() {
    }

    /**
     * Lists the packages that have a class in any of the jars, as {@code io.github.brantunger.unruly.api}. Entries
     * under {@code META-INF/}, such as a multi-release jar's versioned classes, aren't packages of their own.
     *
     * @param jars The jars
     * @return The package names
     */
    static Set<String> packagesIn(Iterable<File> jars) {
        Set<String> packages = [] as Set
        jars.each { jar ->
            new ZipFile(jar).withCloseable { zip ->
                zip.entries().each { entry ->
                    String name = entry.name
                    if (name.endsWith('.class') && name.contains('/') && !name.startsWith('META-INF/')) {
                        packages << name.substring(0, name.lastIndexOf('/')).replace('/', '.')
                    }
                }
            }
        }
        packages
    }
}
