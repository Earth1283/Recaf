package software.coley.recaf.services.analysis.plugin;

import jakarta.annotation.Nonnull;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.services.analysis.plugin.MinecraftPluginAnalysisService.LocatedManifest;
import software.coley.recaf.services.analysis.plugin.semantic.PluginSemanticService;
import software.coley.recaf.test.TestBase;
import software.coley.recaf.workspace.model.Workspace;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static software.coley.recaf.test.TestClassUtils.*;

/**
 * Tests for manually marking a workspace as a Minecraft plugin.
 */
class MinecraftPluginOverrideTest extends TestBase {
	private static MinecraftPluginAnalysisService service;
	private static PluginSemanticService semanticService;

	@BeforeAll
	static void setupServices() {
		service = recaf.get(MinecraftPluginAnalysisService.class);
		semanticService = recaf.get(PluginSemanticService.class);
	}

	@Test
	void notAPluginWithoutManifestOrOverride() {
		Workspace workspace = workspace(null);

		assertNull(service.getPlatformOverride(workspace));
		assertTrue(service.findManifests(workspace, workspace.getPrimaryResource()).isEmpty());
		assertFalse(semanticService.hasManifest(workspace));
	}

	@Test
	void overrideProvidesForcedManifestWithoutAFile() {
		Workspace workspace = workspace(null);
		service.setPlatformOverride(workspace, MinecraftPluginPlatform.BUNGEE);

		List<LocatedManifest> manifests = service.findManifests(workspace, workspace.getPrimaryResource());
		assertEquals(1, manifests.size());
		LocatedManifest located = manifests.getFirst();
		assertTrue(located.forced());
		assertNull(located.path());
		assertSame(workspace.getPrimaryResource(), located.resource());
		assertEquals(MinecraftPluginPlatform.BUNGEE, located.manifest().platform());
		assertNull(located.manifest().mainClass());
		assertTrue(semanticService.hasManifest(workspace));
		assertEquals(1, service.findManifestsRecursive(workspace, workspace.getPrimaryResource()).size());
	}

	@Test
	void clearingOverrideGoesBackToDetection() {
		Workspace workspace = workspace(null);
		service.setPlatformOverride(workspace, MinecraftPluginPlatform.BUKKIT);
		service.setPlatformOverride(workspace, null);

		assertNull(service.getPlatformOverride(workspace));
		assertTrue(service.findManifests(workspace, workspace.getPrimaryResource()).isEmpty());
		assertFalse(semanticService.hasManifest(workspace));
	}

	@Test
	void realManifestIsNotDuplicatedByOverrideForSamePlatform() {
		Workspace workspace = workspace("main: test.Main\nname: Real\n");
		service.setPlatformOverride(workspace, MinecraftPluginPlatform.BUKKIT);

		List<LocatedManifest> manifests = service.findManifests(workspace, workspace.getPrimaryResource());
		assertEquals(1, manifests.size());
		assertFalse(manifests.getFirst().forced());
		assertEquals("Real", manifests.getFirst().manifest().name());
		assertNotNull(manifests.getFirst().path());
	}

	@Test
	void overrideForDifferentPlatformAddsToRealManifest() {
		Workspace workspace = workspace("main: test.Main\n");
		service.setPlatformOverride(workspace, MinecraftPluginPlatform.BUNGEE);

		List<LocatedManifest> manifests = service.findManifests(workspace, workspace.getPrimaryResource());
		assertEquals(2, manifests.size());
		assertEquals(1, manifests.stream().filter(LocatedManifest::forced).count());
		assertEquals(MinecraftPluginPlatform.BUKKIT, manifests.stream()
				.filter(m -> !m.forced()).findFirst().orElseThrow().manifest().platform());
	}

	@Test
	void overridesAreTrackedPerWorkspaceInstanceNotByContent() {
		// Identical content makes these workspaces equal, they must still be independent.
		Workspace first = workspace(null);
		Workspace second = workspace(null);
		service.setPlatformOverride(first, MinecraftPluginPlatform.PAPER);

		assertEquals(MinecraftPluginPlatform.PAPER, service.getPlatformOverride(first));
		assertNull(service.getPlatformOverride(second));
		assertTrue(service.findManifests(second, second.getPrimaryResource()).isEmpty());
	}

	@Test
	void listenersHearAboutChangesOnly() {
		Workspace workspace = workspace(null);
		List<MinecraftPluginPlatform> seen = new ArrayList<>();
		java.util.function.BiConsumer<Workspace, MinecraftPluginPlatform> listener = (w, p) -> {
			if (w == workspace)
				seen.add(p);
		};
		service.addPlatformOverrideListener(listener);
		try {
			service.setPlatformOverride(workspace, MinecraftPluginPlatform.BUKKIT);
			service.setPlatformOverride(workspace, MinecraftPluginPlatform.BUKKIT); // unchanged
			service.setPlatformOverride(workspace, MinecraftPluginPlatform.PAPER);
			service.setPlatformOverride(workspace, null);
			service.setPlatformOverride(workspace, null); // unchanged
		} finally {
			service.removePlatformOverrideListener(listener);
		}

		assertEquals(java.util.Arrays.asList(MinecraftPluginPlatform.BUKKIT, MinecraftPluginPlatform.PAPER, null), seen);
	}

	@Test
	void forcedPluginIsIndexed() {
		Workspace workspace = workspace(null);
		service.setPlatformOverride(workspace, MinecraftPluginPlatform.BUKKIT);

		// Must not throw for a manifest which has no main class.
		assertNotNull(semanticService.buildIndex(workspace));
	}

	@Nonnull
	private static Workspace workspace(String pluginYml) {
		JvmClassInfo cls = createClass("test/Main", node -> node.superName = "java/lang/Object");
		return pluginYml == null ?
				fromBundles(fromClasses(cls), fromFiles()) :
				fromBundles(fromClasses(cls), fromFiles(createFile("plugin.yml", pluginYml.getBytes(StandardCharsets.UTF_8))));
	}
}
