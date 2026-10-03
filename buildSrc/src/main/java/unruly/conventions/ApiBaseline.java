package unruly.conventions;

import org.gradle.api.file.FileCollection;

import java.util.List;

/**
 * The release the API check compares a jar with: the artifact's own newest release, its predecessor's, or none yet.
 *
 * @param archives   The release's jar
 * @param classpath  The release's jar and its dependencies, which its types are loaded against
 * @param name       The release's artifact ID
 * @param version    The release's version, {@code 0} when there is none
 * @param excludes   The packages of a predecessor that aren't in this artifact, left out of the comparison
 * @param note       What the check's message adds after the release, such as why a predecessor is compared
 * @param ownRelease Whether the release is the artifact's own, not a predecessor's
 * @param skip       Whether the artifact has no release to compare with yet, so the check is skipped
 */
public record ApiBaseline(FileCollection archives, FileCollection classpath, String name, String version,
                          List<String> excludes, String note, boolean ownRelease, boolean skip) {
}
