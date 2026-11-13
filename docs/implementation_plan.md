# Fedora Per-Object Migration Utility Implementation Plan

## Goal and scope

Implement the Java 21 picocli application described in
[`per_object_migration.md`](per_object_migration.md). The application has one
command. It reads archival-group UUIDs, exports each group from Fedora 5,
migrates the export to a shared staging OCFL repository, promotes the completed
OCFL object into target Fedora storage, sideloads it, verifies it, and records a
restartable result. The target implements the Fedora 6-compatible API, but all
containerized integration testing uses Fedora 7.1.0-SNAPSHOT.

This plan is intended to be executed in order. Each phase ends at a review or
human-action boundary. Keep the application small: use plain constructors and
narrow service interfaces rather than a dependency-injection framework.

## Decisions already made

- In this document, "Fedora 6" means the Fedora 6-compatible target API and
  its corresponding upgrade-tool configuration, not a requirement to deploy
  Fedora 6. The target test/development deployment is Fedora 7.1.0-SNAPSHOT.
  Keep the `fedora_6_*` property names because they identify that API contract.
- Use Docker containers for both Fedora 5 and the target Fedora environment.
  Treat the Fedora 7.1.0-SNAPSHOT image tag and image digest as pinned test
  inputs; record the digest and the source commit used to build it when a
  published image is not available.
- An existing run resumes only from the ID list and properties copied into its
  working directory. Reject new `--id-list-path` and `--config-path` values on
  resume. `--worker-count` remains an allowed explicit override for that
  invocation and does not modify the run's properties copy.
- Provide a direct CLI override only for `worker_count`. Fedora URIs,
  credentials, and the live OCFL root remain properties-file settings.
- If an OCFL object existed before an attempt, retain it when sideloading
  returns 4xx. Delete on 4xx only when the current attempt promoted the object.
- Validate every promotion copy by comparing relative paths, file sizes, and
  SHA-256 hashes. Do not add repository-wide OCFL validation to the copy path.
- Preserve the documented exporter behavior: ACL and historical-version export
  remain disabled because the source invocation does not enable them. The
  migration includes current RDF, binaries, and audit output.
- Continue processing after object failures, then return a nonzero exit code if
  the run has any failures.
- On shutdown, stop submissions, allow active attempts a bounded grace period,
  then interrupt them. An interrupted object is not added to either status log
  unless it had already reached a terminal result.

## Working-directory contract

Use stable names so operators and tests can inspect a run:

```text
<working-dir>/
    .migration.lock
    migration.properties
    migration_ids.txt
    migration_progress.log
    migration_failure.log
    migration.log
    exports/
    upgrade/
        data/
            ocfl-root/
            ocfl-temp/
            staging/
```

- `migration.properties` and `migration_ids.txt` are the authoritative copied
  inputs. Create each through a temporary sibling and atomic rename so a
  partially initialized run cannot look valid.
- Treat a directory containing only one authoritative input, or malformed
  status files, as an invalid run and fail without replacing files.
- Write one canonical lowercase UUID per line in both status logs. A UUID in
  `migration_progress.log` means the attempt reached either success or terminal
  failure. Its presence in `migration_failure.log` distinguishes failure.
- Append and flush the failure UUID before appending and flushing the progress
  UUID. If either status write fails, treat status recording as a fatal
  application error, stop accepting work, and exit nonzero. Never claim
  progress that cannot be retried safely.
- Protect the copied configuration and logs with owner-only permissions where
  POSIX permissions are supported. Log a warning rather than failing solely
  because the filesystem does not support POSIX permissions. Configuration should
  be 0660.
- Configure logback so the detailed application log is
  `<working-dir>/migration.log`; keep concise lifecycle output on the console.
  Do not log either password.

## Configuration contract

The copied Java properties file supports these keys (requirements and defaults
are described below):

```properties
fedora_ocfl_root_dir=/path/to/fedora7/ocfl_root
fedora_5_base_uri=http://localhost:8181/fcrepo/rest
fedora_5_username=
fedora_5_password=
fedora_6_base_uri=http://localhost:8185/fcrepo/rest
fedora_6_username=
fedora_6_password=
worker_count=1
```

