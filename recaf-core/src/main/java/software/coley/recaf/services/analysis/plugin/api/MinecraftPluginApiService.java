package software.coley.recaf.services.analysis.plugin.api;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import software.coley.recaf.analytics.logging.Logging;
import software.coley.recaf.info.properties.builtin.CachedDecompileProperty;
import software.coley.recaf.services.Service;
import software.coley.recaf.services.analysis.plugin.MinecraftPluginAnalysisService;
import software.coley.recaf.services.file.RecafDirectoriesConfig;
import software.coley.recaf.services.workspace.io.ResourceImporter;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.resource.WorkspaceFileResource;
import software.coley.recaf.workspace.model.resource.WorkspaceResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Service for attaching the server API a plugin was written against to a workspace.
 * <p>
 * A plugin jar does not contain the API it uses, such as Bukkit/Paper. Without it every reference to those types is
 * unresolved. That makes decompilation guess at generics, overloads, lambdas, and casts. Attaching the API as a
 * supporting resource gives the decompiler the types to resolve against.
 * <p>
 * This downloads from the network, so it only ever happens when asked for. Downloaded files are verified against the
 * checksums the repository publishes, and are kept in a cache so each file is only downloaded once.
 */
@ApplicationScoped
public class MinecraftPluginApiService implements Service {
	public static final String SERVICE_ID = "minecraft-plugin-api";
	private static final Logger logger = Logging.get(MinecraftPluginApiService.class);
	private static final long MB = 1024L * 1024L;
	private final MinecraftPluginApiConfig config;
	private final MinecraftPluginAnalysisService analysisService;
	private final PluginApiFetcher fetcher;
	private final ResourceImporter resourceImporter;
	private final Path cacheDirectory;
	private final List<String> repositoryOverride;

	@Inject
	public MinecraftPluginApiService(@Nonnull MinecraftPluginApiConfig config,
	                                 @Nonnull MinecraftPluginAnalysisService analysisService,
	                                 @Nonnull UrlPluginApiFetcher fetcher,
	                                 @Nonnull ResourceImporter resourceImporter,
	                                 @Nonnull RecafDirectoriesConfig directoriesConfig) {
		this(config, analysisService, fetcher, resourceImporter,
				directoriesConfig.getBaseDirectory().resolve("cache").resolve("minecraft-plugin-api"), null);
	}

	/**
	 * @param repositoryOverride
	 * 		Repositories to use in place of those of each {@link PluginApiTarget}. Intended for tests.
	 */
	MinecraftPluginApiService(@Nonnull MinecraftPluginApiConfig config,
	                          @Nonnull MinecraftPluginAnalysisService analysisService,
	                          @Nonnull PluginApiFetcher fetcher,
	                          @Nonnull ResourceImporter resourceImporter,
	                          @Nonnull Path cacheDirectory,
	                          @Nullable List<String> repositoryOverride) {
		this.config = config;
		this.analysisService = analysisService;
		this.fetcher = fetcher;
		this.resourceImporter = resourceImporter;
		this.cacheDirectory = cacheDirectory;
		this.repositoryOverride = repositoryOverride;
	}

	/**
	 * @param workspace
	 * 		Workspace to look at.
	 *
	 * @return APIs that the plugins in the workspace were written against, according to their manifests.
	 */
	@Nonnull
	public List<PluginApiRequest> detectRequests(@Nonnull Workspace workspace) {
		Set<PluginApiRequest> requests = new LinkedHashSet<>();
		analysisService.findManifestsRecursive(workspace, workspace.getPrimaryResource()).forEach(located -> {
			PluginApiTarget target = PluginApiTarget.forPlatform(located.manifest().platform());
			requests.add(new PluginApiRequest(target, located.manifest().apiVersion()));
		});
		return new ArrayList<>(requests);
	}

