package io.github.brantunger.unruly.hidden;

/**
 * Facts whose classes aren't public, which is what a factory returning a package-private implementation hands the
 * engine. Such a fact is read through a public type that declares its accessor, or directly where its package is
 * open, as every package on the class path is; these cover each way that can go.
 */
public final class HiddenFacts {

    /** A public interface, so an implementation of it is readable wherever the implementation lives. */
    public interface Named {

        /**
         * Returns the name.
         *
         * @return The name
         */
        String getName();
    }

    /** A public interface that inherits its accessor from one that isn't public, so nothing public declares it. */
    public interface Boxed extends HiddenBoxed {
    }

    interface HiddenBoxed {

        String getBoxed();
    }

    /** A public interface declaring a record's component accessor, which is how a record exposes itself. */
    public interface HasName {

        /**
         * Returns the name, as the record component of the same name does.
         *
         * @return The name
         */
        String name();
    }

    /** A public interface that declares a static method with an accessor's name, as well as the accessor. */
    public interface Labelled {

        /**
         * Returns a label that has nothing to do with any fact.
         *
         * @return The label
         */
        static String getLabel() {
            return "the interface's own";
        }

        /**
         * Returns the fact's label.
         *
         * @return The label
         */
        String getOwnLabel();
    }

    /** A public interface only the fact's own class implements, above a base class that isn't public. */
    public interface Account {

        /**
         * Returns the account's id.
         *
         * @return The id
         */
        String getId();
    }

    /** An interface that isn't public, between the fact's class and the public one that declares the accessor. */
    interface Inner extends Named {
    }

    private HiddenFacts() {
    }

    /**
     * Returns a record that isn't public and implements no interface, the shape an implementation detail takes.
     *
     * @return The fact
     */
    public static Object bareRecord() {
        return new BareRecord(7);
    }

    /**
     * Returns a fact with a {@code secret} property that nothing public declares.
     *
     * @return The fact
     */
    public static Object withASecret() {
        return new Hidden();
    }

    /**
     * Returns a fact whose class isn't public but whose accessor a public interface declares.
     *
     * @return The fact
     */
    public static Object named() {
        return new HiddenName();
    }

    /**
     * Returns a record that isn't public but implements a public interface, the shape a factory most often returns.
     *
     * @return The fact
     */
    public static Object namedRecord() {
        return new HiddenRecord("record");
    }

    /**
     * Returns a fact that reaches the public interface declaring its accessor through one that isn't public.
     *
     * @return The fact
     */
    public static Object namedThroughAnInnerInterface() {
        return new ViaInner();
    }

    /**
     * Returns a fact whose accessor a public interface only inherits from one that isn't public, so no public type
     * declares it.
     *
     * @return The fact
     */
    public static Object boxed() {
        return new HiddenBox();
    }

    /**
     * Returns a fact that reaches the same interface twice, directly and through its superclass.
     *
     * @return The fact
     */
    public static Object namedTwice() {
        return new NamedTwice();
    }

    /**
     * Returns a fact that is an anonymous implementation of a public interface.
     *
     * @return The fact
     */
    public static Object anonymousName() {
        return new Named() {
            @Override
            public String getName() {
                return "anonymous";
            }
        };
    }

    /**
     * Returns a fact whose accessor is declared on a class that isn't public, below another that isn't either.
     *
     * @return The fact
     */
    public static Object deeplyHidden() {
        return new HiddenDeep();
    }

    /**
     * Returns a fact whose accessor is declared on a base class that isn't public, where the public interface that
     * declares it is implemented by the fact's own class.
     *
     * @return The fact
     */
    public static Object account() {
        return new Savings();
    }

    /**
     * Returns a fact whose class isn't public and whose interface declares a static method named like an accessor.
     *
     * @return The fact
     */
    public static Object labelled() {
        return new HiddenLabel();
    }

    static final class HiddenLabel implements Labelled {

        @Override
        public String getOwnLabel() {
            return "the fact's own";
        }

        public String getLabel() {
            return "the fact's own too";
        }
    }

    abstract static class AbstractAccount {

        public String getId() {
            return "savings-1";
        }
    }

    static final class Savings extends AbstractAccount implements Account {
    }

    static class HiddenMiddle {
    }

    static final class HiddenDeep extends HiddenMiddle {

        public String getDeep() {
            return "deep";
        }
    }

    static final class Hidden {

        public int getSecret() {
            return 1;
        }
    }

    static final class HiddenName implements Named {

        @Override
        public String getName() {
            return "hidden";
        }
    }

    record HiddenRecord(String name) implements HasName {
    }

    record BareRecord(int score) {
    }

    static final class ViaInner implements Inner {

        @Override
        public String getName() {
            return "through an inner interface";
        }
    }

    static final class HiddenBox implements Boxed {

        @Override
        public String getBoxed() {
            return "boxed";
        }
    }

    static class NamedOnce implements Named {

        @Override
        public String getName() {
            return "twice";
        }
    }

    static final class NamedTwice extends NamedOnce implements Named {

        // Declared here, so the walk up from this class meets Named directly and again through NamedOnce.
        @Override
        public String getName() {
            return "twice";
        }
    }
}
