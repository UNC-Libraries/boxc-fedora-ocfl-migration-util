package edu.unc.lib.boxc.migration;

import java.nio.file.Path;

/**
 * Migrates one exported archival group into the shared staging OCFL repository.
 */
interface AttemptUpgrader extends AutoCloseable {
    /** Migrates the exported root and all descendants. */
    void migrate(ObjectIdentity identity, Path attemptDirectory) throws Exception;

    @Override
    void close();
}
