package io.github.brantunger.unruly.hidden;

/**
 * Output objects whose classes aren't public, in a package other than the engine's, which is what a factory returning
 * a package-private implementation hands the engine. Such an output is written through a public type that declares
 * its setter, or directly where its package is open, as every package on the class path is.
 */
public final class HiddenOutputs {

    /** A public interface declaring a property's getter and setter. */
    public interface Scored {

        /**
         * Returns the score.
         *
         * @return The score
         */
        int getScore();

        /**
         * Sets the score.
         *
         * @param score The score
         */
        void setScore(int score);
    }

    private static final class ScoredImplementation implements Scored {
        private int score;

        @Override
        public int getScore() {
            return score;
        }

        @Override
        public void setScore(int score) {
            this.score = score;
        }
    }

    static final class Plain {
        private int score;

        public int getScore() {
            return score;
        }

        public void setScore(int score) {
            this.score = score;
        }
    }

    private HiddenOutputs() {
    }

    /**
     * Returns an output whose class isn't public but implements {@link Scored}.
     *
     * @return The output
     */
    public static Scored scored() {
        return new ScoredImplementation();
    }

    /**
     * Returns an output whose class isn't public and implements nothing.
     *
     * @return The output
     */
    public static Object plain() {
        return new Plain();
    }
}
