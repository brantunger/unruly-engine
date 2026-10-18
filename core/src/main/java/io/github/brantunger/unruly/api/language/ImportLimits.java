package io.github.brantunger.unruly.api.language;

import io.github.brantunger.unruly.internal.ImportSize;
import org.jspecify.annotations.Nullable;

import java.util.Objects;

/**
 * How large an import may be, as the engine bounds the imports it is built with, for a language that reads an
 * {@code import} in an expression's own text: it bounds that import the same way, and rejects it with the same
 * reason, rather than keeping a copy of the limits that could drift. The engine rejects an import before any class
 * loader sees it, because a parallel-capable class loader, such as the JDK's application class loader, keeps an
 * object for each name it is asked for, for as long as it lives, and because a name that isn't a class is looked up
 * again with each dot from the right replaced by {@code $}, so the work grows with the number of parts.
 *
 * <p>
 * The limits and the reason's wording are fixed for 2.x. The class is stateless, and {@link #importSizeProblem}
 * allocates only the reason it returns.
 * </p>
 */
public final class ImportLimits {

    /**
     * The most characters (UTF-16 units) an import may have: 1,000, fixed for 2.x. javac inlines it into the classes
     * that read it.
     */
    public static final int MAX_IMPORT_LENGTH = ImportSize.MAX_IMPORT_LENGTH;

    /**
     * The most dot-separated parts an import may have: 64, fixed for 2.x. javac inlines it into the classes that read
     * it.
     */
    public static final int MAX_IMPORT_PARTS = ImportSize.MAX_IMPORT_PARTS;

    private ImportLimits() {
    }

    /**
     * Says why an import is too large to look up, if it is, as the engine says it: too long, or else with too many
     * parts, counting its dots as separators whatever is between them, so an empty import has one part. The engine
     * writes the reason after the quoted import, as {@code Can't import '} + {@link MessageText#quote quote(name)} +
     * {@code ': } + the reason; a language that rejects an inline import the same way reports it with the engine's
     * words.
     *
     * @param name The import, as the expression or the builder writes it
     * @return {@code it has N characters, and an import may have at most 1000} if the import has more than
     *         {@value #MAX_IMPORT_LENGTH} characters, else {@code it has N dot-separated parts, and an import may have
     *         at most 64} if it has more than {@value #MAX_IMPORT_PARTS} parts, else {@code null}: the engine accepts
     *         it as far as its size goes
     * @throws NullPointerException if {@code name} is {@code null}
     */
    public static @Nullable String importSizeProblem(String name) {
        return ImportSize.problem(Objects.requireNonNull(name, "name must not be null"));
    }
}
