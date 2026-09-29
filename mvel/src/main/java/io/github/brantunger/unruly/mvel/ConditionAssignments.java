package io.github.brantunger.unruly.mvel;

import org.jspecify.annotations.Nullable;

import java.util.Set;

/**
 * Finds assignments in a condition's source text, so
 * {@link io.github.brantunger.unruly.api.RulesEngine#load(java.util.List)} can reject them before the rule ever runs.
 *
 * <p>
 * The text is scanned instead of MVEL's compiled tree because MVEL compiles method arguments lazily, on first
 * evaluation, so {@code claim.check(claim.approved = true)} has no assignment node at compile time. String
 * literals and comments are skipped. The scan reports:
 * </p>
 * <ul>
 *     <li>{@code =} and compound assignments such as {@code +=} or {@code >>=}, but not {@code ==}, {@code !=},
 *     {@code <=}, {@code >=} or {@code ~=}</li>
 *     <li>{@code ++} and {@code --}</li>
 *     <li>the keywords {@code with}, {@code def} and {@code function}, which set properties or declare
 *     functions, and {@code import_static}, which declares the imported method as a variable</li>
 * </ul>
 *
 * <p>
 * It can't see a write made by calling a method, such as {@code claim.setApproved(true)}.
 * </p>
 */
final class ConditionAssignments {

    private static final String STATIC_IMPORT = "import_static";
    private static final Set<String> WRITE_KEYWORDS = Set.of("with", "def", "function", STATIC_IMPORT);
    private static final String OPERATOR_CHARS = "+-*/%&|^<>";

    /**
     * An assignment found in a condition.
     *
     * @param text     The operator or keyword, such as {@code +=} or {@code with}. A run of operator characters
     *                 longer than the longest operator, {@code >>>=}, is cut to its last 4, so the text is bounded
     * @param position Where it starts in the condition, as an index into its text counting from 0
     */
    record Write(String text, int position) {

        /**
         * Tells whether this is {@code import_static}, which needs its own explanation.
         *
         * @return {@code true} for {@code import_static}
         */
        boolean isStaticImport() {
            return STATIC_IMPORT.equals(text);
        }
    }

    private ConditionAssignments() {
    }

    /**
     * Finds the first assignment in a condition.
     *
     * @param condition The condition's source text
     * @return The assignment, or {@code null} if there is none
     */
    static @Nullable Write find(String condition) {
        int index = 0;
        while (index < condition.length()) {
            char ch = condition.charAt(index);
            int next = index + 1;
            @Nullable String found = null;
            int foundAt = index;
            switch (ch) {
                case '\'', '"' -> next = endOfLiteral(condition, index);
                case '/' -> next = endOfSlash(condition, index);
                case '=' -> {
                    if (isDoubled(condition, index)) {
                        next = index + 2;
                    } else if (isAssignment(condition, index)) {
                        found = operatorEndingAt(condition, index);
                        foundAt = index - found.length() + 1;
                    }
                }
                case '+', '-' -> {
                    if (isDoubled(condition, index)) {
                        found = condition.substring(index, index + 2);
                    }
                }
                default -> {
                    if (Character.isJavaIdentifierStart(ch)) {
                        next = endOfIdentifier(condition, index);
                        found = writeKeyword(condition, index, next);
                    }
                }
            }
            if (found != null) {
                return new Write(found, foundAt);
            }
            index = next;
        }
        return null;
    }

    /** An {@code =} not doubled is an assignment unless it ends {@code !=}, {@code ~=}, {@code <=} or {@code >=}. */
    private static boolean isAssignment(String text, int index) {
        char before = charAt(text, index - 1);
        return switch (before) {
            case '!', '~' -> false;
            // <= and >= compare; <<=, >>= and >>>= assign.
            case '<', '>' -> charAt(text, index - 2) == before;
            default -> true;
        };
    }

    private static boolean isDoubled(String text, int index) {
        return charAt(text, index + 1) == text.charAt(index);
    }

    private static String operatorEndingAt(String text, int index) {
        int start = index;
        // The longest assignment operator, >>>=, has 4 characters.
        while (index - start < 3 && OPERATOR_CHARS.indexOf(charAt(text, start - 1)) >= 0) {
            start--;
        }
        return text.substring(start, index + 1);
    }

    /**
     * Skips a string literal, whose quote starts it, and any escaped character in it.
     *
     * @param text  The text
     * @param start Where the literal's quote is
     * @return Where the literal ends, just after its closing quote, or past the end of the text if it has none
     */
    static int endOfLiteral(String text, int start) {
        char quote = text.charAt(start);
        int index = start + 1;
        while (index < text.length() && text.charAt(index) != quote) {
            index += text.charAt(index) == '\\' ? 2 : 1;
        }
        return index + 1;
    }

    /**
     * Skips a {@code //} or {@code /*} comment; a lone {@code /} is just division. A block comment ends at the first
     * {@code *}{@code /} from its opening {@code *}, as MVEL ends it, so {@code /*}{@code /} is a whole, empty
     * comment (#747).
     *
     * @param text  The text
     * @param index Where the {@code /} is
     * @return Where the comment ends, or just after a lone {@code /}
     */
    static int endOfSlash(String text, int index) {
        return switch (charAt(text, index + 1)) {
            case '/' -> endOf(text, "\n", index + 2);
            case '*' -> endOf(text, "*/", index + 1);
            default -> index + 1;
        };
    }

    private static int endOf(String text, String terminator, int from) {
        int found = text.indexOf(terminator, from);
        return found < 0 ? text.length() : found + terminator.length();
    }

    private static int endOfIdentifier(String text, int start) {
        int index = start + 1;
        while (index < text.length() && Character.isJavaIdentifierPart(text.charAt(index))) {
            index++;
        }
        return index;
    }

    /**
     * A keyword used as a member name, as in {@code claim.with} or {@code claim.?with}, is just a property. MVEL
     * allows whitespace, including a line break, between the dot and the name, and counts every character up to a space
     * as whitespace, a control character such as U+0001 too; what Java counts as whitespace is skipped as well.
     */
    private static @Nullable String writeKeyword(String text, int start, int end) {
        String word = text.substring(start, end);
        int before = start - 1;
        // Bounded by the start of the text, where charAt reads U+0000, which is up to a space too.
        while (before >= 0 && (text.charAt(before) <= ' ' || Character.isWhitespace(text.charAt(before)))) {
            before--;
        }
        boolean member = charAt(text, before) == '.'
                || charAt(text, before) == '?' && charAt(text, before - 1) == '.';
        return WRITE_KEYWORDS.contains(word) && !member ? word : null;
    }

    private static char charAt(String text, int index) {
        return index >= 0 && index < text.length() ? text.charAt(index) : Character.MIN_VALUE;
    }
}
