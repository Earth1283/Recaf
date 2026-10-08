package software.coley.recaf.services.analysis.plugin.api;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

/**
 * Description of an API to attach to a workspace.
 *
 * @param target
 * 		The API.
 * @param apiVersion
 * 		The version the plugin asks for <i>(an {@code api-version} such as {@code 1.21})</i>, or {@code null} to use
 * 		the newest available.
 */
public record PluginApiRequest(@Nonnull PluginApiTarget target, @Nullable String apiVersion) {
	/**
	 * @return User-facing description.
	 */
	@Nonnull
	public String describe() {
		return target.displayName() + (apiVersion == null ? " (latest)" : " for " + apiVersion);
	}
}
