package unruly.conventions;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Fails unless the packages the CI workflow's strict native image build lists are exactly the packages of the
 * library's classes in the image. GraalVM for JDK 21 matches each listed package by its whole name, not as a prefix,
 * so a package missing from the list would have its lookups go unchecked, and the strict build would still pass. It
 * reads the list from the {@code packages=} lines of the workflow's step that passes
 * {@code -H:ThrowMissingRegistrationErrors}, which build its value, and the packages from the jars of this build's
 * projects that the image includes.
 */
@CacheableTask
public abstract class StrictImagePackagesCheck extends DefaultTask {

    // A package's name: dot-separated Java identifiers, without the spaces, quotes or $ of a form this can't read.
    private static final String PACKAGE_NAME = "[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*";

    /** The CI workflow, {@code .github/workflows/ci.yml}. */
    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getWorkflow();

    /** The jars of this build's projects that the native image includes. */
    @Classpath
    public abstract ConfigurableFileCollection getLibraries();

    /** Written when the check passes, so Gradle can skip it until the workflow or the jars change. */
    @OutputFile
    public abstract RegularFileProperty getResult();

    @TaskAction
    public void check() throws IOException {
        SortedSet<String> expected = new TreeSet<>();
        for (File jar : getLibraries().getFiles()) {
            try (ZipFile zip = new ZipFile(jar)) {
                Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    String name = entries.nextElement().getName();
                    if (name.endsWith(".class") && !name.startsWith("META-INF/") && name.contains("/")) {
                        expected.add(name.substring(0, name.lastIndexOf('/')).replace('/', '.'));
                    }
                }
            }
        }
        // An empty set would pass only an empty list: the jars weren't found.
        if (expected.isEmpty()) {
            throw new GradleException("No class found in the libraries the native image includes, so the strict "
                    + "image package check would check nothing. Check how native-smoke/build.gradle.kts finds them.");
        }
        SortedSet<String> listed = listedPackages();
        SortedSet<String> missing = new TreeSet<>(expected);
        missing.removeAll(listed);
        SortedSet<String> extra = new TreeSet<>(listed);
        extra.removeAll(expected);
        if (!missing.isEmpty() || !extra.isEmpty()) {
            StringBuilder message = new StringBuilder("The packages=... lines of the strict native image step in "
                    + ".github/workflows/ci.yml must list every package of the library's classes in the image, and "
                    + "nothing else: GraalVM for JDK 21 checks only a package it is given by its whole name.");
            if (!missing.isEmpty()) {
                message.append("\nIn the image but not listed:\n  ").append(String.join("\n  ", missing));
            }
            if (!extra.isEmpty()) {
                message.append("\nListed but not in the image:\n  ").append(String.join("\n  ", extra));
            }
            throw new GradleException(message.toString());
        }
        Files.writeString(getResult().get().getAsFile().toPath(), String.join("\n", expected) + "\n");
    }

    /**
     * Reads the packages the workflow lists, from the one step whose script has
     * {@code -H:ThrowMissingRegistrationErrors=}: each step runs in a shell of its own, so a {@code packages=} line in
     * another step doesn't change the list. The step's script must be a literal block, {@code run: |}, and its
     * {@code packages=} lines must come before that option. The first sets the list, and each later one must add to it
     * with a leading {@code $packages,}, the only forms this reads as the shell does. Any other form, such as a later
     * line without it, which would start the list again, fails the check, as does a name that isn't a package's, a
     * folded script, {@code run: >}, or a {@code packages=} line after the option, before the next named step.
     */
    private SortedSet<String> listedPackages() throws IOException {
        // The lines before the first named step are a step of their own; each line is kept trimmed. The file is
        // decoded as Groovy's getText did, replacing a malformed byte rather than failing.
        List<List<String>> steps = new ArrayList<>();
        steps.add(new ArrayList<>());
        String text = new String(Files.readAllBytes(getWorkflow().get().getAsFile().toPath()), StandardCharsets.UTF_8);
        for (String line : text.lines().toList()) {
            if (line.matches("\\s*- name:.*")) {
                steps.add(new ArrayList<>());
            }
            steps.get(steps.size() - 1).add(line.trim());
        }
        List<List<String>> strict = steps.stream()
                .filter(step -> step.stream().anyMatch(line -> line.contains("-H:ThrowMissingRegistrationErrors=")))
                .toList();
        if (strict.size() != 1) {
            throw new GradleException(strict.size() + " steps in .github/workflows/ci.yml pass "
                    + "-H:ThrowMissingRegistrationErrors, so the strict image package check can't tell which list "
                    + "to read. It reads the packages=... lines of the one step that does.");
        }
        List<String> step = strict.get(0);
        // A step without a run: line isn't a literal block either.
        String run = step.stream().filter(line -> line.startsWith("run:")).findFirst().orElse(null);
        if (run == null || !run.matches("run: \\|-?")) {
            throw new GradleException("The strict native image step of .github/workflows/ci.yml must run its script "
                    + "as a literal block, run: |, which keeps each packages=... line a line of its own: a folded one, "
                    + "run: >, joins them into one.");
        }
        int flag = 0;
        while (!step.get(flag).contains("-H:ThrowMissingRegistrationErrors=")) {
            flag++;
        }
        if (step.subList(flag + 1, step.size()).stream().anyMatch(line -> line.startsWith("packages="))) {
            throw new GradleException("The strict native image step of .github/workflows/ci.yml has a packages=... "
                    + "line after -H:ThrowMissingRegistrationErrors, which can't change the list native-image is "
                    + "given.");
        }
        List<String> lines = step.subList(0, flag).stream().filter(line -> line.startsWith("packages=")).toList();
        if (lines.isEmpty()
                || step.stream().noneMatch(line -> line.contains("-H:ThrowMissingRegistrationErrors=$packages"))) {
            throw new GradleException("No packages=... lines, or no -H:ThrowMissingRegistrationErrors=$packages, in "
                    + "the strict native image step of .github/workflows/ci.yml, so the strict image package check "
                    + "can't read the list.");
        }
        SortedSet<String> listed = new TreeSet<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String value = line.substring("packages=".length());
            if (i > 0) {
                if (!value.startsWith("$packages,")) {
                    throw new GradleException("The strict native image step of .github/workflows/ci.yml has "
                            + "'" + line + "', which doesn't start with packages=$packages, as every packages=... line "
                            + "after the first must, to add to the list rather than start it again.");
                }
                value = value.substring("$packages,".length());
            }
            for (String name : value.split(",", -1)) {
                if (!Pattern.matches(PACKAGE_NAME, name)) {
                    throw new GradleException("The strict native image step of .github/workflows/ci.yml has "
                            + "'" + line + "', in which '" + name + "' isn't a package name.");
                }
                listed.add(name);
            }
        }
        return listed;
    }
}
