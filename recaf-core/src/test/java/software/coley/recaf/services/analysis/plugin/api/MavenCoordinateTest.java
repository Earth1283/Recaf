package software.coley.recaf.services.analysis.plugin.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link MavenCoordinate}.
 */
class MavenCoordinateTest {
	@Test
	void acceptsRealWorldCoordinates() {
		// Taken from the dependencies of the Paper API.
		new MavenCoordinate("io.papermc.paper", "paper-api", "1.21.4-R0.1-SNAPSHOT");
		new MavenCoordinate("io.papermc.paper", "paper-api", "26.1.2.build.74-stable");
		new MavenCoordinate("net.md-5", "bungeecord-chat", "1.21-R0.2-deprecated+build.21");
		new MavenCoordinate("com.google.guava", "listenablefuture", "9999.0-empty-to-avoid-conflict-with-guava");
		new MavenCoordinate("org.eclipse.sisu", "org.eclipse.sisu.inject", "0.9.0.M2");
		new MavenCoordinate("javax.inject", "javax.inject", "1");
		new MavenCoordinate("it.unimi.dsi", "fastutil", "8.5.18");
	}

	@Test
	void buildsRepositoryPaths() {
		MavenCoordinate coordinate = new MavenCoordinate("io.papermc.paper", "paper-api", "1.0");
		assertEquals("io/papermc/paper/paper-api", coordinate.artifactDirectory());
		assertEquals("io/papermc/paper/paper-api/1.0", coordinate.versionDirectory());
		assertEquals("io.papermc.paper:paper-api", coordinate.versionlessKey());
		assertFalse(coordinate.isSnapshot());
		assertTrue(new MavenCoordinate("a", "b", "1-SNAPSHOT").isSnapshot());
	}

	@ParameterizedTest
	@ValueSource(strings = {"", "..", "../evil", "a/b", "a\\b", "/abs", "a b", ".hidden", "a..b/c", "a:b", "a\n", "a%2fb"})
	void rejectsUnsafeGroupIds(String value) {
		assertThrows(IllegalArgumentException.class, () -> new MavenCoordinate(value, "artifact", "1.0"));
	}

	@ParameterizedTest
	@ValueSource(strings = {"", ".", "..", "../evil", "a/b", "a\\b", "/abs", "a b", ".hidden", "a:b", "a\n"})
	void rejectsUnsafeArtifactIds(String value) {
		assertThrows(IllegalArgumentException.class, () -> new MavenCoordinate("group", value, "1.0"));
	}

	@ParameterizedTest
	@ValueSource(strings = {"", ".", "..", "../evil", "1/2", "1\\2", "/abs", "1 2", ".hidden", "1:2", "1\n", "${version}"})
	void rejectsUnsafeVersions(String value) {
		assertThrows(IllegalArgumentException.class, () -> new MavenCoordinate("group", "artifact", value));
	}
}
