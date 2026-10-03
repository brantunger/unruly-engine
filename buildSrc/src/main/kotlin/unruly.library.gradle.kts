// Publishing, coverage and the API compatibility check, shared by the published artifacts.
import com.vanniktech.maven.publish.JavaLibrary
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.SourcesJar
import java.lang.module.ModuleDescriptor
import me.champeau.gradle.japicmp.JapicmpTask
import org.cyclonedx.gradle.CyclonedxDirectTask
import org.gradle.api.artifacts.result.DependencyResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.artifacts.result.UnresolvedDependencyResult
import unruly.conventions.AcceptedBreaks
import unruly.conventions.ApiBaseline
import unruly.conventions.ApiCheckExtension
import unruly.conventions.JapicmpReport
import unruly.conventions.Jars
import unruly.conventions.LinkageFile
import unruly.conventions.ReleasedKit

plugins {
    id("unruly.java-conventions")
    jacoco
    id("unruly.publishing")
    id("me.champeau.gradle.japicmp")
    id("org.cyclonedx.bom")
}

// JaCoCo's version is in gradle/libs.versions.toml, where Dependabot updates it.
val jacocoVersion = extensions.getByType<VersionCatalogsExtension>().named("libs").findVersion("jacoco").get()
    .requiredVersion

jacoco {
    toolVersion = jacocoVersion
}

// The tests are split between core, mvel and test-kit, and mvel's also cover the test kit, so the root project's
// jacocoTestReport and jacocoTestCoverageVerification cover the artifacts together. Each project's own tasks would
// see only its own classes, measured by its own tests.
tasks.named("jacocoTestReport") {
    enabled = false
}
tasks.named("jacocoTestCoverageVerification") {
    enabled = false
}

// The jars each library publishes: the classes, their sources and their Javadoc. unruly.publishing does the rest of
// the publishing setup, which the BOM shares.
mavenPublishing {
    configure(JavaLibrary(JavadocJar.Javadoc(), SourcesJar.Sources()))
}

// Apache-2.0 asks a redistributor to include the license (section 4(a)) and to pass on the NOTICE (section 4(d)),
// so every jar Central receives carries both under META-INF/: the main, sources and javadoc jar of each published
// project. Only they get them: benchmarks and native-smoke apply unruly.java-conventions, not this plugin, and are
// never distributed. withType covers the sources and javadoc jars, which the publishing plugin registers after this
// line. unruly.java-conventions stamps every archive entry with a fixed timestamp, these two included, so adding
// them keeps the jars as reproducible as they were; the note there says what that covers.
//
// The type has to be org.gradle.jvm.tasks.Jar, spelled out. A build script resolves a bare `Jar` to
// org.gradle.api.tasks.bundling.Jar, and plainJavadocJar is a com.vanniktech.maven.publish.tasks.JavadocJar, which
// extends the jvm one and not the bundling one. Matching on the bundling type silently skips the three javadoc
// jars, which are published like the rest.
val noticeFiles = listOf(rootProject.file("LICENSE"), rootProject.file("NOTICE"))
tasks.withType<org.gradle.jvm.tasks.Jar>().configureEach {
    from(noticeFiles) {
        into("META-INF")
    }
}

// API compatibility: every build compares the jar with the newest release on Maven Central that is no higher than the
// version in gradle.properties, and fails on a binary- or source-incompatible change to a public or protected
// member, so a minor or patch release can't break code compiled against an earlier one. Breaks intended for a major
// release are listed in config/japicmp/accepted-breaks.txt. Each project configures its check with apiCheck { }.
//
// The baseline is resolved in a detached configuration: in one of the project's own configurations, Gradle resolves
// the project's coordinates to the project itself, and the check would silently compare the jar with itself.
//
// The range, rather than latest.release, keeps the baseline from ever being higher than the build's own version, so
// a build is always checked against its own release line: a release PR's version isn't published yet, so its
// baseline is the release before it, and a re-run or a local build of an older tag is compared with the release
// before that tag rather than with whatever is newest.
val apiCheck = extensions.create<ApiCheckExtension>("apiCheck")

val projectGroup = project.group.toString()
val projectVersion = project.version.toString()
val refreshBaseline = providers.gradleProperty("apiCheck.refresh").isPresent

