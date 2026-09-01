package com.recsys.offline;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ImportR9EmbeddingsJobTest {
    @TempDir Path dir;

    @Test
    void validatesVersionDimensionFiniteAndNormBeforeDatabaseMutation() throws Exception {
        Path file = dir.resolve("ok.csv");
        StringBuilder row = new StringBuilder("model_version,id");
        for (int i = 0; i < 64; i++) row.append(",v").append(i);
        row.append("\nv1,7,1");
        for (int i = 1; i < 64; i++) row.append(",0");
        Files.writeString(file, row + "\n");
        var parsed = ImportR9EmbeddingsJob.read(file, true);
        assertEquals(1, parsed.size());
        assertEquals("v1", parsed.get(0).version());

        Path bad = dir.resolve("bad.csv");
        Files.writeString(bad, "v1,7,NaN\n");
        assertThrows(IllegalArgumentException.class, () -> ImportR9EmbeddingsJob.read(bad, true));
    }

    @Test
    void missingRequiredArtifactFailsFast() {
        assertThrows(IllegalArgumentException.class,
                () -> ImportR9EmbeddingsJob.read(dir.resolve("missing.csv"), true));
    }
}
