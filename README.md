# Box-c Fedora OCFL Migration Utility

Java 21 command-line utility for migrating Fedora archival groups into a target
Fedora OCFL repository. See `docs/implementation_plan.md` for the phased design.

## Pinned Utility Builds

The application is built against thin, locally installed utility artifacts:

| Utility | Maven coordinate | Source commit |
| --- | --- | --- |
| `fcrepo-import-export` fork | `org.fcrepo.importexport:fcrepo-import-export:2.1.0-boxc-migration-SNAPSHOT` | Based on `a631e20118d4e7d0c1f34b3e44254cef4255d931` on branch `expose-api`; replace with the fork commit after review |
| `fcrepo-upgrade-utils` | `org.fcrepo:fcrepo-upgrade-utils:7.1.0-SNAPSHOT` | `a801459ba6121bcbde699018cd69ccf163ad8658` |

Install both thin artifacts before building this project. The pinned
upgrade-utils POM does not wire `shade.skip` into its shade execution, so its
thin artifact is installed by invoking the lifecycle steps without `package`:

```sh
(cd ../fcrepo-upgrade-utils && mvn clean test jar:jar install:install)
(cd ../fcrepo-import-export && mvn clean install -Dshade.skip=true -DskipITs)
mvn clean package
```

The import/export executable-JAR integration tests are skipped only for this
thin install because they require the shaded artifact. Its 183 unit tests still
run as part of the command.

The import/export fork and this application force
`org.fcrepo.client:fcrepo-java-client:7.0.0`.

## Run Configuration

Initialize a run with `--id-list-path` and `--config-path`; later invocations
of the same working directory resume from its copied inputs. The configuration
file contains the live OCFL root and Fedora credentials:

```properties
fedora_ocfl_root_dir=/srv/fedora/ocfl-root
fedora_5_base_uri=http://localhost:8181/fcrepo/rest
fedora_5_username=source-user
fedora_5_password=REDACTED
fedora_6_base_uri=http://localhost:8185/fcrepo/rest
fedora_6_username=target-user
fedora_6_password=REDACTED
worker_count=1
shutdown_timeout_seconds=300
```

The live OCFL root must already be a writable directory. The run directory
contains the copied inputs, status logs, lock, and future export/upgrade
workspace; do not edit those files while a run is active.
