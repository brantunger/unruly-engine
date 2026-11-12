package io.github.brantunger.unruly.api;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes the serialized exceptions in this folder's parent. It names no type that 3.0 moved, so it compiles against
 * unruly-engine 2.35.4, which generate.sh runs it on, and against 3.0 too. The build doesn't compile it.
 */
public final class WriteStreams {

    private WriteStreams() {
    }

    private static void write(File folder, String file, Object thrown) throws IOException {
        try (ObjectOutputStream out = new ObjectOutputStream(new FileOutputStream(new File(folder, file)))) {
            out.writeObject(thrown);
        }
        System.out.println(file + ": " + thrown.getClass().getName() + " extends "
                + thrown.getClass().getSuperclass().getName());
    }

    /**
     * Writes one stream for what load() throws, one for what run() throws, and one for each application subclass.
     *
     * @param args The folder to write to
     */
    public static void main(String[] args) throws IOException {
        File folder = new File(args[0]);
        RulesEngine<Map<String, Object>> engine =
                RulesEngineBuilder.<Map<String, Object>>firstMatch(HashMap::new).build();
        try {
            engine.load(List.of(Rule.builder().ruleName("bad").priority(1).condition("score >")
                    .action("output.put('a', 1)").build()));
        } catch (RuntimeException e) {
            write(folder, "load-failure.ser", e);
        }
        engine.load(List.of(Rule.builder().ruleName("boom").priority(1).condition("true")
                .action("Integer.parseInt('x')").build()));
        try {
            engine.run(new FactMap<>());
        } catch (RuntimeException e) {
            write(folder, "run-failure.ser", e);
        }
        write(folder, "base-subclass.ser", new SerializedSubclasses.Base());
        write(folder, "compile-failure-subclass.ser", new SerializedSubclasses.CompileFailure());
        write(folder, "run-failure-subclass.ser", new SerializedSubclasses.RunFailure());
        write(folder, "rejection-subclass.ser", new SerializedSubclasses.Rejection());
    }
}
