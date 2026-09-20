package unruly.conventions

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.Directory
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.ProjectLayout
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Nested
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.jvm.toolchain.JavadocTool
import org.gradle.process.ExecOperations

import javax.inject.Inject

/**
 * Generates one Javadoc site for several modules. The javadoc tool gets the modules rather than source files, so it
 * documents only the packages they export, and each module gets its own directory. Gradle's own Javadoc task passes
 * source files, which makes javadoc document every package.
 */
@CacheableTask
abstract class ModuleJavadoc extends DefaultTask {

    /**
     * The source directory of each module, by module name, as a path relative to {@link #getRoot()}. Relative, so
     * the build-cache key holds no absolute path and an entry is reused from another checkout directory, and a
     * map, so the key changes when a directory is given to another module.
     */
    @Input
    abstract MapProperty<String, String> getModuleSourcePaths()

    /** The directory the source paths are relative to: the settings directory, unless set. */
    @Internal
    abstract DirectoryProperty getRoot()

    /** The modules' sources, as files, so a change reruns the task. Derived from the paths. */
    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    abstract ConfigurableFileCollection getSources()

    /** The libraries the modules require. */
    @Classpath
    abstract ConfigurableFileCollection getModulePath()

    @Input
    abstract Property<String> getTitle()

    /** The site's front page, an HTML file javadoc gets with {@code -overview}. Without it, the front page lists the modules. */
    @InputFile
    @PathSensitive(PathSensitivity.RELATIVE)
    @Optional
    abstract RegularFileProperty getOverview()

    @Nested
    abstract Property<JavadocTool> getJavadocTool()

    @OutputDirectory
    abstract DirectoryProperty getDestination()

    @Inject
    protected abstract ExecOperations getExecOperations()

    @Inject
    protected abstract ProjectLayout getLayout()

    ModuleJavadoc() {
        getRoot().convention(getLayout().settingsDirectory)
        getSources().from(getModuleSourcePaths().map { paths -> paths.values().collect { getRoot().dir(it).get() } })
    }

    @TaskAction
    void generate() {
        File destination = getDestination().get().asFile
        destination.deleteDir()
        Map<String, String> modules = getModuleSourcePaths().get()
        Directory root = getRoot().get()
        // A warning fails the task, like the projects' own Javadoc tasks. -notimestamp leaves the generation date
        // out of every page, so rebuilding the site from the same sources produces the same files.
        List<String> arguments = ['-d', destination.absolutePath, '-quiet', '-Xdoclint:all', '-Werror',
                                  '-notimestamp',
                                  '-encoding', 'UTF-8', '-docencoding', 'UTF-8', '-charset', 'UTF-8',
                                  '-doctitle', getTitle().get(), '-windowtitle', getTitle().get(),
                                  '--module', modules.keySet().join(','),
                                  '--module-path', getModulePath().asPath]
        // The absolute paths are resolved here, at execution, where they don't reach the cache key.
        modules.each { module, path ->
            arguments += ['--module-source-path', "${module}=${root.dir(path).asFile.absolutePath}".toString()]
        }
        if (getOverview().isPresent()) {
            arguments += ['-overview', getOverview().get().asFile.absolutePath]
        }
        getExecOperations().exec { spec ->
            spec.executable = getJavadocTool().get().executablePath.asFile.absolutePath
            spec.args = arguments
        }
    }
}
