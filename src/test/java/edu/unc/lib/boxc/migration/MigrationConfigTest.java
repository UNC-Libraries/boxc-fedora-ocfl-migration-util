package edu.unc.lib.boxc.migration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MigrationConfigTest {
    @TempDir
    private Path tempDir;

    @Test
    void appliesDefaultsAndNormalizesUris() {
        final Properties properties = requiredProperties();
        properties.setProperty(MigrationConfig.FEDORA_5_BASE_URI, "http://source.example/fcrepo/rest///");

        final MigrationConfig config = MigrationConfig.fromProperties(properties);

        assertEquals("http://source.example/fcrepo/rest/", config.fedora5BaseUri().toString());
        assertEquals("http://localhost:8185/fcrepo/rest/", config.fedora6BaseUri().toString());
        assertEquals(1, config.workerCount());
        assertEquals(300, config.shutdownTimeoutSeconds());
        assertFalse(config.toString().contains("source-password"));
        assertFalse(config.toString().contains("target-password"));
    }

    @Test
    void rejectsMissingCredentialsAndInvalidCounts() {
        final Properties missing = requiredProperties();
        missing.remove(MigrationConfig.FEDORA_5_PASSWORD);
        assertThrows(IllegalArgumentException.class, () -> MigrationConfig.fromProperties(missing));

        final Properties invalid = requiredProperties();
        invalid.setProperty(MigrationConfig.WORKER_COUNT, "0");
        assertThrows(IllegalArgumentException.class, () -> MigrationConfig.fromProperties(invalid));
    }

    private Properties requiredProperties() {
        final Properties properties = new Properties();
        properties.setProperty(MigrationConfig.OCFL_ROOT, tempDir.toString());
        properties.setProperty(MigrationConfig.FEDORA_5_USERNAME, "source-user");
        properties.setProperty(MigrationConfig.FEDORA_5_PASSWORD, "source-password");
        properties.setProperty(MigrationConfig.FEDORA_6_USERNAME, "target-user");
        properties.setProperty(MigrationConfig.FEDORA_6_PASSWORD, "target-password");
        return properties;
    }
}
