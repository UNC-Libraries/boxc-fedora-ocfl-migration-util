package edu.unc.lib.boxc.migration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import java.util.UUID;

/**
 * Owns an initialized run directory and its exclusive lock.
 */
final class RunDirectory implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(RunDirectory.class);

    static final String PROPERTIES_FILE = "migration.properties";
    static final String IDS_FILE = "migration_ids.txt";
    static final String PROGRESS_FILE = "migration_progress.log";
    static final String FAILURE_FILE = "migration_failure.log";
    static final String LOG_FILE = "migration.log";
    static final String EXPORTS_DIRECTORY = "exports";
    static final String UPGRADE_DIRECTORY = "upgrade";
    static final String UPGRADE_OCFL_ROOT_DIRECTORY = "upgrade/data/ocfl-root";
    static final String UPGRADE_OCFL_TEMP_DIRECTORY = "upgrade/data/ocfl-temp";
    static final String UPGRADE_STAGING_DIRECTORY = "upgrade/data/staging";

    private static final Set<PosixFilePermission> CONFIG_PERMISSIONS = Set.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_WRITE);
    private static final Set<PosixFilePermission> LOG_PERMISSIONS = Set.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_WRITE);

    private final Path path;
    private final Path propertiesPath;
    private final Path idsPath;
    private final Path progressPath;
    private final Path failurePath;
    private final MigrationConfig config;
    private final FileChannel lockChannel;
    private final FileLock lock;
    private final boolean resumed;

    private RunDirectory(final Path path, final MigrationConfig config, final FileChannel lockChannel,
                         final FileLock lock, final boolean resumed) {
        this.path = path;
        this.propertiesPath = path.resolve(PROPERTIES_FILE);
        this.idsPath = path.resolve(IDS_FILE);
        this.progressPath = path.resolve(PROGRESS_FILE);
        this.failurePath = path.resolve(FAILURE_FILE);
        this.config = config;
        this.lockChannel = lockChannel;
        this.lock = lock;
        this.resumed = resumed;
    }

    /** Opens and exclusively locks a new or resumed migration run directory. */
    static RunDirectory open(final Path directory, final Path sourceIds, final Path sourceConfig,
                             final Integer workerCount, final boolean fromFailures) throws IOException {
        final Path path = directory.toAbsolutePath().normalize();
        Files.createDirectories(path);
        final FileChannel lockChannel = FileChannel.open(path.resolve(".migration.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        final FileLock lock;
        try {
            lock = lockChannel.tryLock();
        } catch (OverlappingFileLockException e) {
            lockChannel.close();
            throw new IllegalStateException("Working directory is already locked: " + path, e);
        }
        if (lock == null) {
            lockChannel.close();
            throw new IllegalStateException("Working directory is already locked: " + path);
        }

        try {
            final Path propertiesPath = path.resolve(PROPERTIES_FILE);
            final Path idsPath = path.resolve(IDS_FILE);
            final boolean hasProperties = Files.exists(propertiesPath);
            final boolean hasIds = Files.exists(idsPath);
            // Must have both files or neither, otherwise the working directory is in an invalid state
            if (hasProperties != hasIds) {
                throw new IllegalStateException("Working directory has a partial initialization: " + path);
            }
            final boolean resumed = hasProperties;
            if (resumed) {
                if (sourceIds != null || sourceConfig != null) {
                    throw new IllegalArgumentException("--id-list-path and --config-path are not allowed when resuming");
                }
            } else {
                if (fromFailures) {
                    throw new IllegalArgumentException("--from-failures is only valid when resuming");
                }
                requireReadable(sourceIds, "--id-list-path");
                requireReadable(sourceConfig, "--config-path");
                copyAndValidate(sourceConfig, propertiesPath, true);
                copyAndValidate(sourceIds, idsPath, false);
            }

            MigrationConfig config = MigrationConfig.load(propertiesPath);
            if (workerCount != null) {
                config = config.withWorkerCount(workerCount);
            }
            validateLiveOcflRoot(config.ocflRoot());
            final RunDirectory runDirectory = new RunDirectory(path, config, lockChannel, lock, resumed);
            runDirectory.createStatusFiles();
            runDirectory.validateStatusFiles();
            return runDirectory;
        } catch (IOException | RuntimeException e) {
            lock.release();
            lockChannel.close();
            throw e;
        }
    }

    private static void requireReadable(final Path path, final String option) {
        if (path == null || !Files.isRegularFile(path) || !Files.isReadable(path)) {
            throw new IllegalArgumentException(option + " must identify a readable regular file");
        }
    }

    private static void copyAndValidate(final Path source, final Path target, final boolean config) throws IOException {
        final Path temporary = Files.createTempFile(target.getParent(), target.getFileName().toString(), ".tmp");
        try {
            Files.copy(source, temporary, StandardCopyOption.REPLACE_EXISTING);
            if (config) {
                MigrationConfig.load(temporary);
            } else {
                ObjectIdSource.readInput(temporary);
            }
            atomicMove(temporary, target);
            setPermissions(target, config ? CONFIG_PERMISSIONS : LOG_PERMISSIONS);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /** Atomically replaces a run file with its fully written temporary version. */
    static void atomicMove(final Path temporary, final Path target) throws IOException {
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            throw new IOException("Atomic move is required for " + target, e);
        }
    }

    private void createStatusFiles() throws IOException {
        Files.createDirectories(path.resolve(EXPORTS_DIRECTORY));
        Files.createDirectories(path.resolve(UPGRADE_OCFL_ROOT_DIRECTORY));
        Files.createDirectories(path.resolve(UPGRADE_OCFL_TEMP_DIRECTORY));
        Files.createDirectories(path.resolve(UPGRADE_STAGING_DIRECTORY));
        createProtectedFile(progressPath);
        createProtectedFile(failurePath);
    }

    private static void createProtectedFile(final Path path) throws IOException {
        if (Files.notExists(path)) {
            Files.createFile(path);
        }
        setPermissions(path, LOG_PERMISSIONS);
    }

    private static void setPermissions(final Path path, final Set<PosixFilePermission> permissions) {
        try {
            Files.setPosixFilePermissions(path, permissions);
        } catch (UnsupportedOperationException e) {
            log.warn("POSIX permissions are unavailable for {}", path);
        } catch (IOException e) {
            log.warn("Could not set permissions on {}", path, e);
        }
    }

    private void validateStatusFiles() throws IOException {
        final Set<UUID> progress = ObjectIdSource.readStatus(progressPath);
        final Set<UUID> failures = ObjectIdSource.readStatus(failurePath);
        if (!progress.containsAll(failures)) {
            throw new IllegalStateException("Failure status contains UUIDs absent from progress status");
        }
    }

    private static void validateLiveOcflRoot(final Path root) {
        if (!Files.isDirectory(root) || !Files.isWritable(root)) {
            throw new IllegalArgumentException("fedora_ocfl_root_dir must be a writable directory: " + root);
        }
    }

    /** Returns the working directory for this run. */
    Path path() {
        return path;
    }

    /** Returns the copied object ID list. */
    Path idsPath() {
        return idsPath;
    }

    /** Returns the completed-object status log. */
    Path progressPath() {
        return progressPath;
    }

    /** Returns the failed-object status log. */
    Path failurePath() {
        return failurePath;
    }

    /** Returns the effective configuration for this invocation. */
    MigrationConfig config() {
        return config;
    }

    /** Indicates whether this run directory was resumed. */
    boolean resumed() {
        return resumed;
    }

    /** Releases the exclusive run-directory lock. */
    @Override
    public void close() throws IOException {
        lock.release();
        lockChannel.close();
    }
}
