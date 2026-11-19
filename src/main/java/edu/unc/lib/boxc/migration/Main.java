package edu.unc.lib.boxc.migration;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import org.fcrepo.importexport.common.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.net.URI;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;

/**
 * Command-line entry point for per-object Fedora migration.
 */
@Command(
        name = "boxc-fedora-ocfl-migration",
        description = "Migrates Fedora archival groups into OCFL storage.",
        mixinStandardHelpOptions = true,
        version = "1.0-SNAPSHOT")
public class Main implements Callable<Integer> {
    private static final String LOG_DIRECTORY_PROPERTY = "migration.run.directory";

    @Option(names = "--working-dir", defaultValue = ".",
            description = "Run working directory. Default: ${DEFAULT-VALUE}")
    private Path workingDirectory;

    @Option(names = "--id-list-path", description = "Source object identifier list for a new run")
    private Path idListPath;

    @Option(names = "--config-path", description = "Migration properties file for a new run")
    private Path configPath;

    @Option(names = "--worker-count", description = "Positive worker-count override")
    private Integer workerCount;

    @Option(names = "--from-failures", description = "Retry failed IDs from a resumed run")
    private boolean fromFailures;

    @Override
    public Integer call() throws Exception {
        final Path resolvedWorkingDirectory = workingDirectory.toAbsolutePath().normalize();

        try (RunDirectory runDirectory = RunDirectory.open(resolvedWorkingDirectory, idListPath, configPath,
                workerCount, fromFailures)) {
            configureLogging(runDirectory.path());
            final Logger log = LoggerFactory.getLogger(Main.class);
            final Set<ObjectId> progress = ObjectIdSource.readStatus(runDirectory.progressPath());
            final Set<ObjectId> ids = fromFailures
                    ? ObjectIdSource.readStatus(runDirectory.failurePath())
                    : ObjectIdSource.readInput(runDirectory.idsPath());
            final int inputCount = ids.size();
            if (!fromFailures) {
                ids.removeAll(progress);
            }
            log.info("{} run initialized in {} with {} object IDs", runDirectory.resumed() ? "Resumed" : "New",
                    runDirectory.path(), ids.size());
            final StatusWriter statusWriter = new StatusWriter(runDirectory.progressPath(), runDirectory.failurePath());
            try (ObjectMigrationService service = new ObjectMigrationService(runDirectory.config(), runDirectory.path())) {
                final MigrationCoordinator coordinator = new MigrationCoordinator(runDirectory.config(), statusWriter,
                        service, fromFailures);
                final Thread shutdownHook = new Thread(coordinator::stop, "migration-shutdown");
                Runtime.getRuntime().addShutdownHook(shutdownHook);
                final MigrationCoordinator.MigrationSummary summary;
                try {
                    summary = coordinator.run(ids, inputCount, inputCount - ids.size());
                } finally {
                    try {
                        Runtime.getRuntime().removeShutdownHook(shutdownHook);
                    } catch (IllegalStateException ignored) {
                        // The JVM is already shutting down and the hook owns coordinator shutdown.
                    }
                }
                log.info("Migration summary: input={}, skipped={}, attempted={}, succeeded={}, failed={}, interrupted={}, "
                                + "average migration duration={}, total migration duration={}",
                        summary.input(), summary.skipped(), summary.attempted(), summary.succeeded(), summary.failed(),
                        summary.interrupted(), MigrationCoordinator.formatDuration(summary.averageMigrationDuration()),
                        MigrationCoordinator.formatDuration(summary.totalMigrationDuration()));
                if (summary.aborted() || !ObjectIdSource.readStatus(runDirectory.failurePath()).isEmpty()) {
                    return 1;
                }
            }
        }
        return 0;
    }

    // Reload the logger to ensure that they are written to the working directory
    private static void configureLogging(final Path runDirectory) throws Exception {
        final LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        final JoranConfigurator configurator = new JoranConfigurator();
        context.reset();
        context.putProperty(LOG_DIRECTORY_PROPERTY, runDirectory.toString());
        configurator.setContext(context);
        configurator.doConfigure(Objects.requireNonNull(Main.class.getResource("/logback.xml")));
    }

    /**
     * Creates the export configuration used for Fedora-to-OCFL migration.
     * The destination directory remains per-export state supplied to
     * {@code ResourceExporter.export}.
     *
     * @param repositoryRoot Fedora repository root
     * @param username Fedora username
     * @param password Fedora password
     * @return migration export configuration
     */
    static Config exportConfig(final URI repositoryRoot, final String username, final String password) {
        final Config config = new Config();
        config.setMode("export");
        config.setRepositoryRoot(repositoryRoot);
        config.setUsername(username);
        config.setPassword(password);
        config.setIncludeBinaries(true);
        config.setStreaming(true);
        config.setRdfLanguage(Config.DEFAULT_STREAMING_RDF_LANG);
        config.setPredicates(new String[] { "http://www.w3.org/ns/ldp#contains" });
        config.setAuditLog(true);
        return config;
    }

    /**
     * @param args command-line arguments
     */
    public static void main(final String[] args) {
        System.exit(new CommandLine(new Main()).execute(args));
    }
}
