package software.coley.recaf.services.analysis.plugin;

import jakarta.annotation.Nonnull;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.MethodNode;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.services.analysis.entry.EntryAnalysisService;
import software.coley.recaf.services.analysis.entry.EntryPoint;
import software.coley.recaf.services.analysis.entry.EntryPointKind;
import software.coley.recaf.test.TestBase;
import software.coley.recaf.workspace.model.Workspace;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static software.coley.recaf.test.TestClassUtils.createClass;
import static software.coley.recaf.test.TestClassUtils.createFile;
import static software.coley.recaf.test.TestClassUtils.fromBundles;
import static software.coley.recaf.test.TestClassUtils.fromClasses;
import static software.coley.recaf.test.TestClassUtils.fromFiles;

/**
 * Tests for plugin entry point discovery that uses the plugin manifest as a signal.
 */
class MinecraftPluginEntryPointTest extends TestBase {
	private static EntryAnalysisService service;

	@BeforeAll
	static void setupService() {
		service = recaf.get(EntryAnalysisService.class);
	}

	@Test
	void bukkitFindsOnDisable() {
		JvmClassInfo plugin = createClass("test/Plugin", node -> {
			node.superName = "org/bukkit/plugin/java/JavaPlugin";
			addEmptyMethod(node.methods, "onEnable", "()V");
			addEmptyMethod(node.methods, "onDisable", "()V");
		});
		List<EntryPoint> results = discover(fromClasses(plugin), null, null);

		assertEquals(Set.of("onEnable", "onDisable"), methodNames(results, EntryPointKind.MC_BUKKIT_PLUGIN_INIT));
	}

	@Test
	void bukkitUsesManifestMainWhenSuperclassIsUnrelated() {
		// Not a JavaPlugin as far as the class hierarchy is concerned, but plugin.yml says it is the main class.
		JvmClassInfo plugin = createClass("test/Weird", node -> addEmptyMethod(node.methods, "onEnable", "()V"));
		JvmClassInfo other = createClass("test/Other", node -> addEmptyMethod(node.methods, "onEnable", "()V"));
		List<EntryPoint> results = discover(fromClasses(plugin, other), "plugin.yml", "main: test.Weird\n");

		List<EntryPoint> bukkit = results.stream().filter(e -> e.kind() == EntryPointKind.MC_BUKKIT_PLUGIN_INIT).toList();
		assertEquals(1, bukkit.size(), "Only the class named by the manifest should be found");
		assertEquals("test/Weird", bukkit.getFirst().classPath().getValue().getName());
		assertEquals("onEnable", bukkit.getFirst().memberPath().getValue().getName());
	}

	@Test
	void bukkitManifestMainWithoutLifecycleMethodsPointsToClass() {
		JvmClassInfo plugin = createClass("test/Bare", node -> node.superName = "java/lang/Object");
		List<EntryPoint> results = discover(fromClasses(plugin), "plugin.yml", "main: test.Bare\n");

		assertEquals(1, results.size());
		EntryPoint entry = results.getFirst();
		assertEquals(EntryPointKind.MC_BUKKIT_PLUGIN_INIT, entry.kind());
		assertEquals("test/Bare", entry.classPath().getValue().getName());
		assertNull(entry.memberPath());
	}

	@Test
	void bukkitDoesNotDoubleReportWhenBothSignalsAgree() {
		JvmClassInfo plugin = createClass("test/Both", node -> {
			node.superName = "org/bukkit/plugin/java/JavaPlugin";
			addEmptyMethod(node.methods, "onEnable", "()V");
		});
		List<EntryPoint> results = discover(fromClasses(plugin), "plugin.yml", "main: test.Both\n");

		assertEquals(1, results.size());
	}

	@Test
	void paperManifestMainIsUsedToo() {
		JvmClassInfo plugin = createClass("test/PaperMain", node -> addEmptyMethod(node.methods, "onEnable", "()V"));
		List<EntryPoint> results = discover(fromClasses(plugin), "paper-plugin.yml", "main: test.PaperMain\n");

		assertEquals(Set.of("onEnable"), methodNames(results, EntryPointKind.MC_BUKKIT_PLUGIN_INIT));
	}

