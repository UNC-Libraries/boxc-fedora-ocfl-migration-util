package edu.unc.lib.boxc.migration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FedoraExporterTest {
    @TempDir
    private Path tempDir;

    @Test
    void acceptsConfiguredArchivalGroupTypeAndRejectsOtherRoots() throws Exception {
        final Path archivalGroup = tempDir.resolve("archival-group.nt");
        Files.writeString(archivalGroup, "<info:fedora/root> <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> "
                + "<http://cdr.unc.edu/definitions/model#Work> .\n");
        assertDoesNotThrow(() -> FedoraExporter.validateArchivalGroup(archivalGroup, UUID.randomUUID()));

        final Path other = tempDir.resolve("other.nt");
        Files.writeString(other, "<info:fedora/root> <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> "
                + "<http://www.w3.org/ns/ldp#Container> .\n");
        assertThrows(IllegalArgumentException.class, () -> FedoraExporter.validateArchivalGroup(other, UUID.randomUUID()));
    }
}
