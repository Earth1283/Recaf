package software.coley.recaf.services.analysis.plugin.api;

import jakarta.annotation.Nonnull;

import java.util.regex.Pattern;

/**
 * Coordinate of a Maven artifact.
 * <p>
 * Coordinates are built from text in POM files and repository metadata, which is not trusted. They are used to build
 * both download URLs and paths in the local cache, so each part is restricted to characters that cannot walk out of
 * the directory they belong in.
 *
 * @param groupId
 * 		Group ID, such as {@code io.papermc.paper}.
 * @param artifactId
 * 		Artifact ID, such as {@code paper-api}.
 * @param version
 * 		Version, such as {@code 1.21.4-R0.1-SNAPSHOT}.
 */
public record MavenCoordinate(@Nonnull String groupId, @Nonnull String artifactId, @Nonnull String version) {
	private static final Pattern GROUP_ID = Pattern.compile("[A-Za-z0-9_\\-]+(\\.[A-Za-z0-9_\\-]+)*");
	private static final Pattern ARTIFACT_ID = Pattern.compile("[A-Za-z0-9_\\-][A-Za-z0-9_.\\-]*");
	private static final Pattern VERSION = Pattern.compile("[A-Za-z0-9_\\-][A-Za-z0-9_.+\\-]*");

	/**
	 * @throws IllegalArgumentException
	 * 		When any part contains characters that are not valid in a Maven coordinate.
	 */
	public MavenCoordinate {
		if (!GROUP_ID.matcher(groupId).matches())
			throw new IllegalArgumentException("Invalid group ID: " + groupId);
		if (!ARTIFACT_ID.matcher(artifactId).matches())
			throw new IllegalArgumentException("Invalid artifact ID: " + artifactId);
		if (!VERSION.matcher(version).matches())
			throw new IllegalArgumentException("Invalid version: " + version);
	}

	/**
	 * @param text
	 * 		Text to check, such as a file name taken from repository metadata.
	 *
	 * @return {@code true} when the text is safe to use as part of a file name or URL.
	 */
	static boolean isSafeFileNamePart(@Nonnull String text) {
		return VERSION.matcher(text).matches();
	}

	/**
	 * @return {@code true} when the version is a snapshot, whose files are named with a timestamp in the repository.
	 */
	public boolean isSnapshot() {
		return version.endsWith("-SNAPSHOT");
	}

	/**
	 * @return Key of the artifact without its version.
	 */
	@Nonnull
	public String versionlessKey() {
		return groupId + ':' + artifactId;
	}

	/**
	 * @return Repository directory containing the artifact's versions.
	 */
	@Nonnull
	public String artifactDirectory() {
		return groupId.replace('.', '/') + '/' + artifactId;
	}

	/**
	 * @return Repository directory containing this version's files.
	 */
	@Nonnull
	public String versionDirectory() {
		return artifactDirectory() + '/' + version;
	}

	@Override
	public String toString() {
		return groupId + ':' + artifactId + ':' + version;
	}
}
