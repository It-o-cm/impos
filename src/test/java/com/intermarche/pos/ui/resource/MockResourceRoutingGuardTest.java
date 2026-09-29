package com.intermarche.pos.ui.resource;

import jakarta.ws.rs.Path;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Anti-regression guard on the {@code src/mock} JAX-RS resources: NONE of them
 * may claim a broad class-level {@code @Path} of {@code /api} or {@code /}.
 * <p>
 * RESTEasy classic selects the resource CLASS by the most specific LITERAL path
 * first, so a mock at {@code @Path("/api")} (or {@code "/"}) beats
 * {@code PosHardwareResource} at {@code @Path("/")} and swallows every uncovered
 * {@code /api/**} URI — {@code /api/pos/scan} among them — answering 404 and
 * killing the scan bus of both the e2e tests and {@code quarkus:dev}. That is
 * exactly the trap {@link MockHardwareResource} documents and the one
 * {@code MockCampaignEngineResource} once fell into. Because {@code src/mock} is
 * on the MAIN classpath (add-source, so also in {@code quarkus:dev}), the trap
 * is not test-only, which is why this guard is plain JUnit 5 (no
 * {@code @QuarkusTest}) and runs in the normal unit suite.
 * <p>
 * It walks the {@code src/mock/java} tree, loads each class by its qualified
 * name and inspects its class-level {@code @Path}, so a NEW mock that reclaims a
 * broad path fails here by name rather than in a distant scenario.
 */
class MockResourceRoutingGuardTest {

    /** The mock source root, relative to the module base directory (surefire cwd). */
    private static final String MOCK_SOURCE_ROOT = "src/mock/java";

    /** Class-level paths a mock resource must never claim. */
    private static final List<String> FORBIDDEN_CLASS_PATHS = List.of("/api", "/");

    /**
     * Derives the fully qualified class name of a {@code .java} file from its
     * path under {@link #MOCK_SOURCE_ROOT}.
     *
     * @param relativePath the file path relative to the mock source root
     * @return the qualified class name (dots, no {@code .java} suffix)
     */
    private String qualifiedName(String relativePath) {
        String noSuffix = relativePath.substring(0, relativePath.length() - ".java".length());
        return noSuffix.replace('/', '.').replace('\\', '.');
    }

    /**
     * Lists every {@code .java} file under the mock source root, as paths
     * relative to that root, skipping {@code package-info}.
     *
     * @return the relative source paths
     * @throws IOException if the tree cannot be walked
     */
    private List<String> mockSourceFiles() throws IOException {
        java.nio.file.Path root = Paths.get(MOCK_SOURCE_ROOT);
        List<String> files = new ArrayList<>();
        try (Stream<java.nio.file.Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile)
                .filter(p -> p.toString().endsWith(".java"))
                .filter(p -> !p.getFileName().toString().equals("package-info.java"))
                .forEach(p -> files.add(root.relativize(p).toString()));
        }
        return files;
    }

    /**
     * Every mock resource carries a class-level {@code @Path} narrow enough not
     * to shadow the production resources: a mock claiming {@code /api} or
     * {@code /} fails this guard, named, with the routing rule spelled out.
     *
     * @throws Exception if the tree cannot be walked or a class cannot be loaded
     */
    @Test
    void noMockClaimsABroadRootPath() throws Exception {
        List<String> sources = mockSourceFiles();
        assertTrue(sources.size() >= 1, "the mock source root " + MOCK_SOURCE_ROOT + " should hold at least one resource");
        for (String source : sources) {
            String className = qualifiedName(source);
            Class<?> clazz = Class.forName(className);
            Path path = clazz.getAnnotation(Path.class);
            if (path == null) {
                continue;
            }
            if (FORBIDDEN_CLASS_PATHS.contains(path.value())) {
                fail(className + " declares a broad class @Path(\"" + path.value() + "\"), which shadows the "
                        + "production resources (RESTEasy picks the most specific literal class path first) and answers "
                        + "404 on uncovered /api/** URIs such as /api/pos/scan. Carry the full endpoint path on the "
                        + "class instead — see MockHardwareResource for the rationale.");
            }
        }
    }
}
