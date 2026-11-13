package edu.unc.lib.boxc.migration;

import org.apache.http.HttpEntity;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpHead;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;

/**
 * Pooled authenticated HTTP client for target Fedora sideloading and verification.
 */
final class FedoraTargetRepository implements TargetRepository {
    private static final int BODY_LIMIT = 1024;
    private final CloseableHttpClient client;

    /** Creates the shared client with the configured target credentials and timeouts. */
    FedoraTargetRepository(final MigrationConfig config) {
        final BasicCredentialsProvider credentials = new BasicCredentialsProvider();
        credentials.setCredentials(AuthScope.ANY,
                new UsernamePasswordCredentials(config.fedora6Username(), config.fedora6Password()));
        final RequestConfig requestConfig = RequestConfig.custom()
                .setConnectTimeout(30_000)
                .setSocketTimeout(300_000)
                .setConnectionRequestTimeout(30_000)
                .setRedirectsEnabled(false)
                .build();
        client = HttpClients.custom()
                .setConnectionManager(new PoolingHttpClientConnectionManager())
                .setDefaultCredentialsProvider(credentials)
                .setDefaultRequestConfig(requestConfig)
                .disableAutomaticRetries().build();
    }

    /** Posts a reindex request then requires a successful HEAD response. */
    @Override
    public void sideloadAndVerify(final ObjectIdentity identity) throws TargetRepositoryException {
        final URI resource = identity.targetUri();
        try (CloseableHttpResponse response = client.execute(new HttpPost(resource + "/fcr:reindex"))) {
            final int status = response.getStatusLine().getStatusCode();
            if (status != 204) {
                final String detail = responseDetail(response.getEntity());
                if (status >= 400 && status < 500) {
                    throw new TargetRepositoryException("Sideload rejected with HTTP " + status + detail,
                            MigrationResult.FailureStage.SIDELOAD, true);
                }
                throw new TargetRepositoryException("Unexpected sideload response HTTP " + status + detail,
                        MigrationResult.FailureStage.SIDELOAD, false);
            }
        } catch (IOException e) {
            throw new TargetRepositoryException("Sideload request failed for " + resource,
                    MigrationResult.FailureStage.SIDELOAD, e);
        }

        try (CloseableHttpResponse response = client.execute(new HttpHead(resource))) {
            final int status = response.getStatusLine().getStatusCode();
            if (status < 200 || status >= 300) {
                throw new TargetRepositoryException("Verification returned HTTP " + status + responseDetail(response.getEntity()),
                        MigrationResult.FailureStage.VERIFY, false);
            }
        } catch (IOException e) {
            throw new TargetRepositoryException("Verification request failed for " + resource,
                    MigrationResult.FailureStage.VERIFY, e);
        }
    }

    /** Closes the shared pooled HTTP client. */
    @Override
    public void close() {
        try {
            client.close();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to close target HTTP client", e);
        }
    }

    private static String responseDetail(final HttpEntity entity) throws IOException {
        if (entity == null) {
            return "";
        }
        try (InputStream stream = entity.getContent()) {
            final byte[] bytes = stream.readNBytes(BODY_LIMIT + 1);
            final boolean truncated = bytes.length > BODY_LIMIT;
            return ": " + new String(bytes, 0, Math.min(bytes.length, BODY_LIMIT), StandardCharsets.UTF_8)
                    + (truncated ? "..." : "");
        }
    }
}
