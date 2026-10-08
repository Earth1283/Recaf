package software.coley.recaf.services.analysis.plugin.api;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * In-memory repository standing in for the network.
 */
class FakeMavenRepository implements PluginApiFetcher {
	static final String BASE = "https://repo.example/maven/";
	final Map<String, byte[]> files = new HashMap<>();
	final List<String> requests = new ArrayList<>();

	@Nullable
	@Override
	public byte[] fetch(@Nonnull String url, long maxBytes) throws IOException {
		requests.add(url);
		byte[] content = files.get(url);
		if (content != null && content.length > maxBytes)
			throw new FileTooLargeException(url, maxBytes);
		return content;
	}

	void put(@Nonnull String path, @Nonnull String text) {
		files.put(BASE + path, text.getBytes(StandardCharsets.UTF_8));
	}

	void putBytes(@Nonnull String path, @Nonnull byte[] content) {
		files.put(BASE + path, content);
	}

	void putSha1(@Nonnull String path, @Nonnull byte[] content) {
		try {
			String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(content));
			files.put(BASE + path + ".sha1", (hash + "  file\n").getBytes(StandardCharsets.UTF_8));
		} catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

	/**
	 * Adds a POM for the given artifact.
	 *
	 * @param coordinate
	 * 		Artifact in the form {@code group:artifact:version}.
	 * @param body
	 * 		XML placed inside the {@code project} element.
	 */
	void pom(@Nonnull String coordinate, @Nonnull String body) {
		String[] parts = coordinate.split(":");
		String path = parts[0].replace('.', '/') + '/' + parts[1] + '/' + parts[2] + '/' + parts[1] + '-' + parts[2] + ".pom";
		put(path, "<project><modelVersion>4.0.0</modelVersion><groupId>" + parts[0] + "</groupId><artifactId>" +
				parts[1] + "</artifactId><version>" + parts[2] + "</version>" + body + "</project>");
	}

	static String dep(String group, String artifact, String version) {
		return dep(group, artifact, version, null, "");
	}

	static String dep(String group, String artifact, String version, String scope, String extra) {
		return "<dependency><groupId>" + group + "</groupId><artifactId>" + artifact + "</artifactId>" +
				(version == null ? "" : "<version>" + version + "</version>") +
				(scope == null ? "" : "<scope>" + scope + "</scope>") + extra + "</dependency>";
	}

	static String deps(String... deps) {
		return "<dependencies>" + String.join("", deps) + "</dependencies>";
	}
}
