package io.github.brantunger.unruly.mvel;

import java.net.URL;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** A class loader that records the classes and resources it is asked to load. */
final class RecordingClassLoader extends ClassLoader {

    final List<String> loadedClasses = new CopyOnWriteArrayList<>();
    final List<String> resources = new CopyOnWriteArrayList<>();

    RecordingClassLoader() {
        super(RecordingClassLoader.class.getClassLoader());
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        loadedClasses.add(name);
        return super.loadClass(name, resolve);
    }

    @Override
    public URL getResource(String name) {
        resources.add(name);
        return super.getResource(name);
    }
}
