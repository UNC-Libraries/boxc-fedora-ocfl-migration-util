package edu.unc.lib.boxc.migration;

/**
 * Reports a target Fedora protocol or transport failure.
 */
final class TargetRepositoryException extends Exception {
    private final boolean rejected;
    private final MigrationResult.FailureStage failureStage;

    TargetRepositoryException(final String message, final MigrationResult.FailureStage failureStage,
                              final boolean rejected) {
        super(message);
        this.failureStage = failureStage;
        this.rejected = rejected;
    }

    TargetRepositoryException(final String message, final MigrationResult.FailureStage failureStage,
                              final Throwable cause) {
        super(message, cause);
        this.failureStage = failureStage;
        rejected = false;
    }

    /** Indicates that Fedora definitively rejected the sideload request with a 4xx status. */
    boolean rejected() {
        return rejected;
    }

    /** Returns the attempt stage at which the target interaction failed. */
    MigrationResult.FailureStage failureStage() {
        return failureStage;
    }
}
