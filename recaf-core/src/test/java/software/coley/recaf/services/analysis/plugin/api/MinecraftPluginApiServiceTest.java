package software.coley.recaf.services.analysis.plugin.api;

import jakarta.annotation.Nonnull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.info.properties.builtin.CachedDecompileProperty;
import software.coley.recaf.services.analysis.plugin.MinecraftPluginAnalysisService;
import software.coley.recaf.services.decompile.DecompileResult;
import software.coley.recaf.services.decompile.NoopJvmDecompiler;
import software.coley.recaf.services.workspace.io.ResourceImporter;
import software.coley.recaf.test.TestBase;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.resource.WorkspaceFileResource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static software.coley.recaf.services.analysis.plugin.api.FakeMavenRepository.dep;
import static software.coley.recaf.services.analysis.plugin.api.FakeMavenRepository.deps;
import static software.coley.recaf.test.TestClassUtils.createEmptyClass;
import static software.coley.recaf.test.TestClassUtils.createFile;
import static software.coley.recaf.test.TestClassUtils.fromBundle;
import static software.coley.recaf.test.TestClassUtils.fromBundles;
import static software.coley.recaf.test.TestClassUtils.fromClasses;
import static software.coley.recaf.test.TestClassUtils.fromFiles;

/**
 * Tests for {@link MinecraftPluginApiService}, with the network replaced by {@link FakeMavenRepository}.
 */
class MinecraftPluginApiServiceTest extends TestBase {
	private static final String PAPER = "io/papermc/paper/paper-api/";
	@TempDir
	Path cache;
	FakeMavenRepository repo;
	MinecraftPluginApiConfig config;
	MinecraftPluginApiService service;
	Workspace workspace;
	JvmClassInfo pluginClass;

	@BeforeEach
	void setup() throws IOException {
		repo = new FakeMavenRepository();
		config = new MinecraftPluginApiConfig();
		service = new MinecraftPluginApiService(config, recaf.get(MinecraftPluginAnalysisService.class), repo,
				recaf.get(ResourceImporter.class), cache, List.of(FakeMavenRepository.BASE));

		pluginClass = createEmptyClass("test/MyPlugin");
		workspace = fromBundle(fromClasses(pluginClass));

		// Published versions: two in the 1.21 family, and one other.
		repo.put(PAPER + "maven-metadata.xml", "<metadata><versioning><versions>" +
				"<version>1.20.4-R0.1-SNAPSHOT</version><version>1.21-R0.1-SNAPSHOT</version>" +
				"<version>1.21.4-R0.1-SNAPSHOT</version><version>1.21.11-R0.1-SNAPSHOT</version>" +
				"</versions></versioning></metadata>");
		publishPaper("1.21.11-R0.1-SNAPSHOT", "20250101.000000-5", jarWith("org/bukkit/plugin/java/JavaPlugin"),
				deps(dep("com.google.guava", "guava", "33.0-jre"), dep("net.kyori", "adventure-api", "4.20.0")));
		publishLibrary("com.google.guava", "guava", "33.0-jre", jarWith("com/google/common/collect/ImmutableList"));
		publishLibrary("net.kyori", "adventure-api", "4.20.0", jarWith("net/kyori/adventure/text/Component"));
	}

	private void publishPaper(String version, String timestamp, byte[] jar, String dependencies) {
		String dir = PAPER + version + "/";
		String base = version.replace("-SNAPSHOT", "") + "-" + timestamp;
		repo.put(dir + "maven-metadata.xml", "<metadata><versioning><snapshotVersions>" +
				"<snapshotVersion><extension>jar</extension><value>" + base + "</value></snapshotVersion>" +
				"<snapshotVersion><extension>pom</extension><value>" + base + "</value></snapshotVersion>" +
				"</snapshotVersions></versioning></metadata>");
		repo.putBytes(dir + "paper-api-" + base + ".jar", jar);
		repo.putSha1(dir + "paper-api-" + base + ".jar", jar);
		repo.put(dir + "paper-api-" + base + ".pom", "<project><modelVersion>4.0.0</modelVersion><groupId>io.papermc.paper</groupId>" +
				"<artifactId>paper-api</artifactId><version>" + version + "</version>" + dependencies + "</project>");
	}