The two base URIs use the documented defaults when omitted. Require the OCFL
root, usernames, and passwords; require a positive worker count. Normalize base
URIs to exactly one trailing slash and paths to absolute normalized paths.
Support `shutdown_timeout_seconds` as an optional positive value with a default
of 300 seconds. Keep HTTP timeout defaults in code initially: 30 seconds to
connect and 300 seconds for a response.

CLI options:

- `--working-dir=<path>` defaults to the current directory.
- `--id-list-path=<path>` is required only when initializing a run.
- `--config-path=<path>` is required only when initializing a run.
- `--worker-count=<positive integer>` overrides `worker_count` for the current
  invocation without modifying the copied properties file.
- `--from-failures` is a resumed-run mode that uses
  `migration_failure.log` as the ID source. It is mutually exclusive with
  `--id-list-path` and bypasses normal progress-log skipping so prior failures
  are retried.
- Standard picocli `--help` and `--version` options are enabled.

Reject an ID input line that is nonblank and not a UUID. Ignore blank lines,
canonicalize valid UUIDs to lowercase, and preserve first-seen order while
deduplicating. In normal mode, load completed UUIDs from the progress log before
submission and skip them. In `--from-failures` mode, read and deduplicate the
failure log instead, and do not skip its IDs because they also occur in the
progress log.

## Phase 1: Dependency and executable foundation

### Human actions and pinned inputs

1. Fork `fcrepo-import-export`, check it out at
   `../fcrepo-import-export`, and choose a migration-specific artifact version.
2. Confirm the exact commit of `../fcrepo-upgrade-utils` that includes mutable
   HEAD support for `autoversioning=false`. Record both utility commit hashes
   and Maven versions in this project's README or a build document.
3. Do not implement against changing `*-SNAPSHOT` contents without recording
   the source commit used to install them.

### Import/export fork

Add a public synchronous per-resource export service to
`../fcrepo-import-export`. The public API may use a checked fork-specific
exception, but must provide the equivalent of:

```java
void export(URI rootResource, Path outputDirectory) throws ExportException;
```

The service is constructed once from immutable repository settings and is safe
for concurrent calls. Per-call state, including destination paths, counters,
RDF handlers, discovered resources, and failures, must not be shared. Refactor
the existing exporter internals rather than wrapping `Exporter.run()`:

- Traverse the selected root and descendants synchronously in the calling
  worker. Do not create an executor or queue.
- Do not register a JVM shutdown hook.
- Preserve binaries, streaming N-Triples, `ldp:contains` traversal, and audit
  log behavior from the invocation in the design document.
- Leave ACL and historical-version export disabled to match that invocation.
- Propagate any descendant request or write failure to the caller. A call must
  not report success after a partial export.
- Continue to produce the existing `fcrepo/rest/...` output layout.
- Keep the library artifact thin. The fork does not need a second packaging
  mode; install with `mvn clean install -Dshade.skip=true`.

Add fork tests for a root with nested containers and a binary, a descendant
HTTP failure, an output failure, and simultaneous exports to different output
directories. Assert there are no exporter-created threads or per-call shutdown
hooks.

### Application Maven setup

Replace the boilerplate POM and `Main` class with a Java 21 build modeled only
where useful on `../chompb/pom.xml` and `../chompb/src/main/java/edu/unc/lib/boxc/migration/cdm/CLIMain.java`:

- Add picocli, SLF4J/logback, Apache HttpClient 4.x, the thin import/export fork,
  and the thin `fcrepo-upgrade-utils` artifact.
- Add JUnit Jupiter, Mockito, and a local HTTP stub library for tests.
- Configure `maven-compiler-plugin` with `release` 21 and configure Surefire.
- Configure one `maven-shade-plugin` execution with service-resource merging and
  the application main class in the manifest. This project produces the only
  executable shaded JAR.
- Align shared dependency versions with the pinned utilities. In particular,
  update and test the import/export fork itself with
  `org.fcrepo.client:fcrepo-java-client:7.0.0`, force that version in the
  application, and verify that only that version resolves.
