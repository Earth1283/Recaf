package software.coley.recaf.services.analysis.entry;

import jakarta.annotation.Nonnull;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import software.coley.recaf.info.member.MethodMember;
import software.coley.recaf.path.ClassPathNode;
import software.coley.recaf.path.PathNodes;
import software.coley.recaf.services.analysis.plugin.MinecraftPluginAnalysisService;
import software.coley.recaf.services.analysis.plugin.MinecraftPluginPlatform;
import software.coley.recaf.services.inheritance.InheritanceGraph;
import software.coley.recaf.services.inheritance.InheritanceGraphService;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.resource.WorkspaceResource;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Discovery process for locating entry points in BungeeCord plugins <i>(And forks like Waterfall)</i>.
 * <p>
 * A class is considered a plugin if it extends {@code net.md_5.bungee.api.plugin.Plugin}, <i>or</i> if it is the
 * {@code main} class declared in the {@code bungee.yml} of the resource.
 *
 * @see <a href="https://www.spigotmc.org/wiki/bungeecord-plugin-development/">BungeeCord plugin development</a>
 */
@ApplicationScoped
public class BungeePluginEntryPointDiscovery implements EntryPointDiscovery {
	private final InheritanceGraphService inheritanceGraphService;
	private final MinecraftPluginAnalysisService pluginAnalysisService;

	@Inject
	public BungeePluginEntryPointDiscovery(@Nonnull InheritanceGraphService inheritanceGraphService,
	                                       @Nonnull MinecraftPluginAnalysisService pluginAnalysisService) {
		this.inheritanceGraphService = inheritanceGraphService;
		this.pluginAnalysisService = pluginAnalysisService;
	}

	@Nonnull
	@Override
	public EntryPointKind kind() {
		return EntryPointKind.MC_BUNGEE_PLUGIN_INIT;
	}

	@Nonnull
	@Override
	public List<EntryPoint> findEntryPoints(@Nonnull Workspace workspace, @Nonnull WorkspaceResource resource) {
		InheritanceGraph graph = inheritanceGraphService.getOrCreateInheritanceGraph(workspace);
		Set<String> manifestMainClasses = new HashSet<>();
		pluginAnalysisService.findManifests(workspace, resource).forEach(located -> {
			String mainClass = located.manifest().mainClass();
			if (mainClass != null && located.manifest().platform() == MinecraftPluginPlatform.BUNGEE)
				manifestMainClasses.add(mainClass);
		});

		List<EntryPoint> entries = new ArrayList<>();
		resource.jvmAllClassBundleStream().forEach(bundle -> bundle.forEach(cls -> {
			String className = cls.getName();
			boolean isManifestMain = manifestMainClasses.contains(className);
			ClassPathNode classPath = null;
			Boolean isPlugin = isManifestMain ? Boolean.TRUE : null;
			boolean foundLifecycleMethod = false;
			for (MethodMember method : cls.getMethods()) {
				// Must be a plugin load, enable, or disable method with the lifecycle signature.
				String methodName = method.getName();
				if (!method.getDescriptor().equals("()V"))
					continue;
				if (!methodName.equals("onEnable") && !methodName.equals("onLoad") && !methodName.equals("onDisable"))
					continue;

				// Lazily check if this class is a plugin subtype.
				if (isPlugin == null)
					isPlugin = graph.isAssignableFrom("net/md_5/bungee/api/plugin/Plugin", className);
				if (!isPlugin)
					continue;

				if (classPath == null)
					classPath = PathNodes.classPath(workspace, resource, bundle, cls);
				entries.add(new EntryPoint(kind(), classPath, classPath.child(method)));
				foundLifecycleMethod = true;
			}

			if (isManifestMain && !foundLifecycleMethod) {
				if (classPath == null)
					classPath = PathNodes.classPath(workspace, resource, bundle, cls);
				entries.add(new EntryPoint(kind(), classPath, null));
			}
		}));
		return entries;
	}
}
