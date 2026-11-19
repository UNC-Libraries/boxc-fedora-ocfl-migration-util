package edu.unc.lib.boxc.migration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;
import java.util.Collection;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Coordinates bounded concurrent object migrations and durable result recording. */
final class MigrationCoordinator {
    private static final Logger log = LoggerFactory.getLogger(MigrationCoordinator.class);

    private final ThreadPoolExecutor executor;
    private final StatusWriter statusWriter;
    private final MigrationAttempt migration;
    private final boolean retry;
    private final Duration shutdownTimeout;
    private final AtomicBoolean stopping = new AtomicBoolean();
    private final AtomicReference<Exception> fatalFailure = new AtomicReference<>();
    private final AtomicInteger submitted = new AtomicInteger();
    private final AtomicInteger attempted = new AtomicInteger();
    private final AtomicInteger succeeded = new AtomicInteger();
    private final AtomicInteger failed = new AtomicInteger();
    private final AtomicInteger interrupted = new AtomicInteger();
    private final AtomicLong totalMigrationNanos = new AtomicLong();

    /** Creates a coordinator with a queue sized to twice the worker count. */
    MigrationCoordinator(final MigrationConfig config, final StatusWriter statusWriter, final MigrationAttempt migration,
                         final boolean retry) {
        final int queueSize = Math.max(1, config.workerCount() * 2);
        executor = new ThreadPoolExecutor(config.workerCount(), config.workerCount(), 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueSize), (task, rejectedExecutor) -> {
                    if (rejectedExecutor.isShutdown()) {
                        throw new RejectedExecutionException("Migration executor is shut down");
                    }
                    try {
                        // block the submitting thread until a queue slot becomes available, rather than
                        // failing due to temporary queue saturation.
                        rejectedExecutor.getQueue().put(task);
                        if (rejectedExecutor.isShutdown() && rejectedExecutor.getQueue().remove(task)) {
                            throw new RejectedExecutionException("Migration executor is shut down");
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RejectedExecutionException("Interrupted while waiting to submit migration", e);
                    }
                });
        this.statusWriter = statusWriter;
        this.migration = migration;
        this.retry = retry;
        shutdownTimeout = Duration.ofSeconds(config.shutdownTimeoutSeconds());
    }

    /** Submits IDs incrementally and waits for all accepted tasks to finish. */
    MigrationSummary run(final Collection<ObjectId> ids, final int inputCount, final int skippedCount) {
        final long startedAt = System.nanoTime();
        for (final ObjectId id : ids) {
            if (stopping.get() || fatalFailure.get() != null) {
                break;
            }
            try {
                executor.execute(() -> migrate(id));
                submitted.incrementAndGet();
            } catch (RuntimeException e) {
                if (!stopping.get()) {
                    failFatally(e);
                }
                break;
            }
        }
        awaitCompletion();
        final int notSubmitted = Math.max(0, ids.size() - submitted.get());
        return new MigrationSummary(inputCount, skippedCount, attempted.get(), succeeded.get(), failed.get(),
                interrupted.get() + notSubmitted, fatalFailure.get() != null || stopping.get(),
                averageMigrationDuration(), Duration.ofNanos(System.nanoTime() - startedAt));
    }

    /** Stops new work, gives active tasks time to clean up, then interrupts remaining tasks. */
    void stop() {
        if (!stopping.compareAndSet(false, true)) {
            return;
        }
        executor.shutdown();
        try {
            if (!executor.awaitTermination(shutdownTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
                interruptQueuedTasks();
                executor.awaitTermination(shutdownTimeout.toMillis(), TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException e) {
            interruptQueuedTasks();
            Thread.currentThread().interrupt();
        }
    }

    private void migrate(final ObjectId id) {
        try {
            if (stopping.get()) {
                interrupted.incrementAndGet();
                return;
            }
            attempted.incrementAndGet();
            final long startedAt = System.nanoTime();
            final MigrationResult result = migration.migrate(id);
            try {
                if (result.success()) {
                    statusWriter.recordSuccess(id, retry);
                    succeeded.incrementAndGet();
                } else {
                    statusWriter.recordFailure(id, retry);
                    failed.incrementAndGet();
                }
                final Duration duration = Duration.ofNanos(System.nanoTime() - startedAt);
                totalMigrationNanos.addAndGet(duration.toNanos());
                log.info("Migration of object {} completed in {}", id, formatDuration(duration));
            } catch (IOException e) {
                failFatally(e);
            }
        } catch (RuntimeException e) {
            failFatally(e);
        }
    }

    private void interruptQueuedTasks() {
        // Interrupts the tasks and adds them to the interrupted counter for reporting
        interrupted.addAndGet(executor.shutdownNow().size());
    }

    private void awaitCompletion() {
        executor.shutdown();
        boolean interruptedWhileWaiting = false;
        while (!executor.isTerminated()) {
            try {
                executor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
            } catch (InterruptedException e) {
                interruptedWhileWaiting = true;
                stop();
            }
        }
        if (interruptedWhileWaiting) {
            Thread.currentThread().interrupt();
        }
    }

    private void failFatally(final Exception exception) {
        if (fatalFailure.compareAndSet(null, exception)) {
            log.error("Stopping migration because a fatal application failure occurred", exception);
            stopping.set(true);
            executor.shutdown();
        }
    }

    private Duration averageMigrationDuration() {
        final int completed = succeeded.get() + failed.get();
        return completed == 0 ? Duration.ZERO : Duration.ofNanos(totalMigrationNanos.get() / completed);
    }

    static String formatDuration(final Duration duration) {
        if (duration.toMillisPart() == 0) {
            return duration.toSeconds() + "s";
        }
        return "%d.%03ds".formatted(duration.toSeconds(), duration.toMillisPart()).replaceFirst("0+(?=s$)", "");
    }

    /**
     * Summarizes a coordinator invocation for console reporting and exit-code mapping.
     *
     * @param input deduplicated IDs read from the selected source
     * @param skipped IDs omitted because normal-mode progress already contains them
     * @param attempted IDs whose migration attempt started
     * @param succeeded attempts durably recorded as successes
     * @param failed attempts durably recorded as failures
     * @param interrupted IDs interrupted or not submitted during shutdown
     * @param aborted whether a fatal failure or shutdown stopped the run
     * @param averageMigrationDuration average duration of terminal migration attempts
     * @param totalMigrationDuration elapsed duration of the full migration run
     */
    record MigrationSummary(int input, int skipped, int attempted, int succeeded, int failed, int interrupted,
                             boolean aborted, Duration averageMigrationDuration, Duration totalMigrationDuration) {
    }
}
