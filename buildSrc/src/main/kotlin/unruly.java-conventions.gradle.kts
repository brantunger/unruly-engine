// Compilation, tests and static analysis, shared by every project.
import java.time.Duration

plugins {
    `java-library`
    checkstyle
    pmd
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

// The tools' versions are in gradle/libs.versions.toml, where Dependabot updates them.
val toolVersions = extensions.getByType<VersionCatalogsExtension>().named("libs")

checkstyle {
    // 14.1.0 parses module-info.java; 13.7.0 and earlier can't. It needs Java 21, which the toolchain provides.
    toolVersion = toolVersions.findVersion("checkstyle").get().requiredVersion
    isIgnoreFailures = false
    maxWarnings = 0
}

pmd {
    toolVersion = toolVersions.findVersion("pmd").get().requiredVersion
    isIgnoreFailures = false
    // Main sources only, by decision. On the test sources this rule set finds 2,885 violations (mvel 1,603, core
    // 1,257, core's test fixtures 8, benchmarks 17; measured at 0367bb2), 2,576 of them an assertion without a
    // message, a test with several assertions, or a resource a test doesn't close.
    sourceSets = listOf(project.sourceSets["main"])
    ruleSets = listOf()
    ruleSetConfig = resources.text.fromFile(layout.settingsDirectory.file("config/pmd/ruleset.xml"))
}

// Compilation always uses the Java 21 toolchain. The tests run on the JDK given by -PtestJdk (default 21), so
// CI can run them on each JDK in its matrix; otherwise the test task would use the 21 toolchain every time.
val testJdk = providers.gradleProperty("testJdk").getOrElse("21")

// Every javac warning fails the build, in main and test sources.
tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

// The main sources build strings with StringBuilder, not invokedynamic: on JDK 25 and later, a concatenation whose
// first link overflows the stack fails for good, so one first used deep in a stack could break the engine (#965). It is
// a hidden javac option, which a javac that drops it ignores without a word; StringConcatenationTest catches that.
tasks.named<JavaCompile>("compileJava") {
    options.compilerArgs.add("-XDstringConcat=inline")
}

// Every jar, and any other archive, gets its entries in a stable order and with a fixed timestamp, so two builds of
// the same sources, on the same JDK build, produce byte-identical files. The JDK build is part of that because javac
// records the java.base it compiled against, patch level included, in module-info.class: of the nine published jars
// the three carrying a module descriptor differ between two JDK 21 patch levels in that one entry, and no Gradle
// setting changes it. The lines below change nothing today — Gradle 9 already writes archives that way, with every
// entry stamped 1980-02-01 — they only state the intent, so a later change to those defaults can't quietly make the
// published jars vary. They don't stop a project setting either property back.
tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

// Every Javadoc warning fails the build too: a missing comment, @param or @return, or a broken link or HTML.
tasks.withType<Javadoc>().configureEach {
    (options as CoreJavadocOptions).addBooleanOption("Xdoclint:all", true)
    (options as CoreJavadocOptions).addBooleanOption("Werror", true)
}

// Part of `check`, so `./gradlew build` finds a broken doc comment locally, not first in CI.
tasks.named("check") {
    dependsOn(tasks.named("javadoc"))
}

tasks.named<Test>("test") {
    // Ten minutes, for a test that never returns. The whole suite runs in about a minute, so this never fires on a
    // healthy build; when it does fire, Gradle stops the test worker and fails the task, which a CI job cancelled by
    // its own timeout never does. A task stopped this way writes no JUnit XML at all — measured, not assumed — so
    // what names the test that hung is the HTML report, which does get written.
    timeout = Duration.ofMinutes(10)
    javaLauncher = javaToolchains.launcherFor {
        languageVersion = JavaLanguageVersion.of(testJdk)
    }
    useJUnitPlatform {
        includeEngines("junit-jupiter")
    }
    setSystemProperties(mapOf(
        // Lets a test confirm it really runs on the requested JDK.
        "unruly.test.jdk" to testJdk,
        // LoggingRuleListener logs at DEBUG; enabled so its messages can be asserted.
        "org.slf4j.simpleLogger.log.io.github.brantunger.unruly.api.LoggingRuleListener" to "debug",
        // The engine logs a listener's stack trace at DEBUG; enabled so it can be asserted.
        "org.slf4j.simpleLogger.log.io.github.brantunger.unruly.engine" to "debug",
    ))
}
