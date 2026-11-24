package software.coley.recaf.services.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import software.coley.recaf.test.TestBase;
import software.coley.recaf.test.TestClassUtils;
import software.coley.recaf.test.dummy.HelloWorld;
import software.coley.recaf.test.dummy.Inheritance;
import software.coley.recaf.test.dummy.StringConsumer;
import software.coley.recaf.test.dummy.StringConsumerUser;
import software.coley.recaf.util.io.ZipCreationUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises the MCP tools through the real protocol against a small workspace.
 */
@Execution(ExecutionMode.SAME_THREAD)
class McpToolsTest extends TestBase {
	private static final String PKG = "software/coley/recaf/test/dummy/";
	private static final String HELLO = PKG + "HelloWorld";
	private static final String CONSUMER = PKG + "StringConsumer";
	private static final String USER = PKG + "StringConsumerUser";
	private static McpServerService service;
	private static McpTestClient client;
	private static Path jar;
	@TempDir
	static Path dir;

	@BeforeAll
	static void start() throws Exception {
		service = recaf.get(McpServerService.class);
		service.startHttp(0, "t");
		client = new McpTestClient(service.getHttpPort(), "t");
		client.initialize();
		Map<String, byte[]> entries = new LinkedHashMap<>();
		for (Class<?> type : new Class<?>[]{HelloWorld.class, StringConsumer.class, StringConsumerUser.class,
				Inheritance.class, Inheritance.Edible.class, Inheritance.Red.class, Inheritance.Apple.class,
				Inheritance.AppleWithWorm.class, Inheritance.Grape.class})
			entries.put(type.getName().replace('.', '/') + ".class", TestClassUtils.fromRuntimeClass(type).getBytecode());
		entries.put("config/settings.txt", "mode=fast\nname=recaf".getBytes());
		jar = dir.resolve("sample.jar");
		Files.write(jar, ZipCreationUtils.createZip(entries));
	}

	@AfterAll
	static void stop() {
		service.stopHttp();
	}

	@BeforeEach
	void open() throws Exception {
		ok("workspace_open", args("path", jar.toString(), "force", true));
	}

	@Test
	void listsAndDescribesClasses() throws Exception {
		JsonObject hello = json(ok("class_list", args("filter", "HelloW")));
		assertEquals(1, hello.get("total").getAsInt());
		assertEquals(HELLO, hello.getAsJsonArray("items").get(0).getAsJsonObject().get("name").getAsString());

		JsonObject interfaces = json(ok("class_list", args("kind", "interface")));
		assertEquals(2, interfaces.get("total").getAsInt());

		JsonObject children = json(ok("class_list", args("extends", PKG + "Inheritance$Apple")));
		assertEquals(1, children.get("total").getAsInt());

		JsonObject paged = json(ok("class_list", args("limit", 3)));
		assertEquals(3, paged.get("returned").getAsInt());
		assertTrue(paged.get("truncated").getAsBoolean());

		JsonObject info = json(ok("class_info", args("name", HELLO.replace('/', '.'))));
		assertEquals("class", info.get("kind").getAsString());
		assertTrue(info.getAsJsonArray("methods").toString().contains("main"));
	}

	@Test
	void reportsMissingClassesWithSuggestions() throws Exception {
		String message = fails("class_info", args("name", "other/pkg/HelloWorld"));
		assertTrue(message.contains(HELLO), message);
	}

	@Test
	void decompilesAndDisassembles() throws Exception {
		JsonObject source = json(ok("class_decompile", args("name", HELLO)));
		assertTrue(source.get("text").getAsString().contains("Hello world"));

		String whole = json(ok("class_disassemble", args("name", HELLO))).get("text").getAsString();
		String method = json(ok("class_disassemble", args("name", HELLO, "member", "main"))).get("text").getAsString();
		assertTrue(whole.contains("Hello world"));
		assertTrue(method.length() < whole.length());
	}

