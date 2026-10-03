package io.github.brantunger.unruly.mvel;

import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.FactStore;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mvel2.MVEL;
import org.mvel2.asm.ClassWriter;
import org.mvel2.asm.MethodVisitor;
import org.mvel2.asm.Opcodes;

import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import java.util.logging.Filter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("MVEL's WARNING for a failed conversion is dropped only while a rule's expression runs")
class MvelWarningFilterTest {

    private static final LogRecord KEEP = new LogRecord(Level.WARNING, "keep");
    private static final LogRecord DROP = new LogRecord(Level.WARNING, "drop");

    /**
     * Runs a task and returns the records MVEL's optimizer logged meanwhile that its logger's filter passed. They
     * aren't passed on to the parent handlers, so a record the test expects doesn't print its stack trace in the build.
     */
    private static List<LogRecord> mvelRecordsOf(Runnable task) {
        Logger logger = Logger.getLogger(MvelWarningFilter.LOGGER_NAME);
        List<LogRecord> records = new CopyOnWriteArrayList<>();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {
                // Nothing buffered.
            }

            @Override
            public void close() {
                // Nothing to release.
            }
        };
        boolean useParentHandlers = logger.getUseParentHandlers();
        logger.addHandler(handler);
        logger.setUseParentHandlers(false);
        try {
            task.run();
        } finally {
            logger.setUseParentHandlers(useParentHandlers);
            logger.removeHandler(handler);
        }
        return records;
    }

    /** Defines a class in a new class loader, as a second deployment of the application in the same server would. */
    private static Class<?> defineInAnotherClassLoader(byte[] bytes) {
        class OtherLoader extends ClassLoader {
            OtherLoader() {
                super(MvelWarningFilter.class.getClassLoader());
            }

            Class<?> define() {
                return defineClass(MvelWarningFilter.class.getName(), bytes, 0, bytes.length);
            }
        }
        return new OtherLoader().define();
    }

    /** Loads this class's own bytes as a class of the same name in a new class loader. It isn't initialized. */
    private static Class<?> copyInAnotherClassLoader() throws IOException {
        try (InputStream in = MvelWarningFilter.class.getResourceAsStream("MvelWarningFilter.class")) {
            return defineInAnotherClassLoader(Objects.requireNonNull(in, "MvelWarningFilter.class").readAllBytes());
        }
    }

    /**
     * Calls a copy's static method. The first call initializes the copy, which installs its filter on MVEL's logger,
     * over this class loader's, as a second deployment would; that's undone here.
     *
     * @return What the method returned, and the filter the copy's initialization set on MVEL's logger, or
     *         {@code null} if it had been initialized already
     */
    private static Object[] callCopy(Class<?> copy, String name, Class<?>[] types, Object... arguments)
            throws ReflectiveOperationException {
        Method method = copy.getDeclaredMethod(name, types);
        method.setAccessible(true);
        Filter engines = MvelWarningFilter.LOGGER.getFilter();
        try {
            Object result = method.invoke(null, arguments);
            Filter onMvelsLogger = MvelWarningFilter.LOGGER.getFilter();
            return new Object[] {result, onMvelsLogger == engines ? null : onMvelsLogger};
        } finally {
            MvelWarningFilter.LOGGER.setFilter(engines);
        }
    }

    /**
     * Generates a class with this class's name that is a {@code Filter} and a {@code Supplier}, whose {@code get()}
     * returns a String, not a filter, as a class of the same name the engine didn't write could.
     */
    private static byte[] sameNameSupplyingAString() {
        String name = MvelWarningFilter.class.getName().replace('.', '/');
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER, name, null,
                "java/lang/Object", new String[] {"java/util/logging/Filter", "java/util/function/Supplier"});
        MethodVisitor init = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        MethodVisitor isLoggable = writer.visitMethod(Opcodes.ACC_PUBLIC, "isLoggable",
                "(Ljava/util/logging/LogRecord;)Z", null, null);
        isLoggable.visitCode();
        isLoggable.visitInsn(Opcodes.ICONST_1);
        isLoggable.visitInsn(Opcodes.IRETURN);
        isLoggable.visitMaxs(0, 0);
        isLoggable.visitEnd();
        MethodVisitor get = writer.visitMethod(Opcodes.ACC_PUBLIC, "get", "()Ljava/lang/Object;", null, null);
        get.visitCode();
        get.visitLdcInsn("not a filter");
        get.visitInsn(Opcodes.ARETURN);
        get.visitMaxs(0, 0);
        get.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    /** A filter that is a {@code Supplier<Filter>} too, but not the engine's. */
    private static final class OtherSupplierFilter implements Filter, Supplier<Filter> {

        @Override
        public boolean isLoggable(LogRecord record) {
            return true;
        }

        @Override
        public Filter get() {
            return null;
        }
    }

    private static RulesEngine<Map<String, Object>> engineWith(String condition) {
        RulesEngine<Map<String, Object>> engine =
                RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new).build();
        engine.load(List.of(Rule.builder().ruleName("r").condition(condition).action("output.put('k', 1)").build()));
        return engine;
    }

    @Test
    @DisplayName("MVEL run outside the engine, on a thread that ran a failing rule, still logs its WARNING")
    void mvelOutsideTheEngineStillLogs() {
        RulesEngine<Map<String, Object>> engine = engineWith("items.get(index) == 1");
        FactStore<Object> facts = new FactMap<>();
        facts.setValue("items", List.of(1));
        facts.setValue("index", "x");
        List<LogRecord> duringRun = mvelRecordsOf(() -> assertThrows(RuntimeException.class, () -> engine.run(facts)));
        Map<String, Object> variables = Map.of("items", List.of(1), "index", "x");

        List<LogRecord> afterRun = mvelRecordsOf(() -> assertThrows(RuntimeException.class,
                () -> MVEL.executeExpression(MVEL.compileExpression("items.get(index)"), variables)));

        assertEquals(List.of(), duringRun, "the engine's run logged MVEL's WARNING");
        assertEquals(1, afterRun.size(), "MVEL used on its own lost its WARNING");
        assertInstanceOf(NumberFormatException.class, afterRun.get(0).getThrown());
    }

    @Test
    @DisplayName("the filter is installed once on the logger MVEL logs to, however many engines load rules")
    void installedOnce() {
        engineWith("true");
        Filter first = MvelWarningFilter.LOGGER.getFilter();
        engineWith("true");

        assertSame(Logger.getLogger(MvelWarningFilter.LOGGER_NAME), MvelWarningFilter.LOGGER);
        assertSame(MvelWarningFilter.FILTER, first);
        assertSame(first, MvelWarningFilter.LOGGER.getFilter());
    }

    @Test
    @DisplayName("a filter the logger had decides while no rule runs, and every record is dropped while one does")
    void composesWithThePreviousFilter() {
        Logger logger = Logger.getAnonymousLogger();
        logger.setFilter(record -> !"drop".equals(record.getMessage()));

        Filter filter = MvelWarningFilter.install(logger);

        assertSame(filter, logger.getFilter());
        assertTrue(filter.isLoggable(KEEP));
        assertFalse(filter.isLoggable(DROP));
        boolean outermost = MvelWarningFilter.enter();
        try {
            assertFalse(filter.isLoggable(KEEP));
            assertFalse(filter.isLoggable(DROP));
        } finally {
            MvelWarningFilter.leave(outermost);
        }
        assertTrue(filter.isLoggable(KEEP));
    }

    @Test
    @DisplayName("without a filter before it, a record passes while no rule runs, and a nested run keeps it dropped")
    void noPreviousFilterAndNesting() {
        Filter filter = MvelWarningFilter.install(Logger.getAnonymousLogger());

        assertTrue(filter.isLoggable(KEEP));
        boolean outermost = MvelWarningFilter.enter();
        try {
            boolean nested = MvelWarningFilter.enter();
            MvelWarningFilter.leave(nested);

            assertTrue(outermost);
            assertFalse(nested);
            assertFalse(filter.isLoggable(KEEP), "the nested run's end removed the outer run's mark");
        } finally {
            MvelWarningFilter.leave(outermost);
        }
        assertTrue(filter.isLoggable(KEEP));
    }

    @Test
    @DisplayName("a logger whose filter can't be set is left as it is, and the engine carries on")
    void filterThatCantBeSet() {
        Logger logger = new Logger(null, null) {
            @Override
            public void setFilter(Filter newFilter) {
                throw new SecurityException("setFilter denied");
            }
        };

        assertNull(assertDoesNotThrow(() -> MvelWarningFilter.install(logger)));
        assertNull(logger.getFilter());
    }

    @Test
    @DisplayName("installed twice on a logger, the filter passes records to the logger's first filter, not to itself")
    void installedTwiceDoesNotWrapItself() {
        Logger logger = Logger.getAnonymousLogger();
        Filter original = record -> !"drop".equals(record.getMessage());
        logger.setFilter(original);

        Logger withoutFilter = Logger.getAnonymousLogger();

        MvelWarningFilter.install(logger);
        MvelWarningFilter.install(logger);
        MvelWarningFilter.install(withoutFilter);
        MvelWarningFilter.install(withoutFilter);

        assertSame(original, assertInstanceOf(MvelWarningFilter.class, logger.getFilter()).get());
        assertNull(assertInstanceOf(MvelWarningFilter.class, withoutFilter.getFilter()).get());
    }

    @Test
    @DisplayName("a filter that supplies another filter, but isn't the engine's, is kept as the one records go to")
    void otherSupplierIsKept() {
        Logger logger = Logger.getAnonymousLogger();
        Filter other = new OtherSupplierFilter();
        logger.setFilter(other);

        MvelWarningFilter.install(logger);

        assertSame(other, assertInstanceOf(MvelWarningFilter.class, logger.getFilter()).get());
    }

    @Test
    @DisplayName("a filter with the engine's class name that supplies something else is kept as the one records go to")
    void sameNameSupplyingNoFilterIsKept() throws ReflectiveOperationException {
        Logger logger = Logger.getAnonymousLogger();
        Filter sameName = (Filter) defineInAnotherClassLoader(sameNameSupplyingAString()).getConstructor()
                .newInstance();
        logger.setFilter(sameName);

        MvelWarningFilter filter = MvelWarningFilter.install(logger);

        assertEquals(MvelWarningFilter.class.getName(), sameName.getClass().getName());
        assertSame(sameName, filter.get());
        assertTrue(filter.isLoggable(KEEP));
    }

    @Test
    @DisplayName("another class loader's copy is asked while it lives, and the filter it held is kept in its place")
    void copyFromAnotherClassLoader() throws Exception {
        Class<?> copy = copyInAnotherClassLoader();
        Logger logger = Logger.getAnonymousLogger();
        Filter original = record -> !"drop".equals(record.getMessage());
        logger.setFilter(original);
        Object[] installed = callCopy(copy, "install", new Class<?>[] {Logger.class}, logger);
        Filter copied = logger.getFilter();
        Filter copyOverEngines = (Filter) installed[1];

        MvelWarningFilter filter = MvelWarningFilter.install(logger);

        assertNotSame(MvelWarningFilter.class, copy);
        assertSame(copied, installed[0]);
        assertSame(copy, copied.getClass());
        assertSame(original, filter.get(), "the copy is kept in place of the filter it held");
        // Initializing the copy installed it over this class loader's filter on MVEL's logger, as a second deployment
        // would: it asks this class loader's filter, and keeps the one before it.
        assertSame(copy, copyOverEngines.getClass());
        assertSame(MvelWarningFilter.FILTER.get(), ((Supplier<?>) copyOverEngines).get());
        boolean outermost = MvelWarningFilter.enter();
        try {
            assertFalse(copyOverEngines.isLoggable(KEEP), "the copy doesn't cover this class loader's rules");
        } finally {
            MvelWarningFilter.leave(outermost);
        }
        assertTrue(copyOverEngines.isLoggable(KEEP));
    }

    @Test
    @DisplayName("with two class loaders' copies on one logger, the rules of both are covered")
    void bothClassLoadersAreCovered() throws Exception {
        Class<?> copy = copyInAnotherClassLoader();
        Logger logger = Logger.getAnonymousLogger();
        logger.setFilter(record -> !"drop".equals(record.getMessage()));
        callCopy(copy, "install", new Class<?>[] {Logger.class}, logger);
        Filter copied = logger.getFilter();

        Filter filter = MvelWarningFilter.install(logger);

        assertTrue(filter.isLoggable(KEEP));
        assertFalse(filter.isLoggable(DROP));
        Object copyOutermost = callCopy(copy, "enter", new Class<?>[0])[0];
        try {
            assertFalse(filter.isLoggable(KEEP), "the other class loader's rules aren't covered");
        } finally {
            callCopy(copy, "leave", new Class<?>[] {boolean.class}, copyOutermost);
        }
        boolean outermost = MvelWarningFilter.enter();
        try {
            assertFalse(filter.isLoggable(KEEP), "this class loader's rules aren't covered");
        } finally {
            MvelWarningFilter.leave(outermost);
        }
        assertTrue(filter.isLoggable(KEEP));
        Reference.reachabilityFence(copied);
    }

    @Test
    @DisplayName("once another class loader's copy is gone, the filter asks the one the copy held")
    void copyGoneFallsBackToPrevious() {
        Filter original = record -> !"drop".equals(record.getMessage());
        Filter copied = record -> false;
        WeakReference<Filter> copy = new WeakReference<>(copied);
        MvelWarningFilter filter = new MvelWarningFilter(copy, original);

        assertFalse(filter.isLoggable(KEEP), "the copy isn't asked");
        copy.clear();

        assertTrue(filter.isLoggable(KEEP));
        assertFalse(filter.isLoggable(DROP));
        assertSame(original, filter.get());
        Reference.reachabilityFence(copied);
    }

    /**
     * Creates a filter of a copy of this class, sets it on a logger, and installs this class's filter over it. The
     * installed filter holds the copy's filter only weakly, so the caller's reference is the only strong one.
     *
     * @return The copy's filter
     */
    private static Filter installOverCopy(Class<?> copy, Logger logger, Filter previous)
            throws ReflectiveOperationException {
        Constructor<?> constructor = copy.getDeclaredConstructor(Reference.class, Filter.class);
        constructor.setAccessible(true);
        Filter engines = MvelWarningFilter.LOGGER.getFilter();
        Filter copied;
        try {
            // Creating it initializes the copy, which installs its own filter on MVEL's logger; that's undone.
            copied = (Filter) constructor.newInstance(null, previous);
        } finally {
            MvelWarningFilter.LOGGER.setFilter(engines);
        }
        logger.setFilter(copied);
        MvelWarningFilter.install(logger);
        return copied;
    }

    @Test
    @DisplayName("another class loader's copy is held only weakly, so it can be collected while the filter stays")
    void copyIsHeldWeakly() throws Exception {
        Class<?> copy = copyInAnotherClassLoader();
        Logger logger = Logger.getAnonymousLogger();
        Filter alive = installOverCopy(copy, logger, record -> !"drop".equals(record.getMessage()));
        WeakReference<Object> copied = new WeakReference<>(alive);
        Filter filter = logger.getFilter();
        Object copyOutermost = callCopy(copy, "enter", new Class<?>[0])[0];
        try {
            assertFalse(filter.isLoggable(KEEP), "the copy isn't asked while it lives");
            // Until here the copy's filter is held strongly, as its class loader would hold it; from here only weakly.
            Reference.reachabilityFence(alive);
            alive = null;
            for (int i = 0; i < 50 && copied.get() != null; i++) {
                System.gc();
                Thread.sleep(20);
            }

            assertNull(copied.get(), "the copy's filter is still held strongly after 50 garbage collections");
            // The copy's rule is still running, but only the copy dropped its records: the filter it held decides.
            assertTrue(filter.isLoggable(KEEP));
            assertFalse(filter.isLoggable(DROP));
        } finally {
            callCopy(copy, "leave", new Class<?>[] {boolean.class}, copyOutermost);
        }
    }
}
