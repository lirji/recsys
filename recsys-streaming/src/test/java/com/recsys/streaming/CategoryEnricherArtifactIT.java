package com.recsys.streaming;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** package后独立JVM加载最终shade产物，真实PG证明XML与非Boot运行依赖齐备。 */
@Testcontainers
class CategoryEnricherArtifactIT {
    @Container static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");
    @TempDir Path temporary;

    @Test void finalArtifactExecutesCategoryMapperWithoutMavenClasspath() throws Exception {
        try (var c = PG.createConnection(""); var s = c.createStatement()) {
            s.execute("""
                    CREATE TABLE item (item_id BIGINT, category TEXT);
                    COMMENT ON TABLE item IS 'shade产物独占验证目录';
                    COMMENT ON COLUMN item.item_id IS '含历史空值的目录ID';
                    COMMENT ON COLUMN item.category IS '类目';
                    INSERT INTO item VALUES (9007199254740993,'artifact-类目'),(NULL,'null-id');
                    """);
        }
        Path artifact = Path.of(System.getProperty("streaming.artifact")).toRealPath();
        String testClasses = System.getProperty("streaming.testClasses");
        Path output = temporary.resolve("artifact-probe.log");
        Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", artifact + File.pathSeparator + testClasses,
                CategoryEnricherArtifactProbe.class.getName(), PG.getJdbcUrl(), PG.getUsername(), PG.getPassword(),
                artifact.toString()).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            assertTrue(child.waitFor(45, TimeUnit.SECONDS), "artifact probe timed out");
            String logs = Files.readString(output);
            assertEquals(0, child.exitValue(), logs);
            assertTrue(logs.contains("STREAMING_ARTIFACT_QUERY_PASS"), logs);
        } finally {
            if (child.isAlive()) child.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
        }
    }
}
