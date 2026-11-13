package edu.unc.lib.boxc.migration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Reads UUID inputs and status logs using the migration run format.
 */
final class ObjectIdSource {
    private ObjectIdSource() {
    }

    /** Reads unique UUIDs from a user-provided input list, ignoring blank lines. */
    static Set<UUID> readInput(final Path path) throws IOException {
        final Set<UUID> ids = new LinkedHashSet<>();
        try (var lines = Files.lines(path)) {
            final var iterator = lines.iterator();
            int lineNumber = 0;
            while (iterator.hasNext()) {
                lineNumber++;
                final String line = iterator.next().trim();
                if (!line.isEmpty()) {
                    ids.add(parse(line, path, lineNumber));
                }
            }
        }
        return ids;
    }

    /** Reads canonical UUIDs from a migration status log. */
    static Set<UUID> readStatus(final Path path) throws IOException {
        final Set<UUID> ids = new LinkedHashSet<>();
        try (var lines = Files.lines(path)) {
            final var iterator = lines.iterator();
            int lineNumber = 0;
            while (iterator.hasNext()) {
                lineNumber++;
                final String line = iterator.next();
                final UUID id = parse(line, path, lineNumber);
                if (!line.equals(id.toString())) {
                    throw new IllegalArgumentException("Non-canonical UUID in " + path + " line " + lineNumber);
                }
                ids.add(id);
            }
        }
        return ids;
    }

    private static UUID parse(final String value, final Path path, final int lineNumber) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid UUID in " + path + " line " + lineNumber, e);
        }
    }
}
