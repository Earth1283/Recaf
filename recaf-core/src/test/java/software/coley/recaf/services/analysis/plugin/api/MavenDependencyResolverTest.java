package software.coley.recaf.services.analysis.plugin.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static software.coley.recaf.services.analysis.plugin.api.FakeMavenRepository.dep;
import static software.coley.recaf.services.analysis.plugin.api.FakeMavenRepository.deps;

/**
 * Tests for {@link MavenDependencyResolver}.
 */
class MavenDependencyResolverTest {
	FakeMavenRepository repo;
	MavenCoordinate root = new MavenCoordinate("test", "api", "1.0");

	@BeforeEach
	void setup() {
		repo = new FakeMavenRepository();
	}

	private List<String> resolve(int max) throws IOException {
		MavenRepositoryClient client = new MavenRepositoryClient(repo, List.of(FakeMavenRepository.BASE), null);
		return new MavenDependencyResolver(client, max).resolve(root).stream().map(MavenCoordinate::toString).toList();
	}

	@Test
	void resolvesDirectAndTransitiveCompileDependencies() throws IOException {
		repo.pom("test:api:1.0", deps(dep("lib", "a", "1.0"), dep("lib", "b", "2.0")));
		repo.pom("lib:a:1.0", deps(dep("lib", "c", "3.0")));
		repo.pom("lib:b:2.0", "");
		repo.pom("lib:c:3.0", "");

		assertEquals(List.of("lib:a:1.0", "lib:b:2.0", "lib:c:3.0"), resolve(50), "Direct first, then transitive");
	}

	@Test
	void skipsOptionalAndNonCompileDependencies() throws IOException {
		repo.pom("test:api:1.0", deps(
				dep("lib", "compile", "1"),
				dep("lib", "default-scope", "1"),
				dep("lib", "runtime", "1", "runtime", ""),
				dep("lib", "test", "1", "test", ""),
				dep("lib", "provided", "1", "provided", ""),
				dep("lib", "optional", "1", null, "<optional>true</optional>"),
				dep("lib", "classified", "1", null, "<classifier>natives</classifier>"),
				dep("lib", "pom-type", "1", null, "<type>pom</type>")));
		repo.pom("lib:compile:1", "");
		repo.pom("lib:default-scope:1", "");

		assertEquals(List.of("lib:compile:1", "lib:default-scope:1"), resolve(50));
	}

	@Test
	void exclusionsApplyToChildrenButNeverToTheDependencyItself() throws IOException {
		// The wildcard exclusion on joml must not remove joml, only what joml itself would bring in.
		repo.pom("test:api:1.0", deps(
				dep("lib", "joml", "1", null, "<exclusions><exclusion><groupId>*</groupId><artifactId>*</artifactId></exclusion></exclusions>"),
				dep("lib", "other", "1", null, "<exclusions><exclusion><groupId>lib</groupId><artifactId>noise</artifactId></exclusion></exclusions>")));
		repo.pom("lib:joml:1", deps(dep("lib", "joml-child", "1")));
		repo.pom("lib:other:1", deps(dep("lib", "noise", "1"), dep("lib", "signal", "1")));
		repo.pom("lib:signal:1", "");

		assertEquals(List.of("lib:joml:1", "lib:other:1", "lib:signal:1"), resolve(50));
	}

	@Test
	void exclusionsPropagateDownThePath() throws IOException {
		repo.pom("test:api:1.0", deps(dep("lib", "a", "1", null,
				"<exclusions><exclusion><groupId>lib</groupId><artifactId>deep</artifactId></exclusion></exclusions>")));
		repo.pom("lib:a:1", deps(dep("lib", "b", "1")));
		repo.pom("lib:b:1", deps(dep("lib", "deep", "1")));

		assertEquals(List.of("lib:a:1", "lib:b:1"), resolve(50));
	}

	@Test
	void nearestDeclarationWins() throws IOException {
		repo.pom("test:api:1.0", deps(dep("lib", "a", "1"), dep("lib", "shared", "9")));
		repo.pom("lib:a:1", deps(dep("lib", "shared", "1")));
		repo.pom("lib:shared:9", "");
		repo.pom("lib:shared:1", "");

		assertEquals(List.of("lib:a:1", "lib:shared:9"), resolve(50));
	}

	@Test
	void rootManagementOverridesTransitiveVersions() throws IOException {
		repo.pom("test:api:1.0", "<dependencyManagement>" + deps(dep("lib", "shared", "5")) + "</dependencyManagement>" +
				deps(dep("lib", "a", "1")));
		repo.pom("lib:a:1", deps(dep("lib", "shared", "1")));
		repo.pom("lib:shared:5", "");

		assertEquals(List.of("lib:a:1", "lib:shared:5"), resolve(50));
	}

