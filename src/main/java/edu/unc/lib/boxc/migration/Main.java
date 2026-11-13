package edu.unc.lib.boxc.migration;

import org.fcrepo.importexport.common.Config;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
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

    static final String LOG_PATH_PROPERTY = "migration.log.path";

    @Option(names = "--working-dir", defaultValue = ".",
            description = "Run working directory. Default: ${DEFAULT-VALUE}")
    private Path workingDirectory;

    @Override
    public Integer call() throws Exception {
        final Path resolvedWorkingDirectory = workingDirectory.toAbsolutePath().normalize();
        Files.createDirectories(resolvedWorkingDirectory);
        System.setProperty(LOG_PATH_PROPERTY, resolvedWorkingDirectory.resolve("migration.log").toString());

        final var logger = LoggerFactory.getLogger(Main.class);
        logger.info("Using working directory {}", resolvedWorkingDirectory);
        return 0;
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
