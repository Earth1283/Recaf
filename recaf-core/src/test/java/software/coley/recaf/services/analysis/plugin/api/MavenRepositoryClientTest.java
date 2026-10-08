package software.coley.recaf.services.analysis.plugin.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link MavenRepositoryClient}.
 */
class MavenRepositoryClientTest {
	private static final byte[] JAR = "not really a jar".getBytes(StandardCharsets.UTF_8);
	@TempDir
	Path cache;
	FakeMavenRepository repo;

	@BeforeEach
	void setup() {
		repo = new FakeMavenRepository();
	}

	private MavenRepositoryClient client(Path cacheDir) {
		return new MavenRepositoryClient(repo, List.of(FakeMavenRepository.BASE.substring(0, FakeMavenRepository.BASE.length() - 1)), cacheDir);
	}

	@Test
	void listsVersions() throws IOException {
		repo.put("io/test/api/maven-metadata.xml", """
				<metadata><versioning><versions>
				<version>1.0</version><version>1.1</version><version>2.0-SNAPSHOT</version>
				</versions></versioning></metadata>""");
		assertEquals(List.of("1.0", "1.1", "2.0-SNAPSHOT"), client(null).listVersions("io.test", "api"));
		assertEquals(List.of(), client(null).listVersions("io.test", "missing"));
	}

	@Test
	void fetchesReleaseFile() throws IOException {
		repo.putBytes("io/test/api/1.0/api-1.0.jar", JAR);
		assertArrayEquals(JAR, client(null).fetchFile(new MavenCoordinate("io.test", "api", "1.0"), "jar", 1024, false));
		assertNull(client(null).fetchFile(new MavenCoordinate("io.test", "api", "9.9"), "jar", 1024, false));
	}

	@Test
	void resolvesSnapshotsToTheirTimestampedFile() throws IOException {
		repo.put("io/test/api/1.2-R0.1-SNAPSHOT/maven-metadata.xml", """
				<metadata><versioning><snapshotVersions>
				<snapshotVersion><classifier>sources</classifier><extension>jar</extension><value>1.2-R0.1-20250101.000000-1</value></snapshotVersion>
				<snapshotVersion><extension>jar</extension><value>1.2-R0.1-20250925.065901-231</value></snapshotVersion>
				<snapshotVersion><extension>pom</extension><value>1.2-R0.1-20250925.065901-231</value></snapshotVersion>
				</snapshotVersions></versioning></metadata>""");
		repo.putBytes("io/test/api/1.2-R0.1-SNAPSHOT/api-1.2-R0.1-20250925.065901-231.jar", JAR);
		repo.putBytes("io/test/api/1.2-R0.1-SNAPSHOT/api-1.2-R0.1-20250101.000000-1-sources.jar", "wrong".getBytes());

		assertArrayEquals(JAR, client(null).fetchFile(new MavenCoordinate("io.test", "api", "1.2-R0.1-SNAPSHOT"), "jar", 1024, false),
				"Must pick the main jar and not the sources classifier");
	}

	@Test
	void resolvesSnapshotsFromLegacyMetadata() throws IOException {
		repo.put("io/test/api/1.2-SNAPSHOT/maven-metadata.xml", """
				<metadata><versioning><snapshot><timestamp>20240101.101010</timestamp><buildNumber>5</buildNumber></snapshot></versioning></metadata>""");
		repo.putBytes("io/test/api/1.2-SNAPSHOT/api-1.2-20240101.101010-5.jar", JAR);

		assertArrayEquals(JAR, client(null).fetchFile(new MavenCoordinate("io.test", "api", "1.2-SNAPSHOT"), "jar", 1024, false));
	}

	@Test
	void rejectsSnapshotFileNamesThatCouldEscapeTheCache() {
		repo.put("io/test/api/1.2-SNAPSHOT/maven-metadata.xml", """
				<metadata><versioning><snapshotVersions>
				<snapshotVersion><extension>jar</extension><value>../../../../escaped</value></snapshotVersion>
				</snapshotVersions></versioning></metadata>""");
		MavenCoordinate coordinate = new MavenCoordinate("io.test", "api", "1.2-SNAPSHOT");

		assertThrows(IOException.class, () -> client(cache).fetchFile(coordinate, "jar", 1024, false));
		assertTrue(isEmpty(cache));
		assertTrue(repo.requests.stream().noneMatch(url -> url.contains("..")), () -> repo.requests.toString());
	}

