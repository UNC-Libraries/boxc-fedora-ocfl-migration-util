# Per-Object Fedora 5 to Fedora 6 Migration

## Purpose

This document captures a proposed long-running migration utility that moves
Fedora 5 objects to Fedora 6 independently. The utility will
coordinate export, OCFL migration, copy, and indexing.

The utility will be implemented as a picocli application. It will receive or
discover a list of Fedora object IDs and process them with a bounded executor
pool. Each ID identifies one archival-group root, and each worker owns the
complete lifecycle of that archival group at a time. The input list is chosen
so that archival groups are not nested or overlapping.

## Object Workflow

For each object ID, a worker will:

1. Export the Fedora 5 object into a worker-specific temporary directory.
2. Construct a `ResourceInfo` describing the exported object root.
3. Migrate the resource and all descendants in the export into the shared
   staging OCFL root in the working directory.
4. Move the completed OCFL object into the live Fedora 6 OCFL root.
5. Index the object in live Fedora 6 using sideloading.
6. Record success or failure with enough information to retry the object.
7. Remove the worker-specific temporary export when it is no longer needed.

The export must contain the selected resource and every descendant required
for that object. For example, an object root with an `md` child requires both
the root and `md` export data to be migrated.

## Embedded Export Utility

The migration utility will use a fork of the current `fcrepo-import-export`
release as a library. The fork must expose a public, synchronous operation for
exporting one archival-group root and all of its descendants into a supplied
output directory.

The required API is conceptually:

```java
void export(URI rootResource, Path outputDirectory) throws ExportException;
```

It must preserve the export behavior used by the current command line
invocation:

```sh
--mode export \
--user user:pw \
--repositoryRoot http://localhost:8181/fcrepo/rest \
--binaries \
--rdfLang application/n-triples \
--predicates http://www.w3.org/ns/ldp#contains \
--streaming \
--auditLog
```

For each call, the migration utility supplies the current archival-group URI
as `rootResource` and a unique per-worker export directory as
`outputDirectory`. The existing exporter output layout then produces the
`fcrepo/rest/...` tree consumed by the upgrade step.

The synchronous export API must:

- Export the supplied root and all descendants discovered through the configured
  containment predicate.
- Write streaming N-Triples and binaries in the existing export format.
- Propagate failed requests and write failures to the caller rather than only
  logging them.
- Perform no independent worker-pool scheduling; the migration utility's
  object executor supplies concurrency.
- Register no JVM shutdown hook for an individual export.
- Avoid shared mutable per-operation configuration so separate workers can
  export safely to separate directories.

The existing `Exporter.run()` method is not suitable for this workflow without
these changes. It creates and shuts down its own task manager, registers a JVM
shutdown hook, and logs individual task failures without failing the caller.
Creating one such exporter for each of several million object IDs would
oversubscribe the system and could allow an incomplete export to be migrated.