// The module descriptor carries the artifact's version, so `java --list-modules`, a jlink image and module-aware
// stack traces show which engine is running. javac accepts only a version ModuleDescriptor.Version parses, so a
// build with a version such as -Pversion=local compiles without one rather than failing.
val moduleVersion: Provider<String> = providers.provider {
    try {
        ModuleDescriptor.Version.parse(projectVersion)
        projectVersion
    } catch (e: IllegalArgumentException) {
        null
    }
}
tasks.withType<JavaCompile>().configureEach {
    options.javaModuleVersion = moduleVersion
}

fun newestRelease(notation: String): Pair<Configuration, DependencyResult> {
    val configuration = configurations.detachedConfiguration(dependencies.create("$notation@jar"))
    configuration.isTransitive = false
    // CI and the release workflow pass -PapiCheck.refresh, so they look the newest release up on every build. A local
    // build keeps the lookup for 24 hours, so it may compare with a release up to a day old; pass -PapiCheck.refresh
    // for the newest. Looking it up on every local build would stop the configuration cache from being reused,
    // because the cached lookup would always have expired.
    configuration.resolutionStrategy.cacheDynamicVersionsFor(if (refreshBaseline) 0 else 24, "hours")
    val dependency = configuration.incoming.resolutionResult.rootComponent.get().dependencies.first()
    return Pair(configuration, dependency)
}

// Whether the build's version, such as 2.0.1, is higher than another. ModuleDescriptor.Version orders the numbers of
// each part and puts a pre-release, such as 2.0.0-RC1, below its release. A build version it can't parse, such as
// -Pversion=local, isn't higher than anything; the other version is configured, so it must parse.
fun isHigher(version: String, other: String): Boolean {
    val parsedOther = ModuleDescriptor.Version.parse(other)
    return try {
        ModuleDescriptor.Version.parse(version) > parsedOther
    } catch (e: IllegalArgumentException) {
        false
    }
}

// The classpath of a baseline, resolved for the exact release chosen: the release itself and its dependencies, which
// is what its types are loaded against. The current runtime classpath would miss a dependency the baseline had and
// this version doesn't, and japicmp would then quietly leave out the members it can't load. The release's own jar is
// on it because japicmp loads an excluded package's classes from the classpath, not from the archive: without it,
// the 1.x jar's API classes were being loaded from this build's core jar.
fun baselineClasspath(dependency: ResolvedDependencyResult): Configuration {
    val release = dependency.selected.moduleVersion!!
    return configurations.detachedConfiguration(
        dependencies.create("${release.group}:${release.name}:${release.version}"))
}

// The baseline: the artifact's own newest release, or, while it has none, its predecessor's. Chosen once, when the
// check first needs it.
val apiBaseline: Provider<ApiBaseline> = objects.property<ApiBaseline>().apply {
    value(providers.provider {
        val artifact = apiCheck.artifactId.get()
        val (ownConfiguration, ownDependency) = newestRelease("$projectGroup:$artifact:(,$projectVersion]")
        if (ownDependency is ResolvedDependencyResult) {
            ApiBaseline(ownConfiguration, baselineClasspath(ownDependency), artifact,
                ownDependency.selected.moduleVersion!!.version, listOf(), "", true, false)
        } else if (apiCheck.firstRelease.isPresent && !isHigher(projectVersion, apiCheck.firstRelease.get())) {
            ApiBaseline(files(), files(), artifact, "0", listOf(), "$artifact is new in ${apiCheck.firstRelease.get()}",
                false, true)
        } else if (!apiCheck.predecessor.isPresent) {
            throw GradleException("Can't resolve the API baseline from Maven Central",
                (ownDependency as UnresolvedDependencyResult).failure)
        } else {
            val (predecessorConfiguration, predecessorDependency) = newestRelease(apiCheck.predecessor.get())
            if (predecessorDependency !is ResolvedDependencyResult) {
                throw GradleException("Can't resolve the API baseline from Maven Central: neither $artifact " +
                        "nor ${apiCheck.predecessor.get()} resolved",
                    (predecessorDependency as UnresolvedDependencyResult).failure)
            }
            val excludes = apiCheck.predecessorExcludedPackages.get()
            val release = predecessorDependency.selected.moduleVersion!!
            ApiBaseline(predecessorConfiguration, baselineClasspath(predecessorDependency), release.module.name,
                release.version, excludes,
                ", without ${excludes.joinToString(", ")}, because $artifact has no release up to " + projectVersion,
                false, false)
        }
    })
    finalizeValueOnRead()
    disallowChanges()
}
val apiBaselineMajor = apiBaseline.map { baseline -> baseline.version.split('.').first { it.isNotEmpty() }.toInt() }

