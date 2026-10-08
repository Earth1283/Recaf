package software.coley.recaf.services.analysis.plugin;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.coley.recaf.info.FileInfo;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.info.properties.builtin.ZipEntryIndexProperty;
import software.coley.recaf.services.mapping.IntermediateMappings;
import software.coley.recaf.services.mapping.MappingApplierService;
import software.coley.recaf.services.mapping.MappingResults;
import software.coley.recaf.test.TestBase;
import software.coley.recaf.workspace.model.Workspace;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static software.coley.recaf.test.TestClassUtils.createClass;
import static software.coley.recaf.test.TestClassUtils.createEmptyClass;
import static software.coley.recaf.test.TestClassUtils.createFile;
import static software.coley.recaf.test.TestClassUtils.fromBundles;
import static software.coley.recaf.test.TestClassUtils.fromClasses;
import static software.coley.recaf.test.TestClassUtils.fromFiles;

/**
 * Tests for {@link MinecraftPluginMappingListener}, driven through real mapping application.
 */
class MinecraftPluginMappingListenerTest extends TestBase {
	private static final String PLUGIN_YML = """
			# Example plugin
			name: Example
			main: test.ExamplePlugin
			api-version: '1.21'
			""";
	MappingApplierService mappingApplierService;

	@BeforeEach
	void setup() {
		// Ensure the listener is alive and registered before any mappings are applied.
		recaf.get(MinecraftPluginMappingListener.class).toString();
		mappingApplierService = recaf.get(MappingApplierService.class);
	}

	@Test
	void renamingMainClassUpdatesPluginYml() {
		JvmClassInfo main = createEmptyClass("test/ExamplePlugin");
		FileInfo yml = createFile("plugin.yml", PLUGIN_YML.getBytes(StandardCharsets.UTF_8));
		ZipEntryIndexProperty.set(yml, 7);
		Workspace workspace = fromBundles(fromClasses(main), fromFiles(yml));
		workspaceManager.setCurrent(workspace);

		IntermediateMappings mappings = new IntermediateMappings();
		mappings.addClass("test/ExamplePlugin", "obf/a");
		MappingResults results = mappingApplierService.inCurrentWorkspace().applyToPrimaryResource(mappings);
		results.apply();

		FileInfo updated = workspace.getPrimaryResource().getFileBundle().get("plugin.yml");
		assertNotNull(updated);
		assertEquals(PLUGIN_YML.replace("test.ExamplePlugin", "obf.a"), new String(updated.getRawContent(), StandardCharsets.UTF_8));
		assertEquals(7, ZipEntryIndexProperty.get(updated), "Properties of the original file should carry over");
		assertNull(workspace.getPrimaryResource().getJvmClassBundle().get("test/ExamplePlugin"));
		assertNotNull(workspace.getPrimaryResource().getJvmClassBundle().get("obf/a"));
	}

	@Test
	void renamingUnrelatedClassLeavesPluginYmlUntouched() {
		JvmClassInfo main = createEmptyClass("test/ExamplePlugin");
		JvmClassInfo helper = createClass("test/Helper", node -> {});
		FileInfo yml = createFile("plugin.yml", PLUGIN_YML.getBytes(StandardCharsets.UTF_8));
		Workspace workspace = fromBundles(fromClasses(main, helper), fromFiles(yml));
		workspaceManager.setCurrent(workspace);

		IntermediateMappings mappings = new IntermediateMappings();
		mappings.addClass("test/Helper", "obf/h");
		mappingApplierService.inCurrentWorkspace().applyToPrimaryResource(mappings).apply();

		FileInfo after = workspace.getPrimaryResource().getFileBundle().get("plugin.yml");
		assertEquals(PLUGIN_YML, new String(after.getRawContent(), StandardCharsets.UTF_8));
	}

	@Test
	void renamingPackageUpdatesAllReferences() {
		String yml = "main: test.ExamplePlugin\nbootstrapper: test.Boot\n";
		Workspace workspace = fromBundles(
				fromClasses(createEmptyClass("test/ExamplePlugin"), createEmptyClass("test/Boot")),
				fromFiles(createFile("paper-plugin.yml", yml.getBytes(StandardCharsets.UTF_8))));
		workspaceManager.setCurrent(workspace);

		IntermediateMappings mappings = new IntermediateMappings();
		mappings.addClass("test/ExamplePlugin", "x/y/Main");
		mappings.addClass("test/Boot", "x/y/Bootstrap");
		mappingApplierService.inCurrentWorkspace().applyToPrimaryResource(mappings).apply();

		FileInfo after = workspace.getPrimaryResource().getFileBundle().get("paper-plugin.yml");
		assertEquals("main: x.y.Main\nbootstrapper: x.y.Bootstrap\n", new String(after.getRawContent(), StandardCharsets.UTF_8));
	}
}
