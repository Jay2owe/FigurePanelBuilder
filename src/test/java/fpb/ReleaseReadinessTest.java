/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package fpb;

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ReleaseReadinessTest {

    @Test
    public void compileScopeDependencyIsOnlyImageJ() throws Exception {
        Document pom = DocumentBuilderFactory.newInstance()
                .newDocumentBuilder().parse(new File("pom.xml"));
        NodeList dependencies = pom.getElementsByTagName("dependency");
        List<String> compileDependencies = new ArrayList<String>();
        for (int i = 0; i < dependencies.getLength(); i++) {
            org.w3c.dom.Element dependency =
                    (org.w3c.dom.Element) dependencies.item(i);
            String scope = text(dependency, "scope");
            if (scope.length() == 0 || "compile".equals(scope)) {
                compileDependencies.add(text(dependency, "groupId")
                        + ":" + text(dependency, "artifactId"));
            }
        }

        assertEquals(Arrays.asList("net.imagej:ij"), compileDependencies);
    }

    @Test
    public void requiredReleaseFilesArePresent() {
        for (String path : Arrays.asList("README.md", "LICENSE", "CHANGELOG.md",
                "CITATION.cff", "PUBLISHING_AUDIT.md")) {
            assertTrue(path, new File(path).isFile());
        }
    }

    @Test
    public void repositoryContainsNoLocalPathsCredentialsOrPrivateDataMarkers()
            throws Exception {
        Pattern forbidden = Pattern.compile(
                "C:\\\\Users|Drop" + "box|password\\s*=|secret\\s*=|"
                        + "access[_-]?token\\s*=|api[_-]?key\\s*=",
                Pattern.CASE_INSENSITIVE);
        List<String> hits = new ArrayList<String>();
        collectHits(new File("."), forbidden, hits);

        assertTrue("Private release marker found: " + hits, hits.isEmpty());
    }

    private static String text(org.w3c.dom.Element element, String name) {
        NodeList nodes = element.getElementsByTagName(name);
        if (nodes.getLength() == 0) return "";
        String text = nodes.item(0).getTextContent();
        return text == null ? "" : text.trim();
    }

    private static void collectHits(File file, Pattern forbidden, List<String> hits)
            throws Exception {
        if (file == null || !file.exists()) return;
        String name = file.getName();
        if ("target".equals(name) || ".git".equals(name)) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children == null) return;
            for (File child : children) collectHits(child, forbidden, hits);
            return;
        }
        if (!isTextFile(file)) return;
        List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
        for (int i = 0; i < lines.size(); i++) {
            if (forbidden.matcher(lines.get(i)).find()) {
                hits.add(file.getPath() + ":" + (i + 1));
            }
        }
    }

    private static boolean isTextFile(File file) {
        String name = file.getName().toLowerCase(java.util.Locale.ROOT);
        return name.endsWith(".java") || name.endsWith(".md")
                || name.endsWith(".xml") || name.endsWith(".yml")
                || name.endsWith(".yaml") || name.endsWith(".cff")
                || name.endsWith(".config") || name.endsWith(".txt");
    }
}
