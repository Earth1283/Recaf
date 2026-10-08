package software.coley.recaf.services.analysis.plugin;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import java.util.Arrays;

/**
 * Minecraft server software families that load plugins described by a YAML manifest at the root of the plugin jar.
 * <p>
 * Velocity is intentionally absent, its plugins are described by annotations rather than a hand-written manifest.
 */
public enum MinecraftPluginPlatform {
	/** Bukkit, Spigot, and Paper (when not using the Paper-specific manifest). */
	BUKKIT("plugin.yml", "Bukkit / Spigot / Paper"),
	/** Paper plugins using the newer manifest which supports bootstrappers and custom loaders. */
	PAPER("paper-plugin.yml", "Paper"),
	/** BungeeCord and its forks (Waterfall, FlameCord, ...). */
	BUNGEE("bungee.yml", "BungeeCord");

	private final String manifestFileName;
	private final String displayName;

	MinecraftPluginPlatform(@Nonnull String manifestFileName, @Nonnull String displayName) {
		this.manifestFileName = manifestFileName;
		this.displayName = displayName;
	}

	/**
	 * @return Name of the manifest file, located at the root of the plugin jar.
	 */
	@Nonnull
	public String manifestFileName() {
		return manifestFileName;
	}

	/**
	 * @return User-facing name of the platform.
	 */
	@Nonnull
	public String displayName() {
		return displayName;
	}

	/**
	 * @param fileName
	 * 		Name of a file within a resource.
	 *
	 * @return Platform with the given manifest file name, or {@code null} if no platform uses the name.
	 */
	@Nullable
	public static MinecraftPluginPlatform fromManifestFileName(@Nonnull String fileName) {
		return Arrays.stream(values())
				.filter(platform -> platform.manifestFileName.equals(fileName))
				.findFirst().orElse(null);
	}
}
