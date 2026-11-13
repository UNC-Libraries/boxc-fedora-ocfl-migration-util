package edu.unc.lib.boxc.migration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OcflPromoterTest {
    @TempDir
    private Path tempDir;

    @Test
    void copiesValidatedObjectAndRemovesStaging() throws Exception {
        final ObjectIdentity identity = identity();
        final Path staging = Files.createDirectory(tempDir.resolve("staging"));
        final Path live = Files.createDirectory(tempDir.resolve("live"));
        final Path staged = writeObject(staging, identity, "content");

        final OcflPromoter.Promotion promotion = new OcflPromoter().promote(identity, staging, live);

        assertTrue(promotion.placedLiveObject());
        assertFalse(Files.exists(staged));
        assertTrue(Files.isRegularFile(promotion.livePath().resolve("v1/content/file.txt")));
    }

    @Test
    void retainsExistingLiveObjectAndDiscardsRedundantStaging() throws Exception {
        final ObjectIdentity identity = identity();
        final Path staging = Files.createDirectory(tempDir.resolve("staging"));
        final Path live = Files.createDirectory(tempDir.resolve("live"));
        final Path existing = writeObject(live, identity, "existing");
        final Path staged = writeObject(staging, identity, "replacement");

        final OcflPromoter.Promotion promotion = new OcflPromoter().promote(identity, staging, live);

        assertFalse(promotion.placedLiveObject());
        assertFalse(Files.exists(staged));
        assertTrue(Files.exists(existing.resolve("v1/content/file.txt")));
    }

    @Test
    void rejectsMissingOrWrongInventoryBeforePromotion() throws Exception {
        final ObjectIdentity identity = identity();
        final Path staging = Files.createDirectory(tempDir.resolve("staging"));
        final Path live = Files.createDirectory(tempDir.resolve("live"));
        final Path object = identity.objectPath(staging);
        Files.createDirectories(object);
        Files.createDirectories(object.resolve("extensions/0005-mutable-head/head"));
        Files.writeString(object.resolve("extensions/0005-mutable-head/head/inventory.json"), "{\"id\":\"wrong\"}");

        assertThrows(IllegalStateException.class, () -> new OcflPromoter().promote(identity, staging, live));
    }

    private ObjectIdentity identity() {
        return ObjectIdentity.from(UUID.fromString("3f7510bc-7f53-4ae2-b8fc-0fb4ccd95dbb"),
                URI.create("http://source/rest/"), URI.create("http://target/rest/"));
    }

    private static Path writeObject(final Path root, final ObjectIdentity identity, final String content) throws Exception {
        final Path object = identity.objectPath(root);
        Files.createDirectories(object.resolve("v1/content"));
        Files.createDirectories(object.resolve("extensions/0005-mutable-head/head"));
        Files.writeString(object.resolve("extensions/0005-mutable-head/head/inventory.json"),
                "{\"id\":\"" + identity.internalId() + "\"}");
        Files.writeString(object.resolve("v1/content/file.txt"), content);
        return object;
    }
}
