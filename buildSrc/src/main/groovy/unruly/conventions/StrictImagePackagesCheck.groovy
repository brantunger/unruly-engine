package unruly.conventions

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

import java.util.zip.ZipFile

/**
 * Fails unless the packages the CI workflow's strict native image build lists are exactly the packages of the
 * library's classes in the image. GraalVM for JDK 21 matches each listed package by its whole name, not as a prefix,
 * so a package missing from the list would have its lookups go unchecked, and the strict build would still pass. It
 * reads the list from the {@code packages=} lines of the workflow's step that passes
 * {@code -H:ThrowMissingRegistrationErrors}, which build its value, and the packages from the jars of this build's
 * projects that the image includes.
 */
@CacheableTask
abstract class StrictImagePackagesCheck extends DefaultTask {

    // A package's name: dot-separated Java identifiers, without the spaces, quotes or $ of a form this can't read.
    private static final String PACKAGE_NAME = /[A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z_][A-Za-z0-9_]*)*/

    /** The CI workflow, {@code .github/workflows/ci.yml}. */
    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    abstract RegularFileProperty getWorkflow()

    /** The jars of this build's projects that the native image includes. */
    @Classpath
    abstract ConfigurableFileCollection getLibraries()

    /** Written when the check passes, so Gradle can skip it until the workflow or the jars change. */
    @OutputFile
    abstract RegularFileProperty getResult()

    @TaskAction
    void check() {
        def expected = new TreeSet<String>()
        libraries.files.each { jar ->
            new ZipFile(jar).withCloseable { zip ->
                zip.entries().each { entry ->
                    def name = entry.name
                    if (name.endsWith('.class') && !name.startsWith('META-INF/') && name.contains('/')) {
                        expected << name.substring(0, name.lastIndexOf('/')).replace('/', '.')
                    }
                }
            }
        }
        // An empty set would pass only an empty list: the jars weren't found.
        if (expected.isEmpty()) {
            throw new GradleException('No class found in the libraries the native image includes, so the strict ' +
                    'image package check would check nothing. Check how native-smoke/build.gradle.kts finds them.')
        }
        def listed = listedPackages()
        def missing = expected - listed
        def extra = listed - expected
        if (missing || extra) {
            def message = new StringBuilder('The packages=... lines of the strict native image step in ' +
                    '.github/workflows/ci.yml must list every package of the library\'s classes in the image, and ' +
                    'nothing else: GraalVM for JDK 21 checks only a package it is given by its whole name.')
            if (missing) {
                message << "\nIn the image but not listed:\n  ${missing.join('\n  ')}"
            }
            if (extra) {
                message << "\nListed but not in the image:\n  ${extra.join('\n  ')}"
            }
            throw new GradleException(message.toString())
        }
        result.get().asFile.text = expected.join('\n') + '\n'
    }

    /**
     * Reads the packages the workflow lists, from the one step whose script has
     * {@code -H:ThrowMissingRegistrationErrors=}: each step runs in a shell of its own, so a {@code packages=} line in
     * another step doesn't change the list. The step's script must be a literal block, {@code run: |}, and its
     * {@code packages=} lines must come before that option. The first sets the list, and each later one must add to it
     * with a leading {@code $packages,}, the only forms this reads as the shell does. Any other form, such as a later
     * line without it, which would start the list again, fails the check, as does a name that isn't a package's, a
     * folded script, {@code run: >}, or a {@code packages=} line after the option, before the next named step.
     */
    private SortedSet<String> listedPackages() {
        def steps = [[]]
        workflow.get().asFile.getText('UTF-8').readLines().each { line ->
            if (line ==~ /\s*- name:.*/) {
                steps << []
            }
            steps.last() << line.trim()
        }
        def strict = steps.findAll { step -> step.any { it.contains('-H:ThrowMissingRegistrationErrors=') } }
        if (strict.size() != 1) {
            throw new GradleException("${strict.size()} steps in .github/workflows/ci.yml pass " +
                    '-H:ThrowMissingRegistrationErrors, so the strict image package check can\'t tell which list ' +
                    'to read. It reads the packages=... lines of the one step that does.')
        }
        def step = strict.first()
        if (!(step.find { it.startsWith('run:') } ==~ /run: \|-?/)) {
            throw new GradleException('The strict native image step of .github/workflows/ci.yml must run its script ' +
                    'as a literal block, run: |, which keeps each packages=... line a line of its own: a folded one, ' +
                    'run: >, joins them into one.')
        }
        def flag = step.findIndexOf { it.contains('-H:ThrowMissingRegistrationErrors=') }
        if (step.drop(flag + 1).any { it.startsWith('packages=') }) {
            throw new GradleException('The strict native image step of .github/workflows/ci.yml has a packages=... ' +
                    'line after -H:ThrowMissingRegistrationErrors, which can\'t change the list native-image is given.')
        }
        def lines = step.take(flag).findAll { it.startsWith('packages=') }
        if (lines.isEmpty() || !step.any { it.contains('-H:ThrowMissingRegistrationErrors=$packages') }) {
            throw new GradleException('No packages=... lines, or no -H:ThrowMissingRegistrationErrors=$packages, in ' +
                    'the strict native image step of .github/workflows/ci.yml, so the strict image package check ' +
                    'can\'t read the list.')
        }
        def listed = new TreeSet<String>()
        lines.eachWithIndex { line, i ->
            def value = line.substring('packages='.length())
            if (i > 0) {
                if (!value.startsWith('$packages,')) {
                    throw new GradleException("The strict native image step of .github/workflows/ci.yml has " +
                            "'${line}', which doesn't start with packages=\$packages, as every packages=... line " +
                            'after the first must, to add to the list rather than start it again.')
                }
                value = value.substring('$packages,'.length())
            }
            value.split(',', -1).each { name ->
                if (!(name ==~ PACKAGE_NAME)) {
                    throw new GradleException("The strict native image step of .github/workflows/ci.yml has " +
                            "'${line}', in which '${name}' isn't a package name.")
                }
                listed << name
            }
        }
        listed
    }
}
