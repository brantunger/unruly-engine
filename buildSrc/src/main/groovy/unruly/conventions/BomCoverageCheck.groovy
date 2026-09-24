package unruly.conventions

import groovy.xml.XmlSlurper
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Fails unless the BOM's POM manages exactly the artifacts the build publishes, each at the version it is published
 * with. It reads the generated POM, which is what a Maven or Gradle user imports, rather than the build script's
 * constraints.
 */
@CacheableTask
abstract class BomCoverageCheck extends DefaultTask {

    /** The BOM's generated POM. */
    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    abstract RegularFileProperty getPom()

    /**
     * Every artifact the build publishes, other than the BOM, as {@code group:artifactId:version}. The build derives
     * it from the projects that apply the publishing plugin, so a new published project is expected without anyone
     * listing it.
     */
    @Input
    abstract SetProperty<String> getPublished()

    /** Written when the check passes, so Gradle can skip it until the POM or the published artifacts change. */
    @OutputFile
    abstract RegularFileProperty getResult()

    @TaskAction
    void check() {
        def expected = published.get() as SortedSet
        // An empty set would pass whatever the POM says: the plugin the projects are found by was renamed, or the
        // artifacts were read before their projects set them.
        if (expected.isEmpty()) {
            throw new GradleException('No published artifact found, so the BOM check would pass whatever the BOM ' +
                    'lists. Check how bom/build.gradle finds the published projects.')
        }
        def xml = new XmlSlurper().parse(pom.get().asFile)
        def managed = xml.dependencyManagement.dependencies.dependency.collect { dependency ->
            "${dependency.groupId.text()}:${dependency.artifactId.text()}:${dependency.version.text()}".toString()
        } as SortedSet

        def missing = expected - managed
        def extra = managed - expected
        if (missing || extra) {
            def message = new StringBuilder('The BOM must manage every published artifact and nothing else, at the ' +
                    'version it is published with. Add or fix a constraint in bom/build.gradle.')
            if (missing) {
                message << "\nPublished but not in the BOM:\n  ${missing.join('\n  ')}"
            }
            if (extra) {
                message << "\nIn the BOM but not published:\n  ${extra.join('\n  ')}"
            }
            throw new GradleException(message.toString())
        }
        result.get().asFile.text = expected.join('\n') + '\n'
    }
}
