package edu.unc.lib.boxc.migration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.fcrepo.client.FcrepoClient;
import org.fcrepo.importexport.common.Config;
import org.fcrepo.importexport.importer.Importer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import picocli.CommandLine;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exercises import, migration, OCFL sideloading, and resource retrieval against disposable Fedora containers. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FedoraMigrationIT {
    private static final Path STORAGE_ROOT = Path.of("/tmp/boxc_migration_storage");
    private static final Path COMPOSE_FILE = Path.of("src/test/docker/docker-compose.yml").toAbsolutePath();
    private static final URI FIXTURE_FEDORA = URI.create("http://localhost:8181/fcrepo/rest");
    private static final URI FEDORA_5 = URI.create("http://localhost:8181/fcrepo/rest");
    private static final URI FEDORA_7 = URI.create("http://localhost:48087/fcrepo/rest");
    private static final String USERNAME = "fedoraAdmin";
    private static final String PASSWORD = "fedoraAdmin";
    private static final boolean FEDORA_MANAGED_EXTERNALLY = Boolean.getBoolean("fedora.managed.externally");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private Path fixtureRoot;

    @BeforeAll
    void startFedora() throws Exception {
        fixtureRoot = resourcePath("fcrepo");
        if (!FEDORA_MANAGED_EXTERNALLY) {
            compose("down", "--volumes", "--remove-orphans");
            deleteTree(STORAGE_ROOT);
            Files.createDirectories(STORAGE_ROOT.resolve("fedora7/ocfl"));
            Files.createDirectories(STORAGE_ROOT.resolve("fedora7/staging"));
            Files.createDirectories(STORAGE_ROOT.resolve("fedora7/temp"));
            copyTree(resourcePath("external_binaries"), STORAGE_ROOT);
            compose("up", "--detach", "--force-recreate");
        }
        waitForFedora(FEDORA_5);
        waitForFedora(FEDORA_7);
        createContentRoot();
    }

    @AfterAll
    void stopFedora() throws Exception {
        if (!FEDORA_MANAGED_EXTERNALLY) {
            compose("down", "--volumes", "--remove-orphans");
            deleteTree(STORAGE_ROOT);
        }
    }

    @Test
    @Timeout(10 * 60)
    void importsFedora5ExportsAndMigratesEveryResourceToFedora7() throws Exception {
        importFixture();

        final Path runDirectory = STORAGE_ROOT.resolve("run");
        final Path properties = STORAGE_ROOT.resolve("migration.properties");
        Files.writeString(properties, migrationProperties());

        final int exitCode = new CommandLine(new Main()).execute("--working-dir", runDirectory.toString(),
                "--id-list-path", resourcePath("id_list.txt").toString(), "--config-path", properties.toString(),
                "--worker-count", "2");

        assertEquals(0, exitCode);
        assertEquals("", Files.readString(runDirectory.resolve(RunDirectory.FAILURE_FILE)));
        verifyExportedResources();
        verifyContentRoot();
    }

    private void importFixture() {
        final Config config = new Config();
        config.setMode("import");
        config.setBaseDirectory(fixtureRoot.getParent().toString());
        config.setIncludeBinaries(true);
        config.setRdfLanguage(Config.DEFAULT_STREAMING_RDF_LANG);
        config.setResource(FEDORA_5);
        config.setMap(new String[] { FIXTURE_FEDORA.toString(), FEDORA_5.toString() });
        config.setUsername(USERNAME);
        config.setPassword(PASSWORD);
        new Importer(config, FcrepoClient.client().credentials(USERNAME, PASSWORD).authScope("localhost")).run();
        restoreResourceTypes();
    }

    private void restoreResourceTypes() {
        try (Stream<Path> paths = Files.walk(fixtureRoot)) {
            for (final Path fixture : paths.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".nt")).toList()) {
                final String statements = Files.readAllLines(fixture).stream()
                        .filter(line -> line.contains("<http://www.w3.org/1999/02/22-rdf-syntax-ns#type>"
                                + " <http://cdr.unc.edu/definitions/model#"))
                        .collect(java.util.stream.Collectors.joining("\n"));
                if (!statements.isBlank()) {
                    final HttpResponse<Void> response = request("PATCH", fixtureUri(fixture, FEDORA_5),
                            "INSERT DATA {\n" + statements + "\n}", "application/sparql-update");
                    assertTrue(response.statusCode() >= 200 && response.statusCode() < 300,
                            () -> "Could not restore fixture type for " + fixture + ": " + response.statusCode());
                }
            }
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException("Could not restore fixture types", e);
        }
    }

    private void verifyExportedResources() throws Exception {
        final List<String> objectIds = Files.readAllLines(resourcePath("id_list.txt"));
        try (Stream<Path> paths = Files.walk(fixtureRoot)) {
            for (final Path fixture : paths.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".external") || objectIds.contains(
                            path.getFileName().toString().replaceFirst("\\.nt$", ""))).toList()) {
                final HttpResponse<Void> response = request("HEAD", targetUri(fixture));
                assertTrue(response.statusCode() >= 200 && response.statusCode() < 300,
                        () -> "Expected Fedora 7 resource for " + fixture + ", got " + response.statusCode());
                if (fixture.toString().endsWith(".external")) {
                    assertEquals(externalLocation(fixture), response.headers().firstValue("Content-Location").orElse(null),
                            () -> "Unexpected external content location for " + fixture);
                }
            }
        }
    }

    private void verifyContentRoot() throws Exception {
        final HttpResponse<String> response = client.send(HttpRequest.newBuilder(URI.create(FEDORA_7 + "/content"))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Basic " + Base64.getEncoder().encodeToString(
                        (USERNAME + ":" + PASSWORD).getBytes(StandardCharsets.UTF_8)))
                .header("Accept", "text/turtle")
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), "Could not retrieve Fedora 7 content root");
        for (final String id : Files.readAllLines(resourcePath("id_list.txt"))) {
            final URI target = ObjectIdentity.from(UUID.fromString(id), FEDORA_5, FEDORA_7).targetUri();
            assertTrue(response.body().contains("<" + target + ">"),
                    () -> "Fedora 7 content root does not contain " + target);
        }
    }

    private URI targetUri(final Path fixture) {
        return fixtureUri(fixture, FEDORA_7);
    }

    private URI fixtureUri(final Path fixture, final URI repositoryBaseUri) {
        String path = fixtureRoot.relativize(fixture).toString().replace('\\', '/');
        if (!path.startsWith("rest/")) {
            throw new IllegalArgumentException("Unexpected exported fixture path: " + fixture);
        }
        path = path.substring("rest/".length()).replaceFirst("\\.(nt|external)$", "");
        return URI.create(repositoryBaseUri + "/").resolve(URLDecoder.decode(path, StandardCharsets.UTF_8));
    }

    private String externalLocation(final Path fixture) throws IOException {
        final JsonNode headers = JSON.readTree(Files.readString(Path.of(fixture + ".headers")));
        return headers.path("Content-Location").get(0).asText();
    }

    private void waitForFedora(final URI baseUri) throws Exception {
        final Instant deadline = Instant.now().plus(Duration.ofMinutes(2));
        while (Instant.now().isBefore(deadline)) {
            try {
                final HttpResponse<Void> response = request("GET", baseUri);
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    return;
                }
            } catch (IOException ignored) {
                // Fedora has not finished deploying yet.
            }
            Thread.sleep(1_000);
        }
        if (!FEDORA_MANAGED_EXTERNALLY) {
            compose("logs", "--no-color");
        }
        throw new IllegalStateException("Timed out waiting for Fedora at " + baseUri);
    }

    private void createContentRoot() throws IOException, InterruptedException {
        final HttpResponse<Void> response = client.send(HttpRequest.newBuilder(FEDORA_7)
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Basic " + Base64.getEncoder().encodeToString(
                        (USERNAME + ":" + PASSWORD).getBytes(StandardCharsets.UTF_8)))
                .header("Slug", "content")
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.discarding());
        assertEquals(201, response.statusCode(), "Could not create Fedora 7 content root");
    }

    private HttpResponse<Void> request(final String method, final URI uri) throws IOException, InterruptedException {
        return client.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30))
                .header("Authorization", "Basic " + Base64.getEncoder().encodeToString(
                        (USERNAME + ":" + PASSWORD).getBytes(StandardCharsets.UTF_8)))
                .method(method, HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.discarding());
    }

    private HttpResponse<Void> request(final String method, final URI uri, final String body, final String contentType)
            throws IOException, InterruptedException {
        return client.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30))
                .header("Authorization", "Basic " + Base64.getEncoder().encodeToString(
                        (USERNAME + ":" + PASSWORD).getBytes(StandardCharsets.UTF_8)))
                .header("Content-Type", contentType).method(method, HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.discarding());
    }

    private String migrationProperties() {
        return "fedora_ocfl_root_dir=" + STORAGE_ROOT.resolve("fedora7/ocfl") + "\n"
                + "fedora_5_base_uri=" + FEDORA_5 + "\n"
                + "fedora_5_username=" + USERNAME + "\n"
                + "fedora_5_password=" + PASSWORD + "\n"
                + "fedora_6_base_uri=" + FEDORA_7 + "\n"
                + "fedora_6_username=" + USERNAME + "\n"
                + "fedora_6_password=" + PASSWORD + "\n"
                + "worker_count=1\n";
    }

    private static Path resourcePath(final String resource) throws Exception {
        return Path.of(FedoraMigrationIT.class.getClassLoader().getResource(resource).toURI());
    }

    private static void copyTree(final Path source, final Path target) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(final Path directory, final BasicFileAttributes attributes)
                    throws IOException {
                Files.createDirectories(target.resolve(source.relativize(directory)));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(final Path file, final BasicFileAttributes attributes) throws IOException {
                Files.copy(file, target.resolve(source.relativize(file)));
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void deleteTree(final Path directory) throws IOException {
        if (Files.notExists(directory)) {
            return;
        }
        Files.walkFileTree(directory, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(final Path file, final BasicFileAttributes attributes) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(final Path path, final IOException exception) throws IOException {
                if (exception != null) {
                    throw exception;
                }
                Files.delete(path);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void compose(final String... arguments) throws IOException, InterruptedException {
        final List<String> command = new java.util.ArrayList<>(List.of("docker", "compose", "-f", COMPOSE_FILE.toString()));
        command.addAll(List.of(arguments));
        final Process process = new ProcessBuilder(command).inheritIO().start();
        if (!process.waitFor(5, java.util.concurrent.TimeUnit.MINUTES) || process.exitValue() != 0) {
            process.destroyForcibly();
            throw new IllegalStateException("Docker Compose failed: " + String.join(" ", command));
        }
    }
}
