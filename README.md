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
