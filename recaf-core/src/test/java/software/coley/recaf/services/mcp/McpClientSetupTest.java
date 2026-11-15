package software.coley.recaf.services.mcp;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class McpClientSetupTest {
	@Test
	void httpSnippetsCarryTheEndpointAndToken() {
		assertEquals("http://127.0.0.1:7733/mcp", McpClientSetup.url(7733));
		String command = McpClientSetup.claudeCodeHttp(7733, "abc-DEF_123");
		assertTrue(command.startsWith("claude mcp add --transport http recaf http://127.0.0.1:7733/mcp --header "));
		assertTrue(command.contains("Authorization: Bearer abc-DEF_123"));

		JsonObject server = JsonParser.parseString(McpClientSetup.jsonHttp(7733, "tok")).getAsJsonObject()
				.getAsJsonObject("mcpServers").getAsJsonObject("recaf");
		assertEquals("http://127.0.0.1:7733/mcp", server.get("url").getAsString());
		assertEquals("Bearer tok", server.getAsJsonObject("headers").get("Authorization").getAsString());
	}

	@Test
	void stdioSnippetsLaunchRecafHeadless() {
		JsonObject server = JsonParser.parseString(McpClientSetup.jsonStdio("/jdk/bin/java", "/opt/recaf.jar"))
				.getAsJsonObject().getAsJsonObject("mcpServers").getAsJsonObject("recaf");
		assertEquals("/jdk/bin/java", server.get("command").getAsString());
		assertEquals("[\"-jar\",\"/opt/recaf.jar\",\"-h\",\"--mcp\"]", server.get("args").toString());
		assertEquals("claude mcp add recaf -- /jdk/bin/java -jar /opt/recaf.jar -h --mcp",
				McpClientSetup.claudeCodeStdio("/jdk/bin/java", "/opt/recaf.jar"));
	}

	@Test
	void shellQuotingProtectsSpacesAndQuotes() {
		if (System.getProperty("os.name", "").toLowerCase().contains("win"))
			return;
		assertEquals("plain/path.jar", McpClientSetup.quote("plain/path.jar"));
		assertEquals("'my dir/recaf.jar'", McpClientSetup.quote("my dir/recaf.jar"));
		assertEquals("'it'\\''s'", McpClientSetup.quote("it's"));
		assertEquals("'a;rm -rf /'", McpClientSetup.quote("a;rm -rf /"));
	}

	@Test
	void locatesJavaAndFallsBackToAPlaceholderJar() {
		assertTrue(McpClientSetup.javaExecutable().contains("java"));
		assertFalse(McpClientSetup.recafJar().isBlank());
	}
}
