package edu.unc.lib.boxc.migration;

import org.apache.commons.codec.digest.DigestUtils;

import java.net.URI;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Derives Fedora and OCFL identifiers for one archival-group UUID.
 *
 * @param id archival-group UUID
 * @param fedoraPath repository-relative Fedora path
 * @param internalId Fedora internal identifier
 * @param sourceUri Fedora 5 resource URI
 * @param targetUri target Fedora resource URI
 * @param ocflDigest SHA-256 layout digest of the internal identifier
 */
record ObjectIdentity(UUID id, String fedoraPath, String internalId, URI sourceUri, URI targetUri,
                      String ocflDigest) {
    /** Creates the identity using normalized Fedora repository roots. */
    static ObjectIdentity from(final UUID id, final URI sourceBaseUri, final URI targetBaseUri) {
        final String value = id.toString();
        final String fedoraPath = "content/" + value.substring(0, 2) + "/" + value.substring(2, 4) + "/"
                + value.substring(4, 6) + "/" + value.substring(6, 8) + "/" + value;
        final String internalId = "info:fedora/" + fedoraPath;
        return new ObjectIdentity(id, fedoraPath, internalId, resolveRepositoryPath(sourceBaseUri, fedoraPath),
                resolveRepositoryPath(targetBaseUri, fedoraPath), DigestUtils.sha256Hex(internalId));
    }

    private static URI resolveRepositoryPath(final URI repositoryBaseUri, final String path) {
        final String base = repositoryBaseUri.toString();
        return URI.create(base.endsWith("/") ? base : base + "/").resolve(path);
    }

    /** Resolves this object's Hashed N-Tuple layout directory below an OCFL root. */
    Path objectPath(final Path ocflRoot) {
        return ocflRoot.resolve(ocflDigest.substring(0, 3)).resolve(ocflDigest.substring(3, 6))
                .resolve(ocflDigest.substring(6, 9)).resolve(ocflDigest);
    }

    /** Returns the Fedora content root that contains migrated archival groups. */
    String parentInternalId() {
        return "info:fedora/content";
    }
}
