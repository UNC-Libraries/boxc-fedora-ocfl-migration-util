package edu.unc.lib.boxc.migration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunDirectoryTest {
    @TempDir
    private Path tempDir;

    @Test
    void initializesNewRunWithCopiedInputsAndLayout() throws Exception {
        final Path ids = writeIds();
        final Path config = writeConfig();
        final Path run = tempDir.resolve("run");

        try (var runDirectory = RunDirectory.open(run, ids, config, 3, false)) {
            assertFalse(runDirectory.resumed());
            assertEquals(3, runDirectory.config().workerCount());
            assertTrue(Files.isDirectory(run.resolve("exports")));
            assertTrue(Files.isDirectory(run.resolve("upgrade/data/staging")));
            assertTrue(Files.exists(run.resolve(RunDirectory.PROGRESS_FILE)));
            assertTrue(Files.exists(run.resolve(RunDirectory.FAILURE_FILE)));
        }
        assertFalse(Files.readString(run.resolve(RunDirectory.PROPERTIES_FILE)).contains("worker_count=3"));
    }

    @Test
    void resumesOnlyFromCopiedInputsAndUsesInvocationOverride() throws Exception {
        final Path run = tempDir.resolve("run");
        try (var ignored = RunDirectory.open(run, writeIds(), writeConfig(), null, false)) {
            // Initialize the run.
        }

        try (var resumed = RunDirectory.open(run, null, null, 2, false)) {
            assertTrue(resumed.resumed());
            assertEquals(2, resumed.config().workerCount());
        }
        try (var resumed = RunDirectory.open(run, null, null, null, false)) {
            assertEquals(1, resumed.config().workerCount());
        }
        assertThrows(IllegalArgumentException.class,
                () -> RunDirectory.open(run, writeIds(), null, null, false));
    }

    @Test
    void rejectsPartialInitializationAndLockContention() throws Exception {
        final Path partial = tempDir.resolve("partial");
        Files.createDirectories(partial);
        Files.writeString(partial.resolve(RunDirectory.PROPERTIES_FILE), "x=y\n");
        assertThrows(IllegalStateException.class,
                () -> RunDirectory.open(partial, writeIds(), writeConfig(), null, false));

        final Path run = tempDir.resolve("run");
        try (var first = RunDirectory.open(run, writeIds(), writeConfig(), null, false)) {
            assertThrows(IllegalStateException.class,
                    () -> RunDirectory.open(run, null, null, null, false));
        }
    }

    @Test
    void rejectsInvalidLiveOcflRootAndInconsistentStatus() throws Exception {
        final Path config = writeConfig();
        Files.writeString(config, properties(tempDir.resolve("missing")));
        assertThrows(IllegalArgumentException.class,
                () -> RunDirectory.open(tempDir.resolve("bad-root"), writeIds(), config, null, false));

        final Path run = tempDir.resolve("run");
        try (var ignored = RunDirectory.open(run, writeIds(), writeConfig(), null, false)) {
            // Initialize status files.
        }
        Files.writeString(run.resolve(RunDirectory.FAILURE_FILE), "3f7510bc-7f53-4ae2-b8fc-0fb4ccd95dbb\n");
        assertThrows(IllegalStateException.class, () -> RunDirectory.open(run, null, null, null, false));
    }

    @Test
    void permitsFailureRetryOnlyForResumedRuns() throws Exception {
        final Path run = tempDir.resolve("run");
        assertThrows(IllegalArgumentException.class,
                () -> RunDirectory.open(run, writeIds(), writeConfig(), null, true));

        try (var ignored = RunDirectory.open(run, writeIds(), writeConfig(), null, false)) {
            // Initialize the run.
        }
        try (var resumed = RunDirectory.open(run, null, null, null, true)) {
            assertTrue(resumed.resumed());
        }
    }

    private Path writeIds() throws Exception {
        final Path ids = tempDir.resolve("ids-" + System.nanoTime() + ".txt");
        Files.writeString(ids, "3f7510bc-7f53-4ae2-b8fc-0fb4ccd95dbb\n");
        return ids;
    }

    private Path writeConfig() throws Exception {
        final Path root = Files.createDirectory(tempDir.resolve("ocfl-" + System.nanoTime()));
        final Path config = tempDir.resolve("config-" + System.nanoTime() + ".properties");
        Files.writeString(config, properties(root));
        return config;
    }

    private static String properties(final Path root) {
        return "fedora_ocfl_root_dir=" + root + "\n"
                + "fedora_5_username=user\n"
                + "fedora_5_password=password\n"
                + "fedora_6_username=user\n"
                + "fedora_6_password=password\n";
    }
}
