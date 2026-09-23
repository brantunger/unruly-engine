package unruly.conventions

import java.io.DataInputStream
import java.util.zip.ZipFile

/** Reads what the API check needs from jars. A class rather than a script closure, so a task can keep a reference. */
final class Jars {

    /** The type of each primitive in a descriptor. */
    private static final Map<Character, String> PRIMITIVES = ['B': 'byte', 'C': 'char', 'D': 'double', 'F': 'float',
                                                              'I': 'int', 'J': 'long', 'S': 'short', 'Z': 'boolean']
            .collectEntries { key, value -> [(key as char): value] }

    private static final int FIELD_REF = 9
    private static final int METHOD_REF = 10
    private static final int INTERFACE_METHOD_REF = 11

    private Jars() {
    }

    /**
     * Lists the packages that have a class in any of the jars, as {@code io.github.brantunger.unruly.api}. Entries
     * under {@code META-INF/}, such as a multi-release jar's versioned classes, aren't packages of their own.
     *
     * @param jars The jars
     * @return The package names
     */
    static Set<String> packagesIn(Iterable<File> jars) {
        Set<String> packages = [] as Set
        jars.each { jar ->
            new ZipFile(jar).withCloseable { zip ->
                zip.entries().each { entry ->
                    String name = entry.name
                    if (name.endsWith('.class') && name.contains('/') && !name.startsWith('META-INF/')) {
                        packages << name.substring(0, name.lastIndexOf('/')).replace('/', '.')
                    }
                }
            }
        }
        packages
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
    static Set<String> membersUsed(File jar, Collection<String> packages, Iterable<File> library) {
        // The jar's own classes are searched too: a member can be called through a class of the jar that extends one
        // of the library's.
        Map<String, ClassFile> classes = [:]
        ([jar] + library.toList()).each { searched ->
            eachClass(searched) { ClassFile file -> classes[file.name] = file }
        }
        Set<String> members = [] as SortedSet
        eachClass(jar) { ClassFile file ->
            file.refs.each { List ref ->
                String owner = ref[1] == '<init>' ? ref[0] : declaringClass(classes, ref)
                if (owner != null && packageOf(owner) in packages) {
                    members << japicmpName(owner, ref[1] as String, ref[2] as String)
                }
            }
        }
        members
    }

    /**
     * Finds the class that declares a referenced field or method, searching as the JVM's resolution does (JVMS 5.4.3.2
     * and 5.4.3.3): the class, then its superclasses and interfaces. A reference through a class outside the library
     * is taken to name the declaring class; a search that leaves the library without finding the member finds none.
     *
     * @return The declaring class's internal name, or {@code null} if it is outside the library
     */
    private static String declaringClass(Map<String, ClassFile> classes, List ref) {
        String owner = ref[0]
        if (!classes.containsKey(owner)) {
            return owner
        }
        String member = "${ref[1]}:${ref[2]}"
        List<String> order = ref[3] == FIELD_REF ? fieldOrder(classes, owner) : methodOrder(classes, owner)
        order.find { classes[it].members.contains(member) }
    }

    /** The classes a field is looked for in: the class, its interfaces and theirs, then its superclass, likewise. */
    private static List<String> fieldOrder(Map<String, ClassFile> classes, String name) {
        if (name == null || !classes.containsKey(name)) {
            return []
        }
        List<String> order = [name]
        classes[name].interfaces.each { order.addAll(fieldOrder(classes, it)) }
        order + fieldOrder(classes, classes[name].superclass)
    }

    /** The classes a method is looked for in: the class and its superclasses, then all their interfaces. */
    private static List<String> methodOrder(Map<String, ClassFile> classes, String name) {
        List<String> superclasses = []
        for (String type = name; type != null && classes.containsKey(type); type = classes[type].superclass) {
            superclasses << type
        }
        List<String> interfaces = []
        List<String> pending = superclasses.collectMany { classes[it].interfaces }
        while (pending) {
            String type = pending.remove(0)
            if (!(type in interfaces) && classes.containsKey(type)) {
                interfaces << type
                pending.addAll(classes[type].interfaces)
            }
        }
        superclasses + interfaces
    }

    private static String packageOf(String internalName) {
        internalName.substring(0, Math.max(internalName.lastIndexOf('/'), 0)).replace('/', '.')
    }

    private static void eachClass(File jar, Closure action) {
        new ZipFile(jar).withCloseable { zip ->
            zip.entries().each { entry ->
                if (entry.name.endsWith('.class') && !entry.name.startsWith('META-INF/')
                        && !entry.name.endsWith('module-info.class')) {
                    zip.getInputStream(entry).withCloseable { stream ->
                        action(ClassFile.read(new DataInputStream(new BufferedInputStream(stream))))
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
        String className = internalClass.replace('/', '.')
        if (!descriptor.startsWith('(')) {
            return "${className}#${member}"
        }
        String name = member == '<init>' ? className.substring(className.lastIndexOf('.') + 1) : member
        List<String> parameters = []
        int i = 1
        while (descriptor.charAt(i) != (')' as char)) {
            int dimensions = 0
            while (descriptor.charAt(i) == ('[' as char)) {
                dimensions++
                i++
            }
            String type
            if (descriptor.charAt(i) == ('L' as char)) {
                int end = descriptor.indexOf(';', i)
                type = descriptor.substring(i + 1, end).replace('/', '.')
                i = end + 1
            } else {
                type = PRIMITIVES[descriptor.charAt(i)]
                i++
            }
            parameters << type + '[]' * dimensions
        }
        "${className}#${name}(${parameters.join(',')})"
    }

    /**
     * What a class file says about linking: its name, its superclass and interfaces, the fields and methods it declares
     * as {@code name:descriptor}, and the fields and methods it refers to, each as [class, name, descriptor, constant
     * pool tag].
     */
    private static final class ClassFile {
        String name
        String superclass
        List<String> interfaces
        Set<String> members
        List<List> refs

        /** Reads a class file. Bytes it has no use for are read and dropped: skipBytes may skip fewer. */
        static ClassFile read(DataInputStream input) {
            input.readFully(new byte[8]) // magic, minor_version, major_version
            int count = input.readUnsignedShort()
            Object[] pool = new Object[count]
            for (int i = 1; i < count; i++) {
                int tag = input.readUnsignedByte()
                switch (tag) {
                    case 1: // Utf8
                        pool[i] = input.readUTF()
                        break
                    case 7: case 8: case 16: case 19: case 20: // Class, String, MethodType, Module, Package
                        pool[i] = input.readUnsignedShort()
                        break
                    case 9: case 10: case 11: case 12: // Fieldref, Methodref, InterfaceMethodref, NameAndType
                        pool[i] = [tag, input.readUnsignedShort(), input.readUnsignedShort()]
                        break
                    case 3: case 4: case 17: case 18: // Integer, Float, Dynamic, InvokeDynamic
                        input.readFully(new byte[4])
                        break
                    case 5: case 6: // Long, Double, which take two entries
                        input.readFully(new byte[8])
                        i++
                        break
                    case 15: // MethodHandle
                        input.readFully(new byte[3])
                        break
                    default:
                        throw new IllegalArgumentException("Unknown constant pool tag ${tag}")
                }
            }
            def className = { int index -> index == 0 ? null : pool[pool[index] as int] as String }
            input.readFully(new byte[2]) // access_flags
            ClassFile file = new ClassFile(name: className(input.readUnsignedShort()),
                    superclass: className(input.readUnsignedShort()), members: [] as Set)
            file.interfaces = (0..<input.readUnsignedShort()).collect { className(input.readUnsignedShort()) }
            2.times { // the fields, then the methods
                input.readUnsignedShort().times {
                    input.readFully(new byte[2]) // access_flags
                    file.members << "${pool[input.readUnsignedShort()]}:${pool[input.readUnsignedShort()]}".toString()
                    input.readUnsignedShort().times { // the attributes
                        input.readFully(new byte[2])
                        input.readFully(new byte[input.readInt()])
                    }
                }
            }
            file.refs = pool.findAll { it instanceof List && it[0] in [FIELD_REF, METHOD_REF, INTERFACE_METHOD_REF] }
                    .collect { ref ->
                        List nameAndType = pool[ref[2] as int] as List
                        [className(ref[1] as int), pool[nameAndType[1] as int], pool[nameAndType[2] as int], ref[0]]
                    }
            file
        }
    }
}