	/**
	 * Downloads the requested API and adds it to the workspace as supporting resources.
	 * <p>
	 * This blocks while downloading, so it must not be called from a UI thread.
	 *
	 * @param workspace
	 * 		Workspace to add to.
	 * @param request
	 * 		API to attach.
	 * @param progress
	 * 		Receiver of short status messages, such as for display.
	 *
	 * @return Summary of what was done.
	 *
	 * @throws IOException
	 * 		When the API itself could not be found or downloaded.
	 * 		Problems with individual libraries it depends on do not cause this, they are reported in the result.
	 */
	@Nonnull
	public PluginApiAttachResult attach(@Nonnull Workspace workspace, @Nonnull PluginApiRequest request,
	                                    @Nonnull Consumer<String> progress) throws IOException {
		PluginApiTarget target = request.target();
		MavenRepositoryClient client = new MavenRepositoryClient(fetcher,
				repositoryOverride != null ? repositoryOverride : target.repositories(), cacheDirectory);

		// Decide what version we are getting.
		progress.accept("Looking up " + target.displayName() + " versions");
		List<String> versions = client.listVersions(target.groupId(), target.artifactId());
		if (versions.isEmpty())
			throw new IOException("No versions of " + target.displayName() + " are available");
		String version = target.selectVersion(versions, request.apiVersion())
				.orElseThrow(() -> new IOException("No version of " + target.displayName() + " matches " +
						(request.apiVersion() == null ? "the request" : "api-version " + request.apiVersion())));
		MavenCoordinate apiCoordinate;
		try {
			apiCoordinate = new MavenCoordinate(target.groupId(), target.artifactId(), version);
		} catch (IllegalArgumentException ex) {
			throw new IOException("The repository listed an invalid version: " + version, ex);
		}

		// Download everything before touching the workspace, so a failure part way does not leave it half done.
		List<Download> downloads = new ArrayList<>();
		List<PluginApiAttachResult.Skipped> skipped = new ArrayList<>();
		progress.accept("Downloading " + apiCoordinate);
		long apiLimit = config.getMaxApiSizeMb().getValue() * MB;
		MavenRepositoryClient.Fetched api = client.fetch(apiCoordinate, "jar", apiLimit, true);
		if (api == null)
			throw new IOException("Could not find the jar for " + apiCoordinate);
		downloads.add(new Download(apiCoordinate, api));

		if (config.getIncludeDependencies().getValue()) {
			progress.accept("Resolving libraries of " + apiCoordinate);
			List<MavenCoordinate> dependencies = List.of();
			try {
				dependencies = new MavenDependencyResolver(client, config.getMaxDependencies().getValue()).resolve(apiCoordinate);
			} catch (IOException ex) {
				// The API alone is still useful.
				logger.warn("Could not resolve libraries of {}", apiCoordinate, ex);
				skipped.add(new PluginApiAttachResult.Skipped(apiCoordinate, "Libraries could not be resolved: " + ex.getMessage()));
			}

			long dependencyLimit = config.getMaxDependencySizeMb().getValue() * MB;
			int index = 0;
			for (MavenCoordinate dependency : dependencies) {
				progress.accept("Downloading " + dependency + " (" + (++index) + "/" + dependencies.size() + ")");
				try {
					MavenRepositoryClient.Fetched fetched = client.fetch(dependency, "jar", dependencyLimit, true);
					if (fetched == null)
						skipped.add(new PluginApiAttachResult.Skipped(dependency, "No jar is published"));
					else
						downloads.add(new Download(dependency, fetched));
				} catch (FileTooLargeException ex) {
					logger.debug("Skipping {}: {}", dependency, ex.getMessage());
					skipped.add(new PluginApiAttachResult.Skipped(dependency,
							"Larger than the limit of " + config.getMaxDependencySizeMb().getValue() + " MB"));
				} catch (IOException ex) {
					logger.debug("Skipping {}: {}", dependency, ex.getMessage());
					skipped.add(new PluginApiAttachResult.Skipped(dependency, ex.getMessage() == null ? "Download failed" : ex.getMessage()));
				}
			}
		}

		// Add to the workspace, skipping anything that is already there.
		progress.accept("Adding to workspace");
		Set<String> existingNames = new LinkedHashSet<>();
		for (WorkspaceResource resource : workspace.getSupportingResources())
			if (resource instanceof WorkspaceFileResource fileResource)
				existingNames.add(fileResource.getFileInfo().getName());

		List<MavenCoordinate> attached = new ArrayList<>();
		List<MavenCoordinate> alreadyPresent = new ArrayList<>();
		for (Download download : downloads) {
			if (existingNames.contains(download.fetched.fileName())) {
				alreadyPresent.add(download.coordinate);
				continue;
			}
			try {
				workspace.addSupportingResource(importJar(download.fetched));
				attached.add(download.coordinate);
			} catch (IOException ex) {
				logger.warn("Could not read {} as a resource", download.coordinate, ex);
				if (download.coordinate.equals(apiCoordinate))
					throw ex;
				skipped.add(new PluginApiAttachResult.Skipped(download.coordinate, "Not a readable jar: " + ex.getMessage()));
			}
		}

		// Earlier decompilation results were made without these types and may be worse than what we would get now.
		if (!attached.isEmpty())
			discardCachedDecompilations(workspace);

		logger.info("Attached {} ({} added, {} already present, {} skipped)",
				apiCoordinate, attached.size(), alreadyPresent.size(), skipped.size());
		return new PluginApiAttachResult(apiCoordinate, List.copyOf(attached), List.copyOf(alreadyPresent), List.copyOf(skipped));
	}

	@Nonnull
	private WorkspaceResource importJar(@Nonnull MavenRepositoryClient.Fetched fetched) throws IOException {
		Path path = fetched.cachePath();
		if (path != null && Files.isRegularFile(path))
			return resourceImporter.importResource(path);

		// The cache could not be written. Fall back to a temporary file so the resource still gets a proper name.
		Path temp = Files.createTempDirectory("recaf-plugin-api").resolve(fetched.fileName());
		Files.write(temp, fetched.content());
		temp.toFile().deleteOnExit();
		temp.getParent().toFile().deleteOnExit();
		return resourceImporter.importResource(temp);
	}

	private static void discardCachedDecompilations(@Nonnull Workspace workspace) {
		workspace.getPrimaryResource().jvmAllClassBundleStreamRecursive()
				.forEach(bundle -> bundle.forEach(CachedDecompileProperty::remove));
	}

	@Nonnull
	@Override
	public String getServiceId() {
		return SERVICE_ID;
	}

	@Nonnull
	@Override
	public MinecraftPluginApiConfig getServiceConfig() {
		return config;
	}

	private record Download(@Nonnull MavenCoordinate coordinate, @Nonnull MavenRepositoryClient.Fetched fetched) {}
}
