package software.coley.recaf.services.analysis.entry;

import jakarta.annotation.Nonnull;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import software.coley.recaf.info.member.MethodMember;
import software.coley.recaf.path.ClassPathNode;
import software.coley.recaf.path.PathNodes;
import software.coley.recaf.services.analysis.plugin.MinecraftPluginAnalysisService;
import software.coley.recaf.services.analysis.plugin.MinecraftPluginManifest;
import software.coley.recaf.services.analysis.plugin.MinecraftPluginPlatform;
import software.coley.recaf.services.inheritance.InheritanceGraph;
import software.coley.recaf.services.inheritance.InheritanceGraphService;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.resource.WorkspaceResource;

import java.util.ArrayList;
import java.util.List;

/**
 * Discovery process for locating Paper plugin bootstrappers and loaders.
 * <p>
 * These run <i>before</i> the main plugin class exists, so they are easy to miss when reading a plugin top-down from
 * its {@code onEnable}. They are found either by implementing the Paper interfaces, or by being named in the
 * {@code bootstrapper} / {@code loader} fields of the {@code paper-plugin.yml}.
 *
 * @see <a href="https://docs.papermc.io/paper/dev/getting-started/paper-plugins/">Paper plugins</a>
 */
@ApplicationScoped
public class PaperPluginBootstrapEntryPointDiscovery implements EntryPointDiscovery {
	private static final String BOOTSTRAP_INTERFACE = "io/papermc/paper/plugin/bootstrap/PluginBootstrap";
	private static final String LOADER_INTERFACE = "io/papermc/paper/plugin/loader/PluginLoader";
	private final InheritanceGraphService inheritanceGraphService;
	private final MinecraftPluginAnalysisService pluginAnalysisService;

	@Inject
	public PaperPluginBootstrapEntryPointDiscovery(@Nonnull InheritanceGraphService inheritanceGraphService,
	                                               @Nonnull MinecraftPluginAnalysisService pluginAnalysisService) {
		this.inheritanceGraphService = inheritanceGraphService;
		this.pluginAnalysisService = pluginAnalysisService;
	}

	@Nonnull
	@Override
	public EntryPointKind kind() {
		return EntryPointKind.MC_PAPER_PLUGIN_BOOTSTRAP;
	}

	@Nonnull
	@Override
	public List<EntryPoint> findEntryPoints(@Nonnull Workspace workspace, @Nonnull WorkspaceResource resource) {
		InheritanceGraph graph = inheritanceGraphService.getOrCreateInheritanceGraph(workspace);
		List<MinecraftPluginManifest> manifests = pluginAnalysisService.findManifests(workspace, resource).stream()
				.map(MinecraftPluginAnalysisService.LocatedManifest::manifest)
				.filter(manifest -> manifest.platform() == MinecraftPluginPlatform.PAPER)
				.toList();

		List<EntryPoint> entries = new ArrayList<>();
		resource.jvmAllClassBundleStream().forEach(bundle -> bundle.forEach(cls -> {
			String className = cls.getName();
			boolean namedAsBootstrapper = manifests.stream().anyMatch(m -> m.isBootstrapperClass(className));
			boolean namedAsLoader = manifests.stream().anyMatch(m -> m.isLoaderClass(className));
			ClassPathNode classPath = null;
			boolean foundMethod = false;
			for (MethodMember method : cls.getMethods()) {
				// The descriptors reference Paper types, which makes the method itself a strong signal.
				String name = method.getName();
				String desc = method.getDescriptor();
				boolean bootstrapMethod = name.equals("bootstrap") &&
						desc.equals("(Lio/papermc/paper/plugin/bootstrap/BootstrapContext;)V");
				boolean createPluginMethod = name.equals("createPlugin") &&
						desc.equals("(Lio/papermc/paper/plugin/bootstrap/PluginProviderContext;)Lorg/bukkit/plugin/java/JavaPlugin;");
				boolean loaderMethod = name.equals("classloader") &&
						desc.equals("(Lio/papermc/paper/plugin/loader/PluginClasspathBuilder;)V");
				if (!bootstrapMethod && !createPluginMethod && !loaderMethod)
					continue;

				// Accept when the manifest names the class, or the class implements the matching interface.
				boolean accepted = (bootstrapMethod || createPluginMethod)
						? namedAsBootstrapper || graph.isAssignableFrom(BOOTSTRAP_INTERFACE, className)
						: namedAsLoader || graph.isAssignableFrom(LOADER_INTERFACE, className);
				if (!accepted)
					continue;

				if (classPath == null)
					classPath = PathNodes.classPath(workspace, resource, bundle, cls);
				entries.add(new EntryPoint(kind(), classPath, classPath.child(method)));
				foundMethod = true;
			}

			// Named in the manifest, but nothing matched. Point to the class so it is not lost.
			if ((namedAsBootstrapper || namedAsLoader) && !foundMethod) {
				classPath = PathNodes.classPath(workspace, resource, bundle, cls);
				entries.add(new EntryPoint(kind(), classPath, null));
			}
		}));
		return entries;
	}
}
