package io.github.brantunger.unruly;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs a scenario in a new JVM, for tests in any module that uses these fixtures, and checks that it finished and
 * exited 0. The JVM's output goes to a file, so the wait for it is bounded, and a JVM still running when the time is
 * up is killed, so a scenario that hangs fails its test instead of hanging the build.
 */
public final class ChildJvm {

    /** How long {@link #run(Path, Class, String...)} waits for the JVM to finish. */
    public static final Duration TIMEOUT = Duration.ofSeconds(60);

    private ChildJvm() {
    }

    /**
     * Runs {@code main} in a new JVM, with this JVM's class path, the options given and the engine's logging off, and
     * waits for it at most {@link #TIMEOUT}.
     *
     * @param dir     A directory for the class path's argument file and the output
     * @param main    The class whose {@code main} runs
     * @param options The JVM's options, such as {@code -da}
     * @return What the JVM printed, standard output and error together
     */
    public static String run(Path dir, Class<?> main, String... options) throws IOException, InterruptedException {
        // The class path in an argument file, not on the command line or in the environment, which Windows limits to
        // 32,767 characters. In the file, a quoted argument keeps its spaces and a backslash escapes what follows.
        Path arguments = dir.resolve("classpath.args");
        Files.writeString(arguments, "-cp \"" + System.getProperty("java.class.path").replace("\\", "\\\\") + "\"",
                Charset.forName(System.getProperty("native.encoding")));
        // The child writes its output in UTF-8, as it is read here, whatever the platform's encoding.
        List<String> command = new ArrayList<>(List.of("@" + arguments, "-Dstdout.encoding=UTF-8",
                "-Dstderr.encoding=UTF-8"));
        command.addAll(List.of(options));
        command.addAll(List.of("-Dorg.slf4j.simpleLogger.defaultLogLevel=off", main.getName()));
        return run(dir, TIMEOUT, command);
    }

    /**
     * Runs a new JVM with the arguments given, such as a module path and the module to run, and waits for it at most
     * {@code timeout}. A JVM that hasn't finished by then is killed, with the processes it started.
     *
     * @param dir       A directory for the output
     * @param timeout   How long to wait for the JVM to finish
     * @param arguments The whole command after {@code java}
     * @return What the JVM printed, standard output and error together, read as UTF-8
     */
    public static String run(Path dir, Duration timeout, List<String> arguments)
            throws IOException, InterruptedException {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        // Output to a file, so waiting is bounded by waitFor and not by the child closing a pipe.
        Path log = dir.resolve("scenario.log");
        List<String> command = new ArrayList<>(List.of(java.toString()));
        command.addAll(arguments);
        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start();
        boolean finished;
        try {
            finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } finally {
            // Its own children too, and a wait for it to die, so no process still holds the output file, which the
            // test's temporary directory then couldn't delete.
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            try {
                process.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        String output = Files.readString(log, StandardCharsets.UTF_8);

        assertTrue(finished, "the scenario didn't finish:\n" + output);
        assertEquals(0, process.exitValue(), "scenario output:\n" + output);
        return output;
    }
}