	@Test
	void rejectsLegacySnapshotMetadataThatCouldEscapeTheCache() {
		repo.put("io/test/api/1.2-SNAPSHOT/maven-metadata.xml", """
				<metadata><versioning><snapshot><timestamp>../../x</timestamp><buildNumber>5</buildNumber></snapshot></versioning></metadata>""");
		assertThrows(IOException.class,
				() -> client(cache).fetchFile(new MavenCoordinate("io.test", "api", "1.2-SNAPSHOT"), "jar", 1024, false));
		assertTrue(isEmpty(cache));
	}

	@Test
	void acceptsMatchingChecksum() throws IOException {
		repo.putBytes("io/test/api/1.0/api-1.0.jar", JAR);
		repo.putSha1("io/test/api/1.0/api-1.0.jar", JAR);
		assertArrayEquals(JAR, client(null).fetchFile(new MavenCoordinate("io.test", "api", "1.0"), "jar", 1024, true));
	}

	@Test
	void rejectsMismatchedChecksum() {
		repo.putBytes("io/test/api/1.0/api-1.0.jar", JAR);
		repo.putSha1("io/test/api/1.0/api-1.0.jar", "something else".getBytes(StandardCharsets.UTF_8));
		IOException ex = assertThrows(IOException.class,
				() -> client(cache).fetchFile(new MavenCoordinate("io.test", "api", "1.0"), "jar", 1024, true));
		assertTrue(ex.getMessage().contains("Checksum mismatch"));
		assertTrue(isEmpty(cache), "A file that failed verification must not be cached");
	}

	@Test
	void acceptsFilesWithoutPublishedChecksum() throws IOException {
		repo.putBytes("io/test/api/1.0/api-1.0.jar", JAR);
		assertArrayEquals(JAR, client(null).fetchFile(new MavenCoordinate("io.test", "api", "1.0"), "jar", 1024, true));
	}

	@Test
	void cachesDownloads() throws IOException {
		repo.putBytes("io/test/api/1.0/api-1.0.jar", JAR);
		MavenCoordinate coordinate = new MavenCoordinate("io.test", "api", "1.0");

		assertArrayEquals(JAR, client(cache).fetchFile(coordinate, "jar", 1024, false));
		assertTrue(Files.isRegularFile(cache.resolve("io/test/api/1.0/api-1.0.jar")));
		int requestsAfterFirst = repo.requests.size();

		// Even with the network gone the second read works.
		repo.files.clear();
		assertArrayEquals(JAR, client(cache).fetchFile(coordinate, "jar", 1024, false));
		assertEquals(requestsAfterFirst, repo.requests.size(), "The cached read should not touch the network");
	}

	@Test
	void searchesRepositoriesInOrder() throws IOException {
		FakeMavenRepository second = new FakeMavenRepository() {
			@Override
			public byte[] fetch(String url, long maxBytes) {
				return url.equals("https://second.example/io/test/api/1.0/api-1.0.jar") ? JAR : null;
			}
		};
		PluginApiFetcher both = (url, max) -> url.startsWith("https://second.example/") ? second.fetch(url, max) : repo.fetch(url, max);
		MavenRepositoryClient client = new MavenRepositoryClient(both,
				List.of("https://repo.example/maven", "https://second.example"), null);

		assertArrayEquals(JAR, client.fetchFile(new MavenCoordinate("io.test", "api", "1.0"), "jar", 1024, false));
	}

	private static boolean isEmpty(Path directory) {
		try (var stream = Files.walk(directory)) {
			return stream.noneMatch(Files::isRegularFile);
		} catch (IOException ex) {
			throw new IllegalStateException(ex);
		}
	}
}