- Add `src/main/resources/logback.xml`, but set the application log path from a
  system property established after working-directory resolution.

Implement a single `Callable<Integer>` picocli command and a minimal `main`
method that returns picocli's exit code through `System.exit`.

### Phase 1 verification

Run:

```sh
mvn test
mvn package
mvn dependency:tree
java -jar target/<final-name>.jar --help
```

Inspect the dependency tree for duplicate SLF4J bindings, incompatible Jackson
or Jena versions, and any `fcrepo-java-client` version other than 7.0.0. Review
the fork API and resolved dependency graph before continuing.

## Phase 2: Run initialization, input, and status infrastructure

### Suggested application components

Keep classes under `edu.unc.lib.boxc.migration`. Names may vary, but preserve
these responsibilities:

- `MigrationCommand`: picocli options, startup validation, lifecycle ownership,
  summary, and exit-code mapping.
- `MigrationConfig`: immutable validated effective properties with no
  password-bearing `toString()`.
- `RunDirectory`: initialize or resume the stable working-directory layout,
  copy inputs atomically, apply the worker override, and configure the log path.
- `ObjectIdSource`: validate, canonicalize, deduplicate, and stream IDs while
  skipping progress entries in normal mode or sourcing failures in retry mode.
- `StatusWriter`: synchronized append-and-flush operations for progress and
  failure logs, plus synchronized failure-resolution rewriting in retry mode.
- `MigrationResult`: object UUID, success/failure, failure stage, message, and
  whether a live OCFL object was placed by this attempt.

Do not copy the multi-command arrangement from `../chompb`; this application
has only the root command.

### Initialization and resume sequence

1. Resolve and create the working directory.
2. Open `<working-dir>/.migration.lock`, acquire an exclusive lock with
   `FileChannel.tryLock()`, and hold it through shutdown. Fail before reading or
   modifying run state if another process holds the lock. A lock file left by a
   crashed process is harmless because ownership is determined by the channel
   lock, not file existence.
3. Determine whether it is a new run by checking for both authoritative copied
   inputs. Do not infer resume solely from empty status logs.
4. For a new run, require readable source paths, copy them to temporary sibling
   files, validate the copies, then atomically rename them to their stable names.
5. For a resumed run, reject `--id-list-path` and `--config-path`; load only the
   stable copies. `--from-failures` is valid only for a resumed run and selects
   its failure log instead of `migration_ids.txt`. Apply `--worker-count` to
   that invocation if present.
6. Create missing status files and directories. Do not truncate existing logs.
7. Read status files strictly as UUID-per-line files. Permit duplicate status
   entries defensively by loading them into sets, but never deliberately append
   the same UUID twice in one run. Require every failure-log UUID to occur in
   the progress log; otherwise fail because retry status cannot be interpreted
   safely.
8. Configure detailed file logging before utility objects and workers start.

Before accepting IDs, require the live OCFL root to satisfy
`Files.isDirectory(root)` and `Files.isWritable(root)`. This is intentionally a
non-invasive access check; do not create a probe file in preservation storage.
It cannot guarantee that later writes will succeed, so promotion must still
handle permission changes, read-only remounts, capacity errors, and unwritable
existing descendant paths as normal object failures.

The working-directory lock prevents duplicate use of one run's mutable state.
It deliberately does not coordinate separate working directories that target
the same live OCFL root. Operating multiple migration JVMs against one OCFL
root remains unsupported and is an operator responsibility.

Read the ID source incrementally. Maintain a set for input deduplication and a
set for completed UUIDs, but do not create a `Future` or queued task for every
ID. This keeps executor queue growth bounded even for several million IDs.

### Status and failure-stage behavior

Define stable failure stages for logs and tests: `EXPORT`, `UPGRADE`,
`PROMOTION`, `SIDELOAD`, `VERIFY`, and `STATUS`. Detailed application log
entries include UUID, source URI, export path, stage, timestamp, and exception.
The UUID-only failure log remains suitable as retry input.

On normal terminal failure, call one synchronized status operation that:

1. Appends and flushes the UUID to `migration_failure.log`.
2. Appends and flushes the UUID to `migration_progress.log`.

