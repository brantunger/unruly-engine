package unruly.conventions;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.SetProperty;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * Fails unless the BOM's POM manages exactly the artifacts the build publishes, each at the version it is published
 * with. It reads the generated POM, which is what a Maven or Gradle user imports, rather than the build script's
 * constraints.
 */
@CacheableTask
public abstract class BomCoverageCheck extends DefaultTask {

    /** The BOM's generated POM. */
    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getPom();

    /**
     * Every artifact the build publishes, other than the BOM, as {@code group:artifactId:version}. The build derives
     * it from the projects that apply the publishing plugin, so a new published project is expected without anyone
     * listing it.
     */
    @Input
    public abstract SetProperty<String> getPublished();

    /** Written when the check passes, so Gradle can skip it until the POM or the published artifacts change. */
    @OutputFile
    public abstract RegularFileProperty getResult();

    @TaskAction
    public void check() throws IOException, ParserConfigurationException, SAXException {
        SortedSet<String> expected = new TreeSet<>(getPublished().get());
        // An empty set would pass whatever the POM says: the plugin the projects are found by was renamed, or the
        // artifacts were read before their projects set them.
        if (expected.isEmpty()) {
            throw new GradleException("No published artifact found, so the BOM check would pass whatever the BOM "
                    + "lists. Check how bom/build.gradle.kts finds the published projects.");
        }
        Element xml = parse(getPom().get().getAsFile());
        SortedSet<String> managed = new TreeSet<>();
        for (Element management : children(xml, "dependencyManagement")) {
            for (Element dependencies : children(management, "dependencies")) {
                for (Element dependency : children(dependencies, "dependency")) {
                    managed.add(text(dependency, "groupId") + ":" + text(dependency, "artifactId") + ":"
                            + text(dependency, "version"));
                }
            }
        }

        SortedSet<String> missing = new TreeSet<>(expected);
        missing.removeAll(managed);
        SortedSet<String> extra = new TreeSet<>(managed);
        extra.removeAll(expected);
        if (!missing.isEmpty() || !extra.isEmpty()) {
            StringBuilder message = new StringBuilder("The BOM must manage every published artifact and nothing else, "
                    + "at the version it is published with. Add or fix a constraint in bom/build.gradle.kts.");
            if (!missing.isEmpty()) {
                message.append("\nPublished but not in the BOM:\n  ").append(String.join("\n  ", missing));
            }
            if (!extra.isEmpty()) {
                message.append("\nIn the BOM but not published:\n  ").append(String.join("\n  ", extra));
            }
            throw new GradleException(message.toString());
        }
        Files.writeString(getResult().get().getAsFile().toPath(), String.join("\n", expected) + "\n");
    }

    /**
     * Parses the POM as Groovy's {@code XmlSlurper} did: namespace aware, with secure processing on and a DOCTYPE
     * disallowed.
     */
    private static Element parse(File file) throws IOException, ParserConfigurationException, SAXException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setValidating(false);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        try (InputStream stream = new FileInputStream(file)) {
            InputSource input = new InputSource(stream);
            input.setSystemId("file://" + file.getAbsolutePath());
            return factory.newDocumentBuilder().parse(input).getDocumentElement();
        }
    }

    /** The element's children with the given local name, in any namespace, as a GPath expression finds them. */
    private static List<Element> children(Element parent, String name) {
        List<Element> children = new ArrayList<>();
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element element && name.equals(element.getLocalName())) {
                children.add(element);
            }
        }
        return children;
    }

    /**
     * The text of the element's children with the given local name, joined and not trimmed, or an empty string if it
     * has none, as GPath's {@code text()} gives it, except that whitespace-only text is kept.
     */
    private static String text(Element parent, String name) {
        StringBuilder text = new StringBuilder();
        for (Element child : children(parent, name)) {
            text.append(child.getTextContent());
        }
        return text.toString();
    }
}
