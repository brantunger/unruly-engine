package io.github.brantunger.unruly.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.invoke.MethodType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.spi.ToolProvider;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("config/japicmp/test-kit-linkage.txt lists exactly the core members the test kit links against")
class TestKitLinkageTest {

    /** The directories of the kit's compiled main classes, which the build passes to the tests. */
    private static final List<Path> CLASS_DIRECTORIES = Stream.of(
                    System.getProperty("unruly.test-kit.classes").split(Pattern.quote(File.pathSeparator)))
            .map(Path::of)
            .toList();

    /** The list core's japicmpTestKitLinkage checks, which the build passes to the tests. */
    private static final Path LINKAGE = Path.of(System.getProperty("unruly.test-kit.linkage"));

    /** The package whose members the file lists. A subpackage's aren't. */
    private static final String CORE = "io.github.brantunger.unruly.core";

    /** The class a {@code javap -v} listing is of (group 1), as {@code this_class: #8 // a/b/Foo}. */
    private static final Pattern THIS_CLASS = Pattern.compile("^\\s*this_class: #\\d+\\s+// (\\S+)$");

    /**
     * A constant pool entry, as {@code javap -v} prints it, for a field (group 1 is {@code Field}), method or
     * constructor of one of the library's classes: the class (group 2), which javap leaves out when it is the class
     * the listing is of, the member's name (group 3) and its descriptor (group 4).
     */
    private static final Pattern MEMBER_REF = Pattern.compile(
            "= (Field|Method|InterfaceMethod)ref\\s+#\\d+\\.#\\d+\\s+// "
                    + "(?:(io/github/brantunger/unruly/[\\w/$]+)\\.)?\"?([\\w$<>]+)\"?:(\\S+)$");

    /** The type of each primitive in a descriptor. */
    private static final Map<Character, String> PRIMITIVES = Map.of('B', "byte", 'C', "char", 'D', "double",
            'F', "float", 'I', "int", 'J', "long", 'S', "short", 'Z', "boolean");

    @Test
    @DisplayName("every core constructor, method and field the kit's classes use is listed, and nothing else")
    void linkedMembersListed() throws IOException {
        Set<String> listed = listed();
        Set<String> linked = linked();

        assertAll(
                () -> assertEquals(Set.of(), without(linked, listed),
                        "the kit uses these core members, which " + LINKAGE.getFileName() + " doesn't list"),
                () -> assertEquals(Set.of(), without(listed, linked),
                        LINKAGE.getFileName() + " lists these core members, which the kit doesn't use"));
    }

    private static Set<String> without(Set<String> members, Set<String> others) {
        Set<String> left = new TreeSet<>(members);
        left.removeAll(others);
        return left;
    }

    /** The members the file lists, without its comments and blank lines. */
    private static Set<String> listed() throws IOException {
        return Files.readAllLines(LINKAGE, StandardCharsets.UTF_8).stream()
                .map(String::trim)
                .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                .collect(Collectors.toCollection(TreeSet::new));
    }

    /** The core members the kit's class files refer to, named as japicmp names them. */
    private static Set<String> linked() throws IOException {
        List<String> arguments = new ArrayList<>(List.of("-v", "-p"));
        for (Path directory : CLASS_DIRECTORIES) {
            try (Stream<Path> files = Files.walk(directory)) {
                files.filter(file -> file.toString().endsWith(".class")).sorted()
                        .forEach(file -> arguments.add(file.toString()));
            }
        }
        assertTrue(arguments.size() > 2, "no class files in " + CLASS_DIRECTORIES);

        StringWriter output = new StringWriter();
        StringWriter errors = new StringWriter();
        int status = ToolProvider.findFirst("javap").orElseThrow()
                .run(new PrintWriter(output), new PrintWriter(errors), arguments.toArray(String[]::new));
        assertEquals(0, status, errors::toString);

        Set<String> members = new TreeSet<>();
        String listed = null;
        for (String line : output.toString().lines().toList()) {
            Matcher listing = THIS_CLASS.matcher(line);
            Matcher ref = MEMBER_REF.matcher(line);
            if (listing.find()) {
                listed = listing.group(1);
            } else if (ref.find()) {
                String owner = ref.group(2) == null ? listed : ref.group(2);
                Class<?> declaring = declaringClass(owner, "Field".equals(ref.group(1)), ref.group(3), ref.group(4));
                if (declaring != null && declaring.getPackageName().equals(CORE)) {
                    members.add(japicmpName(declaring.getName().replace('.', '/'), ref.group(3), ref.group(4)));
                }
            }
        }
        return members;
    }