// Each accepted break is an AcceptedBreak: major, kind and element. The file's header describes the format.
val acceptedBreaks = providers.fileContents(layout.settingsDirectory.file("config/japicmp/accepted-breaks.txt"))
    .asText
    .map { text -> AcceptedBreaks.parse(text) }

val japicmp = tasks.register<JapicmpTask>("japicmp") {
    val apiBaseline = apiBaseline
    val apiBaselineMajor = apiBaselineMajor
    val acceptedBreaks = acceptedBreaks
    group = "verification"
    description = "Checks the public API against the newest release on Maven Central up to this version."
    oldArchives.from(apiBaseline.map { baseline -> baseline.archives })
    oldClasspath.from(apiBaseline.map { baseline -> baseline.classpath })
    newArchives.from(tasks.named("jar"))
    newClasspath.from(configurations.runtimeClasspath)
    accessModifier = "protected"
    onlyModified = true
    failOnModification = true
    failOnSourceIncompatibility = true

    // A line applies while the baseline is from an earlier major version than the one the break ships in.
    val applicable = acceptedBreaks.zip(apiBaselineMajor) { entries, major ->
        entries.filter { it.major > major }
    }
    val excluded = apiCheck.excludedPackages.zip(apiBaseline) { packages, baseline -> packages + baseline.excludes }
    packageExcludes = excluded.zip(applicable) { packages, entries ->
        packages + entries.filter { it.kind == "package" }.map { it.element }
    }
    classExcludes = applicable.map { entries -> entries.filter { it.kind == "class" }.map { it.element } }
    methodExcludes = applicable.map { entries -> entries.filter { it.kind == "method" }.map { it.element } }
    fieldExcludes = applicable.map { entries -> entries.filter { it.kind == "field" }.map { it.element } }
    val ignored = acceptedBreaks.zip(apiBaselineMajor) { entries, major ->
        entries.count { it.major <= major }
    }
    // Like the accepted breaks, an exclusion that no longer applies is reported rather than kept for ever: a package
    // excluded because it moved to another artifact is dead once neither the baseline nor the new jar has it, and a
    // predecessor is dead once the artifact has a release of its own. A wildcard exclusion isn't checked.
    val configuredExcludes = excluded.map { packages -> packages.filter { !it.contains('*') } }
    val predecessorConfigured = apiCheck.predecessor.map { true }.orElse(false)
    val buildFileName = rootProject.relativePath(project.buildFile).replace(File.separatorChar, '/')

    htmlOutputFile = layout.buildDirectory.file("reports/japicmp/report.html")
    txtOutputFile = layout.buildDirectory.file("reports/japicmp/report.txt")

    // The lambdas log through the task, not the build script, so the configuration cache can store them.
    onlyIf("the artifact has a release to compare with") { task ->
        val baseline = apiBaseline.get()
        if (baseline.skip) {
            task.logger.lifecycle("Skipping the API check: ${baseline.note}, and has no release to compare with")
        }
        !baseline.skip
    }

    doFirst {
        val task = this as JapicmpTask
        val baseline = apiBaseline.get()
        task.logger.lifecycle("Comparing the API with ${baseline.name} ${baseline.version}${baseline.note}")
        if (ignored.get() > 0) {
            task.logger.warn("${ignored.get()} line(s) in config/japicmp/accepted-breaks.txt are for a major version " +
                    "that is already released, so they no longer apply against ${apiBaselineMajor.get()}.x. " +
                    "Delete them.")
        }
        val present = Jars.packagesIn(task.oldArchives.files + task.newArchives.files)
        val dead = configuredExcludes.get().filter { it !in present }
        if (dead.isNotEmpty()) {
            task.logger.warn("$buildFileName excludes ${dead.joinToString(", ")} from the API check, but " +
                    "neither ${baseline.name} ${baseline.version} nor this jar has that package. Delete the " +
                    "exclusion, or fix its spelling.")
        }
        if (predecessorConfigured.get() && baseline.ownRelease) {
            task.logger.warn("$buildFileName names a predecessor for the API check, but " +
                    "${baseline.name} ${baseline.version} is the artifact's own release. Delete predecessor and " +
                    "predecessorExcludedPackages.")
        }
    }
}

