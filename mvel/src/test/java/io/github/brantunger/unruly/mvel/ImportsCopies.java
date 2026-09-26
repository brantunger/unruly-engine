package io.github.brantunger.unruly.mvel;

/**
 * Gives a test in another package, such as {@code core.ImportLimitCopiesTest}, which also reaches
 * {@code core.ImportResolver}, the copy of the engine's import size check {@link Imports} keeps.
 */
public final class ImportsCopies {

    private ImportsCopies() {
    }

    /**
     * Checks an import's size as {@link Imports#checkSize} does.
     *
     * @param name The import
     * @throws IllegalArgumentException if the import is too long or has too many parts
     */
    public static void checkSize(String name) {
        Imports.checkSize(name);
    }
}
