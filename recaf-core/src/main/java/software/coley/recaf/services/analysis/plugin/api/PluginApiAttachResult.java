package software.coley.recaf.services.analysis.plugin.api;

import jakarta.annotation.Nonnull;

import java.util.List;

/**
 * Outcome of {@link MinecraftPluginApiService#attach}.
 *
 * @param api
 * 		The API artifact that was chosen.
 * @param attached
 * 		Artifacts that were added to the workspace, API first.
 * @param alreadyPresent
 * 		Artifacts that were already in the workspace, and so were left alone.
 * @param skipped
 * 		Artifacts that were not added, with the reason.
 */
public record PluginApiAttachResult(@Nonnull MavenCoordinate api,
                                    @Nonnull List<MavenCoordinate> attached,
                                    @Nonnull List<MavenCoordinate> alreadyPresent,
                                    @Nonnull List<Skipped> skipped) {
	/**
	 * @param coordinate
	 * 		Artifact that was skipped.
	 * @param reason
	 * 		Why it was skipped.
	 */
	public record Skipped(@Nonnull MavenCoordinate coordinate, @Nonnull String reason) {}
}
