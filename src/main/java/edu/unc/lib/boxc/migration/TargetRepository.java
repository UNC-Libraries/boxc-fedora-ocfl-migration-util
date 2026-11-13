package edu.unc.lib.boxc.migration;

/**
 * Sideloads and verifies an object in the target Fedora repository.
 */
interface TargetRepository extends AutoCloseable {
    /** Sideloads the object and verifies that its root is accessible. */
    void sideloadAndVerify(ObjectIdentity identity) throws TargetRepositoryException;

    @Override
    void close();
}
