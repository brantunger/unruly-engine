package unruly.conventions

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Nested
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

    /** The source directory of each module, by module name. */
    @Input
    abstract MapProperty<String, String> getModuleSourcePaths()

    /** The modules' sources, as files, so a change reruns the task. */
    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    abstract ConfigurableFileCollection getSources()

    /** The libraries the modules require. */
    @Classpath
    abstract ConfigurableFileCollection getModulePath()

    @Input
    abstract Property<String> getTitle()

    @Nested
    abstract Property<JavadocTool> getJavadocTool()

    @OutputDirectory
    abstract DirectoryProperty getDestination()

    @Inject
    protected abstract ExecOperations getExecOperations()

    @TaskAction
    void generate() {
        File destination = getDestination().get().asFile
        destination.deleteDir()
        // A warning fails the task, like the projects' own Javadoc tasks.
        List<String> arguments = ['-d', destination.absolutePath, '-quiet', '-Xdoclint:all', '-Werror',
                                  '-encoding', 'UTF-8', '-docencoding', 'UTF-8', '-charset', 'UTF-8',
                                  '-doctitle', getTitle().get(), '-windowtitle', getTitle().get(),
                                  '--module', getModuleSourcePaths().get().keySet().join(','),
                                  '--module-path', getModulePath().asPath]
        getModuleSourcePaths().get().each { module, path ->
            arguments += ['--module-source-path', "${module}=${path}".toString()]
        }
        getExecOperations().exec { spec ->
            spec.executable = getJavadocTool().get().executablePath.asFile.absolutePath
            spec.args = arguments
        }
    }
}
