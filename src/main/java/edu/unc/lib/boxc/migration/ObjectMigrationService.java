package edu.unc.lib.boxc.migration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Performs the complete export, upgrade, promotion, and target verification for one object.
 */
final class ObjectMigrationService implements AutoCloseable, MigrationAttempt {
    private static final Logger log = LoggerFactory.getLogger(ObjectMigrationService.class);

    private final MigrationConfig config;
    private final Path exportsDirectory;
    private final Path stagingRoot;
    private final AttemptExporter exporter;
    private final AttemptUpgrader upgrader;
    private final OcflPromoter promoter;
    private final TargetRepository target;

    /** Creates the production service using shared external utility adapters. */
    ObjectMigrationService(final MigrationConfig config, final Path workingDirectory) {
        this(config, workingDirectory.resolve(RunDirectory.EXPORTS_DIRECTORY),
                workingDirectory.resolve(RunDirectory.UPGRADE_OCFL_ROOT_DIRECTORY), new FedoraExporter(config),
                new FedoraUpgrader(config, workingDirectory.resolve(RunDirectory.UPGRADE_DIRECTORY)),
                new OcflPromoter(), new FedoraTargetRepository(config));
    }

    /** Creates a service with adapters supplied by tests or alternate runtime integrations. */
    ObjectMigrationService(final MigrationConfig config, final Path exportsDirectory, final Path stagingRoot,
                           final AttemptExporter exporter, final AttemptUpgrader upgrader, final OcflPromoter promoter,
                           final TargetRepository target) {
        this.config = config;
        this.exportsDirectory = exportsDirectory;
        this.stagingRoot = stagingRoot;
        this.exporter = exporter;
        this.upgrader = upgrader;
        this.promoter = promoter;
        this.target = target;
    }

    /** Runs one complete object attempt and reports its terminal result. */
    @Override
    public MigrationResult migrate(final UUID id) {
        final ObjectIdentity identity = ObjectIdentity.from(id, config.fedora5BaseUri(), config.fedora6BaseUri());
        Path exportDirectory = null;
        OcflPromoter.Promotion promotion = null;
        MigrationResult result;
        // Export from Fedora 5
        try {
            exportDirectory = Files.createTempDirectory(exportsDirectory, id + "-");
            exporter.export(identity, exportDirectory);
        } catch (Exception e) {
            result = failure(id, MigrationResult.FailureStage.EXPORT, e, false);
            return cleanup(result, exportDirectory, identity, null);
        }

        // Migrate the export to OCFL
        try {
            upgrader.migrate(identity, exportDirectory);
        } catch (Exception e) {
            result = failure(id, MigrationResult.FailureStage.UPGRADE, e, false);
            return cleanup(result, exportDirectory, identity, null);
        }

        // Move the OCFL into Fedora 6's OCFL root
        try {
            promotion = promoter.promote(identity, stagingRoot, config.ocflRoot());
        } catch (Exception e) {
            result = failure(id, MigrationResult.FailureStage.PROMOTION, e, false);
            return cleanup(result, exportDirectory, identity, promotion);
        }

        // Sideload the object in Fedora 6 to trigger indexing, then make sure it is available
        try {
            target.sideloadAndVerify(identity);
            result = new MigrationResult(id, true, null, "Migrated", promotion.placedLiveObject());
        } catch (TargetRepositoryException e) {
            if (e.rejected() && promotion.placedLiveObject()) {
                try {
                    promoter.removeCurrentAttemptObject(promotion);
                } catch (IOException cleanupFailure) {
                    e.addSuppressed(cleanupFailure);
                }
            }
            result = failure(id, e.failureStage(), e, promotion.placedLiveObject());
        }
        return cleanup(result, exportDirectory, identity, promotion);
    }

    /** Closes shared target and upgrade adapters after all worker attempts finish. */
    @Override
    public void close() {
        try {
            target.close();
        } finally {
            upgrader.close();
        }
    }

    private MigrationResult cleanup(final MigrationResult result, final Path exportDirectory, final ObjectIdentity identity,
                                    final OcflPromoter.Promotion promotion) {
        try {
            if (exportDirectory != null) {
                OcflPromoter.deleteTree(exportDirectory);
            }
            if (!result.success() && promotion == null) {
                OcflPromoter.deleteTree(identity.objectPath(stagingRoot));
            }
            return result;
        } catch (IOException e) {
            log.error("Cleanup failed for {}", identity.id(), e);
            return failure(identity.id(), result.failureStage() == null ? MigrationResult.FailureStage.STATUS
                    : result.failureStage(), e, promotion != null && promotion.placedLiveObject());
        }
    }

    private MigrationResult failure(final UUID id, final MigrationResult.FailureStage stage, final Exception exception,
                                    final boolean placedLiveObject) {
        if (exception instanceof InterruptedException) {
            Thread.currentThread().interrupt();
        }
        log.error("Migration failed for {} at {}", id, stage, exception);
        return new MigrationResult(id, false, stage, exception.getMessage(), placedLiveObject);
    }
}
