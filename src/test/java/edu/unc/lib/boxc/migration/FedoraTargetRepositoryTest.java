package edu.unc.lib.boxc.migration;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.head;
import static com.github.tomakehurst.wiremock.client.WireMock.headRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FedoraTargetRepositoryTest {
    @TempDir
    private Path tempDir;
    private WireMockServer server;

    @BeforeEach
    void startServer() {
        server = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop();
    }

    @Test
    void accepts204SideloadAndAny2xxVerification() throws Exception {
        server.stubFor(post(urlEqualTo(resourcePath() + "/fcr:reindex")).willReturn(aResponse().withStatus(204)));
        server.stubFor(head(urlEqualTo(resourcePath())).willReturn(aResponse().withStatus(200)));

        try (var target = new FedoraTargetRepository(config())) {
            target.sideloadAndVerify(identity());
        }

        server.verify(postRequestedFor(urlEqualTo(resourcePath() + "/fcr:reindex")));
        server.verify(headRequestedFor(urlEqualTo(resourcePath())));
    }

    @Test
    void classifiesSideload4xxAsRejectedAndOtherSideloadResponsesAsIndeterminate() throws Exception {
        server.stubFor(post(urlEqualTo(resourcePath() + "/fcr:reindex")).willReturn(aResponse().withStatus(400)));
        final TargetRepositoryException rejected;
        try (var target = new FedoraTargetRepository(config())) {
            rejected = assertThrows(TargetRepositoryException.class, () -> target.sideloadAndVerify(identity()));
        }
        assertEquals(MigrationResult.FailureStage.SIDELOAD, rejected.failureStage());
        assertEquals(true, rejected.rejected());

        server.resetAll();
        server.stubFor(post(urlEqualTo(resourcePath() + "/fcr:reindex")).willReturn(aResponse().withStatus(500)));
        final TargetRepositoryException indeterminate;
        try (var target = new FedoraTargetRepository(config())) {
            indeterminate = assertThrows(TargetRepositoryException.class, () -> target.sideloadAndVerify(identity()));
        }
        assertEquals(MigrationResult.FailureStage.SIDELOAD, indeterminate.failureStage());
        assertFalse(indeterminate.rejected());
    }

    @Test
    void classifiesVerificationFailureSeparately() throws Exception {
        server.stubFor(post(urlEqualTo(resourcePath() + "/fcr:reindex")).willReturn(aResponse().withStatus(204)));
        server.stubFor(head(urlEqualTo(resourcePath())).willReturn(aResponse().withStatus(503)));

        final TargetRepositoryException failure;
        try (var target = new FedoraTargetRepository(config())) {
            failure = assertThrows(TargetRepositoryException.class, () -> target.sideloadAndVerify(identity()));
        }

        assertEquals(MigrationResult.FailureStage.VERIFY, failure.failureStage());
        assertFalse(failure.rejected());
    }

    private MigrationConfig config() throws Exception {
        final Properties properties = new Properties();
        properties.setProperty(MigrationConfig.OCFL_ROOT, Files.createDirectories(tempDir.resolve("ocfl")).toString());
        properties.setProperty(MigrationConfig.FEDORA_5_USERNAME, "source-user");
        properties.setProperty(MigrationConfig.FEDORA_5_PASSWORD, "source-password");
        properties.setProperty(MigrationConfig.FEDORA_6_USERNAME, "target-user");
        properties.setProperty(MigrationConfig.FEDORA_6_PASSWORD, "target-password");
        properties.setProperty(MigrationConfig.FEDORA_6_BASE_URI, "http://localhost:" + server.port() + "/rest/");
        return MigrationConfig.fromProperties(properties);
    }

    private ObjectIdentity identity() {
        return ObjectIdentity.from(UUID.fromString("3f7510bc-7f53-4ae2-b8fc-0fb4ccd95dbb"), URI.create("http://source/rest/"),
                URI.create("http://localhost:" + server.port() + "/rest/"));
    }

    private String resourcePath() {
        return "/rest/content/3f/75/10/bc/3f7510bc-7f53-4ae2-b8fc-0fb4ccd95dbb";
    }
}
