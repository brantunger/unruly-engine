package unruly.conventions;

import org.gradle.api.Action;
import org.gradle.api.GradleException;
import org.gradle.api.Task;
import org.gradle.api.tasks.compile.JavaCompile;

/**
 * Fails a compilation that runs NullAway before javac starts, when its toolchain is a JDK 21 older than 21.0.8. In
 * JSpecify mode, NullAway needs javac's {@code -XDaddTypeAnnotationsToSymbol=true}, which those builds don't support:
 * Error Prone then crashes with a stack trace instead of reporting a finding. This says why, and how to build anyway.
 */
public final class NullAwayJdkFloor implements Action<Task> {

    private static final Runtime.Version FLOOR = Runtime.Version.parse("21.0.8");

    @Override
    public void execute(Task task) {
        String found = ((JavaCompile) task).getJavaCompiler().get().getMetadata().getJavaRuntimeVersion();
        Runtime.Version version;
        try {
            version = Runtime.Version.parse(found);
        } catch (IllegalArgumentException unparsed) {
            // A vendor's own version format: javac is left to report what it can.
            return;
        }
        if (version.feature() <= FLOOR.feature() && version.compareToIgnoreOptional(FLOOR) < 0) {
            throw new GradleException(task.getPath() + " runs NullAway, which needs JDK 21.0.8 or later, but its "
                    + "Java 21 toolchain is " + found + ". Install a newer JDK 21, or build with -PnullAway=false to "
                    + "skip the check; CI enforces it.");
        }
    }
}