On success in normal mode, append and flush only the progress entry. In
`--from-failures` mode, the UUID is already in the progress log: remove it from
the in-memory failure set and rewrite `migration_failure.log` through a
temporary sibling and atomic replacement while holding the status-writer lock.
Flush the replacement before reporting retry success. A retry failure leaves
the existing failure-log UUID in place and does not append duplicate status
entries. Do not status an attempt that is cancelled or interrupted before a
terminal result; normal resume or `--from-failures` will retry it.

### Phase 2 tests and review

Add tests for new-run copying, resume, conflicting resume options, worker-count
override non-persistence, partial initialization, defaults, invalid properties,
invalid UUIDs, blank lines, case normalization, deduplication, progress skips,
strict status parsing, write ordering, flush/write failures, and POSIX-permission
fallback. Test missing, non-directory, and non-writable live roots plus
working-directory lock contention. Add retry-mode tests for option validation,
progress-log bypass, successful failure-log removal, retained retry failures,
and interruption during failure-log replacement. Use picocli's in-process
execution API to test options and exit codes.

Review the on-disk run contract and a redacted example properties file before
connecting to either Fedora utility.

## Phase 3: Implement one complete object attempt

Implement and test each boundary independently, then compose them in an
`ObjectMigrationService`. Use narrow adapters around the external exporter,
`ResourceMigrator`, filesystem promotion, and Fedora 6-compatible HTTP calls so failure
paths can be tested without a live repository.

### Resource identity and paths

For UUID `3f7510bc-7f53-4ae2-b8fc-0fb4ccd95dbb`, derive the Fedora path as:

```text
content/3f/75/10/bc/3f7510bc-7f53-4ae2-b8fc-0fb4ccd95dbb
```

Construct source and destination HTTP URIs by resolving that path against the
normalized Fedora 5 and Fedora 6-compatible target base URIs. Construct the Fedora internal ID by
prefixing the path with `info:fedora/`.

The OCFL object ID is the archival-group internal ID. Its SHA-256 digest is the
object directory name. With the default `HashedNTupleLayoutConfig`, resolve it
under both OCFL roots as:

```text
<root>/<digest[0..3]>/<digest[3..6]>/<digest[6..9]>/<digest>
```

For the example, the digest begins `b9d61f147...`, matching
`b9d/61f/147/b9d61f147...`. Centralize and unit-test this calculation. Before
promotion, require the expected staged directory and verify its
`inventory.json` identifies the expected OCFL object ID. Do not locate objects
by selecting an arbitrary new directory from the staging root.

### Export adapter

Create a unique export directory under `exports/` for every attempt, preferably
using UUID plus a random suffix. Configure the long-lived fork service once
with:

- Fedora 5 repository root, username, and password.
- Binary export enabled.
- Streaming `application/n-triples`.
- `http://www.w3.org/ns/ldp#contains` as the containment predicate.

Call the synchronous API with the object source URI and attempt directory. On
return, require the expected root `.nt` file. The inner directory may be absent
when the root has no exported descendants. Parse the root RDF and require one
of the six configured archival-group types before upgrade starts; a non-AG root
could otherwise produce multiple independent OCFL objects.

### Upgrade adapter

At command startup, create exactly one upgrade `Config`, one shared
`OcflObjectSessionFactory`, and one shared `ResourceMigrator`. Use the values in
the design document: Fedora 5 to the Fedora 6-compatible target API, source
N-Triples, SHA-256, mutable HEAD,
the six Box-c archival-group RDF types, Fedora 5 base URI, and
`<working-dir>/upgrade` as output.

For each export, construct the root `ResourceInfo.container` from:

- Parent ID: the internal ID through the fourth hash segment.
- Full ID: the archival-group internal ID ending in the UUID.
- Initial archival-group ID: `null`.
- Outer directory: the exported directory containing `<uuid>.nt`.
- Encoded name: the UUID.

Use a worker-local deque. Call `ResourceMigrator.migrate()` for the root and
every returned child until the deque is empty. Do not construct a
`MigrationTaskManager`. The application validates the root's archival-group type
before migration; `ResourceMigrator` owns archival-group ID propagation and its
session factory through `close()`. Close the migrator exactly once, only after
all workers terminate.

