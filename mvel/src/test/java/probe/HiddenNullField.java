package probe;

/**
 * A class whose public static field is {@code null}, but which isn't public, so the engine can't read the field, in
 * {@code InlineNullStaticFieldTest} (#1198).
 */
final class HiddenNullField {

    /**
     * The field whose value is {@code null}.
     */
    public static Object X = null;

    private HiddenNullField() {
    }
}