	@Test
	void assemblingEditsTheClassAndBadInputChangesNothing() throws Exception {
		String assembly = json(ok("class_disassemble", args("name", HELLO))).get("text").getAsString();
		JsonObject good = json(ok("class_assemble", args("name", HELLO, "source", assembly.replace("Hello world", "Goodbye"))));
		assertTrue(good.get("assembled").getAsBoolean(), good.toString());
		assertTrue(json(ok("class_decompile", args("name", HELLO))).get("text").getAsString().contains("Goodbye"));

		JsonObject bad = json(ok("class_assemble", args("name", HELLO, "source", "this is not assembly")));
		assertFalse(bad.get("assembled").getAsBoolean());
		assertFalse(bad.getAsJsonArray("diagnostics").isEmpty());
		assertTrue(json(ok("class_decompile", args("name", HELLO))).get("text").getAsString().contains("Goodbye"));

		String renamed = assembly.replace(HELLO, PKG + "Other");
		assertTrue(fails("class_assemble", args("name", HELLO, "source", renamed)).contains("cannot change"));
	}

	@Test
	void searches() throws Exception {
		assertEquals(1, json(ok("search_strings", args("query", "Hello"))).get("total").getAsInt());
		assertEquals(1, json(ok("search_strings", args("query", "hel+o w", "mode", "regex", "ignore_case", true)))
				.get("total").getAsInt());
		assertTrue(fails("search_strings", args("query", "(", "mode", "regex")).contains("Invalid regex"));
		assertEquals(0, json(ok("search_strings", args("query", "no such text anywhere"))).get("total").getAsInt());

		JsonObject refs = json(ok("search_references", args("owner", CONSUMER)));
		assertTrue(refs.getAsJsonArray("items").toString().contains(USER), refs.toString());

		JsonObject declarations = json(ok("search_declarations", args("name", "main", "mode", "equals")));
		assertTrue(declarations.get("total").getAsInt() >= 2);

		JsonObject numbers = json(ok("search_numbers", args("value", "0", "mode", "gte")));
		assertTrue(numbers.get("total").getAsInt() >= 1);
		assertTrue(fails("search_numbers", args("value", "abc")).contains("not a number"));
	}

	@Test
	void graphs() throws Exception {
		JsonObject inheritance = json(ok("inheritance_info", args("name", PKG + "Inheritance$AppleWithWorm")));
		assertTrue(inheritance.getAsJsonArray("parents").toString().contains("Inheritance$Apple"));
		assertTrue(inheritance.getAsJsonArray("allParents").toString().contains("Inheritance$Edible"));

		assertTrue(json(ok("inheritance_is_assignable", args("to", PKG + "Inheritance$Edible",
				"from", PKG + "Inheritance$AppleWithWorm"))).get("assignable").getAsBoolean());

		JsonObject callers = json(ok("callgraph_callers", args("class", CONSUMER, "method", "accept", "descriptor", "(Ljava/lang/String;)V")));
		assertTrue(callers.getAsJsonArray("callers").toString().contains("StringConsumerUser"), callers.toString());
		JsonObject callees = json(ok("callgraph_callees", args("class", USER, "method", "main")));
		assertTrue(callees.getAsJsonArray("callees").toString().contains("StringConsumer"), callees.toString());
	}

	@Test
	void filesCanBeReadWrittenAndDeleted() throws Exception {
		assertEquals(1, json(ok("file_list", args("filter", "settings"))).get("total").getAsInt());
		assertTrue(json(ok("file_read", args("path", "config/settings.txt"))).get("content").getAsString().contains("mode=fast"));

		ok("file_write", args("path", "config/new.txt", "text", "hello"));
		assertEquals("hello", json(ok("file_read", args("path", "config/new.txt"))).get("content").getAsString());
		ok("file_write", args("path", "config/new.txt", "text", "changed"));
		assertEquals("changed", json(ok("file_read", args("path", "config/new.txt"))).get("content").getAsString());
		ok("file_delete", args("path", "config/new.txt"));
		assertTrue(fails("file_read", args("path", "config/new.txt")).contains("No file"));
		assertTrue(fails("file_write", args("path", "x")).contains("Provide"));
	}