### OCFL promotion

Promote only the expected object directory, never OCFL root declarations or
extension configuration.

1. Resolve the expected staging and live object paths.
2. If the final live path exists, treat it as a collision without validating its
   OCFL contents. Mark it as preexisting for this attempt, remove the redundant
   staged object, and continue to sideload. A sideload or verification failure
   records the UUID for human investigation; never leave a redundant staged
   object merely because the preexisting destination may be invalid.
3. Create the final parent directories.
4. Create the final object directory only if it does not already exist, then
   recursively copy the staged object into it. Staging is on working storage and
   promotion always crosses to preservation storage, so do not attempt a
   `Files.move`. If final-directory creation reports that it now exists, treat
   it as the collision described in step 2. Multiple migration JVMs sharing
   roots are unsupported.
5. Compare source and final-copy relative regular-file paths and sizes. Reject
   symlinks and unexpected non-regular entries.
6. Remove the staging source only after copy validation succeeds.

Record whether this attempt created the final object directory. On copy or copy
validation failure, attempt to remove that directory and the staging object. A
process interruption or failed cleanup can leave a partial final object; on
retry it is treated as a collision and sideloading failure surfaces it for human
attention. This accepted risk avoids a temporary destination in preservation
storage.

### Target Fedora sideload and verification

Create one shared thread-safe, pooled Apache HttpClient and close it after all
workers stop. Send authenticated requests to the Fedora 6-compatible target
API. The integration target is Fedora 7.1.0-SNAPSHOT:

1. `POST <resource-uri>/fcr:reindex`.
2. Require 204 for sideload success.
3. On 4xx, classify the attempt as terminally rejected. Delete the final OCFL
   directory only if this attempt placed it. If it was preexisting, retain it
   and log that manual review is required.
4. On 5xx or transport failure, retain the final object and record terminal
   failure for later retry because the result is indeterminate.
5. Treat redirects and unexpected non-204 2xx/3xx responses as protocol
   failures, retain the object, and log them for investigation.
6. After 204, send authenticated `HEAD <resource-uri>`. Any 2xx is success.
   Any other status or transport failure is terminal failure, but retain the
   object because Fedora may already know about it.

Always consume/close HTTP responses. Include status and a bounded response-body
excerpt in detailed errors without logging credentials.

### Cleanup rules

In a `finally` block, recursively remove the attempt export directory. If
export or upgrade fails, or promotion never completes, remove the expected
staging object and the final object only when this attempt created it. Never
delete a preexisting final live object during generic cleanup. Cleanup failures
are logged and make an otherwise successful attempt fail before status is
written, because leaked working data over a multi-week run is operationally
significant.

The critical restart states are:

- Interruption before final promotion: no progress entry; retry cleans partial
  staging/export state and starts over.
- Interruption during promotion copy: no progress entry; retry sees the partial
  final object as preexisting, discards a redundant staged object, and lets
  sideloading report the incomplete object for human attention.
- Interruption after validated copy but before progress: retry sees the final
  object as preexisting, discards a redundant staged object, and repeats
  sideload and verification.
- Recorded failure after 5xx, transport error, or failed verification: original
  run skips it; `--from-failures` retries it and retains/reuses the live OCFL
  object.

### Phase 3 tests and review

Use temporary directories and HTTP stubs to cover:

- URI/internal-ID/layout derivation and root `ResourceInfo` construction.
- Root RDF archival-group validation, including a root without descendants and
  rejection of a non-archival-group root.
- Descendant deque traversal, including binary and nested resources.
- Export and upgrade failure cleanup.
- Copy promotion, existing-object collision behavior, staging cleanup, and no
  replacement.
- Copy comparison success, missing/extra files, size mismatch, hash mismatch,
  cleanup of a current-attempt final object, and collision races.
- Every sideload class: 204, 4xx for newly placed and preexisting objects, 5xx,
  transport failure, redirect, and unexpected status.
- Verification 2xx, non-2xx, and transport failure after successful sideload.
- Status ordering and exact retained/deleted paths for every failure stage.

