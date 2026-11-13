package edu.unc.lib.boxc.migration;

import java.io.IOException;
import java.io.Reader;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Validated configuration for one migration run.
 */
public final class MigrationConfig {
    static final String OCFL_ROOT = "fedora_ocfl_root_dir";
    static final String FEDORA_5_BASE_URI = "fedora_5_base_uri";
    static final String FEDORA_5_USERNAME = "fedora_5_username";
    static final String FEDORA_5_PASSWORD = "fedora_5_password";
    static final String FEDORA_6_BASE_URI = "fedora_6_base_uri";
    static final String FEDORA_6_USERNAME = "fedora_6_username";
    static final String FEDORA_6_PASSWORD = "fedora_6_password";
    static final String WORKER_COUNT = "worker_count";
    static final String SHUTDOWN_TIMEOUT_SECONDS = "shutdown_timeout_seconds";

    private static final URI DEFAULT_FEDORA_5_BASE_URI = URI.create("http://localhost:8181/fcrepo/rest/");
    private static final URI DEFAULT_FEDORA_6_BASE_URI = URI.create("http://localhost:8185/fcrepo/rest/");

    private final Path ocflRoot;
    private final URI fedora5BaseUri;
    private final String fedora5Username;
    private final String fedora5Password;
    private final URI fedora6BaseUri;
    private final String fedora6Username;
    private final String fedora6Password;
    private final int workerCount;
    private final int shutdownTimeoutSeconds;

    private MigrationConfig(final Path ocflRoot, final URI fedora5BaseUri, final String fedora5Username,
                            final String fedora5Password, final URI fedora6BaseUri, final String fedora6Username,
                            final String fedora6Password, final int workerCount, final int shutdownTimeoutSeconds) {
        this.ocflRoot = ocflRoot;
        this.fedora5BaseUri = fedora5BaseUri;
        this.fedora5Username = fedora5Username;
        this.fedora5Password = fedora5Password;
        this.fedora6BaseUri = fedora6BaseUri;
        this.fedora6Username = fedora6Username;
        this.fedora6Password = fedora6Password;
        this.workerCount = workerCount;
        this.shutdownTimeoutSeconds = shutdownTimeoutSeconds;
    }

    /** Loads and validates configuration from a migration properties file. */
    static MigrationConfig load(final Path path) throws IOException {
        final Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(path)) {
            properties.load(reader);
        }
        return fromProperties(properties);
    }

    /** Creates validated configuration from migration properties. */
    static MigrationConfig fromProperties(final Properties properties) {
        final Path ocflRoot = Path.of(required(properties, OCFL_ROOT)).toAbsolutePath().normalize();
        return new MigrationConfig(ocflRoot, baseUri(properties, FEDORA_5_BASE_URI, DEFAULT_FEDORA_5_BASE_URI),
                required(properties, FEDORA_5_USERNAME), required(properties, FEDORA_5_PASSWORD),
                baseUri(properties, FEDORA_6_BASE_URI, DEFAULT_FEDORA_6_BASE_URI),
                required(properties, FEDORA_6_USERNAME), required(properties, FEDORA_6_PASSWORD),
                positive(properties, WORKER_COUNT, 1), positive(properties, SHUTDOWN_TIMEOUT_SECONDS, 300));
    }

    /** Returns this configuration with an invocation-scoped worker-count override. */
    MigrationConfig withWorkerCount(final int workerCount) {
        if (workerCount <= 0) {
            throw new IllegalArgumentException("worker_count must be positive");
        }
        return new MigrationConfig(ocflRoot, fedora5BaseUri, fedora5Username, fedora5Password, fedora6BaseUri,
                fedora6Username, fedora6Password, workerCount, shutdownTimeoutSeconds);
    }

    private static String required(final Properties properties, final String key) {
        final String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing required property " + key);
        }
        return value.trim();
    }

    private static int positive(final Properties properties, final String key, final int defaultValue) {
        final String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            final int result = Integer.parseInt(value.trim());
            if (result <= 0) {
                throw new IllegalArgumentException(key + " must be positive");
            }
            return result;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " must be a positive integer", e);
        }
    }

    private static URI baseUri(final Properties properties, final String key, final URI defaultValue) {
        final String value = properties.getProperty(key);
        final URI uri = value == null || value.isBlank() ? defaultValue : URI.create(value.trim());
        if (!uri.isAbsolute()) {
            throw new IllegalArgumentException(key + " must be an absolute URI");
        }
        return URI.create(uri.toString().replaceFirst("/+$", "") + "/");
    }

    /** Returns the live OCFL root. */
    Path ocflRoot() {
        return ocflRoot;
    }

    /** Returns the Fedora 5 repository root. */
    URI fedora5BaseUri() {
        return fedora5BaseUri;
    }

    /** Returns the Fedora 6 repository root. */
    URI fedora6BaseUri() {
        return fedora6BaseUri;
    }

    /** Returns the number of concurrent migration workers. */
    int workerCount() {
        return workerCount;
    }

    /** Returns the worker shutdown timeout in seconds. */
    int shutdownTimeoutSeconds() {
        return shutdownTimeoutSeconds;
    }

    /** Returns the Fedora 5 username. */
    String fedora5Username() {
        return fedora5Username;
    }

    /** Returns the Fedora 5 password. */
    String fedora5Password() {
        return fedora5Password;
    }

    /** Returns the Fedora 6 username. */
    String fedora6Username() {
        return fedora6Username;
    }

    /** Returns the Fedora 6 password. */
    String fedora6Password() {
        return fedora6Password;
    }

    @Override
    public String toString() {
        return "MigrationConfig[ocflRoot=" + ocflRoot + ", fedora5BaseUri=" + fedora5BaseUri
                + ", fedora6BaseUri=" + fedora6BaseUri + ", workerCount=" + workerCount
                + ", shutdownTimeoutSeconds=" + shutdownTimeoutSeconds + "]";
    }
}