    /**
     * Finds the class that declares a member a reference names. A reference names the class it was made through,
     * which can be a subclass of the one that declares the member, and japicmp names a member after the class that
     * declares it. So the member is looked for as the JVM looks for it: a constructor in the class; a field in the
     * class, its interfaces, then its superclass; a method in the class and its superclasses, then their interfaces.
     *
     * @return The declaring class, or {@code null} if the member isn't found, as a JDK member called through a
     *         library class isn't
     */
    private static Class<?> declaringClass(String owner, boolean field, String name, String descriptor) {
        Class<?> type;
        try {
            type = Class.forName(owner.replace('/', '.'), false, TestKitLinkageTest.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            throw new AssertionError("the kit refers to " + owner + ", which isn't on the test class path", e);
        }
        if ("<init>".equals(name)) {
            return type;
        }
        return field ? fieldDeclarer(type, name, descriptor) : methodDeclarer(type, name, descriptor);
    }

    private static Class<?> fieldDeclarer(Class<?> type, String name, String descriptor) {
        if (type == null) {
            return null;
        }
        if (Arrays.stream(type.getDeclaredFields()).anyMatch(field ->
                field.getName().equals(name) && field.getType().descriptorString().equals(descriptor))) {
            return type;
        }
        for (Class<?> implemented : type.getInterfaces()) {
            Class<?> declaring = fieldDeclarer(implemented, name, descriptor);
            if (declaring != null) {
                return declaring;
            }
        }
        return fieldDeclarer(type.getSuperclass(), name, descriptor);
    }

    private static Class<?> methodDeclarer(Class<?> type, String name, String descriptor) {
        List<Class<?>> candidates = new ArrayList<>();
        for (Class<?> superclass = type; superclass != null; superclass = superclass.getSuperclass()) {
            candidates.add(superclass);
        }
        Deque<Class<?>> pending = new ArrayDeque<>();
        candidates.forEach(superclass -> pending.addAll(List.of(superclass.getInterfaces())));
        while (!pending.isEmpty()) {
            Class<?> implemented = pending.removeFirst();
            if (!candidates.contains(implemented)) {
                candidates.add(implemented);
                pending.addAll(List.of(implemented.getInterfaces()));
            }
        }
        return candidates.stream()
                .filter(candidate -> Arrays.stream(candidate.getDeclaredMethods()).anyMatch(method ->
                        method.getName().equals(name) && MethodType.methodType(method.getReturnType(),
                                method.getParameterTypes()).toMethodDescriptorString().equals(descriptor)))
                .findFirst()
                .orElse(null);
    }

    /**
     * Names a member as japicmp does: the class, then {@code #}, then the member's name, a constructor's being its
     * class's simple name, and, for a method or constructor, its parameter types in brackets, separated by commas.
     */
    private static String japicmpName(String internalClass, String member, String descriptor) {
        String className = internalClass.replace('/', '.');
        if (!descriptor.startsWith("(")) {
            return className + "#" + member;
        }
        String name = "<init>".equals(member) ? className.substring(className.lastIndexOf('.') + 1) : member;
        List<String> parameters = new ArrayList<>();
        int i = 1;
        while (descriptor.charAt(i) != ')') {
            int dimensions = 0;
            while (descriptor.charAt(i) == '[') {
                dimensions++;
                i++;
            }
            String type;
            if (descriptor.charAt(i) == 'L') {
                int end = descriptor.indexOf(';', i);
                type = descriptor.substring(i + 1, end).replace('/', '.');
                i = end + 1;
            } else {
                type = PRIMITIVES.get(descriptor.charAt(i));
                i++;
            }
            parameters.add(type + "[]".repeat(dimensions));
        }
        return className + "#" + name + "(" + String.join(",", parameters) + ")";
    }
}