tasks.named("check") {
    dependsOn(japicmp)
}

// The members of an internal package that the published test kit links against, such as the core constructors
// LanguageTestContexts calls. A user's build can pair an older test kit with a newer core, so a change to one of them
// breaks that kit with a NoSuchMethodError, although the package is left out of the check above. This second check
// compares only them: japicmp applies excludes before includes, so the check above can't include them, and including
// them there would narrow it to them. The project names the file in apiCheck { testKitLinkage = ... }; its header
// describes the format.
//
// The file lists what this version's kit uses. The members the kit released at the baseline's version uses, read from
// its jar, are checked too, so deleting a line from the file doesn't stop the check on a member that kit calls. Kits
// older than that one aren't read.
afterEvaluate {
    if (!apiCheck.testKitLinkage.isPresent) {
        return@afterEvaluate
    }
    val linkageFile = apiCheck.testKitLinkage.get().asFile
    val linkageName = rootProject.relativePath(linkageFile).replace(File.separatorChar, '/')
    val buildFileName = rootProject.relativePath(project.buildFile).replace(File.separatorChar, '/')
    if (!linkageFile.isFile()) {
        throw GradleException("$buildFileName names $linkageName as apiCheck.testKitLinkage, " +
                "but there is no such file")
    }
    val linkedMembers = providers.fileContents(apiCheck.testKitLinkage).asText.map { text ->
        LinkageFile.parse(text, linkageName)
    }

    // The members of the packages the check above leaves out that the released kit uses. The kit is released with
    // the same version as this artifact, so it is resolved at the baseline's version, and read once.
    val testKitArtifact = "unruly-engine-test"
    val releasedKitMembers: Provider<ReleasedKit> = objects.property<ReleasedKit>().apply {
        value(apiBaseline.zip(apiCheck.excludedPackages) { baseline, packages ->
            if (baseline.skip) {
                ReleasedKit(testKitArtifact, baseline.version, listOf())
            } else {
                val notation = "$projectGroup:$testKitArtifact:${baseline.version}@jar"
                val kit = configurations.detachedConfiguration(dependencies.create(notation))
                kit.isTransitive = false
                val jar = try {
                    kit.singleFile
                } catch (e: Exception) {
                    throw GradleException("Can't resolve $notation from Maven Central, the released test kit " +
                            "whose links into ${baseline.name} the linkage check compares", e)
                }
                // The kit was compiled against the baseline, so its references are resolved against it.
                ReleasedKit(testKitArtifact, baseline.version,
                    Jars.membersUsed(jar, packages, baseline.archives.files).toList())
            }
        })
        finalizeValueOnRead()
        disallowChanges()
    }

    // The members compared: those the file lists and those the released kit uses, except any that an accepted break
    // applying against the baseline names, by the member, its class or its package. An intended break is accepted as
    // in the check above, by a line in config/japicmp/accepted-breaks.txt. It is left out of the includes rather than
    // excluded: japicmp keeps a class whose included member is excluded, and reports it as removed from the jar that
    // no longer has the member.
    // An element is compared without its spaces, as the check above trims it: the linkage file's names have none.
    val acceptedElements = acceptedBreaks.zip(apiBaselineMajor) { entries, major ->
        entries.filter { it.major > major }.map { it.element.replace(Regex("\\s+"), "") }.toSet()
    }
    val listedMembers = linkedMembers.zip(acceptedElements) { entries, accepted ->
        LinkageFile.notAccepted(entries, accepted)
    }
    val checkedMembers = linkedMembers.zip(releasedKitMembers) { entries, kit -> (entries + kit.members).distinct() }
        .zip(acceptedElements) { entries, accepted -> LinkageFile.notAccepted(entries, accepted) }

    val linkage = tasks.register<JapicmpTask>("japicmpTestKitLinkage") {
        val apiBaseline = apiBaseline
        group = "verification"
        description = "Checks the members the published test kit links against with the newest release on Maven " +
                "Central up to this version."
        oldArchives.from(apiBaseline.map { baseline -> baseline.archives })
        oldClasspath.from(apiBaseline.map { baseline -> baseline.classpath })
        newArchives.from(tasks.named("jar"))
        // The jar too: the linked members' classes implement the API's interfaces, which japicmp loads from here.
        newClasspath.from(configurations.runtimeClasspath, tasks.named("jar"))
        accessModifier = "public"
        onlyModified = false
        failOnModification = true
        failOnSourceIncompatibility = true
        // japicmp compares every member of a kind it has no include for, so a kind with none of the members gets one
        // that matches nothing.
        methodIncludes = checkedMembers.map { entries ->
            entries.filter { it.contains('(') }.ifEmpty { listOf("none.None#none()") }
        }
        fieldIncludes = checkedMembers.map { entries ->
            entries.filter { !it.contains('(') }.ifEmpty { listOf("none.None#none") }
        }

        htmlOutputFile = layout.buildDirectory.file("reports/japicmp/test-kit-linkage.html")
        txtOutputFile = layout.buildDirectory.file("reports/japicmp/test-kit-linkage.txt")

        onlyIf("the artifact has a release to compare with") { task ->
            val baseline = apiBaseline.get()
            if (baseline.skip) {
                task.logger.lifecycle("Skipping the test kit's linkage check: ${baseline.note}, and has no release " +
                        "to compare with")
            }
            !baseline.skip
        }
        // Without an include, japicmp would compare every public member of the jar.
        onlyIf("a linked member is left to compare") { task ->
            if (checkedMembers.get().isEmpty()) {
                task.logger.lifecycle("Skipping the test kit's linkage check: $linkageName lists no member that " +
                        "config/japicmp/accepted-breaks.txt doesn't accept a break to")
            }
            !checkedMembers.get().isEmpty()
        }

        doFirst {
            val task = this as JapicmpTask
            val baseline = apiBaseline.get()
            val kit = releasedKitMembers.get()
            task.logger.lifecycle("Comparing the members the test kit links against, as $linkageName lists them " +
                    "and as ${kit.name} ${kit.version} uses them, with ${baseline.name} ${baseline.version}" +
                    "${baseline.note}")
        }

        // japicmp compares a line that matches no member with nothing, and passes. The report lists every member it
        // compared, changed or not, because onlyModified is off, so a line that isn't in it fails the check: a typo,
        // or a member that neither the baseline nor this jar has. The released kit's members aren't looked for: it
        // was compiled against the baseline, so each of them is there.
        doLast {
            val task = this as JapicmpTask
            val unmatched = JapicmpReport.unlisted(task.txtOutputFile.get().asFile.readLines(), listedMembers.get())
            if (unmatched.isNotEmpty()) {
                throw GradleException("$linkageName lists members that neither ${apiBaseline.get().name} " +
                        "${apiBaseline.get().version} nor this jar has, so nothing checks them. Fix their " +
                        "spelling, or delete them:\n  ${unmatched.joinToString("\n  ")}")
            }
        }
    }

    tasks.named("check") {
        dependsOn(linkage)
    }
}

