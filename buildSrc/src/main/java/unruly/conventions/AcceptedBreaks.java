package unruly.conventions;

import org.gradle.api.GradleException;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads {@code config/japicmp/accepted-breaks.txt}. A class rather than a script function, so the configuration cache
 * never sees a reference to the build script.
 */
public final class AcceptedBreaks {

    /** The kinds of element a line can name. */
    private static final List<String> KINDS = List.of("package", "class", "method", "field");

    private AcceptedBreaks() {
    }

    /**
     * Parses the file. A blank line, or one starting with {@code #}, is skipped; every other line is
     * {@code <major version> | <kind> | <element> | <justification>}.
     *
     * @param text The file's contents
     * @return The accepted breaks, in the file's order
     * @throws GradleException naming the line, if a line isn't in that format
     */
    public static List<AcceptedBreak> parse(String text) {
        List<AcceptedBreak> entries = new ArrayList<>();
        List<String> lines = text.lines().toList();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] fields = line.split("\\|", -1);
            for (int f = 0; f < fields.length; f++) {
                fields[f] = fields[f].trim();
            }
            if (fields.length != 4 || List.of(fields).contains("") || !fields[0].matches("[1-9][0-9]*")
                    || !KINDS.contains(fields[1])) {
                throw new GradleException("config/japicmp/accepted-breaks.txt:" + (i + 1) + ": expected "
                        + "'<major version> | package, class, method or field | <element> | <justification>', "
                        + "got: " + line);
            }
            entries.add(new AcceptedBreak(Integer.parseInt(fields[0]), fields[1], fields[2]));
        }
        return entries;
    }
}
