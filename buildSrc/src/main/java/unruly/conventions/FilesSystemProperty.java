package unruly.conventions;

import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.process.CommandLineArgumentProvider;

import java.util.List;

/**
 * Passes files to a test JVM as a system property holding their paths. The files are an input of the task, compared by
 * their paths relative to their roots and their contents, and their absolute paths aren't, so the task's outputs can
 * still come from the build cache.
 */
public abstract class FilesSystemProperty implements CommandLineArgumentProvider {

    @Input
    public abstract Property<String> getName();

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getFiles();

    @Override
    public Iterable<String> asArguments() {
        return List.of("-D" + getName().get() + "=" + getFiles().getAsPath());
    }
}
