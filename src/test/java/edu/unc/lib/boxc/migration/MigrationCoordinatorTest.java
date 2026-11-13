package edu.unc.lib.boxc.migration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MigrationCoordinatorTest {
    @TempDir
    private Path tempDir;

    @Test
    void limitsWorkersAndRecordsTerminalResults() throws Exception {
        final Path progress = Files.createFile(tempDir.resolve("progress.log"));
        final Path failures = Files.createFile(tempDir.resolve("failures.log"));
        final AtomicInteger active = new AtomicInteger();
        final AtomicInteger peak = new AtomicInteger();
        final List<UUID> ids = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        final MigrationAttempt attempt = id -> {
            peak.accumulateAndGet(active.incrementAndGet(), Math::max);
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                active.decrementAndGet();
            }
            return new MigrationResult(id, !id.equals(ids.get(1)), MigrationResult.FailureStage.SIDELOAD,
                    "result", false);
        };

        final MigrationCoordinator.MigrationSummary summary = new MigrationCoordinator(config(2),
                new StatusWriter(progress, failures), attempt, false).run(ids, ids.size(), 0);

        assertTrue(peak.get() <= 2);
        assertEquals(4, summary.attempted());
        assertEquals(3, summary.succeeded());
        assertEquals(1, summary.failed());
        assertEquals(ids.get(1) + System.lineSeparator(), Files.readString(failures));
        assertEquals(4, ObjectIdSource.readStatus(progress).size());
    }

    @Test
    void removesFailureForSuccessfulRetry() throws Exception {
        final Path progress = Files.createFile(tempDir.resolve("progress.log"));
        final Path failures = Files.createFile(tempDir.resolve("failures.log"));
        final UUID id = UUID.randomUUID();
        Files.writeString(progress, id + System.lineSeparator());
        Files.writeString(failures, id + System.lineSeparator());

        final MigrationCoordinator.MigrationSummary summary = new MigrationCoordinator(config(1),
                new StatusWriter(progress, failures), value -> new MigrationResult(value, true, null, "ok", false), true)
                .run(List.of(id), 1, 0);

        assertEquals(1, summary.succeeded());
        assertEquals("", Files.readString(failures));
    }

    @Test
    void shutdownRecordsTerminalResultFromInterruptedAttempt() throws Exception {
        final Path progress = Files.createFile(tempDir.resolve("progress.log"));
        final Path failures = Files.createFile(tempDir.resolve("failures.log"));
        final UUID id = UUID.randomUUID();
        final CountDownLatch started = new CountDownLatch(1);
        final MigrationAttempt attempt = value -> {
            started.countDown();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new MigrationResult(value, true, null, "interrupted", false);
        };
        final MigrationCoordinator coordinator = new MigrationCoordinator(config(1), new StatusWriter(progress, failures),
                attempt, false);
        try (var executor = Executors.newSingleThreadExecutor()) {
            final Future<MigrationCoordinator.MigrationSummary> result = executor.submit(
                    () -> coordinator.run(List.of(id, UUID.randomUUID()), 2, 0));
            started.await();
            coordinator.stop();

            assertTrue(result.get().aborted());
        }
        assertEquals(id + System.lineSeparator(), Files.readString(progress));
        assertEquals("", Files.readString(failures));
    }

    private MigrationConfig config(final int workers) throws Exception {
        final Properties properties = new Properties();
        properties.setProperty(MigrationConfig.OCFL_ROOT, Files.createDirectory(tempDir.resolve("ocfl-" + workers)).toString());
        properties.setProperty(MigrationConfig.FEDORA_5_USERNAME, "user");
        properties.setProperty(MigrationConfig.FEDORA_5_PASSWORD, "password");
        properties.setProperty(MigrationConfig.FEDORA_6_USERNAME, "user");
        properties.setProperty(MigrationConfig.FEDORA_6_PASSWORD, "password");
        properties.setProperty(MigrationConfig.WORKER_COUNT, Integer.toString(workers));
        properties.setProperty(MigrationConfig.SHUTDOWN_TIMEOUT_SECONDS, "1");
        return MigrationConfig.fromProperties(properties);
    }
}