	@Test
	void renamingUpdatesReferences() throws Exception {
		ok("rename_class", args("name", CONSUMER, "new_name", PKG + "Renamed"));
		assertEquals(0, json(ok("class_list", args("filter", "re:/StringConsumer$"))).get("total").getAsInt());
		assertEquals(1, json(ok("class_list", args("filter", "re:/Renamed$"))).get("total").getAsInt());
		assertTrue(json(ok("class_decompile", args("name", USER))).get("text").getAsString().contains("Renamed"));
		assertTrue(fails("rename_class", args("name", USER, "new_name", PKG + "Renamed")).contains("already exists"));

		ok("rename_member", args("class", PKG + "Renamed", "name", "accept", "descriptor", "(Ljava/lang/String;)V", "new_name", "consume"));
		assertTrue(json(ok("class_info", args("name", PKG + "Renamed"))).getAsJsonArray("methods").toString().contains("consume"));
	}

	@Test
	void compilingJavaSourceReplacesTheClass() throws Exception {
		JsonObject result = json(ok("java_compile", args("name", HELLO, "source", """
				package software.coley.recaf.test.dummy;
				public class HelloWorld {
					public static void main(String[] args) { System.out.println("compiled by mcp"); }
				}""")));
		assertTrue(result.get("compiled").getAsBoolean(), result.toString());
		assertTrue(json(ok("class_decompile", args("name", HELLO))).get("text").getAsString().contains("compiled by mcp"));

		JsonObject broken = json(ok("java_compile", args("name", HELLO, "source", "package x; class HelloWorld { void f( }")));
		assertFalse(broken.get("compiled").getAsBoolean());
		assertFalse(broken.getAsJsonArray("diagnostics").isEmpty());
	}

	@Test
	void transformersAreListedAndPreviewDoesNotChangeAnything() throws Exception {
		JsonArray transformers = JsonParser.parseString(text(ok("transform_list", new JsonObject()))).getAsJsonArray();
		assertFalse(transformers.isEmpty());
		String identifier = transformers.get(0).getAsJsonObject().get("identifier").getAsString();
		String before = json(ok("class_disassemble", args("name", HELLO))).get("text").getAsString();
		JsonObject run = new JsonObject();
		JsonArray ids = new JsonArray();
		ids.add(identifier);
		run.add("transformers", ids);
		run.addProperty("preview", true);
		JsonObject result = json(ok("transform_run", run));
		assertFalse(result.get("applied").getAsBoolean());
		assertEquals(before, json(ok("class_disassemble", args("name", HELLO))).get("text").getAsString());
		JsonObject unknown = new JsonObject();
		JsonArray names = new JsonArray();
		names.add("nonsense");
		unknown.add("transformers", names);
		assertTrue(fails("transform_run", unknown).contains("Unknown transformer"));
	}

	@Test
	void analysisToolsRunOnAPlainJar() throws Exception {
		assertTrue(text(ok("analysis_entry_points", new JsonObject())).contains("HelloWorld"));
		ok("analysis_anti_reversal", new JsonObject());
		ok("analysis_hashes", new JsonObject());
		ok("analysis_areas", new JsonObject());
		ok("analysis_bytecode_hotspots", new JsonObject());
		JsonObject scan = json(ok("plugin_security_scan", new JsonObject()));
		assertEquals(0, scan.get("total").getAsInt());
		ok("plugin_manifest", new JsonObject());
	}

	@Test
	void cryptoToolsDecode() throws Exception {
		java.io.ByteArrayOutputStream gzipped = new java.io.ByteArrayOutputStream();
		try (java.util.zip.GZIPOutputStream out = new java.util.zip.GZIPOutputStream(gzipped)) {
			out.write("The quick brown fox jumps over the lazy dog, twice over.".getBytes());
		}
		String layered = java.util.Base64.getEncoder().encodeToString(gzipped.toByteArray());
		JsonObject decoded = json(ok("crypto_auto_decode", args("input", layered)));
		assertTrue(decoded.get("found").getAsBoolean(), decoded.toString());
		assertTrue(decoded.get("preview").getAsString().contains("quick brown fox"));
		JsonObject plain = json(ok("crypto_codec", args("input", "SGVsbG8=", "codec", "BASE64")));
		assertTrue(plain.get("preview").getAsString().contains("Hello"));
		assertTrue(json(ok("crypto_hash", args("input", "abc", "algorithm", "MD5"))).get("hash").getAsString()
				.startsWith("900150983cd24fb0d6963f7d28e17f72"));
		assertTrue(fails("crypto_codec", args("input", "!!", "codec", "BASE64", "input_format", "HEX")).length() > 0);
	}

