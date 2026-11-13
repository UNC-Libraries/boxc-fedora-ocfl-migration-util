package edu.unc.lib.boxc.migration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ObjectMigrationServiceTest {
    private static final UUID ID = UUID.fromString("3f7510bc-7f53-4ae2-b8fc-0fb4ccd95dbb");

    @TempDir
    private Path tempDir;

    @Test
    void removesCurrentAttemptObjectWhenSideloadIsRejected() throws Exception {
        final MigrationConfig config = config();
        final Path staging = Files.createDirectories(tempDir.resolve("staging"));
        final Path exports = Files.createDirectories(tempDir.resolve("exports"));
        final ObjectIdentity identity = identity();
        final ObjectMigrationService service = service(config, exports, staging, target(true));

        final MigrationResult result = service.migrate(ID);

        assertFalse(result.success());
        assertEquals(MigrationResult.FailureStage.SIDELOAD, result.failureStage());
        assertTrue(result.placedLiveObject());
        assertFalse(Files.exists(identity.objectPath(config.ocflRoot())));
        assertFalse(Files.exists(identity.objectPath(staging)));
    }

    @Test
    void retainsPreexistingObjectWhenSideloadIsRejected() throws Exception {
        final MigrationConfig config = config();
        final Path staging = Files.createDirectories(tempDir.resolve("staging"));
        final Path exports = Files.createDirectories(tempDir.resolve("exports"));
        final ObjectIdentity identity = identity();
        writeObject(identity.objectPath(config.ocflRoot()), identity);
        final ObjectMigrationService service = service(config, exports, staging, target(true));

        final MigrationResult result = service.migrate(ID);

        assertFalse(result.success());
        assertFalse(result.placedLiveObject());
        assertTrue(Files.exists(identity.objectPath(config.ocflRoot()).resolve("v1/content/file.txt")));
    }

    private ObjectMigrationService service(final MigrationConfig config, final Path exports, final Path staging,
                                           final TargetRepository target) {
        final AttemptExporter exporter = (identity, directory) -> { };
        final AttemptUpgrader upgrader = new AttemptUpgrader() {
            @Override
            public void migrate(final ObjectIdentity identity, final Path directory) throws Exception {
                writeObject(identity.objectPath(staging), identity);
            }

            @Override
            public void close() {
            }
        };
        return new ObjectMigrationService(config, exports, staging, exporter, upgrader, new OcflPromoter(), target);
    }

    private TargetRepository target(final boolean rejected) {
        return new TargetRepository() {
            @Override
            public void sideloadAndVerify(final ObjectIdentity identity) throws TargetRepositoryException {
                throw new TargetRepositoryException("rejected", MigrationResult.FailureStage.SIDELOAD, rejected);
            }

            @Override
            public void close() {
            }
        };
    }

    private MigrationConfig config() throws Exception {
        final Properties properties = new Properties();
        properties.setProperty(MigrationConfig.OCFL_ROOT, Files.createDirectories(tempDir.resolve("live")).toString());
        properties.setProperty(MigrationConfig.FEDORA_5_USERNAME, "source-user");
        properties.setProperty(MigrationConfig.FEDORA_5_PASSWORD, "source-password");
        properties.setProperty(MigrationConfig.FEDORA_6_USERNAME, "target-user");
        properties.setProperty(MigrationConfig.FEDORA_6_PASSWORD, "target-password");
        return MigrationConfig.fromProperties(properties);
    }

    private static ObjectIdentity identity() {
        return ObjectIdentity.from(ID, URI.create("http://source/rest/"), URI.create("http://target/rest/"));
    }

    private static void writeObject(final Path object, final ObjectIdentity identity) throws Exception {
        Files.createDirectories(object.resolve("v1/content"));
        Files.createDirectories(object.resolve("extensions/0005-mutable-head/head"));
        Files.writeString(object.resolve("extensions/0005-mutable-head/head/inventory.json"),
                "{\"id\":\"" + identity.internalId() + "\"}");
        Files.writeString(object.resolve("v1/content/file.txt"), "content");
    }
}
