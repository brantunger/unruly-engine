package unruly.conventions;

import java.util.List;

/**
 * The test kit released at the baseline's version, and the members of the packages the API check leaves out that it
 * links against.
 *
 * @param name    The kit's artifact ID
 * @param version The kit's version
 * @param members The members, named as japicmp names them; none when the check is skipped
 */
public record ReleasedKit(String name, String version, List<String> members) {
}
