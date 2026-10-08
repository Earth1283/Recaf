package software.coley.recaf.services.analysis.plugin.api;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import org.slf4j.Logger;
import org.w3c.dom.Element;
import software.coley.recaf.analytics.logging.Logging;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * Minimal client for reading from Maven repositories.
 * <p>
 * Files that were downloaded are kept in a cache directory. Version listings are never cached, because they change.
 */
public class MavenRepositoryClient {
	private static final Logger logger = Logging.get(MavenRepositoryClient.class);
	private static final long MAX_TEXT_BYTES = 8L * 1024 * 1024;
	private final PluginApiFetcher fetcher;
	private final List<String> repositories;
	private final Path cacheDirectory;

	/**
	 * @param fetcher
	 * 		Fetcher to download with.
	 * @param repositories
	 * 		Base URLs of repositories, searched in order.
	 * @param cacheDirectory
	 * 		Directory to cache downloaded files in, or {@code null} to not cache.
	 */
	public MavenRepositoryClient(@Nonnull PluginApiFetcher fetcher, @Nonnull List<String> repositories,
	                             @Nullable Path cacheDirectory) {
		this.fetcher = fetcher;
		this.repositories = repositories.stream().map(url -> url.endsWith("/") ? url : url + '/').toList();
		this.cacheDirectory = cacheDirectory;
	}

	/**
	 * @param groupId
	 * 		Group ID of the artifact.
	 * @param artifactId
	 * 		Artifact ID.
	 *
	 * @return All versions of the artifact that the first repository knowing about it lists. Empty if none do.
	 *
	 * @throws IOException
	 * 		When a repository could not be read.
	 */
	@Nonnull
	public List<String> listVersions(@Nonnull String groupId, @Nonnull String artifactId) throws IOException {
		String dir = groupId.replace('.', '/') + '/' + artifactId;
		for (String repository : repositories) {
			byte[] metadata = fetcher.fetch(repository + dir + "/maven-metadata.xml", MAX_TEXT_BYTES);
			if (metadata == null)
				continue;
			Element versioning = MavenXml.child(MavenXml.parse(metadata), "versioning");
			Element versions = versioning == null ? null : MavenXml.child(versioning, "versions");
			List<String> list = new ArrayList<>();
			if (versions != null)
				for (Element version : MavenXml.children(versions, "version"))
					list.add(version.getTextContent().trim());
			return list;
		}
		return List.of();
	}

	/**
	 * @param coordinate
	 * 		Artifact to get the file of.
	 * @param extension
	 * 		File extension, such as {@code jar} or {@code pom}.
	 * @param maxBytes
	 * 		Maximum size to accept.
	 * @param verifyChecksum
	 * 		When {@code true} the file must match the SHA-1 published next to it, if the repository publishes one.
	 *
	 * @return Content of the file, or {@code null} if no repository has it.
	 *
	 * @throws IOException
	 * 		When a repository could not be read, or a checksum did not match.
	 */
	@Nullable
	public byte[] fetchFile(@Nonnull MavenCoordinate coordinate, @Nonnull String extension,
	                        long maxBytes, boolean verifyChecksum) throws IOException {
		Fetched fetched = fetch(coordinate, extension, maxBytes, verifyChecksum);
		return fetched == null ? null : fetched.content();
	}

	/**
	 * Same as {@link #fetchFile(MavenCoordinate, String, long, boolean)}, but also says where the file is cached.
	 *
	 * @param coordinate
	 * 		Artifact to get the file of.
	 * @param extension
	 * 		File extension, such as {@code jar} or {@code pom}.
	 * @param maxBytes
	 * 		Maximum size to accept.
	 * @param verifyChecksum
	 * 		When {@code true} the file must match the SHA-1 published next to it, if the repository publishes one.
	 *
	 * @return The file, or {@code null} if no repository has it.
	 *
	 * @throws IOException
	 * 		When a repository could not be read, or a checksum did not match.
	 */
	@Nullable
	public Fetched fetch(@Nonnull MavenCoordinate coordinate, @Nonnull String extension,
	                     long maxBytes, boolean verifyChecksum) throws IOException {
		for (String repository : repositories) {
			String fileName = resolveFileName(repository, coordinate, extension);
			if (fileName == null)
				continue;

			// Use the cache if we have it. The name includes the timestamp for snapshots, so it never goes stale.
			Path cached = cacheDirectory == null ? null :
					cacheDirectory.resolve(coordinate.versionDirectory()).resolve(fileName);
			if (cached != null && Files.isRegularFile(cached)) {
				byte[] cachedContent = null;
				try {
					cachedContent = Files.readAllBytes(cached);
				} catch (IOException ex) {
					logger.warn("Could not read cached file '{}', downloading it again", cached, ex);
				}
				if (cachedContent != null) {
					if (cachedContent.length > maxBytes)
						throw new FileTooLargeException(fileName, maxBytes);
					return new Fetched(fileName, cachedContent, cached);
				}
			}

			String url = repository + coordinate.versionDirectory() + '/' + fileName;
			byte[] content = fetcher.fetch(url, maxBytes);
			if (content == null)
				continue;
			if (verifyChecksum)
				verifySha1(url, content);

			boolean wasCached = cached != null && writeCache(cached, content);
			return new Fetched(fileName, content, wasCached ? cached : null);
		}
		return null;
	}

