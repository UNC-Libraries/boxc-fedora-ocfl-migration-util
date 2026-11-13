package edu.unc.lib.boxc.migration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ObjectIdSourceTest {
    @TempDir
    private Path tempDir;

    @Test
    void normalizesAndDeduplicatesInputIds() throws Exception {
        final Path ids = tempDir.resolve("ids.txt");
        Files.writeString(ids, "\n3F7510BC-7F53-4AE2-B8FC-0FB4CCD95DBB\n3f7510bc-7f53-4ae2-b8fc-0fb4ccd95dbb\n");

        final var result = ObjectIdSource.readInput(ids);

        assertEquals(1, result.size());
        assertEquals("3f7510bc-7f53-4ae2-b8fc-0fb4ccd95dbb", result.iterator().next().toString());
    }

    @Test
    void rejectsInvalidInputAndNonCanonicalStatus() throws Exception {
        final Path invalid = tempDir.resolve("invalid.txt");
        Files.writeString(invalid, "not-a-uuid\n");
        assertThrows(IllegalArgumentException.class, () -> ObjectIdSource.readInput(invalid));

        final Path status = tempDir.resolve("status.log");
        Files.writeString(status, "3F7510BC-7F53-4AE2-B8FC-0FB4CCD95DBB\n");
        assertThrows(IllegalArgumentException.class, () -> ObjectIdSource.readStatus(status));
    }
}