// A CycloneDX SBOM of each published artifact, which the release workflow attaches to the GitHub Release and covers
// with the same provenance attestation as the jars. It lists what a user's build resolves for the artifact, so only
// runtimeClasspath: the plugin's default takes every configuration, Checkstyle's, PMD's and the tests' included. The
// file is named after the artifact, like its jar, because the three projects' default bom.json would collide as
// assets of one Release. JSON only; the XML copy would say the same thing.
//
// It isn't part of the publication, so Central gets no SBOM: an entry for another of this build's modules names the
// Gradle project rather than the artifact, as in pkg:maven/io.github.brantunger/core@<version>, and the serial
// number and timestamp differ on every run, so the file isn't reproducible like the jars. `assemble` builds it, so
// every build checks that the plugin still works; the three take well under a second.
val bom = tasks.named<CyclonedxDirectTask>("cyclonedxDirectBom") {
    val projectVersion = projectVersion
    includeConfigs = listOf("runtimeClasspath")
    componentName = base.archivesName
    jsonOutput = layout.buildDirectory.file(base.archivesName.map { name ->
        "reports/cyclonedx-direct/$name-$projectVersion.cdx.json"
    })
    xmlOutput.unsetConvention()
}

tasks.named("assemble") {
    dependsOn(bom)
}
