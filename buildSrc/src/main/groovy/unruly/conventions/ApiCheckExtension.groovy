package unruly.conventions

import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property

/** Configures the API compatibility check of one artifact. */
abstract class ApiCheckExtension {

    /** The artifact ID whose releases are the baseline: the project's own artifact. */
    abstract Property<String> getArtifactId()

    /** Packages that are never compared: internal packages, and packages published in another artifact. */
    abstract ListProperty<String> getExcludedPackages()

    /**
     * For an artifact without a release yet: the dependency notation, with a version range, of the releases its classes
     * were published in before. Its newest release in the range is the baseline until the artifact has a release.
     */
    abstract Property<String> getPredecessor()

    /** The packages of the predecessor that aren't in this artifact. */
    abstract ListProperty<String> getPredecessorExcludedPackages()
}
