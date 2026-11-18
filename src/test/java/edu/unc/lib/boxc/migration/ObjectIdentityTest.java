package edu.unc.lib.boxc.migration;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ObjectIdentityTest {
    @Test
    void derivesFedoraUrisAndHashedNTuplePath() {
        final ObjectIdentity identity = ObjectIdentity.from(UUID.fromString("3f7510bc-7f53-4ae2-b8fc-0fb4ccd95dbb"),
                URI.create("http://source/fcrepo/rest/"), URI.create("http://target/fcrepo/rest/"));

        assertEquals("content/3f/75/10/bc/3f7510bc-7f53-4ae2-b8fc-0fb4ccd95dbb", identity.fedoraPath());
        assertEquals("info:fedora/content/3f/75/10/bc/3f7510bc-7f53-4ae2-b8fc-0fb4ccd95dbb", identity.internalId());
        assertEquals("info:fedora/content", identity.parentInternalId());
        assertEquals(URI.create("http://source/fcrepo/rest/" + identity.fedoraPath()), identity.sourceUri());
        assertEquals(URI.create("http://target/fcrepo/rest/" + identity.fedoraPath()), identity.targetUri());
        assertEquals("b9d61f1471dc68900abc2e1e1f193f3898cf3f3d5b69a4dc679a14d03f65faa6", identity.ocflDigest());
        assertEquals(Path.of("root/b9d/61f/147/" + identity.ocflDigest()), identity.objectPath(Path.of("root")));
    }

    @Test
    void derivesDepositFedoraUrisAndHashedNTuplePath() {
        final UUID id = UUID.fromString("56fd7c53-f152-40ab-ad56-5ddb9e52dc40");
        final ObjectIdentity identity = ObjectIdentity.from(new ObjectId(id, true),
                URI.create("http://source/fcrepo/rest/"), URI.create("http://target/fcrepo/rest/"));

        assertEquals("deposits/56/fd/7c/53/56fd7c53-f152-40ab-ad56-5ddb9e52dc40", identity.fedoraPath());
        assertEquals("info:fedora/deposits/56/fd/7c/53/56fd7c53-f152-40ab-ad56-5ddb9e52dc40", identity.internalId());
        assertEquals("info:fedora/deposits", identity.parentInternalId());
        assertEquals(URI.create("http://source/fcrepo/rest/" + identity.fedoraPath()), identity.sourceUri());
        assertEquals(URI.create("http://target/fcrepo/rest/" + identity.fedoraPath()), identity.targetUri());
    }
}
