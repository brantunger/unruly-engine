package unruly.conventions;

/**
 * A line of {@code config/japicmp/accepted-breaks.txt}: a break the API check accepts while the baseline is from an
 * earlier major version than the one it ships in.
 *
 * @param major   The major version the break ships in
 * @param kind    What the element is: {@code package}, {@code class}, {@code method} or {@code field}
 * @param element The package, class, method or field, as japicmp names it
 */
public record AcceptedBreak(int major, String kind, String element) {
}
