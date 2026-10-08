package software.coley.recaf.services.analysis.plugin;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link MinecraftPluginManifestRewriter}.
 */
class MinecraftPluginManifestRewriterTest {
	private static final Map<String, String> RENAMES = Map.of(
			"com/example/Main", "a/b",
			"com/example/Boot", "a/c",
			"com/example/Load", "a/d",
			"com/example/Outer$Inner", "a/E$F");

	@Test
	void rewritesMainAndKeepsEverythingElseByteForByte() {
		String yml = """
				# My plugin
				name: Example   # the name
				main: com.example.Main  # entry
				version: '1.0'
				commands:
				  main:
				    description: not a class key
				""";
		MinecraftPluginManifestRewriter.Result result = MinecraftPluginManifestRewriter.rewrite("plugin.yml", yml, RENAMES);

		assertTrue(result.isModified());
		assertEquals(yml.replace("main: com.example.Main  # entry", "main: a.b  # entry"), result.text());
		assertEquals(List.of(new MinecraftPluginManifestRewriter.Change("main", "com/example/Main", "a/b")), result.changes());
		assertTrue(result.unresolved().isEmpty());
	}

	@Test
	void preservesQuotingStyle() {
		assertEquals("main: \"a.b\"\n",
				MinecraftPluginManifestRewriter.rewrite("plugin.yml", "main: \"com.example.Main\"\n", RENAMES).text());
		assertEquals("main: 'a.b'\n",
				MinecraftPluginManifestRewriter.rewrite("plugin.yml", "main: 'com.example.Main'\n", RENAMES).text());
		assertEquals("main:   a.b\n",
				MinecraftPluginManifestRewriter.rewrite("plugin.yml", "main:   com.example.Main\n", RENAMES).text());
	}

	@Test
	void preservesWindowsLineEndings() {
		String yml = "name: X\r\nmain: com.example.Main\r\nversion: 1\r\n";
		assertEquals("name: X\r\nmain: a.b\r\nversion: 1\r\n",
				MinecraftPluginManifestRewriter.rewrite("plugin.yml", yml, RENAMES).text());
	}

	@Test
	void rewritesPaperBootstrapperAndLoader() {
		String yml = "main: com.example.Main\nbootstrapper: com.example.Boot\nloader: com.example.Load\n";
		MinecraftPluginManifestRewriter.Result result = MinecraftPluginManifestRewriter.rewrite("paper-plugin.yml", yml, RENAMES);

		assertEquals("main: a.b\nbootstrapper: a.c\nloader: a.d\n", result.text());
		assertEquals(3, result.changes().size());
		assertTrue(result.unresolved().isEmpty());
	}

	@Test
	void handlesNestedClassNames() {
		MinecraftPluginManifestRewriter.Result result =
				MinecraftPluginManifestRewriter.rewrite("plugin.yml", "main: com.example.Outer$Inner\n", RENAMES);
		assertEquals("main: a.E$F\n", result.text());
	}

	@Test
	void leavesUnrenamedClassesAlone() {
		String yml = "main: com.example.Other\n";
		MinecraftPluginManifestRewriter.Result result = MinecraftPluginManifestRewriter.rewrite("plugin.yml", yml, RENAMES);
		assertSame(yml, result.text());
		assertFalse(result.isModified());
		assertTrue(result.unresolved().isEmpty());
	}

	@Test
	void ignoresIndentedKeys() {
		// Only a top-level 'main' is the plugin main class. Indented ones belong to something else.
		String yml = "main: com.example.Other\nsomething:\n  main: com.example.Main\n";
		MinecraftPluginManifestRewriter.Result result = MinecraftPluginManifestRewriter.rewrite("plugin.yml", yml, RENAMES);
		assertSame(yml, result.text());
	}

	@Test
	void reportsReferencesItCouldNotUpdate() {
		// A layout the line-based edit does not understand. The name must not be left stale without saying so.
		String yml = "{name: X, main: com.example.Main}\n";
		MinecraftPluginManifestRewriter.Result result = MinecraftPluginManifestRewriter.rewrite("plugin.yml", yml, RENAMES);

		assertFalse(result.isModified());
		assertEquals(List.of("main"), result.unresolved());
	}

	@Test
	void reportsQuotedBlockScalarMain() {
		String yml = "main: >\n  com.example.Main\n";
		MinecraftPluginManifestRewriter.Result result = MinecraftPluginManifestRewriter.rewrite("plugin.yml", yml, RENAMES);

		assertFalse(result.isModified());
		assertEquals(List.of("main"), result.unresolved());
	}
}
