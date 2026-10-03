package unruly.conventions;

import org.gradle.api.GradleException;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Reads the file that lists the members the published test kit links against, and leaves out the members an accepted
 * break names. A class rather than script functions, so the configuration cache never sees a reference to the build
 * script.
 */
public final class LinkageFile {

    /** A member as japicmp names it, such as {@code a.b.Foo#Foo(java.util.Map,int)} or {@code a.b.Foo#name}. */
    private static final Pattern MEMBER = Pattern.compile("[\\w.$]+#[\\w$]+(\\([\\w.$\\[\\],]*\\))?");

    private LinkageFile() {
    }

    /**
     * Parses the file. A blank line, or one starting with {@code #}, is skipped; every other line is a member.
     *
     * @param text The file's contents
     * @param name The file's path, relative to the root project, for the error message
     * @return The members, in the file's order
     * @throws GradleException naming the line, if a line isn't a member
     */
    public static List<String> parse(String text, String name) {
        List<String> entries = new ArrayList<>();
        List<String> lines = text.lines().toList();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (!MEMBER.matcher(line).matches()) {
                throw new GradleException(name + ":" + (i + 1) + ": expected a member as japicmp names it, such as "
                        + "'io.github.brantunger.unruly.core.EngineEvaluationContext#EngineEvaluationContext("
                        + "java.util.Map,java.time.Instant)', got: " + line);
            }
            entries.add(line);
        }
        return entries;
    }

    /**
     * Leaves out the members that an accepted element names, by the member, its class or its package.
     *
     * @param entries  The members
     * @param accepted The accepted elements, without their spaces
     * @return The members left, in their order
     */
    public static List<String> notAccepted(Collection<String> entries, Set<String> accepted) {
        List<String> kept = new ArrayList<>();
        for (String entry : entries) {
            String owner = entry.substring(0, entry.indexOf('#'));
            if (!(accepted.contains(entry) || accepted.contains(owner)
                    || accepted.contains(owner.substring(0, owner.lastIndexOf('.'))))) {
                kept.add(entry);
            }
        }
        return kept;
    }
}
