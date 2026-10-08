package software.coley.recaf.services.analysis.plugin;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link MinecraftPluginManifestParser}.
 */
class MinecraftPluginManifestParserTest {
	@Test
	void parsesBukkitManifest() {
		String yml = """
				name: Example
				version: 1.2.3
				main: com.example.ExamplePlugin
				api-version: '1.21'
				depend: [Vault, PlaceholderAPI]
				softdepend:
				  - WorldEdit
				loadbefore: Other
				commands:
				  spawn:
				    description: Go to spawn
				  home: {}
				permissions:
				  example.admin:
				    default: op
				""";
		MinecraftPluginManifest manifest = MinecraftPluginManifestParser.parse("plugin.yml", yml).orElseThrow();

		assertEquals(MinecraftPluginPlatform.BUKKIT, manifest.platform());
		assertEquals("Example", manifest.name());
		assertEquals("1.2.3", manifest.version());
		assertEquals("com/example/ExamplePlugin", manifest.mainClass());
		assertEquals("1.21", manifest.apiVersion());
		assertEquals(List.of("Vault", "PlaceholderAPI"), manifest.dependencies());
		assertEquals(List.of("WorldEdit"), manifest.softDependencies());
		assertEquals(List.of("Other"), manifest.loadBefore(), "A lone string should be accepted as a list");
		assertEquals(List.of("spawn", "home"), manifest.commands());
		assertEquals(List.of("example.admin"), manifest.permissions());
		assertTrue(manifest.isMainClass("com/example/ExamplePlugin"));
	}

	@Test
	void keepsUnquotedApiVersionTextAsWritten() {
		// An unquoted 1.20 is the float 1.2 to a YAML loader that constructs values.
		MinecraftPluginManifest manifest = MinecraftPluginManifestParser
				.parse("plugin.yml", "main: a.B\napi-version: 1.20\n").orElseThrow();
		assertEquals("1.20", manifest.apiVersion());
	}

	@Test
	void parsesPaperManifest() {
		String yml = """
				name: PaperExample
				version: '1.0'
				main: com.example.Main
				bootstrapper: com.example.Bootstrap
				loader: com.example.Loader
				api-version: '1.21'
				dependencies:
				  bootstrap:
				    Core:
				      load: BEFORE
				  server:
				    Vault:
				      load: BEFORE
				      required: true
				    Extras:
				      load: AFTER
				      required: false
				""";
		MinecraftPluginManifest manifest = MinecraftPluginManifestParser.parse("paper-plugin.yml", yml).orElseThrow();

		assertEquals(MinecraftPluginPlatform.PAPER, manifest.platform());
		assertEquals("com/example/Main", manifest.mainClass());
		assertTrue(manifest.isBootstrapperClass("com/example/Bootstrap"));
		assertTrue(manifest.isLoaderClass("com/example/Loader"));
		assertEquals(List.of("Core", "Vault"), manifest.dependencies(), "Required defaults to true");
		assertEquals(List.of("Extras"), manifest.softDependencies());
	}

	@Test
	void parsesBungeeManifest() {
		String yml = """
				name: ProxyExample
				version: 2
				main: com.example.Proxy
				depends: [Other]
				softDepends: [Another]
				""";
		MinecraftPluginManifest manifest = MinecraftPluginManifestParser.parse("bungee.yml", yml).orElseThrow();

		assertEquals(MinecraftPluginPlatform.BUNGEE, manifest.platform());
		assertEquals("com/example/Proxy", manifest.mainClass());
		assertEquals(List.of("Other"), manifest.dependencies());
		assertEquals(List.of("Another"), manifest.softDependencies());
		assertNull(manifest.apiVersion());
	}

	@Test
	void toleratesBomAndMissingFields() {
		MinecraftPluginManifest manifest = MinecraftPluginManifestParser
				.parse("plugin.yml", "﻿name: Only\n").orElseThrow();
		assertEquals("Only", manifest.name());
		assertNull(manifest.mainClass());
		assertTrue(manifest.dependencies().isEmpty());
	}

	@Test
	void rejectsUnknownFileNamesAndNonMappings() {
		assertEquals(Optional.empty(), MinecraftPluginManifestParser.parse("config.yml", "name: x\n"));
		assertEquals(Optional.empty(), MinecraftPluginManifestParser.parse("plugin.yml", "- just\n- a list\n"));
		assertEquals(Optional.empty(), MinecraftPluginManifestParser.parse("plugin.yml", ""));
	}

	@Test
	void rejectsMalformedAndHostileYaml() {
		assertEquals(Optional.empty(), MinecraftPluginManifestParser.parse("plugin.yml", "main: [unterminated\n"));

		// Global tags (arbitrary class construction) are refused by the loader, so the document is rejected outright.
		String gadget = "main: !!javax.script.ScriptEngineManager [!!java.net.URLClassLoader [[!!java.net.URL [\"http://127.0.0.1/\"]]]]\n";
		assertEquals(Optional.empty(), MinecraftPluginManifestParser.parse("plugin.yml", gadget));

		// Alias expansion bomb ("billion laughs") is cut off by the loader limits.
		StringBuilder bomb = new StringBuilder("a: &a [x, x, x, x, x, x, x, x, x]\n");
		String prev = "a";
		for (char c = 'b'; c <= 'j'; c++) {
			bomb.append(c).append(": &").append(c).append(" [");
			for (int i = 0; i < 9; i++)
				bomb.append(i == 0 ? "" : ", ").append('*').append(prev);
			bomb.append("]\n");
			prev = String.valueOf(c);
		}
		assertEquals(Optional.empty(), MinecraftPluginManifestParser.parse("plugin.yml", bomb.toString()));

		// Control: the same document shape with a few aliases is fine, so the above was cut off by limits and
		// not by a mistake in the generated text.
		String small = "a: &a [x, x]\nb: &b [*a, *a]\nmain: ok.Main\n";
		assertEquals("ok/Main", MinecraftPluginManifestParser.parse("plugin.yml", small).orElseThrow().mainClass());

		// Deep nesting is cut off too.
		String deep = "[".repeat(200) + "]".repeat(200);
		assertEquals(Optional.empty(), MinecraftPluginManifestParser.parse("plugin.yml", "main: x\ndeep: " + deep + "\n"));
	}
}
