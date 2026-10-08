package software.coley.recaf.services.analysis.plugin;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import java.util.List;

/**
 * Model of the information in a Minecraft plugin manifest that Recaf cares about.
 * <p>
 * Class names are stored in their internal form <i>({@code com/example/Main})</i> so they can be compared directly with
 * workspace class names. The values are exactly what the manifest declares, so they may reference classes that do not
 * exist in the workspace.
 *
 * @param platform
 * 		Platform the manifest is for.
 * @param name
 * 		Plugin name.
 * @param version
 * 		Plugin version.
 * @param mainClass
 * 		Internal name of the main plugin class.
 * @param apiVersion
 * 		Minimum API version as declared <i>(Bukkit and Paper only)</i>. For example {@code 1.21}.
 * @param bootstrapperClass
 * 		Internal name of the Paper bootstrapper class.
 * @param loaderClass
 * 		Internal name of the Paper plugin loader class.
 * @param dependencies
 * 		Names of required plugin dependencies.
 * @param softDependencies
 * 		Names of optional plugin dependencies.
 * @param loadBefore
 * 		Names of plugins that should load after this one.
 * @param commands
 * 		Names of the commands declared in the manifest.
 * @param permissions
 * 		Names of the permissions declared in the manifest.
 */
public record MinecraftPluginManifest(@Nonnull MinecraftPluginPlatform platform,
                                      @Nullable String name,
                                      @Nullable String version,
                                      @Nullable String mainClass,
                                      @Nullable String apiVersion,
                                      @Nullable String bootstrapperClass,
                                      @Nullable String loaderClass,
                                      @Nonnull List<String> dependencies,
                                      @Nonnull List<String> softDependencies,
                                      @Nonnull List<String> loadBefore,
                                      @Nonnull List<String> commands,
                                      @Nonnull List<String> permissions) {
	/**
	 * @param className
	 * 		Internal class name.
	 *
	 * @return {@code true} when the name is the main class of the plugin.
	 */
	public boolean isMainClass(@Nonnull String className) {
		return className.equals(mainClass);
	}

	/**
	 * @param className
	 * 		Internal class name.
	 *
	 * @return {@code true} when the name is the Paper bootstrapper class of the plugin.
	 */
	public boolean isBootstrapperClass(@Nonnull String className) {
		return className.equals(bootstrapperClass);
	}

	/**
	 * @param className
	 * 		Internal class name.
	 *
	 * @return {@code true} when the name is the Paper loader class of the plugin.
	 */
	public boolean isLoaderClass(@Nonnull String className) {
		return className.equals(loaderClass);
	}
}
