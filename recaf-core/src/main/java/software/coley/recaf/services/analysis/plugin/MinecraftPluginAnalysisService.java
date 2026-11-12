package software.coley.recaf.services.analysis.plugin;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import software.coley.recaf.info.FileInfo;
import software.coley.recaf.path.FilePathNode;
import software.coley.recaf.path.PathNodes;
import software.coley.recaf.services.Service;
import software.coley.recaf.services.workspace.WorkspaceManager;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.bundle.FileBundle;
import software.coley.recaf.workspace.model.resource.WorkspaceResource;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;

/**
 * Service for locating and reading Minecraft plugin manifests in workspace resources.
 * <p>
 * Workspaces that are not detected as plugins, because their manifest is missing, stripped, or obfuscated, can be
 * marked as plugins by the user with {@link #setPlatformOverride(Workspace, MinecraftPluginPlatform)}. The primary
 * resource of such a workspace then reports a {@link LocatedManifest#forced() forced} manifest for that platform, so all
 * the plugin-specific analysis treats it like any other plugin.
 *
 * @see MinecraftPluginManifest
 */
@ApplicationScoped
public class MinecraftPluginAnalysisService implements Service {
	public static final String SERVICE_ID = "minecraft-plugin-analysis";
	// Workspaces compare by their (mutable) content, so overrides are tracked by identity instead.
	private final Map<Workspace, MinecraftPluginPlatform> overrides = new IdentityHashMap<>();
	private final List<BiConsumer<Workspace, MinecraftPluginPlatform>> overrideListeners = new CopyOnWriteArrayList<>();
	private final MinecraftPluginAnalysisConfig config;

	@Inject
	public MinecraftPluginAnalysisService(@Nonnull MinecraftPluginAnalysisConfig config,
	                                      @Nonnull WorkspaceManager workspaceManager) {
		this.config = config;

		// Overrides are only meaningful for the workspace they were made in.
		workspaceManager.addWorkspaceCloseListener(workspace -> {
			synchronized (overrides) {
				overrides.remove(workspace);
			}
		});
	}

	/**
	 * @param workspace
	 * 		Workspace to look at.
	 *
	 * @return Platform the user marked the workspace as a plugin for, or {@code null} if the workspace is left to
	 * automatic detection.
	 */
	@Nullable
	public MinecraftPluginPlatform getPlatformOverride(@Nonnull Workspace workspace) {
		synchronized (overrides) {
			return overrides.get(workspace);
		}
	}

	/**
	 * Marks the workspace as a Minecraft plugin for the given platform, regardless of whether a manifest can be found.
	 * Listeners are notified of the change.
	 *
	 * @param workspace
	 * 		Workspace to mark.
	 * @param platform
	 * 		Platform to treat the primary resource as a plugin for, or {@code null} to go back to automatic detection.
	 */
	public void setPlatformOverride(@Nonnull Workspace workspace, @Nullable MinecraftPluginPlatform platform) {
		MinecraftPluginPlatform previous;
		synchronized (overrides) {
			previous = platform == null ? overrides.remove(workspace) : overrides.put(workspace, platform);
		}
		if (previous != platform)
			overrideListeners.forEach(listener -> listener.accept(workspace, platform));
	}

	/**
	 * @param listener
	 * 		Listener to add. Called with the workspace and its new override, or {@code null} when the override was cleared.
	 * 		May be called from any thread.
	 */
	public void addPlatformOverrideListener(@Nonnull BiConsumer<Workspace, MinecraftPluginPlatform> listener) {
		overrideListeners.add(listener);
	}

	/**
	 * @param listener
	 * 		Listener to remove.
	 */
	public void removePlatformOverrideListener(@Nonnull BiConsumer<Workspace, MinecraftPluginPlatform> listener) {
		overrideListeners.remove(listener);
	}

	/**
	 * @param workspace
	 * 		Containing workspace.
	 * @param resource
	 * 		Resource to look in. Embedded resources are not included.
	 *
	 * @return Manifests found at the root of the given resource. When the resource is the primary resource of a
	 * workspace the user marked as a plugin, and no manifest for the chosen platform exists, this includes a
	 * {@link LocatedManifest#forced() forced} manifest that has no details besides the platform.
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

		if (resource == workspace.getPrimaryResource()) {
			MinecraftPluginPlatform override = getPlatformOverride(workspace);
			if (override != null && manifests.stream().noneMatch(m -> m.manifest().platform() == override))
				manifests.add(new LocatedManifest(MinecraftPluginManifest.forced(override), resource, null));
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
	 * 		Path to the manifest file, or {@code null} when the manifest was {@link #forced() forced} and has no file.
	 */
	public record LocatedManifest(@Nonnull MinecraftPluginManifest manifest,
	                              @Nonnull WorkspaceResource resource,
	                              @Nullable FilePathNode path) {
		/**
		 * @return {@code true} when there is no manifest file, and the user marked the workspace as a plugin.
		 */
		public boolean forced() {
			return path == null;
		}
	}
}