	@Test
	void importsBomsAndFillsInVersions() throws IOException {
		repo.pom("test:api:1.0", "<dependencyManagement><dependencies>" +
				dep("bom", "bom", "7", "import", "<type>pom</type>") + "</dependencies></dependencyManagement>" +
				deps(dep("lib", "managed", null)));
		repo.pom("bom:bom:7", "<dependencyManagement>" + deps(dep("lib", "managed", "4.2")) + "</dependencyManagement>");
		repo.pom("lib:managed:4.2", "");

		assertEquals(List.of("lib:managed:4.2"), resolve(50));
	}

	@Test
	void usesParentPropertiesAndInheritedDependencies() throws IOException {
		repo.pom("test:api:1.0", "<parent><groupId>test</groupId><artifactId>parent</artifactId><version>3</version></parent>" +
				deps(dep("lib", "versioned", "${lib.version}")));
		repo.pom("test:parent:3", "<properties><lib.version>8.1</lib.version></properties>" +
				deps(dep("lib", "inherited", "${project.version}")));
		repo.pom("lib:versioned:8.1", "");
		repo.pom("lib:inherited:3", "");

		assertEquals(List.of("lib:inherited:3", "lib:versioned:8.1"), resolve(50),
				"Inherited dependencies come first, and ${project.version} is the declaring project's version");
	}

	@Test
	void survivesCyclesAndSkipsUnresolvableVersions() throws IOException {
		repo.pom("test:api:1.0", deps(
				dep("lib", "a", "1"),
				dep("lib", "ranged", "[1.0,2.0)"),
				dep("lib", "unresolved", "${nope}"),
				dep("lib", "pinned", "[2.5]")));
		repo.pom("lib:a:1", deps(dep("lib", "b", "1")));
		repo.pom("lib:b:1", deps(dep("lib", "a", "1"), dep("test", "api", "1.0")));
		repo.pom("lib:pinned:2.5", "");

		assertEquals(List.of("lib:a:1", "lib:pinned:2.5", "lib:b:1"), resolve(50));
	}

	@Test
	void keepsDependencyWhenItsOwnPomIsMissing() throws IOException {
		repo.pom("test:api:1.0", deps(dep("lib", "nopom", "1"), dep("lib", "fine", "1")));
		repo.pom("lib:fine:1", "");

		assertEquals(List.of("lib:nopom:1", "lib:fine:1"), resolve(50));
	}

	@Test
	void failsWhenRootPomIsMissing() {
		assertThrows(IOException.class, () -> resolve(50));
	}

	@Test
	void stopsAtTheArtifactLimit() throws IOException {
		repo.pom("test:api:1.0", deps(dep("lib", "a", "1"), dep("lib", "b", "1"), dep("lib", "c", "1")));
		repo.pom("lib:a:1", "");
		repo.pom("lib:b:1", "");
		repo.pom("lib:c:1", "");

		assertEquals(List.of("lib:a:1", "lib:b:1"), resolve(2));
	}

	@Test
	void skipsDependenciesWithCoordinatesThatCouldEscapeTheCache() throws IOException {
		repo.pom("test:api:1.0", deps(
				dep("../../evil", "a", "1"),
				dep("lib", "../../evil", "1"),
				dep("lib", "b", "../../1"),
				dep("lib", "fine", "1")));
		repo.pom("lib:fine:1", "");

		assertEquals(List.of("lib:fine:1"), resolve(50));
		assertTrue(repo.requests.stream().noneMatch(url -> url.contains("evil")),
				() -> "No request should be made for a hostile coordinate: " + repo.requests);
	}

	@Test
	void rejectsPomWithHostileParent() {
		repo.pom("test:api:1.0", "<parent><groupId>../../evil</groupId><artifactId>p</artifactId><version>1</version></parent>");
		assertThrows(IOException.class, () -> resolve(50));
		assertTrue(repo.requests.stream().noneMatch(url -> url.contains("evil")),
				() -> "The hostile parent must not be requested: " + repo.requests);
	}

	@Test
	void ignoresBomImportsWithHostileCoordinates() throws IOException {
		repo.pom("test:api:1.0", "<dependencyManagement><dependencies>" +
				dep("../../evil", "bom", "1", "import", "<type>pom</type>") + "</dependencies></dependencyManagement>" +
				deps(dep("lib", "fine", "1")));
		repo.pom("lib:fine:1", "");

		assertEquals(List.of("lib:fine:1"), resolve(50));
		assertTrue(repo.requests.stream().noneMatch(url -> url.contains("evil")),
				() -> "The hostile BOM must not be requested: " + repo.requests);
	}

	@Test
	void rejectsXmlThatUsesEntities() {
		repo.put("test/api/1.0/api-1.0.pom", """
				<?xml version="1.0"?>
				<!DOCTYPE project [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
				<project><groupId>&xxe;</groupId></project>
				""");
		assertThrows(IOException.class, () -> resolve(50));
	}
}
