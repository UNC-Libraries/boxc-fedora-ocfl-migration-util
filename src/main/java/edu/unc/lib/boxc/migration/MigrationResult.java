package edu.unc.lib.boxc.migration;

import java.util.UUID;

/**
 * Terminal result of one object migration attempt.
 *
 * @param id object UUID
 * @param success whether the attempt completed successfully
 * @param failureStage stage at which an unsuccessful attempt failed
 * @param message result summary suitable for logging
 * @param placedLiveObject whether this attempt placed an object in live OCFL storage
 */
public record MigrationResult(UUID id, boolean success, FailureStage failureStage, String message,
                              boolean placedLiveObject) {
    /**
     * Named stage at which an object attempt can fail.
     */
    public enum FailureStage {
        EXPORT, UPGRADE, PROMOTION, SIDELOAD, VERIFY, STATUS
    }
}