	private void publishLibrary(String group, String artifact, String version, byte[] jar) {
		String path = group.replace('.', '/') + "/" + artifact + "/" + version + "/" + artifact + "-" + version;
		repo.putBytes(path + ".jar", jar);
		repo.putSha1(path + ".jar", jar);
		repo.pom(group + ":" + artifact + ":" + version, "");
	}

	private static byte[] jarWith(String className) throws IOException {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
			zip.putNextEntry(new ZipEntry(className + ".class"));
			zip.write(createEmptyClass(className).getBytecode());
			zip.closeEntry();
		}
		return bytes.toByteArray();
	}

	private static boolean hasClass(@Nonnull Workspace workspace, @Nonnull String className) {
		return workspace.getSupportingResources().stream()
				.anyMatch(resource -> resource.getJvmClassBundle().containsKey(className));
	}

	private PluginApiRequest paper(String apiVersion) {
		return new PluginApiRequest(PluginApiTarget.PAPER, apiVersion);
	}

	@Test
	void attachesApiAndItsLibraries() throws IOException {
		PluginApiAttachResult result = service.attach(workspace, paper("1.21"), status -> {});

		assertEquals("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT", result.api().toString(),
				"Newest of the 1.21 family");
		assertEquals(3, result.attached().size());
		assertTrue(result.skipped().isEmpty(), () -> "Nothing should be skipped: " + result.skipped());
		assertEquals(3, workspace.getSupportingResources().size());
		assertTrue(hasClass(workspace, "org/bukkit/plugin/java/JavaPlugin"));
		assertTrue(hasClass(workspace, "com/google/common/collect/ImmutableList"));
		assertTrue(hasClass(workspace, "net/kyori/adventure/text/Component"));
		assertEquals(1, workspace.getPrimaryResource().getJvmClassBundle().size(),
				"The plugin's own resource is untouched");
	}

	@Test
	void attachingTwiceDoesNotDuplicate() throws IOException {
		service.attach(workspace, paper("1.21"), status -> {});
		PluginApiAttachResult second = service.attach(workspace, paper("1.21"), status -> {});

		assertTrue(second.attached().isEmpty());
		assertEquals(3, second.alreadyPresent().size());
		assertEquals(3, workspace.getSupportingResources().size());
	}

	@Test
	void reportsProgress() throws IOException {
		List<String> messages = new java.util.ArrayList<>();
		service.attach(workspace, paper("1.21"), messages::add);

		assertTrue(messages.stream().anyMatch(m -> m.contains("paper-api")), () -> "Got: " + messages);
		assertTrue(messages.stream().anyMatch(m -> m.contains("guava")), () -> "Got: " + messages);
	}

	@Test
	void canSkipLibraries() throws IOException {
		config.getIncludeDependencies().setValue(false);
		PluginApiAttachResult result = service.attach(workspace, paper("1.21"), status -> {});

		assertEquals(1, result.attached().size());
		assertEquals(1, workspace.getSupportingResources().size());
		assertTrue(hasClass(workspace, "org/bukkit/plugin/java/JavaPlugin"));
	}

	@Test
	void skipsOversizedLibrariesButKeepsTheRest() throws IOException {
		// A big library, like fastutil. Make guava exceed the limit.
		byte[] big = new byte[2 * 1024 * 1024];
		publishLibrary("com.google.guava", "guava", "33.0-jre", big);
		config.getMaxDependencySizeMb().setValue(1);

		PluginApiAttachResult result = service.attach(workspace, paper("1.21"), status -> {});

		assertEquals(2, result.attached().size(), "API and adventure");
		assertEquals(1, result.skipped().size());
		assertEquals("com.google.guava:guava:33.0-jre", result.skipped().getFirst().coordinate().toString());
		assertEquals("Larger than the limit of 1 MB", result.skipped().getFirst().reason());
		assertTrue(hasClass(workspace, "net/kyori/adventure/text/Component"));
	}

	@Test
	void skipsLibraryThatFailsChecksumButKeepsTheRest() throws IOException {
		byte[] jar = jarWith("com/google/common/collect/ImmutableList");
		repo.putSha1("com/google/guava/guava/33.0-jre/guava-33.0-jre.jar", "tampered".getBytes(StandardCharsets.UTF_8));
		assertNotNull(jar);

		PluginApiAttachResult result = service.attach(workspace, paper("1.21"), status -> {});

		assertEquals(1, result.skipped().size());
		assertTrue(result.skipped().getFirst().reason().contains("Checksum mismatch"), result.skipped().getFirst().reason());
		assertTrue(!hasClass(workspace, "com/google/common/collect/ImmutableList"));
		assertTrue(hasClass(workspace, "org/bukkit/plugin/java/JavaPlugin"));
	}

	@Test
	void failsWithoutChangingTheWorkspaceWhenTheApiChecksumDoesNotMatch() throws IOException {
		repo.putSha1(PAPER + "1.21.11-R0.1-SNAPSHOT/paper-api-1.21.11-R0.1-20250101.000000-5.jar",
				"tampered".getBytes(StandardCharsets.UTF_8));

		IOException ex = assertThrows(IOException.class, () -> service.attach(workspace, paper("1.21"), status -> {}));
		assertTrue(ex.getMessage().contains("Checksum mismatch"), ex.getMessage());
		assertTrue(workspace.getSupportingResources().isEmpty(), "Nothing should be added when the API failed");
	}

	@Test
	void failsWhenNoVersionMatches() {
		IOException ex = assertThrows(IOException.class, () -> service.attach(workspace, paper("30.0"), status -> {}));
		assertTrue(ex.getMessage().contains("30.0"), ex.getMessage());
		assertTrue(workspace.getSupportingResources().isEmpty());
	}

	@Test
	void failsCleanlyWhenTheRepositoryListsAnInvalidVersion() {
		// The Bungee target takes any release it is told about, so a junk entry reaches coordinate validation.
		repo.put("net/md-5/bungeecord-api/maven-metadata.xml",
				"<metadata><versioning><versions><version>../../evil</version></versions></versioning></metadata>");
		IOException ex = assertThrows(IOException.class,
				() -> service.attach(workspace, new PluginApiRequest(PluginApiTarget.BUNGEE, null), status -> {}));
		assertTrue(ex.getMessage().contains("invalid version"), ex.getMessage());
		assertTrue(workspace.getSupportingResources().isEmpty());
	}

	@Test
	void failsWhenTheRepositoryHasNoVersions() {
		repo.files.clear();
		assertThrows(IOException.class, () -> service.attach(workspace, paper("1.21"), status -> {}));
		assertTrue(workspace.getSupportingResources().isEmpty());
	}

	@Test
	void discardsCachedDecompilationsOfPrimaryClasses() throws IOException {
		JvmClassInfo cls = workspace.getPrimaryResource().getJvmClassBundle().get("test/MyPlugin");
		CachedDecompileProperty.set(cls, NoopJvmDecompiler.getInstance(), new DecompileResult("old output", 0));
		assertNotNull(CachedDecompileProperty.get(cls, NoopJvmDecompiler.getInstance()));

		service.attach(workspace, paper("1.21"), status -> {});

		JvmClassInfo after = workspace.getPrimaryResource().getJvmClassBundle().get("test/MyPlugin");
		assertNull(CachedDecompileProperty.get(after, NoopJvmDecompiler.getInstance()),
				"Output made without the API should not be reused");
	}

	@Test
	void keepsCachedDecompilationsWhenNothingWasAdded() throws IOException {
		service.attach(workspace, paper("1.21"), status -> {});
		JvmClassInfo cls = workspace.getPrimaryResource().getJvmClassBundle().get("test/MyPlugin");
		CachedDecompileProperty.set(cls, NoopJvmDecompiler.getInstance(), new DecompileResult("fresh output", 0));

		service.attach(workspace, paper("1.21"), status -> {});

		JvmClassInfo after = workspace.getPrimaryResource().getJvmClassBundle().get("test/MyPlugin");
		assertNotNull(CachedDecompileProperty.get(after, NoopJvmDecompiler.getInstance()));
	}

	@Test
	void secondRunUsesTheCacheInsteadOfTheNetwork() throws IOException {
		service.attach(workspace, paper("1.21"), status -> {});
		long jarRequests = repo.requests.stream().filter(url -> url.endsWith(".jar")).count();
		assertTrue(jarRequests > 0);

		Workspace other = fromBundle(fromClasses(createEmptyClass("test/Other")));
		service.attach(other, paper("1.21"), status -> {});

		assertEquals(jarRequests, repo.requests.stream().filter(url -> url.endsWith(".jar")).count(),
				"Jars should come from the cache the second time");
		assertEquals(3, other.getSupportingResources().size());
	}

	@Test
	void detectsRequestsFromManifests() throws IOException {
		Workspace plugins = fromBundles(fromClasses(createEmptyClass("test/A")), fromFiles(
				createFile("plugin.yml", "main: test.A\napi-version: '1.21'\n".getBytes(StandardCharsets.UTF_8)),
				createFile("paper-plugin.yml", "main: test.A\napi-version: '1.21'\n".getBytes(StandardCharsets.UTF_8))));
		assertEquals(List.of(paper("1.21")), service.detectRequests(plugins),
				"The same API from two manifests is one request");

		Workspace bungee = fromBundles(fromClasses(createEmptyClass("test/B")), fromFiles(
				createFile("bungee.yml", "main: test.B\n".getBytes(StandardCharsets.UTF_8))));
		assertEquals(List.of(new PluginApiRequest(PluginApiTarget.BUNGEE, null)), service.detectRequests(bungee));

		assertTrue(service.detectRequests(workspace).isEmpty(), "No manifest, nothing to detect");
	}

	@Test
	void bungeeUsesTheNewestRelease() throws IOException {
		repo.put("net/md-5/bungeecord-api/maven-metadata.xml", "<metadata><versioning><versions>" +
				"<version>1.21-R0.1</version><version>1.21-R0.4</version><version>1.21-R0.3</version>" +
				"<version>1.22-R0.1-SNAPSHOT</version></versioning></versions></metadata>".replace("</versioning></versions>", "</versions></versioning>"));
		publishLibrary("net.md-5", "bungeecord-api", "1.21-R0.4", jarWith("net/md_5/bungee/api/plugin/Plugin"));

		PluginApiAttachResult result = service.attach(workspace, new PluginApiRequest(PluginApiTarget.BUNGEE, null), status -> {});

		assertEquals("net.md-5:bungeecord-api:1.21-R0.4", result.api().toString());
		assertTrue(hasClass(workspace, "net/md_5/bungee/api/plugin/Plugin"));
	}

	@Test
	void importedResourcesAreNamedAfterTheirJars() throws IOException {
		service.attach(workspace, paper("1.21"), status -> {});

		// Detecting what is already attached relies on this.
		List<String> names = workspace.getSupportingResources().stream()
				.map(resource -> ((WorkspaceFileResource) resource).getFileInfo().getName())
				.sorted().toList();
		assertEquals(List.of("adventure-api-4.20.0.jar", "guava-33.0-jre.jar", "paper-api-1.21.11-R0.1-20250101.000000-5.jar"), names);
	}
}
