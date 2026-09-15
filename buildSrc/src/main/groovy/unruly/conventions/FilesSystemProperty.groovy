package unruly.conventions

import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.process.CommandLineArgumentProvider

/**
 * Passes files to a test JVM as a system property holding their paths. The files are an input of the task, compared by
 * their paths relative to their roots and their contents, and their absolute paths aren't, so the task's outputs can
 * still come from the build cache.
 */
abstract class FilesSystemProperty implements CommandLineArgumentProvider {

    @Input
    abstract Property<String> getName()

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    abstract ConfigurableFileCollection getFiles()

    @Override
    Iterable<String> asArguments() {
        ["-D${getName().get()}=${getFiles().asPath}".toString()]
    }
}
