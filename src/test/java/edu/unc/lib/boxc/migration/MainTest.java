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
    void initializesRunAndConfiguresLogPath() throws Exception {
        final Path workingDirectory = tempDir.resolve("work");
        final Path ocflRoot = Files.createDirectory(tempDir.resolve("ocfl"));
        final Path ids = tempDir.resolve("ids.txt");
        final Path config = tempDir.resolve("config.properties");
        Files.writeString(ids, "3f7510bc-7f53-4ae2-b8fc-0fb4ccd95dbb\n");
        Files.writeString(config, properties(ocflRoot));

        final int exitCode = new CommandLine(new Main()).execute(
                "--working-dir", workingDirectory.toString(),
                "--id-list-path", ids.toString(), "--config-path", config.toString());

        assertEquals(0, exitCode);
        assertTrue(Files.isDirectory(workingDirectory));
        assertEquals(workingDirectory.resolve("migration.log").toString(),
                System.getProperty(Main.LOG_PATH_PROPERTY));
        assertTrue(Files.exists(workingDirectory.resolve(RunDirectory.PROPERTIES_FILE)));
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

    private static String properties(final Path ocflRoot) {
        return "fedora_ocfl_root_dir=" + ocflRoot + "\n"
                + "fedora_5_username=user\n"
                + "fedora_5_password=password\n"
                + "fedora_6_username=user\n"
                + "fedora_6_password=password\n";
    }
}
