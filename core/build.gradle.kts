plugins {
    id("unruly.library")
    // core's tests share fixtures with mvel's and test-kit's: the tests that need MVEL or the test kit are there, and
    // all three source sets use the same engines, log capture, test languages and junit-platform.properties.
    `java-test-fixtures`
}

// The engine without an expression language. The mvel project publishes unruly-engine, which adds MVEL.
val artifact = "unruly-engine-core"

base {
    archivesName = artifact
}

dependencies {
    implementation(libs.slf4j.api)
    // The public API's nullness annotations. An api dependency, so users' compilers, Kotlin and IDEs can read them.
    api(libs.jspecify)

    // EngineLogs asserts with JUnit, and both source sets' tests use it. An api dependency, because Executable
    // is in its signatures. The fixtures aren't published, so this adds nothing to unruly-engine-core.
    testFixturesApi(platform(libs.junit.bom))
    testFixturesApi("org.junit.jupiter:junit-jupiter-api")

    testImplementation(platform(libs.junit.bom))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly(libs.slf4j.simple)
}

mavenPublishing {
    coordinates(project.group.toString(), artifact, project.version.toString())

    pom {
        name = artifact
        description = "Unruly is a pure Java rules engine with pluggable expression languages. This is the engine " +
                "without a language; unruly-engine adds MVEL"
    }
}

apiCheck {
    artifactId = artifact
    // core is internal: the module exports it only to the test kit's module, and a class in it is public only where
    // the builder or the test kit needs it.
    excludedPackages = listOf("io.github.brantunger.unruly.core")
    // Except the members the published test kit links against: an older kit can run on a newer core, so
    // japicmpTestKitLinkage checks the ones this file lists.
    testKitLinkage = layout.settingsDirectory.file("config/japicmp/test-kit-linkage.txt")
}

// StackEndSweepTest overflows its thread's stack thousands of times, at every depth near the end. HotSpot throws a
// StackOverflowError once a Java frame has less stack left than its shadow zone, the room it keeps for the JVM's own
// code and native code below that frame; code there that needs more runs through the guard pages, and the JVM dies
// with nothing to catch. The zone is 20 pages on every 64-bit platform except Windows on x64, where it is 8, and there
// the test JVM died in CI with STATUS_STACK_OVERFLOW (0xC00000FD). 20 pages gives Windows the room the others have.
// The sweep measures from where the stack ends, so it still reaches the same last depths.
tasks.named<Test>("test") {
    jvmArgs("-XX:StackShadowPages=20")
}

// The Javadoc documents the API only. core's public classes exist for the API package and the test kit, not users.
tasks.named<Javadoc>("javadoc") {
    exclude("io/github/brantunger/unruly/core/**")
    // Lets javadoc find the excluded classes that the documented ones use.
    (options as StandardJavadocDocletOptions).addPathOption("-source-path").value = listOf(file("src/main/java"))
}

// java-test-fixtures adds the fixtures to components.java, which unruly.library publishes, so unruly-engine-core
// would gain -test-fixtures.jar and -test-fixtures-sources.jar. The fixtures are for this build's own tests, and the
// released artifact set is fixed, so all three variants are left out of the publication.
val javaComponent = components["java"] as AdhocComponentWithVariants
javaComponent.withVariantsFromConfiguration(configurations["testFixturesApiElements"]) { skip() }
javaComponent.withVariantsFromConfiguration(configurations["testFixturesRuntimeElements"]) { skip() }
javaComponent.withVariantsFromConfiguration(configurations["testFixturesSourcesElements"]) { skip() }

// mvel applies extra-java-module-info, which fails on any jar that doesn't name a module, and the fixtures jar has
// no module-info: its classes sit in core's packages, and it is only ever on a class path. A name in the manifest
// satisfies the check and leaves the jar as it is. It is never published, so the name is not an API.
tasks.named<Jar>("testFixturesJar") {
    manifest {
        attributes("Automatic-Module-Name" to "io.github.brantunger.unruly.testfixtures")
    }
}
