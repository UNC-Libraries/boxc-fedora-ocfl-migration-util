package edu.unc.lib.boxc.migration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MainTest {

    @TempDir
    private Path tempDir;

    @Test
    void resolvesWorkingDirectoryAndConfiguresLogPath() {
        final Path workingDirectory = tempDir.resolve("work");

        final int exitCode = new CommandLine(new Main()).execute(
                "--working-dir", workingDirectory.toString());

        assertEquals(0, exitCode);
        assertTrue(Files.isDirectory(workingDirectory));
        assertEquals(workingDirectory.resolve("migration.log").toString(),
                System.getProperty(Main.LOG_PATH_PROPERTY));
    }

    @Test
    void enablesStandardHelp() {
        assertEquals(0, new CommandLine(new Main()).execute("--help"));
    }

    @Test
    void createsMigrationExportConfiguration() {
        final URI repositoryRoot = URI.create("http://localhost:8181/fcrepo/rest");

        final var config = Main.exportConfig(repositoryRoot, "user", "password");

        assertTrue(config.isExport());
        assertEquals(repositoryRoot, config.getRepositoryRoot());
        assertEquals("user", config.getUsername());
        assertEquals("password", config.getPassword());
        assertTrue(config.isIncludeBinaries());
        assertTrue(config.isStreaming());
        assertEquals("application/n-triples", config.getRdfLanguage());
        assertEquals("http://www.w3.org/ns/ldp#contains", config.getPredicates()[0]);
        assertTrue(config.getAuditLog().isInfoEnabled());
    }
}
