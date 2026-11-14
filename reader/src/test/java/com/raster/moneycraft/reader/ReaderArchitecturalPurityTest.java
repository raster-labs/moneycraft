package com.raster.moneycraft.reader;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Enforces MIG-003 standalone purity (zero Spring, JPA/Hibernate, Jakarta Servlet, Jackson, or network imports),
 * MCJ1-DATA-01 exact fixed-point arithmetic (zero float/double), and QUAL-001 zero stubs in reader/src/main.
 */
class ReaderArchitecturalPurityTest {

    private static final Path MAIN_JAVA_DIR = Path.of(
            "/Users/pushparaj/projects/Money_Craft/moneycraft-target/reader/src/main/java");

    private static final List<String> FORBIDDEN_IMPORT_PREFIXES = List.of(
            "org.springframework",
            "jakarta.",
            "javax.",
            "org.hibernate",
            "com.fasterxml.jackson",
            "java.net.",
            "java.sql."
    );

    private static final Pattern FLOATING_POINT_TOKEN =
            Pattern.compile("\\b(float|double|Float|Double)\\b");

    private static final Pattern STUB_MARKER =
            Pattern.compile("\\b(" + "UnsupportedOperation" + "Exception)\\b");

    @Test
    void enforcesZeroFrameworkZeroNetworkAndZeroFloatingPointInReaderMainSources() throws IOException {
        List<Path> javaFiles;
        try (Stream<Path> walk = Files.walk(MAIN_JAVA_DIR)) {
            javaFiles = walk.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        }
        assertTrue(javaFiles.size() >= 25, "Expected full reader source tree to be present");

        List<String> violations = new ArrayList<>();
        for (Path javaFile : javaFiles) {
            List<String> lines = Files.readAllLines(javaFile, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i).trim();
                int lineNo = i + 1;
                if (line.startsWith("import ")) {
                    String imported = line.substring("import ".length()).replace(";", "").trim();
                    for (String forbidden : FORBIDDEN_IMPORT_PREFIXES) {
                        if (imported.startsWith(forbidden)) {
                            violations.add(javaFile.getFileName() + ":" + lineNo + " forbidden import " + imported);
                        }
                    }
                    if (!imported.startsWith("java.") && !imported.startsWith("com.raster.moneycraft.reader.")) {
                        violations.add(javaFile.getFileName() + ":" + lineNo + " non-java.base import " + imported);
                    }
                }
                String codeWithoutComments = stripLineComment(line);
                if (!codeWithoutComments.startsWith("*") && !codeWithoutComments.startsWith("/*")) {
                    if (FLOATING_POINT_TOKEN.matcher(codeWithoutComments).find()) {
                        violations.add(javaFile.getFileName() + ":" + lineNo + " forbidden float/double token");
                    }
                    if (STUB_MARKER.matcher(codeWithoutComments).find()) {
                        violations.add(javaFile.getFileName() + ":" + lineNo + " forbidden stub exception");
                    }
                }
            }
        }

        assertEquals(List.of(), violations, "Reader architectural purity violations detected");
    }

    private static String stripLineComment(String line) {
        int idx = line.indexOf("//");
        return idx >= 0 ? line.substring(0, idx) : line;
    }
}
