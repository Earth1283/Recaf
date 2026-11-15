package software.coley.recaf.services.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import software.coley.recaf.test.TestBase;
import software.coley.recaf.test.TestClassUtils;
import software.coley.recaf.test.dummy.ClassWithConstructor;
import software.coley.recaf.test.dummy.StringConsumer;
import software.coley.recaf.util.io.ZipCreationUtils;

import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises the HTTP transport and tool plumbing end to end.
 */
@Execution(ExecutionMode.SAME_THREAD)
class McpServerServiceTest extends TestBase {
	private static final String TOKEN = "test-token";
	private static McpServerService service;
	private static McpTestClient client;
	@TempDir
	static Path dir;

	@BeforeAll
	static void start() throws Exception {
		service = recaf.get(McpServerService.class);
		service.startHttp(0, TOKEN);
		client = new McpTestClient(service.getHttpPort(), TOKEN);
		client.initialize();
	}

	@AfterAll
	static void stop() {
		service.stopHttp();
	}

	@Test
	void configToggleStartsAndStopsTheHttpServer() {
		McpServerConfig config = recaf.get(McpServerConfig.class);
		service.stopHttp();
		assertFalse(service.isHttpRunning());
		config.getHttpPort().setValue(0);
		config.getHttpEnabled().setValue(true);
		try {
			assertTrue(service.isHttpRunning());
			assertTrue(service.getHttpPort() > 0);
			assertNotNull(service.getHttpToken());
			config.getHttpEnabled().setValue(false);
			assertFalse(service.isHttpRunning());
		} finally {
			config.getHttpEnabled().setValue(false);
			service.startHttp(0, TOKEN);
			client = new McpTestClient(service.getHttpPort(), TOKEN);
		}
	}

	@Test
	void togglingTiersChangesTheExposedToolsImmediately() throws Exception {
		McpServerConfig config = recaf.get(McpServerConfig.class);
		try {
			assertFalse(toolNames().contains("script_run"));
			config.getAllowExecute().setValue(true);
			assertTrue(toolNames().contains("script_run"));
			assertTrue(toolNames().contains("attach_list"));

			config.getAllowWrite().setValue(false);
			assertFalse(toolNames().contains("workspace_export"));
			assertTrue(toolNames().contains("workspace_info"));
			JsonObject params = new JsonObject();
			params.addProperty("name", "class_delete");
			params.add("arguments", obj("name", "x"));
			assertTrue(client.request("tools/call", params).has("error"), "a hidden tool cannot be called");
		} finally {
			config.getAllowExecute().setValue(false);
			config.getAllowWrite().setValue(true);
		}
		assertFalse(toolNames().contains("script_run"));
		assertTrue(toolNames().contains("workspace_export"));
	}

	@Test
	void callsAreRecordedAndReportedToListeners() throws Exception {
		service.clearRecentCalls();
		java.util.List<McpCall> heard = new java.util.concurrent.CopyOnWriteArrayList<>();
		service.addCallListener(heard::add);
		try {
			client.call("workspace_info", new JsonObject());
			client.call("class_info", obj("name", "does/not/Exist"));
		} finally {
			service.removeCallListener(heard::add);
		}
		java.util.List<McpCall> recent = service.getRecentCalls();
		assertEquals("class_info", recent.get(0).tool(), "newest first");
		assertFalse(recent.get(0).success());
		assertTrue(recent.get(0).summary().contains("does/not/Exist"));
		assertEquals("workspace_info", recent.get(1).tool());
		assertEquals(2, heard.size());
		service.clearRecentCalls();
		assertTrue(service.getRecentCalls().isEmpty());
	}

	@Test
	void statusListenersHearTransportChanges() {
		java.util.concurrent.atomic.AtomicInteger heard = new java.util.concurrent.atomic.AtomicInteger();
		Runnable listener = heard::incrementAndGet;
		service.addStatusListener(listener);
		try {
			service.stopHttp();
			service.startHttp(0, TOKEN);
			client = new McpTestClient(service.getHttpPort(), TOKEN);
			assertTrue(heard.get() >= 2);
		} finally {
			service.removeStatusListener(listener);
		}
	}

	private static java.util.Set<String> toolNames() throws Exception {
		java.util.Set<String> names = new java.util.HashSet<>();
		for (JsonElement tool : client.listTools())
			names.add(tool.getAsJsonObject().get("name").getAsString());
		return names;
	}

	@Test
	void rejectsMissingOrWrongToken() throws Exception {
		McpTestClient bad = new McpTestClient(service.getHttpPort(), "wrong");
		assertEquals(401, bad.post("{}", null, null).statusCode());
		assertEquals(401, bad.post("{}", "wrong", null).statusCode());
	}

	@Test
	void rejectsForeignOrigin() throws Exception {
		HttpResponse<String> response = client.post("{}", TOKEN, "http://evil.example");
		assertEquals(403, response.statusCode());
	}

	@Test
	void listsToolsOfAllowedTiersOnly() throws Exception {
		JsonArray tools = client.listTools();
		Map<String, JsonObject> byName = new LinkedHashMap<>();
		for (JsonElement tool : tools)
			byName.put(tool.getAsJsonObject().get("name").getAsString(), tool.getAsJsonObject());
		assertTrue(byName.containsKey("workspace_open"));
		assertTrue(byName.containsKey("workspace_export"), "write tier is on by default");
		JsonObject annotations = byName.get("workspace_info").getAsJsonObject("annotations");
		assertTrue(annotations.get("readOnlyHint").getAsBoolean());
		assertFalse(byName.containsKey("script_run"), "execute tier is off by default");
	}

	@Test
	void opensWorkspaceAndReportsErrors() throws Exception {
		JsonObject missing = client.call("workspace_open", obj("path", dir.resolve("nope.jar").toString()));
		assertTrue(missing.get("isError").getAsBoolean());
		assertTrue(McpTestClient.text(missing).contains("No such file"));

		Path jar = dir.resolve("sample.jar");
		Files.write(jar, ZipCreationUtils.createZip(Map.of(
				"ClassWithConstructor.class", TestClassUtils.fromRuntimeClass(ClassWithConstructor.class).getBytecode(),
				"StringConsumer.class", TestClassUtils.fromRuntimeClass(StringConsumer.class).getBytecode(),
				"readme.txt", "hello".getBytes())));
		JsonObject opened = client.call("workspace_open", obj("path", jar.toString()));
		assertFalse(opened.get("isError").getAsBoolean(), McpTestClient.text(opened));
		JsonObject info = JsonParser.parseString(McpTestClient.text(opened)).getAsJsonObject();
		assertEquals(2, info.get("totalClasses").getAsInt());
	}

	static JsonObject obj(String key, String value) {
		JsonObject object = new JsonObject();
		object.addProperty(key, value);
		return object;
	}
}
