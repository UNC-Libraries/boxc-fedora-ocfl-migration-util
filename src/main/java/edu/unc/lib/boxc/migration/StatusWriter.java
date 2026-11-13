package edu.unc.lib.boxc.migration;

import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Synchronized durable updates to progress and failure status logs.
 */
final class StatusWriter {
    private final Path progressPath;
    private final Path failurePath;
    private final Set<UUID> progress;
    private final Set<UUID> failures;

    /** Opens a writer using the existing progress and failure status logs. */
    StatusWriter(final Path progressPath, final Path failurePath) throws IOException {
        this.progressPath = progressPath;
        this.failurePath = failurePath;
        progress = new LinkedHashSet<>(ObjectIdSource.readStatus(progressPath));
        failures = new LinkedHashSet<>(ObjectIdSource.readStatus(failurePath));
    }

    /** Records a successful initial migration or clears a successful retry failure. */
    synchronized void recordSuccess(final UUID id, final boolean retry) throws IOException {
        if (retry) {
            if (failures.remove(id)) {
                rewriteFailures();
            }
        } else if (progress.add(id)) {
            append(progressPath, id);
        }
    }

    /** Records an initial migration failure in both status logs. */
    synchronized void recordFailure(final UUID id, final boolean retry) throws IOException {
        if (!retry && failures.add(id)) {
            append(failurePath, id);
        }
        if (!retry && progress.add(id)) {
            append(progressPath, id);
        }
    }

    private static void append(final Path path, final UUID id) throws IOException {
        try (Writer writer = Files.newBufferedWriter(path, StandardOpenOption.APPEND)) {
            writer.write(id.toString());
            writer.write(System.lineSeparator());
            writer.flush();
        }
    }

    private void rewriteFailures() throws IOException {
        final Path temporary = Files.createTempFile(failurePath.getParent(), failurePath.getFileName().toString(), ".tmp");
        try {
            try (Writer writer = Files.newBufferedWriter(temporary)) {
                for (final UUID failure : failures) {
                    writer.write(failure.toString());
                    writer.write(System.lineSeparator());
                }
                writer.flush();
            }
            RunDirectory.atomicMove(temporary, failurePath);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
