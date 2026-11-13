package edu.unc.lib.boxc.migration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StatusWriterTest {
    @TempDir
    private Path tempDir;

    @Test
    void writesFailureBeforeProgressAndRemovesSuccessfulRetry() throws Exception {
        final Path progress = Files.createFile(tempDir.resolve("progress.log"));
        final Path failures = Files.createFile(tempDir.resolve("failures.log"));
        final UUID id = UUID.fromString("3f7510bc-7f53-4ae2-b8fc-0fb4ccd95dbb");
        final StatusWriter writer = new StatusWriter(progress, failures);

        writer.recordFailure(id, false);
        assertEquals(id + System.lineSeparator(), Files.readString(failures));
        assertEquals(id + System.lineSeparator(), Files.readString(progress));

        writer.recordSuccess(id, true);
        assertEquals("", Files.readString(failures));
        assertEquals(id + System.lineSeparator(), Files.readString(progress));
    }
}
