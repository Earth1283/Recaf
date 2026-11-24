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
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.resource.WorkspaceResource;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Discovery process for locating entry points in Velocity plugins.
 *
 * @author TheWakz
 * @see <a href="https://docs.papermc.io/velocity/dev/api-basics/#create-the-plugin-class">Velocity plugin entry points</a>
 */
@ApplicationScoped
public class VelocityPluginEntryPointDiscovery implements EntryPointDiscovery {
	private final MinecraftPluginAnalysisService pluginAnalysisService;

	@Inject
	public VelocityPluginEntryPointDiscovery(@Nonnull MinecraftPluginAnalysisService pluginAnalysisService) {
		this.pluginAnalysisService = pluginAnalysisService;
	}

	@Nonnull
	@Override
	public EntryPointKind kind() {
		return EntryPointKind.MC_VELOCITY_PLUGIN_INIT;
	}

	@Nonnull
	@Override
	public List<EntryPoint> findEntryPoints(@Nonnull Workspace workspace, @Nonnull WorkspaceResource resource) {
		Set<String> manifestMainClasses = pluginAnalysisService.findManifests(workspace, resource).stream()
				.map(MinecraftPluginAnalysisService.LocatedManifest::manifest)
				.filter(manifest -> manifest.platform() == MinecraftPluginPlatform.VELOCITY)
				.map(MinecraftPluginManifest::mainClass)
				.filter(Objects::nonNull)
				.collect(Collectors.toSet());
		List<EntryPoint> entries = new ArrayList<>();
		resource.jvmAllClassBundleStream().forEach(bundle -> bundle.forEach(cls -> {
			ClassPathNode classPath = null;

			// Check for initialization event receivers
			for (MethodMember method : cls.getMethods()) {
				String desc = method.getDescriptor();
				if ("(Lcom/velocitypowered/api/event/proxy/ProxyInitializeEvent;)V".equals(desc)
						|| "(Lcom/velocitypowered/api/event/proxy/ProxyReloadEvent;)V".equals(desc)
				) {
					if (classPath == null)
						classPath = PathNodes.classPath(workspace, resource, bundle, cls);
					entries.add(new EntryPoint(kind(), classPath, classPath.child(method)));
				}
			}

			// An event receiver is more specific than the class itself.
			if (classPath == null && (manifestMainClasses.contains(cls.getName()) ||
					Stream.concat(cls.getAnnotations().stream(), cls.getTypeAnnotations().stream())
							.anyMatch(annotation -> "Lcom/velocitypowered/api/plugin/Plugin;".equals(annotation.getDescriptor())))) {
				classPath = PathNodes.classPath(workspace, resource, bundle, cls);
				entries.add(new EntryPoint(kind(), classPath, null));
			}
		}));
		return entries;
	}
}
