package software.coley.recaf.services.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import jakarta.annotation.Nonnull;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * Minimal JSON-RPC client for the MCP HTTP transport.
 */
public class McpTestClient {
	private final HttpClient client = HttpClient.newHttpClient();
	private final URI uri;
	private final String token;
	private int nextId = 1;

	public McpTestClient(int port, String token) {
		this.uri = URI.create("http://127.0.0.1:" + port + "/mcp");
		this.token = token;
	}

	public HttpResponse<String> post(String body, String token, String origin) throws IOException, InterruptedException {
		HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
				.header("Content-Type", "application/json")
				.header("Accept", "application/json, text/event-stream")
				.POST(HttpRequest.BodyPublishers.ofString(body));
		if (token != null)
			builder.header("Authorization", "Bearer " + token);
		if (origin != null)
			builder.header("Origin", origin);
		return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
	}

	@Nonnull
	public JsonObject request(String method, JsonObject params) throws IOException, InterruptedException {
		JsonObject message = new JsonObject();
		message.addProperty("jsonrpc", "2.0");
		message.addProperty("id", nextId++);
		message.addProperty("method", method);
		message.add("params", params);
		HttpResponse<String> response = post(message.toString(), token, null);
		if (response.statusCode() != 200)
			throw new IOException("HTTP " + response.statusCode() + ": " + response.body());
		return JsonParser.parseString(response.body()).getAsJsonObject();
	}

	public void initialize() throws IOException, InterruptedException {
		JsonObject params = new JsonObject();
		params.addProperty("protocolVersion", "2025-06-18");
		params.add("capabilities", new JsonObject());
		JsonObject info = new JsonObject();
		info.addProperty("name", "test");
		info.addProperty("version", "1");
		params.add("clientInfo", info);
		request("initialize", params);
	}

	@Nonnull
	public JsonArray listTools() throws IOException, InterruptedException {
		return request("tools/list", new JsonObject()).getAsJsonObject("result").getAsJsonArray("tools");
	}

	/**
	 * @return The {@code result} object of the call, which holds {@code content} and {@code isError}.
	 */
	@Nonnull
	public JsonObject call(String tool, JsonObject arguments) throws IOException, InterruptedException {
		JsonObject params = new JsonObject();
		params.addProperty("name", tool);
		params.add("arguments", arguments);
		JsonObject response = request("tools/call", params);
		if (response.has("error"))
			throw new IOException("JSON-RPC error: " + response.get("error"));
		return response.getAsJsonObject("result");
	}

	public static String text(JsonObject callResult) {
		return callResult.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString();
	}
}
