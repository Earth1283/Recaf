package software.coley.recaf.services.analysis.plugin;

import jakarta.annotation.Nonnull;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import software.coley.recaf.info.FileInfo;
import software.coley.recaf.path.FilePathNode;
import software.coley.recaf.path.PathNodes;
import software.coley.recaf.services.Service;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.bundle.FileBundle;
import software.coley.recaf.workspace.model.resource.WorkspaceResource;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Service for locating and reading Minecraft plugin manifests in workspace resources.
 *
 * @see MinecraftPluginManifest
 */
@ApplicationScoped
public class MinecraftPluginAnalysisService implements Service {
	public static final String SERVICE_ID = "minecraft-plugin-analysis";
	private final MinecraftPluginAnalysisConfig config;

	@Inject
	public MinecraftPluginAnalysisService(@Nonnull MinecraftPluginAnalysisConfig config) {
		this.config = config;
	}

	/**
	 * @param workspace
	 * 		Containing workspace.
	 * @param resource
	 * 		Resource to look in. Embedded resources are not included.
	 *
	 * @return Manifests found at the root of the given resource.
	 */
	@Nonnull
	public List<LocatedManifest> findManifests(@Nonnull Workspace workspace, @Nonnull WorkspaceResource resource) {
		List<LocatedManifest> manifests = new ArrayList<>(1);
		FileBundle bundle = resource.getFileBundle();
		for (MinecraftPluginPlatform platform : MinecraftPluginPlatform.values()) {
			FileInfo file = bundle.get(platform.manifestFileName());
			if (file == null)
				continue;
			Optional<MinecraftPluginManifest> manifest =
					MinecraftPluginManifestParser.parse(platform.manifestFileName(), file.getRawContent());
			if (manifest.isPresent())
				manifests.add(new LocatedManifest(manifest.get(), resource,
						PathNodes.filePath(workspace, resource, bundle, file)));
		}
		return manifests;
	}

	/**
	 * @param workspace
	 * 		Containing workspace.
	 * @param resource
	 * 		Resource to look in, including its embedded resources.
	 *
	 * @return Manifests found at the root of the given resource, and of each embedded resource.
	 */
	@Nonnull
	public List<LocatedManifest> findManifestsRecursive(@Nonnull Workspace workspace, @Nonnull WorkspaceResource resource) {
		List<LocatedManifest> manifests = new ArrayList<>(findManifests(workspace, resource));
		for (WorkspaceResource embedded : resource.getEmbeddedResources().values())
			manifests.addAll(findManifestsRecursive(workspace, embedded));
		return manifests;
	}

	@Nonnull
	@Override
	public String getServiceId() {
		return SERVICE_ID;
	}

	@Nonnull
	@Override
	public MinecraftPluginAnalysisConfig getServiceConfig() {
		return config;
	}

	/**
	 * @param manifest
	 * 		The parsed manifest.
	 * @param resource
	 * 		Resource containing the manifest file.
	 * @param path
	 * 		Path to the manifest file.
	 */
	public record LocatedManifest(@Nonnull MinecraftPluginManifest manifest,
	                              @Nonnull WorkspaceResource resource,
	                              @Nonnull FilePathNode path) {}
}