	/**
	 * @param repository
	 * 		Repository to look in.
	 * @param coordinate
	 * 		Artifact to name.
	 * @param extension
	 * 		File extension.
	 *
	 * @return Name of the file in the repository, or {@code null} if the repository doesn't have the version.
	 */
	@Nullable
	private String resolveFileName(@Nonnull String repository, @Nonnull MavenCoordinate coordinate,
	                               @Nonnull String extension) throws IOException {
		if (!coordinate.isSnapshot())
			return coordinate.artifactId() + '-' + coordinate.version() + '.' + extension;

		// Snapshots are stored under a timestamped name, which the version's metadata gives us.
		byte[] metadata = fetcher.fetch(repository + coordinate.versionDirectory() + "/maven-metadata.xml", MAX_TEXT_BYTES);
		if (metadata == null)
			return null;
		Element versioning = MavenXml.child(MavenXml.parse(metadata), "versioning");
		if (versioning == null)
			return null;

		Element snapshotVersions = MavenXml.child(versioning, "snapshotVersions");
		if (snapshotVersions != null) {
			for (Element snapshotVersion : MavenXml.children(snapshotVersions, "snapshotVersion")) {
				if (MavenXml.child(snapshotVersion, "classifier") != null)
					continue;
				if (!extension.equals(MavenXml.text(snapshotVersion, "extension")))
					continue;
				String value = MavenXml.text(snapshotVersion, "value");
				if (value != null) {
					requireSafe(value);
					return coordinate.artifactId() + '-' + value + '.' + extension;
				}
			}
		}

		// Older metadata only has the latest timestamp and build number.
		Element snapshot = MavenXml.child(versioning, "snapshot");
		String timestamp = snapshot == null ? null : MavenXml.text(snapshot, "timestamp");
		String buildNumber = snapshot == null ? null : MavenXml.text(snapshot, "buildNumber");
		if (timestamp != null && buildNumber != null) {
			requireSafe(timestamp);
			requireSafe(buildNumber);
			String base = coordinate.version().substring(0, coordinate.version().length() - "-SNAPSHOT".length());
			return coordinate.artifactId() + '-' + base + '-' + timestamp + '-' + buildNumber + '.' + extension;
		}
		return null;
	}

	private static void requireSafe(@Nonnull String metadataValue) throws IOException {
		if (!MavenCoordinate.isSafeFileNamePart(metadataValue))
			throw new IOException("Repository metadata contains an invalid file name part: " + metadataValue);
	}

	private void verifySha1(@Nonnull String url, @Nonnull byte[] content) throws IOException {
		byte[] published = fetcher.fetch(url + ".sha1", 4096);
		if (published == null) {
			logger.debug("No checksum is published for {}", url);
			return;
		}

		// The file may be just the hash, or the hash followed by a file name.
		String text = new String(published, StandardCharsets.US_ASCII).trim();
		String expected = text.split("\\s+")[0].toLowerCase();
		String actual;
		try {
			actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(content));
		} catch (NoSuchAlgorithmException ex) {
			throw new IOException("SHA-1 is not available", ex);
		}
		if (!actual.equals(expected))
			throw new IOException("Checksum mismatch for " + url + " (expected " + expected + ", got " + actual + ")");
	}

	/**
	 * @return {@code true} when the file was written.
	 */
	private static boolean writeCache(@Nonnull Path target, @Nonnull byte[] content) {
		try {
			Files.createDirectories(target.getParent());
			Path temp = Files.createTempFile(target.getParent(), target.getFileName().toString(), ".part");
			try {
				Files.write(temp, content);
				Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
				return true;
			} finally {
				Files.deleteIfExists(temp);
			}
		} catch (IOException ex) {
			// Not being able to cache is not a reason to fail.
			logger.warn("Could not cache '{}'", target, ex);
			return false;
		}
	}

	/**
	 * @param fileName
	 * 		Name of the file in the repository.
	 * @param content
	 * 		Content of the file.
	 * @param cachePath
	 * 		Where the file is stored on disk, or {@code null} if it is not cached.
	 */
	public record Fetched(@Nonnull String fileName, @Nonnull byte[] content, @Nullable Path cachePath) {}
}