	@Test
	void exportRoundTripsEdits() throws Exception {
		ok("file_write", args("path", "marker.txt", "text", "edited"));
		Path out = dir.resolve("out/exported.jar");
		ok("workspace_export", args("path", out.toString()));
		assertTrue(Files.size(out) > 0);
		ok("workspace_open", args("path", out.toString(), "force", true));
		assertEquals("edited", json(ok("file_read", args("path", "marker.txt"))).get("content").getAsString());
	}

	@Test
	void decompileExportWritesSourceFiles() throws Exception {
		Path out = dir.resolve("src");
		JsonObject result = json(ok("decompile_export", args("directory", dir.resolve(".").resolve("src").toString())));
		assertEquals(out.toString(), result.get("directory").getAsString());
		assertTrue(result.get("filesWritten").getAsInt() >= 3, result.toString());
		assertTrue(Files.readString(out.resolve(HELLO + ".java")).contains("Hello world"));
	}

	@Test
	void classDiffShowsChanges() throws Exception {
		JsonObject diff = json(ok("class_diff", args("a", HELLO, "b", CONSUMER)));
		assertFalse(diff.get("identical").getAsBoolean());
		assertTrue(json(ok("class_diff", args("a", HELLO, "b", HELLO))).get("identical").getAsBoolean());
	}

	@Test
	void executeTierIsHiddenAndRefused() throws Exception {
		for (JsonElement tool : client.listTools())
			assertNotEquals("script_run", tool.getAsJsonObject().get("name").getAsString());
		JsonObject response = client.request("tools/call", paramsFor("script_run", args("source", "return;")));
		assertTrue(response.has("error") || response.getAsJsonObject("result").get("isError").getAsBoolean());
	}

	@Test
	void toolsReportABadWorkspaceState() throws Exception {
		ok("workspace_close", args("force", true));
		assertTrue(fails("class_list", new JsonObject()).contains("No workspace is open"));
		assertTrue(fails("workspace_info", new JsonObject()).contains("No workspace is open"));
	}

	static JsonObject paramsFor(String tool, JsonObject arguments) {
		JsonObject params = new JsonObject();
		params.addProperty("name", tool);
		params.add("arguments", arguments);
		return params;
	}

	static JsonObject ok(String tool, JsonObject arguments) throws IOException, InterruptedException {
		JsonObject result = client.call(tool, arguments);
		assertFalse(result.has("isError") && result.get("isError").getAsBoolean(),
				tool + " failed: " + McpTestClient.text(result));
		return result;
	}

	static String fails(String tool, JsonObject arguments) throws IOException, InterruptedException {
		JsonObject result = client.call(tool, arguments);
		assertTrue(result.has("isError") && result.get("isError").getAsBoolean(), tool + " should have failed");
		return McpTestClient.text(result);
	}

	static String text(JsonObject result) {
		return McpTestClient.text(result);
	}

	static JsonObject json(JsonObject result) {
		return JsonParser.parseString(text(result)).getAsJsonObject();
	}

	static JsonObject args(Object... keysAndValues) {
		JsonObject object = new JsonObject();
		for (int i = 0; i < keysAndValues.length; i += 2) {
			String key = (String) keysAndValues[i];
			Object value = keysAndValues[i + 1];
			if (value instanceof Number n) object.addProperty(key, n);
			else if (value instanceof Boolean b) object.addProperty(key, b);
			else object.addProperty(key, String.valueOf(value));
		}
		return object;
	}
}
