package unruly.conventions;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Reads what the API check needs from jars. A class rather than a script function, so a task can keep a reference. */
public final class Jars {

    /** The type of each primitive in a descriptor. */
    private static final Map<Character, String> PRIMITIVES = Map.of('B', "byte", 'C', "char", 'D', "double",
            'F', "float", 'I', "int", 'J', "long", 'S', "short", 'Z', "boolean");

    private static final int FIELD_REF = 9;
    private static final int METHOD_REF = 10;
    private static final int INTERFACE_METHOD_REF = 11;

    private Jars() {
    }

    /**
     * Lists the packages that have a class in any of the jars, as {@code io.github.brantunger.unruly.api}. Entries
     * under {@code META-INF/}, such as a multi-release jar's versioned classes, aren't packages of their own.
     *
     * @param jars The jars
     * @return The package names
     */
    public static Set<String> packagesIn(Iterable<File> jars) throws IOException {
        Set<String> packages = new LinkedHashSet<>();
        for (File jar : jars) {
            try (ZipFile zip = new ZipFile(jar)) {
                Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    String name = entries.nextElement().getName();
                    if (name.endsWith(".class") && name.contains("/") && !name.startsWith("META-INF/")) {
                        packages.add(name.substring(0, name.lastIndexOf('/')).replace('/', '.'));
                    }
                }
            }
        }
        return packages;
    }

    /**
     * Lists the fields, methods and constructors of classes in the given packages that the jar's classes refer to,
     * named as japicmp names them, such as {@code io.github.brantunger.unruly.core.CopyLimit#CopyLimit(int,boolean)}.
     * They are read from each class file's constant pool, which holds every member the class links against.
     *
     * <p>A reference names the class it was made through, which can be a subclass of the one that declares the member,
     * and japicmp names a member after the class that declares it. So each reference is resolved as the JVM resolves
     * it, against the library the jar was compiled with. A member declared outside the library, such as
     * {@code Object.toString()} called through a library class, isn't the library's, and isn't listed.
     *
     * @param jar      The jar
     * @param packages The packages whose members count, as {@code io.github.brantunger.unruly.core}; a subpackage
     *                 doesn't
     * @param library  The jars the jar was compiled with that hold those packages
     * @return The members
     */
    public static Set<String> membersUsed(File jar, Collection<String> packages, Iterable<File> library)
            throws IOException {
        // The jar's own classes are searched too: a member can be called through a class of the jar that extends one
        // of the library's.
        Map<String, ClassFile> classes = new HashMap<>();
        List<File> searched = new ArrayList<>();
        searched.add(jar);
        library.forEach(searched::add);
        for (File file : searched) {
            eachClass(file, classFile -> classes.put(classFile.name, classFile));
        }
        Set<String> members = new TreeSet<>();
        eachClass(jar, file -> {
            for (Ref ref : file.refs) {
                String owner = "<init>".equals(ref.name()) ? ref.owner() : declaringClass(classes, ref);
                if (owner != null && packages.contains(packageOf(owner))) {
                    members.add(japicmpName(owner, ref.name(), ref.descriptor()));
                }
            }
        });
        return members;
    }

    /**
     * Finds the class that declares a referenced field or method, searching as the JVM's resolution does (JVMS 5.4.3.2
     * and 5.4.3.3): the class, then its superclasses and interfaces. A reference through a class outside the library
     * is taken to name the declaring class; a search that leaves the library without finding the member finds none.
     *
     * @return The declaring class's internal name, or {@code null} if it is outside the library
     */
    private static String declaringClass(Map<String, ClassFile> classes, Ref ref) {
        String owner = ref.owner();
        if (!classes.containsKey(owner)) {
            return owner;
        }
        String member = ref.name() + ":" + ref.descriptor();
        List<String> order = ref.tag() == FIELD_REF ? fieldOrder(classes, owner) : methodOrder(classes, owner);
        for (String type : order) {
            if (classes.get(type).members.contains(member)) {
                return type;
            }
        }
        return null;
    }

    /** The classes a field is looked for in: the class, its interfaces and theirs, then its superclass, likewise. */
    private static List<String> fieldOrder(Map<String, ClassFile> classes, String name) {
        if (name == null || !classes.containsKey(name)) {
            return new ArrayList<>();
        }
        List<String> order = new ArrayList<>();
        order.add(name);
        for (String type : classes.get(name).interfaces) {
            order.addAll(fieldOrder(classes, type));
        }
        order.addAll(fieldOrder(classes, classes.get(name).superclass));
        return order;
    }

    /** The classes a method is looked for in: the class and its superclasses, then all their interfaces. */
    private static List<String> methodOrder(Map<String, ClassFile> classes, String name) {
        List<String> superclasses = new ArrayList<>();
        for (String type = name; type != null && classes.containsKey(type); type = classes.get(type).superclass) {
            superclasses.add(type);
        }
        List<String> interfaces = new ArrayList<>();
        List<String> pending = new ArrayList<>();
        for (String type : superclasses) {
            pending.addAll(classes.get(type).interfaces);
        }
        while (!pending.isEmpty()) {
            String type = pending.remove(0);
            if (!interfaces.contains(type) && classes.containsKey(type)) {
                interfaces.add(type);
                pending.addAll(classes.get(type).interfaces);
            }
        }
        List<String> order = new ArrayList<>(superclasses);
        order.addAll(interfaces);
        return order;
    }

    private static String packageOf(String internalName) {
        return internalName.substring(0, Math.max(internalName.lastIndexOf('/'), 0)).replace('/', '.');
    }

    private static void eachClass(File jar, Consumer<ClassFile> action) throws IOException {
        try (ZipFile zip = new ZipFile(jar)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.getName().endsWith(".class") && !entry.getName().startsWith("META-INF/")
                        && !entry.getName().endsWith("module-info.class")) {
                    try (InputStream stream = zip.getInputStream(entry)) {
                        action.accept(ClassFile.read(new DataInputStream(new BufferedInputStream(stream))));
                    }
                }
            }
        }
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

    /**
     * A field or method a class file refers to: the class it is referred to through, its name and descriptor, and the
     * constant pool tag of the reference.
     */
    private record Ref(String owner, String name, String descriptor, int tag) {
    }

    /**
     * What a class file says about linking: its name, its superclass and interfaces, the fields and methods it declares
     * as {@code name:descriptor}, and the fields and methods it refers to, each as a {@link Ref} holding the class, the
     * name, the descriptor and the constant pool tag.
     */
    private static final class ClassFile {
        String name;
        String superclass;
        List<String> interfaces;
        Set<String> members;
        List<Ref> refs;

        /** Reads a class file. Bytes it has no use for are read and dropped: skipBytes may skip fewer. */
        static ClassFile read(DataInputStream input) throws IOException {
            input.readFully(new byte[8]); // magic, minor_version, major_version
            int count = input.readUnsignedShort();
            Object[] pool = new Object[count];
            for (int i = 1; i < count; i++) {
                int tag = input.readUnsignedByte();
                switch (tag) {
                    case 1 -> // Utf8
                            pool[i] = input.readUTF();
                    case 7, 8, 16, 19, 20 -> // Class, String, MethodType, Module, Package
                            pool[i] = input.readUnsignedShort();
                    case 9, 10, 11, 12 -> // Fieldref, Methodref, InterfaceMethodref, NameAndType
                            pool[i] = new int[] {tag, input.readUnsignedShort(), input.readUnsignedShort()};
                    case 3, 4, 17, 18 -> // Integer, Float, Dynamic, InvokeDynamic
                            input.readFully(new byte[4]);
                    case 5, 6 -> { // Long, Double, which take two entries
                        input.readFully(new byte[8]);
                        i++;
                    }
                    case 15 -> // MethodHandle
                            input.readFully(new byte[3]);
                    default -> throw new IllegalArgumentException("Unknown constant pool tag " + tag);
                }
            }
            input.readFully(new byte[2]); // access_flags
            ClassFile file = new ClassFile();
            file.name = className(pool, input.readUnsignedShort());
            file.superclass = className(pool, input.readUnsignedShort());
            file.members = new LinkedHashSet<>();
            int interfaceCount = input.readUnsignedShort();
            file.interfaces = new ArrayList<>();
            for (int i = 0; i < interfaceCount; i++) {
                file.interfaces.add(className(pool, input.readUnsignedShort()));
            }
            for (int kind = 0; kind < 2; kind++) { // the fields, then the methods
                int memberCount = input.readUnsignedShort();
                for (int i = 0; i < memberCount; i++) {
                    input.readFully(new byte[2]); // access_flags
                    file.members.add(pool[input.readUnsignedShort()] + ":" + pool[input.readUnsignedShort()]);
                    int attributeCount = input.readUnsignedShort();
                    for (int j = 0; j < attributeCount; j++) { // the attributes
                        input.readFully(new byte[2]);
                        input.readFully(new byte[input.readInt()]);
                    }
                }
            }
            file.refs = new ArrayList<>();
            for (Object entry : pool) {
                if (entry instanceof int[] ref
                        && (ref[0] == FIELD_REF || ref[0] == METHOD_REF || ref[0] == INTERFACE_METHOD_REF)) {
                    int[] nameAndType = (int[]) pool[ref[2]];
                    file.refs.add(new Ref(className(pool, ref[1]), (String) pool[nameAndType[1]],
                            (String) pool[nameAndType[2]], ref[0]));
                }
            }
            return file;
        }

        private static String className(Object[] pool, int index) {
            return index == 0 ? null : (String) pool[(Integer) pool[index]];
        }
    }
}
