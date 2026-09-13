package io.github.brantunger.unruly.mvel;

import java.util.Set;

/**
 * Finds assignments in a condition's source text, so {@link io.github.brantunger.unruly.api.RulesEngine#setRuleList(java.util.List)} can
 * reject them before the rule ever runs.
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
     * @param text     The operator or keyword, such as {@code +=} or {@code with}
     * @param position Where it starts in the condition
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

        /** Describes the assignment, such as {@code "'+=' at position 13"}. */
        @Override
        public String toString() {
            return "'" + text + "' at position " + position;
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
    static Write find(String condition) {
        int index = 0;
        while (index < condition.length()) {
            char ch = condition.charAt(index);
            int next = index + 1;
            String found = null;
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
        while (OPERATOR_CHARS.indexOf(charAt(text, start - 1)) >= 0) {
            start--;
        }
        return text.substring(start, index + 1);
    }

    private static int endOfLiteral(String text, int start) {
        char quote = text.charAt(start);
        int index = start + 1;
        while (index < text.length() && text.charAt(index) != quote) {
            index += text.charAt(index) == '\\' ? 2 : 1;
        }
        return index + 1;
    }

    /** Skips a {@code //} or {@code /*} comment; a lone {@code /} is just division. */
    private static int endOfSlash(String text, int index) {
        return switch (charAt(text, index + 1)) {
            case '/' -> endOf(text, "\n", index + 2);
            case '*' -> endOf(text, "*/", index + 2);
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
     * allows whitespace, including a line break, between the dot and the name.
     */
    private static String writeKeyword(String text, int start, int end) {
        String word = text.substring(start, end);
        int before = start - 1;
        while (Character.isWhitespace(charAt(text, before))) {
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
