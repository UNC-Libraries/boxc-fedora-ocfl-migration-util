package edu.unc.lib.boxc.migration;

/** Performs one complete migration attempt. */
@FunctionalInterface
interface MigrationAttempt {
    /**
     * @param id object identifier
     * @return terminal migration result
     */
    MigrationResult migrate(ObjectId id);
}
