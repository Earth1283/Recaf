package software.coley.recaf.services.mcp;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import jakarta.annotation.Nonnull;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * Builds the text a user pastes into an MCP client to connect it to Recaf.
 *
 * @author Matt Coley
 */
public final class McpClientSetup {
	private static final Pattern SAFE_SHELL_WORD = Pattern.compile("[A-Za-z0-9_@%+=:,./-]+");
	private static final String JAR_PLACEHOLDER = "/path/to/recaf.jar";

	private McpClientSetup() {}

	/**
	 * @param port
	 * 		HTTP port.
	 *
	 * @return Endpoint URL.
	 */
	@Nonnull
	public static String url(int port) {
		return "http://127.0.0.1:" + port + "/mcp";
	}

	/**
	 * @param port
	 * 		HTTP port.
	 * @param token
	 * 		Bearer token.
	 *
	 * @return Shell command that registers the HTTP server with Claude Code.
	 */
	@Nonnull
	public static String claudeCodeHttp(int port, @Nonnull String token) {
		return "claude mcp add --transport http recaf " + url(port) + " --header " +
				quote("Authorization: Bearer " + token);
	}

	/**
	 * @param port
	 * 		HTTP port.
	 * @param token
	 * 		Bearer token.
	 *
	 * @return Configuration for clients that read an {@code mcpServers} JSON file.
	 */
	@Nonnull
	public static String jsonHttp(int port, @Nonnull String token) {
		JsonObject server = new JsonObject();
		server.addProperty("type", "http");
		server.addProperty("url", url(port));
		JsonObject headers = new JsonObject();
		headers.addProperty("Authorization", "Bearer " + token);
		server.add("headers", headers);
		return wrap(server);
	}

	/**
	 * @param java
	 * 		Path of the java executable.
	 * @param jar
	 * 		Path of the Recaf jar.
	 *
	 * @return Shell command that registers a Recaf the client launches itself, without the UI.
	 */
	@Nonnull
	public static String claudeCodeStdio(@Nonnull String java, @Nonnull String jar) {
		return "claude mcp add recaf -- " + quote(java) + " -jar " + quote(jar) + " -h --mcp";
	}

	/**
	 * @param java
	 * 		Path of the java executable.
	 * @param jar
	 * 		Path of the Recaf jar.
	 *
	 * @return Configuration for clients that read an {@code mcpServers} JSON file.
	 */
	@Nonnull
	public static String jsonStdio(@Nonnull String java, @Nonnull String jar) {
		JsonObject server = new JsonObject();
		server.addProperty("command", java);
		JsonArray args = new JsonArray();
		args.add("-jar");
		args.add(jar);
		args.add("-h");
		args.add("--mcp");
		server.add("args", args);
		return wrap(server);
	}

	/**
	 * @return Path of the java executable running this Recaf.
	 */
	@Nonnull
	public static String javaExecutable() {
		String home = System.getProperty("java.home");
		if (home == null)
			return "java";
		String name = System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java";
		return Path.of(home, "bin", name).toString();
	}

	/**
	 * @return Path of the jar Recaf is running from, or a placeholder when it is not running from a jar.
	 */
	@Nonnull
	public static String recafJar() {
		try {
			var source = McpClientSetup.class.getProtectionDomain().getCodeSource();
			if (source != null) {
				Path path = Path.of(source.getLocation().toURI());
				if (path.getFileName() != null && path.getFileName().toString().endsWith(".jar"))
					return path.toString();
			}
		} catch (URISyntaxException | RuntimeException ignored) {
			// Falls through to the placeholder.
		}
		return JAR_PLACEHOLDER;
	}

	/**
	 * @param word
	 * 		Shell argument.
	 *
	 * @return The argument, quoted when it has characters a shell would treat specially.
	 */
	@Nonnull
	static String quote(@Nonnull String word) {
		if (SAFE_SHELL_WORD.matcher(word).matches())
			return word;
		if (System.getProperty("os.name", "").toLowerCase().contains("win"))
			return '"' + word.replace("\"", "\\\"") + '"';
		return "'" + word.replace("'", "'\\''") + "'";
	}

	@Nonnull
	private static String wrap(@Nonnull JsonObject server) {
		JsonObject servers = new JsonObject();
		servers.add("recaf", server);
		JsonObject root = new JsonObject();
		root.add("mcpServers", servers);
		return new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create().toJson(root);
	}
}
