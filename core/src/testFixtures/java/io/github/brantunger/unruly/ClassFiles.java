package io.github.brantunger.unruly;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Reads the library's class files, for tests that check what a class's code links, as #1093 and #1097 need: the JVM
 * links a lambda or a method reference, or any other {@code invokedynamic} call site, the first time it runs, which
 * makes a class and takes more stack than the engine's checks make room for, so code that runs only when something
 * fails or waits must have none. A class whose code is all such code is checked whole, by {@link #namesLambdaFactory};
 * a class whose other code links lambdas on paths every run takes is checked method by method, by
 * {@link #invokeDynamicMethods}, which walks each method's bytecode, as a byte of {@code 0xBA}, the opcode, can also be
 * an operand or a constant. There is no class-file library on the test class path, so this reads the format itself
 * (JVMS chapter 4).
 */
public final class ClassFiles {

    private static final byte[] FACTORY = "java/lang/invoke/LambdaMetafactory".getBytes(StandardCharsets.UTF_8);
    private static final int INVOKEDYNAMIC = 0xBA;

    private ClassFiles() {
    }

    /**
     * Reads the class file of a class, and those of the classes nested in it, from the directory or jar it was loaded
     * from.
     *
     * @param type The class
     * @return Each class file's bytes, by its entry name, such as
     *         {@code io/github/brantunger/unruly/core/Failures.class}
     * @throws IOException        if one can't be read
     * @throws URISyntaxException if the class's location isn't a path
     */
    public static Map<String, byte[]> of(Class<?> type) throws IOException, URISyntaxException {
        Path classes = Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI());
        String name = type.getName().replace('.', '/');
        Map<String, byte[]> read = new LinkedHashMap<>();
        if (Files.isDirectory(classes)) {
            try (Stream<Path> files = Files.walk(classes)) {
                for (Path file : files.toList()) {
                    String entry = classes.relativize(file).toString().replace('\\', '/');
                    if (isOf(entry, name)) {
                        read.put(entry, Files.readAllBytes(file));
                    }
                }
            }
        } else {
            try (ZipFile jar = new ZipFile(classes.toFile())) {
                for (ZipEntry entry : jar.stream().filter(e -> isOf(e.getName(), name)).toList()) {
                    try (InputStream in = jar.getInputStream(entry)) {
                        read.put(entry.getName(), in.readAllBytes());
                    }
                }
            }
        }
        return read;
    }

    /**
     * Reads the class file of a class alone, as {@link #of} does.
     *
     * @param type The class
     * @return Its bytes
     * @throws IOException        if it can't be read, or isn't there
     * @throws URISyntaxException if the class's location isn't a path
     */
    public static byte[] classFile(Class<?> type) throws IOException, URISyntaxException {
        byte[] bytes = of(type).get(type.getName().replace('.', '/') + ".class");
        if (bytes == null) {
            throw new IOException("no class file read for " + type.getName());
        }
        return bytes;
    }

    /**
     * Tells whether a class file holds the name of {@code LambdaMetafactory}, as its constant pool does for a call
     * site linked to it, a lambda's or a method reference's.
     *
     * @param classFile The class file's bytes
     * @return {@code true} if it does
     */
    public static boolean namesLambdaFactory(byte[] classFile) {
        for (int i = 0; i <= classFile.length - FACTORY.length; i++) {
            int at = 0;
            while (at < FACTORY.length && classFile[i + at] == FACTORY[at]) {
                at++;
            }
            if (at == FACTORY.length) {
                return true;
            }
        }
        return false;
    }

    /**
     * Lists the methods of a class file whose code has an {@code invokedynamic} instruction: a lambda, a method
     * reference, a string concatenation compiled with the JDK's default, or any other call site the JVM links when it
     * first runs. A lambda's body is a method of its own, but the instruction that links it is in the method that
     * creates it.
     *
     * @param classFile The class file's bytes
     * @return Every method's name, and each method with one, as its name and descriptor, such as
     *         {@code partDone(I)Ljava/lang/Error;}
     * @throws IOException if the class file is malformed
     */
    public static Methods invokeDynamicMethods(byte[] classFile) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(classFile));
        if (in.readInt() != 0xCAFEBABE) {
            throw new IOException("not a class file");
        }
        in.readUnsignedShort();
        in.readUnsignedShort();
        String[] utf8 = constantPool(in);
        in.readUnsignedShort();
        in.readUnsignedShort();
        in.readUnsignedShort();
        int interfaces = in.readUnsignedShort();
        in.skipNBytes(2L * interfaces);
        int fields = in.readUnsignedShort();
        for (int i = 0; i < fields; i++) {
            in.skipNBytes(6);
            skipAttributes(in);
        }
        List<String> names = new ArrayList<>();
        List<String> linking = new ArrayList<>();
        int methods = in.readUnsignedShort();
        for (int i = 0; i < methods; i++) {
            in.readUnsignedShort();
            String name = utf8[in.readUnsignedShort()];
            String descriptor = utf8[in.readUnsignedShort()];
            names.add(name);
            boolean links = false;
            int attributes = in.readUnsignedShort();
            for (int a = 0; a < attributes; a++) {
                String attribute = utf8[in.readUnsignedShort()];
                byte[] content = new byte[in.readInt()];
                in.readFully(content);
                if ("Code".equals(attribute)) {
                    links = hasInvokeDynamic(content);
                }
            }
            if (links) {
                linking.add(name + descriptor);
            }
        }
        return new Methods(names, linking);
    }

    /**
     * The methods of a class file: every method's name, and each one whose code has an {@code invokedynamic}, by its
     * name and descriptor.
     *
     * @param names   Every method's name, an overloaded one once for each overload
     * @param linking Each method with an {@code invokedynamic}, as its name and descriptor
     */
    public record Methods(List<String> names, List<String> linking) {

        /**
         * Lists the methods of a name that have an {@code invokedynamic}.
         *
         * @param name The methods' name
         * @return Each of them with one, as its name and descriptor
         */
        public List<String> linking(String name) {
            List<String> named = new ArrayList<>();
            for (String method : linking) {
                if (method.startsWith(name + "(")) {
                    named.add(method);
                }
            }
            return named;
        }
    }

    // The class itself, or one nested in it.
    private static boolean isOf(String entry, String name) {
        return entry.equals(name + ".class") || entry.startsWith(name + "$") && entry.endsWith(".class");
    }

    // Reads the constant pool, and returns its UTF-8 entries by index; the others are skipped.
    private static String[] constantPool(DataInputStream in) throws IOException {
        int count = in.readUnsignedShort();
        String[] utf8 = new String[count];
        for (int i = 1; i < count; i++) {
            int tag = in.readUnsignedByte();
            switch (tag) {
                case 1 -> utf8[i] = in.readUTF();
                case 7, 8, 16, 19, 20 -> in.skipNBytes(2);
                case 15 -> in.skipNBytes(3);
                case 3, 4, 9, 10, 11, 12, 17, 18 -> in.skipNBytes(4);
                case 5, 6 -> {
                    in.skipNBytes(8);
                    // A long or a double takes two entries.
                    i++;
                }
                default -> throw new IOException("unknown constant pool tag " + tag + " at entry " + i);
            }
        }
        return utf8;
    }

    private static void skipAttributes(DataInputStream in) throws IOException {
        int attributes = in.readUnsignedShort();
        for (int i = 0; i < attributes; i++) {
            in.readUnsignedShort();
            in.skipNBytes(Integer.toUnsignedLong(in.readInt()));
        }
    }

    // Walks a Code attribute's instructions, from its code_length, and tells whether one is an invokedynamic.
    private static boolean hasInvokeDynamic(byte[] attribute) throws IOException {
        int length = ((attribute[4] & 0xFF) << 24) | ((attribute[5] & 0xFF) << 16) | ((attribute[6] & 0xFF) << 8)
                | (attribute[7] & 0xFF);
        int start = 8;
        int pc = 0;
        while (pc < length) {
            int opcode = attribute[start + pc] & 0xFF;
            if (opcode == INVOKEDYNAMIC) {
                return true;
            }
            pc += instructionLength(attribute, start, pc, opcode);
        }
        return false;
    }

    // The length of the instruction at pc, its opcode and operands (JVMS 6.5).
    private static int instructionLength(byte[] code, int start, int pc, int opcode) throws IOException {
        if (opcode <= 0x0F || opcode >= 0x1A && opcode <= 0x35 || opcode >= 0x3B && opcode <= 0x83
                || opcode >= 0x85 && opcode <= 0x98 || opcode >= 0xAC && opcode <= 0xB1 || opcode == 0xBE
                || opcode == 0xBF || opcode == 0xC2 || opcode == 0xC3) {
            return 1;
        }
        if (opcode == 0x10 || opcode == 0x12 || opcode >= 0x15 && opcode <= 0x19 || opcode >= 0x36 && opcode <= 0x3A
                || opcode == 0xA9 || opcode == 0xBC) {
            return 2;
        }
        if (opcode == 0x11 || opcode == 0x13 || opcode == 0x14 || opcode == 0x84 || opcode >= 0x99 && opcode <= 0xA8
                || opcode >= 0xB2 && opcode <= 0xB8 || opcode == 0xBB || opcode == 0xBD || opcode == 0xC0
                || opcode == 0xC1 || opcode == 0xC6 || opcode == 0xC7) {
            return 3;
        }
        if (opcode == 0xC5) {
            return 4;
        }
        if (opcode == 0xB9 || opcode == INVOKEDYNAMIC || opcode == 0xC8 || opcode == 0xC9) {
            return 5;
        }
        if (opcode == 0xC4) {
            // wide: an iinc with two-byte operands, or a load, a store or a ret with a two-byte index.
            return (code[start + pc + 1] & 0xFF) == 0x84 ? 6 : 4;
        }
        if (opcode == 0xAA || opcode == 0xAB) {
            // tableswitch and lookupswitch: padded so their operands start at a multiple of four from the code's start.
            int operands = pc + 1 + (4 - (pc + 1) % 4) % 4;
            if (opcode == 0xAA) {
                int low = intAt(code, start + operands + 4);
                int high = intAt(code, start + operands + 8);
                return operands - pc + 12 + 4 * (high - low + 1);
            }
            return operands - pc + 8 + 8 * intAt(code, start + operands + 4);
        }
        throw new IOException("unknown opcode " + opcode + " at " + pc);
    }

    private static int intAt(byte[] bytes, int at) {
        return ((bytes[at] & 0xFF) << 24) | ((bytes[at + 1] & 0xFF) << 16) | ((bytes[at + 2] & 0xFF) << 8)
                | (bytes[at + 3] & 0xFF);
    }
}
