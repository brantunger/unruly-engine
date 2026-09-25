package io.github.brantunger.unruly.mvel;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runs a scenario's main class in a new JVM, with this JVM's class path, and checks that it finished and exited 0. */
final class ChildJvm {

    private ChildJvm() {
    }

    /**
     * Runs {@code main} in a new JVM with the options given and the engine's logging off.
     *
     * @param dir     A directory for the class path's argument file and the output
     * @param main    The class whose {@code main} runs
     * @param options The JVM's options, such as {@code -da}
     * @return What the JVM printed, standard output and error together
     */
    static String run(Path dir, Class<?> main, String... options) throws IOException, InterruptedException {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        // Output to a file, so waiting is bounded by waitFor and not by the child closing a pipe.
        Path log = dir.resolve("scenario.log");
        // The class path in an argument file, not on the command line or in the environment, which Windows limits to
        // 32,767 characters. In the file, a quoted argument keeps its spaces and a backslash escapes what follows.
        Path arguments = dir.resolve("classpath.args");
        Files.writeString(arguments, "-cp \"" + System.getProperty("java.class.path").replace("\\", "\\\\") + "\"",
                Charset.forName(System.getProperty("native.encoding")));
        // The child writes its output in UTF-8, as it is read here, whatever the platform's encoding.
        List<String> command = new ArrayList<>(List.of(java.toString(), "@" + arguments, "-Dstdout.encoding=UTF-8",
                "-Dstderr.encoding=UTF-8"));
        command.addAll(List.of(options));
        command.addAll(List.of("-Dorg.slf4j.simpleLogger.defaultLogLevel=off", main.getName()));
        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start();
        boolean finished;
        try {
            finished = process.waitFor(60, TimeUnit.SECONDS);
        } finally {
            process.destroyForcibly();
        }
        String output = Files.readString(log, StandardCharsets.UTF_8);

        assertTrue(finished, "the scenario didn't finish:\n" + output);
        assertEquals(0, process.exitValue(), "scenario output:\n" + output);
        return output;
    }
}
