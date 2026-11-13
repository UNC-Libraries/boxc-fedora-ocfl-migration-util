package edu.unc.lib.boxc.migration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Map;

/**
 * Validates and copies staged OCFL objects into live preservation storage.
 */
final class OcflPromoter {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path MUTABLE_HEAD_INVENTORY = Path.of("extensions/0005-mutable-head/head/inventory.json");

    /** Promotes one staged object without replacing an existing live object. */
    Promotion promote(final ObjectIdentity identity, final Path stagingRoot, final Path liveRoot) throws IOException {
        final Path staged = identity.objectPath(stagingRoot);
        final Path live = identity.objectPath(liveRoot);
        validateInventory(staged, identity.internalId());
        // Object already appears to be in Fedora's OCFL, so we will skip moving it and cleanup the staged files
        if (Files.exists(live)) {
            deleteTree(staged);
            return new Promotion(live, false);
        }

        boolean created = false;
        try {
            // Fill in parent directories in the OCFL root
            Files.createDirectories(live.getParent());
            try {
                // Create directory for the object itself, cleaning up if that triggers a collision
                Files.createDirectory(live);
                created = true;
            } catch (FileAlreadyExistsException e) {
                deleteTree(staged);
                return new Promotion(live, false);
            }
            // copy the staged object in to the OCFL root, make sure it looks okay, then cleanup the staged version
            var copiedTreeMap = copyTree(staged, live);
            compareTrees(copiedTreeMap, live);
            deleteTree(staged);
            return new Promotion(live, true);
        } catch (IOException | RuntimeException e) {
            if (created) {
                deleteTree(live);
            }
            deleteTree(staged);
            throw e;
        }
    }

    /** Removes an object directory when it was created by the current attempt. */
    void removeCurrentAttemptObject(final Promotion promotion) throws IOException {
        if (promotion.placedLiveObject()) {
            deleteTree(promotion.livePath());
        }
    }

    /** Recursively removes a directory without following symbolic links. */
    static void deleteTree(final Path directory) throws IOException {
        if (Files.notExists(directory)) {
            return;
        }
        Files.walkFileTree(directory, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(final Path dir, final IOException exception) throws IOException {
                if (exception != null) {
                    throw exception;
                }
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void validateInventory(final Path staged, final String expectedId) throws IOException {
        final Path inventory = staged.resolve(MUTABLE_HEAD_INVENTORY);
        if (!Files.isDirectory(staged) || !Files.isRegularFile(inventory)) {
            throw new IllegalStateException("Expected staged OCFL object is missing: " + staged);
        }
        final JsonNode root = JSON.readTree(inventory.toFile());
        if (!expectedId.equals(root.path("id").asText())) {
            throw new IllegalStateException("Staged OCFL inventory has an unexpected object ID: " + staged);
        }
    }

    private static Map<Path, FileInfo> copyTree(final Path source, final Path target) throws IOException {
        final Map<Path, FileInfo> copiedFiles = new HashMap<>();
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(final Path dir, final BasicFileAttributes attrs) throws IOException {
                if (Files.isSymbolicLink(dir)) {
                    throw new IOException("Symbolic links are not allowed in OCFL objects: " + dir);
                }
                if (!dir.equals(source)) {
                    Files.createDirectory(target.resolve(source.relativize(dir)));
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) throws IOException {
                if (!attrs.isRegularFile() || Files.isSymbolicLink(file)) {
                    throw new IOException("Unexpected OCFL entry: " + file);
                }
                final Path relativePath = source.relativize(file);
                copiedFiles.put(relativePath, copyWithDigest(file, target.resolve(relativePath)));
                return FileVisitResult.CONTINUE;
            }
        });
        return copiedFiles;
    }

    private static void compareTrees(final Map<Path, FileInfo> sourceFiles, final Path target) throws IOException {
        if (!sourceFiles.equals(files(target))) {
            throw new IOException("Promoted OCFL object does not match staging object");
        }
    }

    private static FileInfo copyWithDigest(final Path source, final Path target) throws IOException {
        final MessageDigest digest = newDigest();
        long size = 0;
        try (InputStream input = Files.newInputStream(source); OutputStream output = Files.newOutputStream(target)) {
            final byte[] buffer = new byte[8192];
            for (int read; (read = input.read(buffer)) >= 0;) {
                output.write(buffer, 0, read);
                digest.update(buffer, 0, read);
                size += read;
            }
        }
        return new FileInfo(size, java.util.HexFormat.of().formatHex(digest.digest()));
    }

    private static Map<Path, FileInfo> files(final Path root) throws IOException {
        final Map<Path, FileInfo> files = new HashMap<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(final Path dir, final BasicFileAttributes attrs) throws IOException {
                if (Files.isSymbolicLink(dir)) {
                    throw new IOException("Symbolic links are not allowed in OCFL objects: " + dir);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) throws IOException {
                if (!attrs.isRegularFile() || Files.isSymbolicLink(file)) {
                    throw new IOException("Unexpected OCFL entry: " + file);
                }
                files.put(root.relativize(file), new FileInfo(attrs.size(), digest(file)));
                return FileVisitResult.CONTINUE;
            }
        });
        return files;
    }

    private static String digest(final Path file) throws IOException {
        final MessageDigest digest = newDigest();
        try (InputStream input = Files.newInputStream(file)) {
            final byte[] buffer = new byte[8192];
            for (int read; (read = input.read(buffer)) >= 0;) {
                digest.update(buffer, 0, read);
            }
        }
        return java.util.HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 is unavailable", e);
        }
    }

    /**
     * Describes whether the current attempt placed the live object.
     *
     * @param livePath final object directory
     * @param placedLiveObject whether this attempt created the final directory
     */
    record Promotion(Path livePath, boolean placedLiveObject) {
    }

    /**
     * Captures a regular file's expected copy-validation attributes.
     *
     * @param size file size in bytes
     * @param sha1 SHA-1 hash in lowercase hexadecimal
     */
    private record FileInfo(long size, String sha1) {
    }
}
