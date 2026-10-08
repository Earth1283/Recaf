package software.coley.recaf.services.analysis.plugin.api;

import jakarta.annotation.Nonnull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.MethodNode;
import software.coley.recaf.info.ClassInfo;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.info.member.MethodMember;
import software.coley.recaf.services.analysis.plugin.MinecraftPluginAnalysisService;
import software.coley.recaf.services.inheritance.InheritanceGraph;
import software.coley.recaf.services.inheritance.InheritanceGraphService;
import software.coley.recaf.services.mapping.Mappings;
import software.coley.recaf.services.mapping.gen.MappingGenerator;
import software.coley.recaf.services.mapping.gen.filter.NameGeneratorFilter;
import software.coley.recaf.services.mapping.gen.naming.AlphabetNameGenerator;
import software.coley.recaf.services.workspace.io.ResourceImporter;
import software.coley.recaf.test.TestBase;
import software.coley.recaf.workspace.model.Workspace;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static software.coley.recaf.test.TestClassUtils.createClass;
import static software.coley.recaf.test.TestClassUtils.fromBundle;
import static software.coley.recaf.test.TestClassUtils.fromClasses;

/**
 * Shows why attaching the API matters beyond decompiler output: with the API in the workspace, Recaf can tell that
 * {@code onEnable} overrides a method the server calls by name, so automatic renaming must leave it alone.
 */
class MinecraftPluginApiMappingTest extends TestBase {
	private static final String API_CLASS = "org/bukkit/plugin/java/JavaPlugin";
	private static final String PLUGIN_CLASS = "test/MyPlugin";
	@TempDir
	Path cache;
	FakeMavenRepository repo;
	MinecraftPluginApiService service;

	@BeforeEach
	void setup() throws IOException {
		repo = new FakeMavenRepository();
		service = new MinecraftPluginApiService(new MinecraftPluginApiConfig(), recaf.get(MinecraftPluginAnalysisService.class),
				repo, recaf.get(ResourceImporter.class), cache, List.of(FakeMavenRepository.BASE));

		// An API with a JavaPlugin class declaring onEnable, published in the newer (non-snapshot) version scheme.
		JvmClassInfo javaPlugin = createClass(API_CLASS, node -> addMethod(node.methods, "onEnable"));
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
			zip.putNextEntry(new ZipEntry(API_CLASS + ".class"));
			zip.write(javaPlugin.getBytecode());
			zip.closeEntry();
		}
		String version = "26.1.2.build.74-stable";
		repo.put("io/papermc/paper/paper-api/maven-metadata.xml",
				"<metadata><versioning><versions><version>" + version + "</version></versions></versioning></metadata>");
		repo.putBytes("io/papermc/paper/paper-api/" + version + "/paper-api-" + version + ".jar", bytes.toByteArray());
		repo.pom("io.papermc.paper:paper-api:" + version, "");
	}

	@Test
	void attachedApiStopsAutomaticRenamingOfOverriddenApiMethods() throws IOException {
		JvmClassInfo plugin = createClass(PLUGIN_CLASS, node -> {
			node.superName = API_CLASS;
			addMethod(node.methods, "onEnable");
			addMethod(node.methods, "ownHelper");
		});
		Workspace workspace = fromBundle(fromClasses(plugin));
		workspaceManager.setCurrent(workspace);

		// The graph is created first, so the attach below also shows that it picks up the new library.
		InheritanceGraph graph = recaf.get(InheritanceGraphService.class).getCurrentWorkspaceInheritanceGraph();
		MappingGenerator generator = recaf.get(MappingGenerator.class);

		Mappings before = generate(generator, workspace, graph);
		assertNotNull(before.getMappedMethodName(PLUGIN_CLASS, "onEnable", "()V"),
				"Without the API, onEnable looks like an ordinary method and would be renamed, breaking the plugin");
		assertNotNull(before.getMappedMethodName(PLUGIN_CLASS, "ownHelper", "()V"));

		service.attach(workspace, new PluginApiRequest(PluginApiTarget.PAPER, "26.1"), status -> {});

		Mappings after = generate(generator, workspace, graph);
		assertNull(after.getMappedMethodName(PLUGIN_CLASS, "onEnable", "()V"),
				"With the API attached, onEnable is known to override the server's method and is left alone");
		assertNotNull(after.getMappedMethodName(PLUGIN_CLASS, "ownHelper", "()V"),
				"The plugin's own methods are still renamed");
	}

	private static Mappings generate(@Nonnull MappingGenerator generator, @Nonnull Workspace workspace, @Nonnull InheritanceGraph graph) {
		return generator.generate(workspace, workspace.getPrimaryResource(), graph,
				new AlphabetNameGenerator("abcdefghijklmnopqrstuvwxyz", 3),
				new NameGeneratorFilter(null, true) {
					@Override
					public boolean shouldMapClass(@Nonnull ClassInfo info) {
						return true;
					}

					@Override
					public boolean shouldMapMethod(@Nonnull ClassInfo owner, @Nonnull MethodMember method) {
						return true;
					}
				});
	}

	private static void addMethod(@Nonnull List<MethodNode> methods, @Nonnull String name) {
		MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC, name, "()V", null, null);
		method.visitCode();
		method.visitInsn(Opcodes.RETURN);
		method.visitEnd();
		methods.add(method);
	}
}
