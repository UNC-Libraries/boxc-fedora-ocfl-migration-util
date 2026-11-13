package edu.unc.lib.boxc.migration;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.vocabulary.RDF;
import org.fcrepo.importexport.exporter.ResourceExporter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

/**
 * Exports and validates archival-group roots through the import-export fork.
 */
final class FedoraExporter implements AttemptExporter {
    static final Set<String> ARCHIVAL_GROUP_TYPES = Set.of(
            "http://cdr.unc.edu/definitions/model#FileObject",
            "http://cdr.unc.edu/definitions/model#Work",
            "http://cdr.unc.edu/definitions/model#Folder",
            "http://cdr.unc.edu/definitions/model#Collection",
            "http://cdr.unc.edu/definitions/model#AdminUnit",
            "http://cdr.unc.edu/definitions/model#DepositRecord");

    private final ResourceExporter exporter;

    /** Creates an exporter using the immutable migration configuration. */
    FedoraExporter(final MigrationConfig config) {
        exporter = new ResourceExporter(Main.exportConfig(config.fedora5BaseUri(), config.fedora5Username(),
                config.fedora5Password()));
    }

    /** Exports the root and rejects an exported root that is not an archival group. */
    @Override
    public void export(final ObjectIdentity identity, final Path attemptDirectory) throws Exception {
        exporter.export(identity.sourceUri(), attemptDirectory);
        final Path rootRdf = exportRoot(identity, attemptDirectory).resolve(identity.id() + ".nt");
        if (!Files.isRegularFile(rootRdf)) {
            throw new IllegalStateException("Export did not produce root RDF " + rootRdf);
        }
        validateArchivalGroup(rootRdf, identity.id());
    }

    /** Validates that root RDF declares one of the configured archival-group types. */
    static void validateArchivalGroup(final Path rootRdf, final java.util.UUID id) {
        final Model model = RDFDataMgr.loadModel(rootRdf.toUri().toString(), Lang.NT);
        if (model.listObjectsOfProperty(RDF.type).toList().stream()
                .map(node -> node.isURIResource() ? node.asResource().getURI() : "")
                .noneMatch(ARCHIVAL_GROUP_TYPES::contains)) {
            throw new IllegalArgumentException("Exported root is not an archival group: " + id);
        }
    }

    /** Returns the directory containing the root RDF in the exporter layout. */
    static Path exportRoot(final ObjectIdentity identity, final Path attemptDirectory) {
        return attemptDirectory.resolve("fcrepo/rest").resolve(identity.fedoraPath()).getParent();
    }
}
