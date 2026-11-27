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
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
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

	@Test
	void packageListAndEnrichedClassInfoAndLinePaging() throws Exception {
		JsonObject packages = json(ok("package_list", args("depth", 3, "sort", "classes")));
		assertTrue(packages.get("total").getAsInt() >= 1);
		JsonObject firstPkg = packages.getAsJsonArray("items").get(0).getAsJsonObject();
		assertEquals("software/coley/recaf", firstPkg.get("package").getAsString());
		assertEquals(9, firstPkg.get("classes").getAsInt());

		JsonObject filteredPkg = json(ok("package_list", args("prefix", "software/coley/recaf/test")));
		assertEquals(1, filteredPkg.get("total").getAsInt());

		JsonObject info = json(ok("class_info", args("name", HELLO)));
		JsonArray methods = info.getAsJsonArray("methods");
		assertTrue(methods.get(0).getAsJsonObject().has("instructions"));

		JsonObject fullDecompile = json(ok("class_decompile", args("name", HELLO)));
		int totalLines = fullDecompile.get("totalLines").getAsInt();
		assertTrue(totalLines >= 4);

		JsonObject sliced = json(ok("class_decompile", args("name", HELLO, "start_line", 2, "max_lines", 2, "line_numbers", true)));
		assertEquals(2, sliced.get("startLine").getAsInt());
		assertEquals(3, sliced.get("endLine").getAsInt());
		assertTrue(sliced.get("truncated").getAsBoolean());
		assertTrue(sliced.get("text").getAsString().contains("2: "));

		JsonObject disasm = json(ok("class_disassemble", args("name", HELLO, "start_line", 1, "max_lines", 3)));
		assertTrue(disasm.get("totalLines").getAsInt() > 3);
		assertTrue(disasm.get("truncated").getAsBoolean());
		assertEquals(1, disasm.get("startLine").getAsInt());
		assertEquals(3, disasm.get("endLine").getAsInt());
	}

	@Test
	void searchFiltersAndOwnerlessReferences() throws Exception {
		JsonObject scoped = json(ok("search_strings", args("query", "mode=fast", "scope", "files")));
		assertEquals(1, scoped.get("total").getAsInt());
		assertEquals(0, json(ok("search_strings", args("query", "mode=fast", "scope", "classes"))).get("total").getAsInt());

		JsonObject pkgFiltered = json(ok("search_strings", args("query", "Hello", "package", "com/other")));
		assertEquals(0, pkgFiltered.get("total").getAsInt());
		assertEquals(1, json(ok("search_strings", args("query", "Hello", "package", "software/coley/recaf/test/dummy"))).get("total").getAsInt());

		JsonObject byNameOnly = json(ok("search_references", args("name", "accept", "mode", "equals")));
		assertTrue(byNameOnly.getAsJsonArray("items").toString().contains(USER), byNameOnly.toString());
		assertTrue(fails("search_references", new JsonObject()).contains("Provide at least one"));
	}

	@Test
	void antiReversalHotspotsAndObfuscationAreStructured() throws Exception {
		String anti = text(ok("analysis_anti_reversal", new JsonObject()));
		assertFalse(anti.contains("TransformationApplier$"), anti);
		JsonObject antiJson = JsonParser.parseString(anti).getAsJsonObject();
		assertTrue(antiJson.getAsJsonObject("transformer-impact-analysis").has("jvm"));

		JsonObject hotspots = json(ok("analysis_bytecode_hotspots", new JsonObject()));
		assertTrue(hotspots.has("totalCallSites"));
		assertTrue(hotspots.has("totalBootstraps"));
		assertTrue(hotspots.has("nativeLibraries"));
		assertTrue(hotspots.has("nativeMethods"));
		JsonObject firstMethod = hotspots.getAsJsonArray("items").get(0).getAsJsonObject();
		assertTrue(firstMethod.has("instructions"));
		assertTrue(firstMethod.has("branches"));
		assertTrue(firstMethod.has("score"));

		JsonObject obf = json(ok("analysis_obfuscation", args("in_depth_control_flow", true)));
		assertTrue(obf.has("fingerprints"));
		assertTrue(obf.has("technologies"));
		assertTrue(obf.has("suggestedPresets"));
	}

	@Test
	void methodBytecodeAnalyzeCallSitesAndEvaluateWork() throws Exception {
		JsonObject raw = json(ok("method_bytecode", args("class", USER, "method", "main", "section", "all")));
		assertTrue(raw.has("diagnostics"));
		assertTrue(raw.getAsJsonObject("instructions").get("text").getAsString().contains("StringConsumer"));

		JsonObject analyzed = json(ok("method_analyze", args("class", USER, "method", "main", "include_instructions", true, "slice_from", 0)));
		assertTrue(analyzed.has("summary"));
		assertTrue(analyzed.getAsJsonObject("summary").get("reachableInstructions").getAsInt() > 0);
		assertTrue(analyzed.has("slice"));
		assertTrue(analyzed.getAsJsonObject("instructions").get("total").getAsInt() > 0);

		// Add a class with a constant-input static string helper to test bytecode_call_sites & bytecode_evaluate
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "demo/HelperUser", null, "java/lang/Object", null);
		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "greet", "()Ljava/lang/String;", null, null);
		mv.visitCode();
		mv.visitLdcInsn("ifmmp");
		mv.visitMethodInsn(Opcodes.INVOKESTATIC, "demo/HelperUser", "shift", "(Ljava/lang/String;)Ljava/lang/String;", false);
		mv.visitInsn(Opcodes.ARETURN);
		mv.visitMaxs(0, 0);
		mv.visitEnd();

		MethodVisitor helper = cw.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "shift", "(Ljava/lang/String;)Ljava/lang/String;", null, null);
		helper.visitCode();
		helper.visitVarInsn(Opcodes.ALOAD, 0);
		helper.visitInsn(Opcodes.ARETURN);
		helper.visitMaxs(0, 0);
		helper.visitEnd();
		cw.visitEnd();

		ok("class_put_bytes", args("data", java.util.Base64.getEncoder().encodeToString(cw.toByteArray())));

		JsonObject sites = json(ok("bytecode_call_sites", args("class", "demo/HelperUser", "kind", "string_call")));
		assertEquals(1, sites.get("total").getAsInt());
		int insn = sites.getAsJsonArray("items").get(0).getAsJsonObject().get("instruction").getAsInt();

		JsonObject singleEval = json(ok("bytecode_evaluate", args("class", "demo/HelperUser", "method", "greet", "instruction", insn)));
		assertEquals("VALUE", singleEval.get("status").getAsString());
		assertEquals("ifmmp", singleEval.get("value").getAsString());

		JsonObject allEval = json(ok("bytecode_evaluate", args("class", "demo/HelperUser", "method", "greet")));
		assertEquals(1, allEval.get("evaluated").getAsInt());
		assertEquals("ifmmp", allEval.getAsJsonArray("results").get(0).getAsJsonObject().get("value").getAsString());
	}

	@Test
	void jarCompareCrossResourceDiffCallgraphPathPresetsAndReadableCopy() throws Exception {
		// Create a modified v2 jar with a changed HelloWorld and a new text resource
		ok("java_compile", args("name", HELLO, "source", """
				package software.coley.recaf.test.dummy;
				public class HelloWorld {
					public static final int VERSION = 2;
					public static void main(String[] args) { System.out.println("Hello v2"); }
				}"""));
		JsonObject v2Info = json(ok("class_info", args("name", HELLO)));
		assertEquals(2, v2Info.getAsJsonArray("fields").get(0).getAsJsonObject().get("value").getAsInt());
		ok("file_write", args("path", "config/settings.txt", "text", "mode=slow\nname=recaf"));
		Path jarV2 = dir.resolve("sample-v2.jar");
		ok("workspace_export", args("path", jarV2.toString()));

		// Compare jars directly on disk
		JsonObject cmp = json(ok("jar_compare", args("before", jar.toString(), "after", jarV2.toString())));
		assertTrue(cmp.getAsJsonObject("summary").get("totalChanges").getAsInt() >= 2);
		JsonObject bcDiff = json(ok("jar_compare_diff", args("before", jar.toString(), "after", jarV2.toString(), "path", HELLO)));
		assertFalse(bcDiff.get("identical").getAsBoolean());
		JsonObject srcDiff = json(ok("jar_compare_diff", args("before", jar.toString(), "after", jarV2.toString(), "path", HELLO, "view", "source")));
		assertFalse(srcDiff.get("identical").getAsBoolean());
		assertTrue(srcDiff.get("text").getAsString().contains("Hello v2"));

		// Cross-resource class_diff (v1 primary + v2 library)
		JsonArray libs = new JsonArray();
		libs.add(jarV2.toString());
		ok("workspace_open", args("path", jar.toString(), "libraries", libs, "force", true));
		JsonObject crossDiff = json(ok("class_diff", args("a", HELLO, "b", HELLO,
				"resource_a", "primary:sample.jar", "resource_b", "library:sample-v2.jar")));
		assertFalse(crossDiff.get("identical").getAsBoolean());
		assertTrue(crossDiff.get("text").getAsString().contains("Hello v2"));

		// callgraph_path
		JsonObject path = json(ok("callgraph_path", args("to_class", CONSUMER, "to_method", "accept", "to_descriptor", "(Ljava/lang/String;)V")));
		assertTrue(path.get("found").getAsInt() >= 1, path.toString());

		// Built-in presets & scoped transform_run
		JsonObject presets = json(ok("transform_presets", new JsonObject()));
		assertTrue(presets.has("control-flow"));
		assertTrue(presets.has("obfuscated-plugin"));
		assertTrue(presets.has("skidfuscator"));
		assertTrue(presets.has("bitwise"));

		JsonObject previewRun = json(ok("transform_run", args("preset", "control-flow", "package", "software/coley/recaf/test/dummy", "preview", true)));
		assertFalse(previewRun.get("applied").getAsBoolean());
		assertTrue(previewRun.has("modifiedClasses"));

		// workspace_readable_copy
		JsonObject copy = json(ok("workspace_readable_copy", args("force", true)));
		assertTrue(copy.has("report"));
		assertTrue(copy.get("report").getAsString().contains("READABLE ANALYSIS COPY"));
		assertFalse(json(ok("workspace_info", new JsonObject())).getAsJsonArray("libraries").isEmpty());
	}

	@Test
	void classSummaryGrepFileTypesResourceFiltersAndHints() throws Exception {
		// class_summary on HelloWorld & StringConsumerUser
		JsonObject helloSum = json(ok("class_summary", args("name", HELLO)));
		assertTrue(helloSum.get("totalStrings").getAsInt() >= 1);
		assertTrue(helloSum.getAsJsonArray("strings").toString().contains("Hello world"));
		JsonObject sum = json(ok("class_summary", args("name", USER)));
		assertTrue(sum.getAsJsonArray("externalCalls").toString().contains("StringConsumer"));
		assertTrue(sum.getAsJsonArray("referencedClasses").toString().contains("StringConsumer"));

		// class_decompile & class_disassemble grep
		JsonObject grepSrc = json(ok("class_decompile", args("name", HELLO, "grep", "Hello world", "context", 1)));
		assertEquals(1, grepSrc.get("matches").getAsInt());
		assertTrue(grepSrc.get("text").getAsString().contains("Hello world"));
		JsonObject grepAsm = json(ok("class_disassemble", args("name", HELLO, "grep", "ldc", "context", 1)));
		assertTrue(grepAsm.get("matches").getAsInt() >= 1);

		// class_info member_filter / include_fields / include_methods
		JsonObject filteredInfo = json(ok("class_info", args("name", USER, "member_filter", "main", "include_fields", false)));
		assertFalse(filteredInfo.has("fields"));
		assertEquals(1, filteredInfo.getAsJsonArray("methods").size());
		assertTrue(filteredInfo.get("methodCount").getAsInt() >= 1);

		// file_list binary type, resource property, file_read grep
		ok("file_write", args("path", "data/blob.bin", "base64", java.util.Base64.getEncoder().encodeToString(new byte[]{0, 1, 2, 3})));
		JsonObject files = json(ok("file_list", new JsonObject()));
		assertTrue(files.getAsJsonArray("items").toString().contains("\"type\":\"binary\""));
		assertTrue(files.getAsJsonArray("items").toString().contains("\"resource\":\"primary:sample.jar\""));
		JsonObject fileGrep = json(ok("file_read", args("path", "config/settings.txt", "grep", "fast", "context", 0)));
		assertEquals(1, fileGrep.get("matches").getAsInt());
		assertTrue(fileGrep.get("text").getAsString().contains("1: mode=fast"));

		// hotspots bootstrap controls
		JsonObject hs = json(ok("analysis_bytecode_hotspots", args("bootstrap_limit", 0, "package", "software/coley/recaf/test/dummy")));
		assertTrue(hs.has("customBootstraps"));
		assertEquals(0, hs.getAsJsonArray("bootstraps").size());

		// callgraph_path without to_method & rootCallers when unreachable
		JsonObject noMethodPath = json(ok("callgraph_path", args("to_class", CONSUMER)));
		assertTrue(noMethodPath.get("found").getAsInt() >= 1, noMethodPath.toString());

		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "demo/Isolated", null, "java/lang/Object", null);
		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "rootEntry", "()V", null, null);
		mv.visitCode();
		mv.visitMethodInsn(Opcodes.INVOKESTATIC, "demo/Isolated", "sink", "()V", false);
		mv.visitInsn(Opcodes.RETURN);
		mv.visitMaxs(0, 0);
		mv.visitEnd();
		MethodVisitor sink = cw.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "sink", "()V", null, null);
		sink.visitCode();
		sink.visitInsn(Opcodes.RETURN);
		sink.visitMaxs(0, 0);
		sink.visitEnd();
		cw.visitEnd();
		ok("class_put_bytes", args("data", java.util.Base64.getEncoder().encodeToString(cw.toByteArray())));

		JsonObject unreachable = json(ok("callgraph_path", args("to_class", "demo/Isolated", "to_method", "sink")));
		assertEquals(0, unreachable.get("found").getAsInt());
		assertTrue(unreachable.has("rootCallers"));
		assertTrue(unreachable.getAsJsonArray("rootCallers").toString().contains("rootEntry"));

		// resource filter on class_list / package_list / file_list & jar_compare exclude/package
		ok("java_compile", args("name", HELLO, "source", """
				package software.coley.recaf.test.dummy;
				public class HelloWorld {
					public static void main(String[] args) { System.out.println("v2"); }
				}"""));
		Path jarV2 = dir.resolve("v2-lib.jar");
		ok("workspace_export", args("path", jarV2.toString()));
		JsonArray libs = new JsonArray();
		libs.add(jarV2.toString());
		ok("workspace_open", args("path", jar.toString(), "libraries", libs, "force", true));

		JsonObject libClasses = json(ok("class_list", args("resource", "library:v2-lib.jar")));
		assertTrue(libClasses.get("total").getAsInt() >= 9);
		JsonObject libPkgs = json(ok("package_list", args("resource", "library:v2-lib.jar")));
		assertTrue(libPkgs.get("total").getAsInt() >= 1);
		JsonObject libFiles = json(ok("file_list", args("resource", "library:v2-lib.jar")));
		assertEquals(2, libFiles.get("total").getAsInt());

		JsonObject cmpExcluded = json(ok("jar_compare", args("before", jar.toString(), "after", jarV2.toString(),
				"exclude", "re:(settings\\.txt|blob\\.bin|Isolated)")));
		assertEquals(1, cmpExcluded.get("total").getAsInt());
		JsonObject cmpNoMembers = json(ok("jar_compare", args("before", jar.toString(), "after", jarV2.toString(),
				"package", "software/coley/recaf/test/dummy", "include_members", false)));
		assertEquals(1, cmpNoMembers.get("total").getAsInt());
		JsonObject cmpEntry = cmpNoMembers.getAsJsonArray("items").get(0).getAsJsonObject();
		assertTrue(cmpEntry.has("memberCount"));
		assertFalse(cmpEntry.has("members"));
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
			if (value instanceof JsonElement e) object.add(key, e);
			else if (value instanceof Number n) object.addProperty(key, n);
			else if (value instanceof Boolean b) object.addProperty(key, b);
			else object.addProperty(key, String.valueOf(value));
		}
		return object;
	}
}
