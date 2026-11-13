package edu.unc.lib.boxc.migration;

import java.util.UUID;

/** Performs one complete migration attempt. */
@FunctionalInterface
interface MigrationAttempt {
    /**
     * @param id object UUID
     * @return terminal migration result
     */
    MigrationResult migrate(UUID id);
}
