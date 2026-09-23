package unruly.conventions

import java.util.regex.Pattern

/**
 * Reads which members a japicmp text report compared. A class rather than a script closure, so a task can keep a
 * reference.
 */
final class JapicmpReport {

    /**
     * A class, interface, enum or annotation, as {@code ***! MODIFIED CLASS: PUBLIC NON_FINAL (<- FINAL) a.b.Foo
     * (not serializable)}: its name (group 1) is the last word before the closing serialization status.
     */
    private static final Pattern TYPE =
            ~/^\S+\s+\S+\s+(?:CLASS|INTERFACE|ENUM|ANNOTATION)\b.*\s([\w.$]+)\s+\([^()]*\)\s*$/

    /** A constructor, method or field of the type above it, indented: its declaration (group 1). */
    private static final Pattern MEMBER = ~/^\s+\S+\s+\S+\s+(?:CONSTRUCTOR|METHOD|FIELD): (.*)$/

    private JapicmpReport() {
    }

    /**
     * Finds the members that a report doesn't list. A member is named as japicmp's includes name it, such as
     * {@code a.b.Foo#Foo(java.util.Map,int)} or {@code a.b.Foo#name}. The report writes a member as its declaration,
     * such as {@code PUBLIC(+) Foo(java.util.Map<java.lang.String,java.lang.Object>, int)}, with a change written after
     * the new value, as {@code NON_FINAL (<- FINAL)}. So the changes and the generics are taken out, then the spaces
     * between parameters, and the member's name and parameters are looked for after a space.
     *
     * @param report  The lines of the report
     * @param members The members
     * @return The members the report doesn't list, in their order
     */
    static List<String> unlisted(List<String> report, Collection<String> members) {
        Map<String, List<String>> declarations = [:].withDefault { [] }
        String type = null
        report.each { line ->
            def typeLine = TYPE.matcher(line)
            def memberLine = MEMBER.matcher(line)
            if (typeLine.find()) {
                type = typeLine.group(1)
            } else if (memberLine.find()) {
                declarations[type] << declaration(memberLine.group(1))
            }
        }
        members.findAll { member ->
            int hash = member.indexOf('#')
            String signature = member.substring(hash + 1)
            Pattern pattern = ~("(^|\\s)${Pattern.quote(signature)}" + (signature.contains('(') ? '' : '(\\s|$)'))
            !declarations[member.substring(0, hash)].any { pattern.matcher(it).find() }
        }
    }

    /**
     * Takes a declaration's changes and generics out, until none are left: a change can hold generics, and generics
     * can nest.
     */
    static String declaration(String text) {
        String previous = null
        String current = text
        while (current != previous) {
            previous = current
            current = current.replaceAll(/\s*\(<-[^()]*\)/, '').replaceAll(/<[^<>]*>/, '')
        }
        current.replace(', ', ',')
    }
}
