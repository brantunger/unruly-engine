package unruly.conventions

import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property

/** Configures the API compatibility check of one artifact. */
abstract class ApiCheckExtension {

    /** The artifact ID whose releases are the baseline: the project's own artifact. */
    abstract Property<String> getArtifactId()

    /**
     * Packages that are never compared: internal packages, and packages published in another artifact. A package
     * that neither the baseline jar nor the new jar has is reported, so a moved package's exclusion is deleted once
     * the baseline has moved on; a name with a wildcard isn't checked.
     */
    abstract ListProperty<String> getExcludedPackages()

    /**
     * For an artifact without a release yet: the dependency notation, with a version range, of the releases its classes
     * were published in before. Its newest release in the range is the baseline until the artifact has a release,
     * after which the check reports that this setting can go.
     */
    abstract Property<String> getPredecessor()

    /** The packages of the predecessor that aren't in this artifact, checked like {@link #getExcludedPackages()}. */
    abstract ListProperty<String> getPredecessorExcludedPackages()

    /**
     * For a new artifact without a predecessor: the version of its first release. While it has no release and the
     * build's version is no higher than this, the check is skipped; after that, a missing baseline fails the build.
     */
    abstract Property<String> getFirstRelease()
}
