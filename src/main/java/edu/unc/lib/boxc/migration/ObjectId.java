package edu.unc.lib.boxc.migration;

import java.util.UUID;

/** Identifies a content object or a deposit record. */
record ObjectId(UUID uuid, boolean deposit) {
    private static final String DEPOSITS_PREFIX = "deposits/";

    static ObjectId parse(final String value) {
        final boolean deposit = value.startsWith(DEPOSITS_PREFIX);
        return new ObjectId(UUID.fromString(deposit ? value.substring(DEPOSITS_PREFIX.length()) : value), deposit);
    }

    @Override
    public String toString() {
        return deposit ? DEPOSITS_PREFIX + uuid : uuid.toString();
    }
}
