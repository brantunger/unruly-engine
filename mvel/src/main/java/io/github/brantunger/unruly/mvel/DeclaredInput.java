package io.github.brantunger.unruly.mvel;

/**
 * The type MVEL's strong typing compiles one name against: its class, and the classes of its type arguments where
 * MVEL can use them, which is for a {@link java.util.Collection} or a {@link java.util.Map} declared with arguments
 * MVEL can check (see {@link DeclaredTypes}). A plain class, not a record: nothing compares or prints one, a record's
 * own methods would link through {@code ObjectMethods}, which can fail for good when first called deep in the stack
 * (#996), and a record would hand out its array, which anything could then change.
 */
// Each accessor is named after the field it returns, as a record's are.
@SuppressWarnings("PMD.AvoidFieldNameMatchingMethodName")
final class DeclaredInput {
    private final Class<?> type;
    private final Class<?>[] typeArguments;

    /**
     * Creates the type of one name, with a copy of its type arguments.
     *
     * @param type          The class of the name's declared type
     * @param typeArguments The class of each type argument MVEL is told, in the order the class declares its type
     *                      parameters, or none to tell MVEL the class alone
     */
    DeclaredInput(Class<?> type, Class<?>... typeArguments) {
        this.type = type;
        this.typeArguments = typeArguments.clone();
    }

    /**
     * Returns the class of the name's declared type.
     *
     * @return The class
     */
    Class<?> type() {
        return type;
    }

    /**
     * Returns the class of each type argument MVEL is told, as a copy.
     *
     * @return The classes, in the order the class declares its type parameters, or an empty array for the class alone
     */
    Class<?>[] typeArguments() {
        return typeArguments.clone();
    }
}