Review the destructive-path tests, especially 4xx handling, before running
against a live target Fedora OCFL root.

## Phase 4: Bounded concurrency, shutdown, and end-to-end validation

### Coordinator and backpressure

Use one fixed-size `ThreadPoolExecutor` with `worker_count` workers and a bounded
queue no larger than a small multiple of the worker count. The ID-reading thread
submits incrementally and blocks or waits for capacity; do not use an unbounded
executor queue. Track completed results as workers finish so memory use is not
proportional to all submitted IDs.

Each task owns one UUID through export cleanup. Shared objects are limited to
the thread-safe exporter service, `ResourceMigrator` and session factory,
HttpClient, immutable configuration, coordinator counters, and synchronized
`StatusWriter`.

Continue after ordinary object failures. Stop new submissions for fatal
application failures such as an unusable status writer, executor failure, or
loss of shared migration infrastructure. Let already active tasks reach safe
cleanup where possible.

### Shutdown

Register one application shutdown hook, not one per export. The hook signals a
shared stop flag and calls orderly executor shutdown. Submission checks the
flag and stops reading IDs. Wait up to `shutdown_timeout_seconds` for active
workers, then call `shutdownNow` and preserve interrupt status. Close the
session factory, HttpClient, and status files only after workers have stopped.
Make normal command completion remove or harmlessly disable the hook so cleanup
does not run twice.

An interrupted task must preserve the thread interrupt flag, execute safe
temporary cleanup, and omit terminal status entries. Print a final summary with
input, skipped, attempted, succeeded, failed, and interrupted/not-submitted
counts.

### Exit codes

- Return 0 only when initialization and processing completed and the run's
  failure log is empty.
- Return 1 for object failures, fatal runtime/configuration errors, status-write
  failures, or interrupted shutdown.
- Allow picocli to use its normal distinct usage-error code for invalid command
  syntax.

Considering the existing failure log in the final result ensures that resuming
an original run does not return success merely because all previously failed
UUIDs were skipped.

### Integration and operational verification

1. Add a command-level test with multiple IDs, a worker count greater than one,
   mixed HTTP outcomes, duplicate input, and pre-populated progress. Assert
   bounded concurrency and exact status files.
2. Add a shutdown test with one blocked worker and queued IDs. Assert bounded
   waiting, interruption, cleanup, and retryable omissions from progress.
3. Build the utility dependencies as thin JARs, then run this project's tests,
   package, dependency tree, and shaded-JAR smoke test.
4. Define a disposable Docker Compose (or equivalent Docker) environment with
   Fedora 5 and a Fedora 7.1.0-SNAPSHOT target container. Mount the target
   container's OCFL root where the utility can inspect it, configure the utility
   with the containers' reachable endpoints, and migrate one archival group
   containing nested metadata and a binary. Inspect the exported tree, staging
   cleanup, OCFL inventory/path, sideload response, and HEAD response.
5. Terminate the process once before promotion, once during copy, and once after
   promotion. Resume each run and verify the restart states above.
6. Run a small concurrency/load test while watching Fedora 5 request load,
   working/live storage throughput, Fedora 7 sideload time, heap, open files,
   and queue depth. Use the result to choose the deployment worker count.

Final human review should approve the pinned commits, dependency tree,
filesystem permissions, tested worker count, shutdown timeout, and backup or
recovery procedure for the live OCFL root before a production run.

## Completion criteria

- The shaded Java 21 JAR runs as a one-command picocli application.
- One long-lived process and one bounded object executor handle all IDs.
- Export and migration process every descendant synchronously within an object
  task and propagate partial failures.
- Promotion never overwrites an existing OCFL object, and every completed copy
  is validated before its staging source is removed.
- Sideload and verification follow the retention/deletion rules above.
- Status logs are concurrency-safe, flushed in restart-safe order, and usable
  directly for resume and separate retry runs.
- Temporary exports, staging objects, and copy temporaries are removed according
  to the failure and interruption rules.
- Unit, command, concurrency, restart, and disposable-environment integration
  tests pass, and the resolved graph contains `fcrepo-java-client` 7.0.0.