	@Test
	void bungeeFindsLifecycleMethods() {
		JvmClassInfo plugin = createClass("test/Proxy", node -> {
			node.superName = "net/md_5/bungee/api/plugin/Plugin";
			addEmptyMethod(node.methods, "onLoad", "()V");
			addEmptyMethod(node.methods, "onEnable", "()V");
			addEmptyMethod(node.methods, "onDisable", "()V");
		});
		List<EntryPoint> results = discover(fromClasses(plugin), null, null);

		assertEquals(Set.of("onLoad", "onEnable", "onDisable"), methodNames(results, EntryPointKind.MC_BUNGEE_PLUGIN_INIT));
		assertTrue(methodNames(results, EntryPointKind.MC_BUKKIT_PLUGIN_INIT).isEmpty());
	}

	@Test
	void bungeeUsesManifestMain() {
		JvmClassInfo plugin = createClass("test/Proxy", node -> addEmptyMethod(node.methods, "onEnable", "()V"));
		List<EntryPoint> results = discover(fromClasses(plugin), "bungee.yml", "main: test.Proxy\n");

		assertEquals(Set.of("onEnable"), methodNames(results, EntryPointKind.MC_BUNGEE_PLUGIN_INIT));
		assertTrue(methodNames(results, EntryPointKind.MC_BUKKIT_PLUGIN_INIT).isEmpty(),
				"A bungee.yml main class is not a Bukkit plugin");
	}

	@Test
	void paperBootstrapperAndLoaderFoundByInterface() {
		JvmClassInfo bootstrap = createClass("test/Boot", node -> {
			node.interfaces.add("io/papermc/paper/plugin/bootstrap/PluginBootstrap");
			addEmptyMethod(node.methods, "bootstrap", "(Lio/papermc/paper/plugin/bootstrap/BootstrapContext;)V");
		});
		JvmClassInfo loader = createClass("test/Load", node -> {
			node.interfaces.add("io/papermc/paper/plugin/loader/PluginLoader");
			addEmptyMethod(node.methods, "classloader", "(Lio/papermc/paper/plugin/loader/PluginClasspathBuilder;)V");
		});
		List<EntryPoint> results = discover(fromClasses(bootstrap, loader), null, null);

		assertEquals(Set.of("bootstrap", "classloader"), methodNames(results, EntryPointKind.MC_PAPER_PLUGIN_BOOTSTRAP));
	}

	@Test
	void paperBootstrapperFoundByManifestOnly() {
		// Does not declare the interface, so only the manifest ties it to being a bootstrapper.
		JvmClassInfo bootstrap = createClass("test/Boot", node ->
				addEmptyMethod(node.methods, "bootstrap", "(Lio/papermc/paper/plugin/bootstrap/BootstrapContext;)V"));
		JvmClassInfo unrelated = createClass("test/Unrelated", node ->
				addEmptyMethod(node.methods, "bootstrap", "(Lio/papermc/paper/plugin/bootstrap/BootstrapContext;)V"));
		List<EntryPoint> results = discover(fromClasses(bootstrap, unrelated), "paper-plugin.yml",
				"main: test.Main\nbootstrapper: test.Boot\n");

		List<EntryPoint> paper = results.stream().filter(e -> e.kind() == EntryPointKind.MC_PAPER_PLUGIN_BOOTSTRAP).toList();
		assertEquals(1, paper.size());
		assertEquals("test/Boot", paper.getFirst().classPath().getValue().getName());
	}

	private static List<EntryPoint> discover(@Nonnull software.coley.recaf.workspace.model.bundle.JvmClassBundle classes,
	                                         String manifestName, String manifestText) {
		Workspace workspace = manifestName == null ?
				fromBundles(classes, fromFiles()) :
				fromBundles(classes, fromFiles(createFile(manifestName, manifestText.getBytes(StandardCharsets.UTF_8))));
		return service.findEntryPoints(workspace, workspace.getPrimaryResource());
	}

	private static Set<String> methodNames(@Nonnull List<EntryPoint> entries, @Nonnull EntryPointKind kind) {
		return entries.stream()
				.filter(e -> e.kind() == kind)
				.filter(e -> e.memberPath() != null)
				.map(e -> e.memberPath().getValue().getName())
				.collect(Collectors.toSet());
	}

	private static void addEmptyMethod(@Nonnull List<MethodNode> methods, @Nonnull String name, @Nonnull String desc) {
		MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC, name, desc, null, null);
		method.visitCode();
		method.visitInsn(Opcodes.RETURN);
		method.visitEnd();
		methods.add(method);
	}
}
