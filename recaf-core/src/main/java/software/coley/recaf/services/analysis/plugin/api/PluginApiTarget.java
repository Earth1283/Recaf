package software.coley.recaf.services.analysis.plugin.api;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import software.coley.recaf.services.analysis.plugin.MinecraftPluginPlatform;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An API a plugin is written against, which is not included in the plugin itself.
 */
public enum PluginApiTarget {
	/**
	 * The Paper API. It is a superset of the Bukkit and Spigot APIs, so it covers plugins written for any of them.
	 */
	PAPER("Paper API", "io.papermc.paper", "paper-api", List.of(
			"https://repo.papermc.io/repository/maven-public/",
			"https://repo.maven.apache.org/maven2/")) {
		@Nonnull
		@Override
		Optional<String> selectVersion(@Nonnull Collection<String> available, @Nullable String requested) {
			return PaperApiVersionSelector.select(available, requested);
		}
	},
	/**
	 * The BungeeCord API. Plugins do not declare which version they target, so the newest release is used.
	 */
	BUNGEE("BungeeCord API", "net.md-5", "bungeecord-api", List.of(
			"https://repo.maven.apache.org/maven2/",
			"https://repo.papermc.io/repository/maven-public/")) {
		@Nonnull
		@Override
		Optional<String> selectVersion(@Nonnull Collection<String> available, @Nullable String requested) {
			return available.stream()
					.filter(version -> !version.contains("SNAPSHOT"))
					.max(Comparator.comparing(PluginApiTarget::numericTokens, PluginApiTarget::compareTokens));
		}
	};

	private static final Pattern DIGITS = Pattern.compile("\\d+");
	private final String displayName;
	private final String groupId;
	private final String artifactId;
	private final List<String> repositories;

	PluginApiTarget(@Nonnull String displayName, @Nonnull String groupId, @Nonnull String artifactId,
	                @Nonnull List<String> repositories) {
		this.displayName = displayName;
		this.groupId = groupId;
		this.artifactId = artifactId;
		this.repositories = repositories;
	}

	/**
	 * @return User-facing name.
	 */
	@Nonnull
	public String displayName() {
		return displayName;
	}

	/**
	 * @return Group ID of the API artifact.
	 */
	@Nonnull
	public String groupId() {
		return groupId;
	}

	/**
	 * @return Artifact ID of the API artifact.
	 */
	@Nonnull
	public String artifactId() {
		return artifactId;
	}

	/**
	 * @return Base URLs of the repositories to download from, in order of preference.
	 */
	@Nonnull
	public List<String> repositories() {
		return repositories;
	}

	/**
	 * @param available
	 * 		Versions of the artifact that are published.
	 * @param requested
	 * 		Version the plugin asks for, if it says.
	 *
	 * @return The version to use, or empty if nothing is suitable.
	 */
	@Nonnull
	abstract Optional<String> selectVersion(@Nonnull Collection<String> available, @Nullable String requested);

	/**
	 * @param platform
	 * 		Platform a plugin is written for.
	 *
	 * @return API the plugin is written against.
	 */
	@Nonnull
	public static PluginApiTarget forPlatform(@Nonnull MinecraftPluginPlatform platform) {
		return switch (platform) {
			case BUKKIT, PAPER -> PAPER;
			case BUNGEE -> BUNGEE;
		};
	}

	@Nonnull
	private static List<Integer> numericTokens(@Nonnull String version) {
		List<Integer> tokens = new ArrayList<>();
		Matcher matcher = DIGITS.matcher(version);
		while (matcher.find()) {
			try {
				tokens.add(Integer.parseInt(matcher.group()));
			} catch (NumberFormatException ex) {
				tokens.add(Integer.MAX_VALUE);
			}
		}
		return tokens;
	}

	private static int compareTokens(@Nonnull List<Integer> a, @Nonnull List<Integer> b) {
		for (int i = 0; i < Math.min(a.size(), b.size()); i++) {
			int cmp = Integer.compare(a.get(i), b.get(i));
			if (cmp != 0)
				return cmp;
		}
		return Integer.compare(a.size(), b.size());
	}
}
