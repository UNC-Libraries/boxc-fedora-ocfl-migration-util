package edu.unc.lib.boxc.migration;

import org.apache.jena.riot.Lang;
import org.fcrepo.storage.ocfl.OcflObjectSessionFactory;
import org.fcrepo.upgrade.utils.Config;
import org.fcrepo.upgrade.utils.FedoraVersion;
import org.fcrepo.upgrade.utils.UpgradeManagerFactory;
import org.fcrepo.upgrade.utils.f6.ResourceInfo;
import org.fcrepo.upgrade.utils.f6.ResourceMigrator;

import java.nio.file.Path;
import java.util.ArrayDeque;

/**
 * Adapts the Fedora upgrade utility for direct per-object migration.
 */
final class FedoraUpgrader implements AttemptUpgrader {
    private final ResourceMigrator migrator;

    /** Creates the one shared mutable-HEAD upgrader for a migration run. */
    FedoraUpgrader(final MigrationConfig migrationConfig, final Path upgradeDirectory) {
        final Config config = new Config();
        config.setSourceVersion(FedoraVersion.V_5);
        config.setTargetVersion(FedoraVersion.V_6);
        config.setOutputDir(upgradeDirectory.toFile());
        config.setBaseUri(migrationConfig.fedora5BaseUri().toString());
        config.setSrcRdfLang(Lang.NT);
        config.setDigestAlgorithm("sha256");
        config.setAutoversioning(false);
        config.setArchivalGroupRdfTypes(String.join(",", FedoraExporter.ARCHIVAL_GROUP_TYPES));
        final OcflObjectSessionFactory sessions = UpgradeManagerFactory.createOcflObjectSessionFactory(config);
        migrator = new ResourceMigrator(config, sessions);
    }

    /** Migrates the root then drains each synchronous child result. */
    @Override
    public void migrate(final ObjectIdentity identity, final Path attemptDirectory) {
        final Path rootDirectory = FedoraExporter.exportRoot(identity, attemptDirectory);
        final ArrayDeque<ResourceInfo> pending = new ArrayDeque<>();
        pending.add(ResourceInfo.container(identity.parentInternalId(), identity.internalId(), null, rootDirectory,
                identity.id().toString()));
        while (!pending.isEmpty()) {
            pending.addAll(migrator.migrate(pending.removeLast()));
        }
    }

    /** Closes the shared OCFL session factory owned by the migrator. */
    @Override
    public void close() {
        migrator.close();
    }
}
