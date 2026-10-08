package software.coley.recaf.services.analysis.plugin;

import jakarta.annotation.Nonnull;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import software.coley.recaf.analytics.logging.Logging;
import software.coley.recaf.cdi.EagerInitialization;
import software.coley.recaf.info.FileInfo;
import software.coley.recaf.info.TextFileInfo;
import software.coley.recaf.info.builder.FileInfoBuilder;
import software.coley.recaf.path.ClassPathNode;
import software.coley.recaf.services.mapping.MappingApplicationListener;
import software.coley.recaf.services.mapping.MappingListeners;
import software.coley.recaf.services.mapping.MappingResults;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.bundle.FileBundle;
import software.coley.recaf.workspace.model.resource.WorkspaceResource;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Keeps plugin manifests pointing at the right classes when classes are renamed.
 * <p>
 * Server software finds a plugin by looking up the {@code main} class named in its manifest. If that class is renamed
 * and the manifest is not, the plugin fails to load. This is most likely to matter for obfuscated plugins, where
 * renaming the main class is one of the first things you do.
 *
 * @see MinecraftPluginManifestRewriter
 */
@EagerInitialization
@ApplicationScoped
public class MinecraftPluginMappingListener implements MappingApplicationListener {
	private static final Logger logger = Logging.get(MinecraftPluginMappingListener.class);

	@Inject
	public MinecraftPluginMappingListener(@Nonnull MappingListeners mappingListeners) {
		mappingListeners.addMappingApplicationListener(this);
	}

	@Override
	public void onPreApply(@Nonnull Workspace workspace, @Nonnull MappingResults mappingResults) {
		// no-op, we update the manifests once the classes themselves have been updated.
	}

	@Override
	public void onPostApply(@Nonnull Workspace workspace, @Nonnull MappingResults mappingResults) {
		// The results also list classes that were visited but kept their name, those are not interesting.
		Map<String, String> renames = new HashMap<>();
		mappingResults.getMappedClasses().forEach((oldName, newName) -> {
			if (!oldName.equals(newName))
				renames.put(oldName, newName);
		});
		if (renames.isEmpty())
			return;

		// A manifest names classes in the same resource (jar) it lives in, so only look at resources
		// that had renamed classes in them.
		Set<WorkspaceResource> resources = new LinkedHashSet<>();
		for (ClassPathNode path : mappingResults.getPreMappingPaths().values()) {
			WorkspaceResource resource = path.getValueOfType(WorkspaceResource.class);
			if (resource != null)
				resources.add(resource);
		}
		for (WorkspaceResource resource : resources)
			updateManifests(resource, renames);
	}

	private void updateManifests(@Nonnull WorkspaceResource resource, @Nonnull Map<String, String> renames) {
		FileBundle bundle = resource.getFileBundle();
		for (MinecraftPluginPlatform platform : MinecraftPluginPlatform.values()) {
			String fileName = platform.manifestFileName();
			FileInfo file = bundle.get(fileName);
			if (file == null)
				continue;

			try {
				Charset charset = file instanceof TextFileInfo textFile ? textFile.getCharset() : StandardCharsets.UTF_8;
				String text = new String(file.getRawContent(), charset);
				MinecraftPluginManifestRewriter.Result result = MinecraftPluginManifestRewriter.rewrite(fileName, text, renames);
				if (result.isModified()) {
					FileInfo updated = FileInfoBuilder.forFile(file)
							.withRawContent(result.text().getBytes(charset))
							.build();
					bundle.put(updated);
					result.changes().forEach(change ->
							logger.info("Updated '{}' key '{}': {} --> {}", fileName, change.key(), change.oldName(), change.newName()));
				}
				for (String key : result.unresolved())
					logger.warn("'{}' key '{}' refers to a renamed class, but could not be updated. " +
							"Edit it manually or the plugin will not load.", fileName, key);
			} catch (Throwable t) {
				logger.error("Failed to update '{}' after class renaming", fileName, t);
			}
		}
	}
}
