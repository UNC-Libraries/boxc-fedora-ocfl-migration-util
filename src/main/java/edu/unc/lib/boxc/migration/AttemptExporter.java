package edu.unc.lib.boxc.migration;

import java.nio.file.Path;

/**
 * Exports one archival group into an attempt-specific directory.
 */
interface AttemptExporter {
    /** Exports and validates the root RDF for an archival group. */
    void export(ObjectIdentity identity, Path attemptDirectory) throws Exception;
}