The import-export fork is required for the synchronous export API, not for
packaging changes. Build it as a normal, unshaded library as described in
[Build Considerations](#build-considerations).


## Embedded Upgrade Utility

Do not invoke the upgrade utility JAR once per object. A long-lived Java
process avoids repeated JVM startup, dependency loading, Jena initialization,
OCFL repository initialization, and executor creation.

Configure the upgrade utility once when the picocli command starts, then
create one shared `OcflObjectSessionFactory` and `ResourceMigrator`:

```java
var config = new Config();
config.setSourceVersion(FedoraVersion.V_5);
config.setTargetVersion(FedoraVersion.V_6);
config.setOutputDir(ocflOutputDirectory.toFile());
config.setBaseUri(sourceFedoraBaseUri);
config.setSrcRdfLang(Lang.NT);
config.setDigestAlgorithm("sha256");
config.setAutoversioning(false);
config.setArchivalGroupRdfTypes(
    "http://cdr.unc.edu/definitions/model#FileObject,"
        + "http://cdr.unc.edu/definitions/model#Work,"
        + "http://cdr.unc.edu/definitions/model#Folder,"
        + "http://cdr.unc.edu/definitions/model#Collection,"
        + "http://cdr.unc.edu/definitions/model#AdminUnit,"
        + "http://cdr.unc.edu/definitions/model#DepositRecord");

var sessionFactory = UpgradeManagerFactory.createOcflObjectSessionFactory(config);
var migrator = new ResourceMigrator(config, sessionFactory);
```

This configuration is equivalent to the migration-specific portions of the
current CLI invocation:

```sh
--source-version 5+ --target-version 6+ \
--source-rdf application/n-triples \
--autoversioning false \
-d sha256 \
-ag "http://cdr.unc.edu/definitions/model#FileObject,http://cdr.unc.edu/definitions/model#Work,http://cdr.unc.edu/definitions/model#Folder,http://cdr.unc.edu/definitions/model#Collection,http://cdr.unc.edu/definitions/model#AdminUnit,http://cdr.unc.edu/definitions/model#DepositRecord" \
--base-uri http://localhost:8181/fcrepo/rest
```

The output directory is `/tmp/fedora-ocfl/individual/` in the current test
invocation. Its equivalent in the long-running tool is the configured shared
staging OCFL output directory under the working directory. This staging OCFL
root holds objects only while they are being upgraded; completed objects are
moved to the Fedora 6 OCFL root so they do not accumulate on the faster working
storage. `autoversioning=false` stages the imported state in mutable HEAD
rather than committing a new OCFL version.

The per-object worker should construct `ResourceInfo` directly rather than
writing a `--resource-info-file`. For the example object export below:

```text
.../fcrepo/rest/content/3f/75/10/bc/
    3f7510bc-7f53-4ae2-b8fc-0fb4ccd95dbb.nt
    3f7510bc-7f53-4ae2-b8fc-0fb4ccd95dbb/
```

the root resource information is conceptually:

```java
var root = ResourceInfo.container(
    "info:fedora/content/3f/75/10/bc",
    "info:fedora/content/3f/75/10/bc/3f7510bc-7f53-4ae2-b8fc-0fb4ccd95dbb",
    null,
    Path.of(".../fcrepo/rest/content/3f/75/10/bc"),
    "3f7510bc-7f53-4ae2-b8fc-0fb4ccd95dbb");
```

`ResourceMigrator.migrate()` is synchronous and returns immediate children.
The worker must process the returned children; calling it only for the root
does not migrate the complete exported object tree.

```java
var pending = new ArrayDeque<ResourceInfo>();
pending.add(root);

while (!pending.isEmpty()) {
    var resource = pending.removeLast();
    pending.addAll(migrator.migrate(resource));
}
```

This uses `ResourceMigrator` directly and deliberately does not create a
`MigrationTaskManager`. The outer application executor already supplies the
desired object-level concurrency. A second worker pool would add redundant
queueing and make it harder for a worker to know when its object is complete.

Close the shared session factory only when the application is shutting down,
after all object workers have completed.


## Move Object to OCFL Root

After the root and all of its descendants have been migrated, move the OCFL
object directory generated by the upgrade utility from the staging OCFL root
into the live Fedora 6 OCFL root, creating any missing parent directories
first. It would be equivalent to the following commands:
```
mkdir -p /mnt/external_test/ocfl_root/b9d/61f/147
mv /tmp/fedora-ocfl/individual/data/ocfl-root/b9d/61f/147/b9d61f1471dc68900abc2e1e1f193f3898cf3f3d5b69a4dc679a14d03f65faa6 /mnt/external_test/ocfl_root/b9d/61f/147
```
Where `/mnt/external_test/ocfl_root` is the Fedora 6 OCFL root.

The staging and destination repositories must use compatible OCFL versions,
layouts, digest algorithms, and logical path mapping. Move only the completed
OCFL object directory, not repository-level OCFL declaration or configuration
files. If the two roots are on the same filesystem, the move should use an
atomic rename. If they are on different filesystems, copy the object to a
temporary destination path, validate the copy, rename it into its final path,
and only then remove the staging copy.

Fedora 6 may remain live while the object is placed in its OCFL root. Fedora
does not become aware of the newly placed object until the sideloading request
is processed. Before copying or moving the staged object, check whether its
destination path already exists. If it does, do not replace it; discard any
redundant staging copy and continue with sideloading and verification. This
supports resuming a process that stopped after placing the object but before
recording its completion.

## Sideload to index the object

Trigger sideloading API endpoint for the resource. The example endpoint usage is:
`curl -X POST http://localhost:8080/rest/path/to/resource/fcr:reindex`

For our specific example, it would be equivalent to:
`curl -X POST http://localhost:8181/fcrepo/rest/content/3f/75/10/bc/3f7510bc-7f53-4ae2-b8fc-0fb4ccd95dbb/fcr:reindex`

The migration utility will perform this using Apache HttpClient. A 204 response
is expected if the operation was successful. A 4xx response is a definitive
rejection, so the object should be removed from the Fedora 6 OCFL root and
recorded as failed. A 5xx response or transport failure leaves the result
indeterminate; retain the OCFL object, record the failure, and allow a later
retry to repeat sideloading and verification.

Documentation reference: https://wiki.lyrasis.org/spaces/FEDORA7x/pages/416973104/RESTful+HTTP+API+-+Side+Loading

## Verify object appears in Fedora

Perform a head request against the migrated resource to ensure it is now accessible, equivalent to:
`curl -I http://localhost:8181/fcrepo/rest/content/3f/75/10/bc/3f7510bc-7f53-4ae2-b8fc-0fb4ccd95dbb`

The object should be marked as successful if it returns a 2xx response. If
verification fails after sideloading returned 204, retain the OCFL object
because Fedora may now be aware of it. Record the error prominently for manual
investigation rather than deleting or automatically replacing the object.
This is intentionally a simple availability check of the archival-group root;
more comprehensive verification may be added later if needed.

## Cleanup

Regardless of whether an object was successfully migrated or not, clean up the
worker-specific temporary export directory. Also remove a partial object from
the staging OCFL root if migration fails or the object cannot be promoted to
the Fedora 6 OCFL root. Do not rely on `ResourceMigrator` to remove all earlier
commits for the archival group.

## Concurrency

Use a bounded executor with a configurable number of object workers. The
shared `ResourceMigrator` and its OCFL object session factory are designed to
be used concurrently by the upgrade utility's normal full-repository process.

Each worker must use a unique temporary export directory. Workers may run in
parallel only when their exported resource trees do not overlap.

Do not concurrently migrate:

- The same object root.
- Objects whose exports contain overlapping resources.

The repository's flat hash-based path structure means ordinary UUID objects
are expected to be independent. The input IDs are specifically the archival
group roots, so a worker migrates that root and its descendants into one OCFL
object. Their Fedora parent IDs may refer to already created hash-path
containers without requiring those parents to be migrated in the same worker.

Prefer one migration JVM per OCFL output repository. Multiple processes
sharing the same local OCFL output, work, and staging directories are not
coordinated by this utility.

## Failure Handling and Restartability

Treat each object ID as the unit of progress and retry. 

Recording progress will primarily be performed using two log files:
* migration_progress.log - The migration utility should record a progress log,
  which will contain the UUID of every object that has finished processing,
  whether successfully or unsuccessfully. An object is appended only after its
  worker has finished processing the root and all descendants, or after the
  attempt has reached a terminal failure. Objects present in this log are
  skipped when resuming the same run.
* migration_failure.log - A failure log containing the UUID of every object
  that reached a terminal failure at any point in its workflow. This is the
  input for a separate retry of failed objects; its entries do not cause a
  failed object to be retried automatically while resuming the original run.

Writes to both files must be protected from concurrent worker writes, such as
through a synchronized status writer. For a failed object, append and flush its
failure-log entry before appending it to the progress log. This prevents a
process interruption from marking the object complete without also recording
it for retry.

This is in addition to regular application logs populated by logback, which would carry the full details
of workflows and failures.

In case of a failure, the application log should record at least the
object ID, export location or export request details, failure stage, exception,
and timestamp.

The upgrade utility's `ResourceInfoLogger` and `--resource-info-file` support
its batch-resume model, but are not required for this design. The migration
utility should own retries and status tracking because it also owns export,
copy, and indexing.

If migration fails, `ResourceMigrator` attempts to delete the OCFL object for
the resource that failed. The migration utility should nevertheless verify the
object's final state before retrying, especially after process interruption.

## Configuration/Options

The migration utility will need to be configured with the following information:
* working_dir - Base path that the migration utility will store in-progress files and progress logs
to for a specific run. This is the base path where exports and upgrades will be staged to.
* fedora_ocfl_root_dir - OCFL root directory for the Fedora instance. OCFL objects move to here.
* fedora_5_base_uri - Base URI that export requests will be made against, defaulting to http://localhost:8181/fcrepo/rest
* fedora_6_base_uri - Base URI that fedora 6 requests will be made against, defaulting to http://localhost:8185/fcrepo/rest
* fedora_5_username - username used to authenticate against fedora 5 API
* fedora_5_password - password used to authenticate against fedora 5 API
* fedora_6_username - username used to authenticate against fedora 6 API
* fedora_6_password - password used to authenticate against fedora 6 API
* worker_count - Number of concurrent workers used for processing object ids.

Protect copied configuration and logs with deployment-appropriate filesystem
permissions because configuration may contain Fedora credentials.

For using the CLI, the following parameters would be expected:
* --working-dir - sets working_dir. If it doesn't exist, create it. Migration will be resumed against its state if run 
again and the directory exists and contains progress logs. It could use the current working directory if not provided.
* --id-list-path - Path to the list of object ids for migration. One uuid per line. Gets copied into working-dir
and used from there. Not required when resuming.
* --config-path - Path to a .properties file containing the configuration to use for this run. It will be copied
into the working directory, and any properties overwritten by CLI params will be recorded to the copy. This is how
the various fedora config properties will be supplied.

Each UUID maps mechanically to its Fedora resource URI. Normalize the base URI
to end in `/`, split the first eight UUID characters into four two-character
path segments, and construct:

```text
{base-uri}content/{chars-1-2}/{chars-3-4}/{chars-5-6}/{chars-7-8}/{uuid}
```

For example, UUID `3f7510bc-7f53-4ae2-b8fc-0fb4ccd95dbb` maps to:

```text
http://localhost:8181/fcrepo/rest/content/3f/75/10/bc/3f7510bc-7f53-4ae2-b8fc-0fb4ccd95dbb
```

This logic may be copied from or reused from the existing `boxc` utilities.
Deduplicate UUIDs before submitting them to workers so the same object is not
processed concurrently.
 

## Resuming

The migration utility should automatically resume if its working directory
contains progress logs. It skips only IDs already recorded in
`migration_progress.log`; an ID must not be recorded there while its worker is
still processing descendants or performing a later workflow step.

## API Stability

The required types are public:

- `org.fcrepo.upgrade.utils.Config`
- `org.fcrepo.upgrade.utils.FedoraVersion`
- `org.fcrepo.upgrade.utils.UpgradeManagerFactory`
- `org.fcrepo.upgrade.utils.f6.ResourceInfo`
- `org.fcrepo.upgrade.utils.f6.ResourceMigrator`

`UpgradeManagerFactory.createOcflObjectSessionFactory()` is public but marked
`@VisibleForTesting`, so it should not be treated as a stable library contract.
Pin the upgrade utility version for the migration, or maintain a small fork
that exposes a supported per-resource migration service.

## Build Considerations

The migration utility should produce the only shaded executable. Its two
utility dependencies must be installed as thin, non-shaded JARs into the local
Maven repository before building the migration utility:

```sh
# From the selected fcrepo-upgrade-utils checkout
mvn clean install -Dshade.skip=true

# From the forked fcrepo-import-export checkout
mvn clean install -Dshade.skip=true
```

`fcrepo-import-export` is forked only to provide the synchronous export API;
it does not need a packaging modification. `fcrepo-upgrade-utils` does not
need to be forked merely to produce a thin JAR.

The final picocli application should depend on these locally installed Maven
artifacts and use its own shade configuration to create one executable JAR.
This lets Maven resolve a single version of shared libraries rather than
combining pre-shaded JARs. In particular, the resolved graph must use
`fcrepo-java-client` 7.0.0 and should be checked with `mvn dependency:tree`.

No internal Maven repository is required. Reproducibility instead requires
recording the exact source commit and version for both utility checkouts, and
running the two local `mvn clean install -Dshade.skip=true` steps before the
migration utility build.

## Open Design Items

- Select worker count based on Fedora 5 load, storage I/O, and Fedora 6 load.
- Define metrics and operational controls for a migration that runs for weeks.
